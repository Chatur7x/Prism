/**
 * Typed wrappers for every backend endpoint.
 *
 * <p>One function per operation, each returning the exact shape declared in
 * `types.ts`. No component calls `request` directly, so there is a single place
 * to look when a route changes and a single place where a wrong path or method
 * becomes a type error.
 */
import { getToken, qs, request } from './client'
import type {
  ApprovalQueue,
  AuthResponse,
  AuthUser,
  BackgroundJobView,
  ChatAnswer,
  ChatSession,
  ChatSessionDetail,
  Claim,
  ClaimPage,
  CommunityRow,
  Contradiction,
  ContradictionPage,
  Corpus,
  CorpusStatistics,
  Debate,
  DebatePage,
  DocumentChunk,
  DocumentContent,
  DocumentProgress,
  DocumentRow,
  Entity,
  FsmView,
  GraphScope,
  GraphView,
  PageRankRow,
  QuarantinePage,
  ScanResult,
  SynthesisReport,
  SynthesizeResult,
  SystemStatus,
  TraceRunDetail,
  TraceRunPage,
  Triple,
  TriplePage,
  UserSummary,
  Verdict,
  VerdictPage,
  VerdictType,
  VerifyAllResult,
  VerificationOutcome,
  WeightResult,
  XRayView,
} from './types'

// ---- auth -----------------------------------------------------------------

export const authApi = {
  register: (username: string, email: string, password: string) =>
    request<AuthResponse>('/api/auth/register', {
      method: 'POST',
      anonymous: true,
      body: { username, email, password },
    }),

  login: (username: string, password: string) =>
    request<AuthResponse>('/api/auth/login', {
      method: 'POST',
      anonymous: true,
      body: { username, password },
    }),

  me: () => request<AuthUser>('/api/auth/me'),
}

// ---- corpora --------------------------------------------------------------

export const corpusApi = {
  list: () => request<Corpus[]>('/api/corpora'),

  create: (name: string, description: string) =>
    request<Corpus>('/api/corpora', { method: 'POST', body: { name, description } }),

  get: (id: number) => request<Corpus>(`/api/corpora/${id}`),

  update: (id: number, name: string, description: string) =>
    request<Corpus>(`/api/corpora/${id}`, { method: 'PATCH', body: { name, description } }),

  /** Archive, not delete: provenance must survive for audit. */
  archive: (id: number) => request<void>(`/api/corpora/${id}`, { method: 'DELETE' }),

  statistics: (id: number) => request<CorpusStatistics>(`/api/corpora/${id}/statistics`),
}

// ---- documents ------------------------------------------------------------

export const documentApi = {
  list: (corpusId: number, size = 200) =>
    request<DocumentRow[]>(`/api/documents${qs({ corpusId, size })}`),

  createText: (corpusId: number, title: string, contentText: string) =>
    request<DocumentRow>('/api/documents', {
      method: 'POST',
      body: { corpusId, title, contentText },
    }),

  upload: async (corpusId: number, title: string, file: File): Promise<DocumentRow> => {
    // FormData is sent without a Content-Type header on purpose: the browser must
    // add the multipart boundary itself, and setting it manually produces a
    // request the server cannot parse.
    const form = new FormData()
    form.append('corpusId', String(corpusId))
    if (title) form.append('title', title)
    form.append('file', file, file.name)

    const headers: Record<string, string> = {}
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`

    const response = await fetch('/api/documents', {
      method: 'POST',
      headers,
      body: form,
      credentials: 'omit',
    })

    if (!response.ok) {
      const text = await response.text()
      let detail: { message?: string; error?: string; traceId?: string } | null = null
      try {
        detail = JSON.parse(text)
      } catch {
        detail = { message: text }
      }
      throw new Error(detail?.message ?? `Upload failed with status ${response.status}`)
    }
    return (await response.json()) as DocumentRow
  },

  get: (id: number) => request<DocumentRow>(`/api/documents/${id}`),

  content: (id: number) => request<DocumentContent>(`/api/documents/${id}/content`),

  chunks: (id: number) => request<DocumentChunk[]>(`/api/documents/${id}/chunks`),

  progress: (id: number) => request<DocumentProgress>(`/api/documents/${id}/progress`),

  quarantine: (id: number) => request<QuarantinePage>(`/api/documents/${id}/quarantine`),

  reprocess: (id: number) => request<DocumentRow>(`/api/documents/${id}/reprocess`, { method: 'POST' }),
}

// ---- knowledge ------------------------------------------------------------

export const knowledgeApi = {
  approvalQueue: (corpusId: number, page = 0, size = 50) =>
    request<ApprovalQueue>(`/api/approval-queue${qs({ corpusId, page, size })}`),

  approveTriple: (id: number, note: string) =>
    request<Triple>(`/api/triples/${id}/approve`, { method: 'POST', body: { note } }),

  rejectTriple: (id: number, note: string) =>
    request<Triple>(`/api/triples/${id}/reject`, { method: 'POST', body: { note } }),

  approveClaim: (id: number) => request<Claim>(`/api/claims/${id}/approve`, { method: 'POST' }),

  rejectClaim: (id: number, note: string) =>
    request<Claim>(`/api/claims/${id}/reject`, { method: 'POST', body: { note } }),

  triples: (corpusId: number, status?: string, page = 0, size = 50) =>
    request<TriplePage>(`/api/triples${qs({ corpusId, status, page, size })}`),

  claims: (corpusId: number, status?: string, page = 0, size = 50) =>
    request<ClaimPage>(`/api/claims${qs({ corpusId, status, page, size })}`),

  entities: (corpusId: number, search?: string) =>
    request<Entity[]>(`/api/entities${qs({ corpusId, search })}`),
}

// ---- verification ---------------------------------------------------------

export const verificationApi = {
  verify: (claimId: number) =>
    request<VerificationOutcome>('/api/claims/verify', { method: 'POST', body: { claimId } }),

  verifyAll: (corpusId: number, limit = 10) =>
    request<VerifyAllResult>(`/api/claims/verify-all${qs({ corpusId, limit })}`, {
      method: 'POST',
      // Each verification performs its own retrieval and model call, so this is
      // legitimately slow. The default 30s timeout would abort it spuriously.
      timeoutMs: 900_000,
    }),

  verdicts: (corpusId: number, page = 0, size = 50) =>
    request<VerdictPage>(`/api/verdicts${qs({ corpusId, page, size })}`),

  verdict: (id: number) => request<Verdict>(`/api/verdicts/${id}`),

  history: (id: number) => request<Record<string, unknown>[]>(`/api/verdicts/${id}/history`),

  adjudicate: (id: number, verdict: VerdictType, note: string) =>
    request<Verdict>(`/api/verdicts/${id}/adjudicate`, { method: 'POST', body: { verdict, note } }),
}

// ---- graph ----------------------------------------------------------------

export const graphApi = {
  get: (corpusId: number, scope: GraphScope = 'ALL_APPROVED') =>
    request<GraphView>(`/api/graph/${corpusId}${qs({ scope })}`),

  pagerank: (corpusId: number, scope: GraphScope = 'ALL_APPROVED', limit = 50) =>
    request<PageRankRow[]>(`/api/graph/${corpusId}/pagerank${qs({ scope, limit })}`),

  communities: (corpusId: number, scope: GraphScope = 'ALL_APPROVED') =>
    request<CommunityRow[]>(`/api/graph/${corpusId}/communities${qs({ scope })}`),
}

// ---- contradictions -------------------------------------------------------

export const contradictionApi = {
  list: (corpusId: number, status?: string, size = 50) =>
    request<ContradictionPage>(`/api/contradictions${qs({ corpusId, status, size })}`),

  /**
   * Pipeline counters for the whole corpus, verifier-gated.
   *
   * <p>There is no separate contradiction-count endpoint by design: the counts
   * are only meaningful next to the document, approval, and verdict counters,
   * because that is what tells a reviewer whether the human gate is the
   * bottleneck.
   */
  counts: (corpusId: number) => request<CorpusStatistics>(`/api/corpora/${corpusId}/statistics`),

  get: (id: number) => request<Contradiction>(`/api/contradictions/${id}`),

  scan: (corpusId: number) =>
    request<ScanResult>(`/api/contradictions/scan${qs({ corpusId })}`, { method: 'POST' }),

  convene: (corpusId: number, contradictionId: number, chair?: string) =>
    request<Debate>(`/api/contradictions/${contradictionId}/debate`, {
      method: 'POST',
      body: { corpusId, chair },
    }),

  dismiss: (id: number) =>
    request<Contradiction>(`/api/contradictions/${id}/dismiss`, { method: 'POST' }),

  debates: (corpusId: number, size = 50) =>
    request<DebatePage>(`/api/debates${qs({ corpusId, size })}`),
}

// ---- debate ---------------------------------------------------------------

export const debateApi = {
  get: (id: number) => request<Debate>(`/api/debates/${id}`),

  fsm: () => request<FsmView>('/api/debates/fsm'),

  start: (id: number) => request<Debate>(`/api/debates/${id}/start`, { method: 'POST' }),

  advance: (id: number) => request<Debate>(`/api/debates/${id}/advance`, { method: 'POST' }),

  abort: (id: number) => request<Debate>(`/api/debates/${id}/abort`, { method: 'POST' }),

  weight: (debateId: number, argumentId: number, weight: number, note?: string) =>
    request<WeightResult>(`/api/debates/${debateId}/arguments/${argumentId}/weight`, {
      method: 'POST',
      body: { weight, note },
    }),

  /** Returns a receipt. Read the report itself from {@link report}. */
  synthesize: (id: number) =>
    request<SynthesizeResult>(`/api/debates/${id}/synthesize`, { method: 'POST', timeoutMs: 300_000 }),

  report: (id: number) => request<SynthesisReport>(`/api/debates/${id}/report`),

  /**
   * SSE endpoint.
   *
   * <p>The browser `EventSource` API cannot set request headers, so the token
   * travels as a query parameter. That is acceptable here and nowhere else: the
   * value is short-lived, the endpoint is read-only, and it is the only way to
   * stream with the built-in browser API.
   */
  streamUrl: (id: number) => {
    const token = getToken() ?? ''
    return `/api/debates/${id}/stream${qs({ access_token: token })}`
  },
}

// ---- synthesis ------------------------------------------------------------

export const reportApi = {
  forDebate: (debateId: number) => request<SynthesisReport>(`/api/debates/${debateId}/report`),
}

// ---- chat -----------------------------------------------------------------

export const chatApi = {
  sessions: () => request<ChatSession[]>('/api/chat/sessions'),

  createSession: (corpusId: number, title: string) =>
    request<ChatSession>('/api/chat/sessions', { method: 'POST', body: { corpusId, title } }),

  session: (id: number) => request<ChatSessionDetail>(`/api/chat/sessions/${id}`),

  ask: (sessionId: number, question: string) =>
    request<ChatAnswer>(`/api/chat/sessions/${sessionId}/messages`, {
      method: 'POST',
      body: { question },
      // Retrieval plus a model call, plus a refusal path that skips the model.
      timeoutMs: 120_000,
    }),
}

// ---- glass box ------------------------------------------------------------

export const traceApi = {
  list: (corpusId: number, page = 0, size = 50) =>
    request<TraceRunPage>(`/api/traces${qs({ corpusId, page, size })}`),

  get: (id: number) => request<TraceRunDetail>(`/api/traces/${id}`),

  steps: (id: number) => request<TraceRunDetail['steps']>(`/api/traces/${id}/steps`),

  xray: (id: number) => request<XRayView>(`/api/traces/${id}/xray`),
}

// ---- admin ----------------------------------------------------------------

export const adminApi = {
  users: () => request<UserSummary[]>('/api/admin/users'),

  createUser: (username: string, email: string, password: string, role: string) =>
    request<UserSummary>('/api/admin/users', {
      method: 'POST',
      body: { username, email, password, role },
    }),

  updateRole: (id: number, role: string) =>
    request<UserSummary>(`/api/admin/users/${id}`, { method: 'PATCH', body: { role } }),

  jobs: () => request<BackgroundJobView[]>('/api/admin/jobs'),

  status: () => request<SystemStatus>('/api/admin/system/status'),
}
