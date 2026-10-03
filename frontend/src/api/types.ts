/**
 * Typed API surface.
 *
 * <p>Every interface here is transcribed from the backend's own OpenAPI
 * document (`docs/openapi.json`, regenerated from a running instance) rather
 * than from memory. That matters: hand-guessed field names silently render
 * `undefined`, which in an audit tool looks like the system has no data rather
 * than like the client asked for the wrong field.
 *
 * <p>When a backend response record changes, regenerate the document and update
 * this file in the same commit. A mismatch here is a real defect, not a
 * cosmetic one.
 *
 * <p><b>One envelope for every collection.</b> {@code PageResponse<T>} below is the
 * single shape used by {@code GET /api/documents}, {@code GET /api/admin/users}
 * and the quarantine listing. It replaced three different shapes, one of which
 * the frontend mistyped as a bare array -- which is why the admin page rendered
 * "No users" unconditionally. Spring Data's own {@code Page} is never exposed:
 * its JSON carries an internal {@code pageable} and changes between versions.
 *
 * <p><b>Nullable means ABSENT, not null.</b> The backend serialises with
 * `JsonInclude.Include.NON_NULL`, so a field whose value is null is left out of
 * the response entirely -- it is never sent as `null`. Every nullable backend
 * field is therefore declared optional (`foo?: T`), not `foo: T | null`.
 *
 * <p>This was previously documented the other way round and typed the other way
 * round for 103 fields, which was a defect rather than a style preference: a
 * field typed `T | null` but absent at runtime passes `strict` TypeScript, so
 * any call site using it as definitely-present type-checked and then read
 * `undefined` in the browser.
 * `scripts/contract-check.ps1` compares live JSON against these interfaces and
 * is what surfaced it.
 *
 * <p>Making the backend emit explicit nulls would have been the other way to
 * make the old comment true, and was rejected deliberately: the same
 * ObjectMapper builds LLM request bodies, so adding nulls to a prompt changes
 * what a model sees.
 */

// ---- auth -----------------------------------------------------------------

export type Role = 'ANALYST' | 'VERIFIER' | 'ADMIN'

export interface AuthUser {
  id: number
  username: string
  email: string
  role: Role
  enabled: boolean
  createdAt: string
}

export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: AuthUser
}

// ---- corpora --------------------------------------------------------------

export type CorpusStatus = 'ACTIVE' | 'ARCHIVED'

export interface Corpus {
  id: number
  name: string
  description?: string
  ownerId: number
  ownerUsername: string
  status: CorpusStatus
  createdAt: string
  updatedAt: string
}

export interface CorpusStatistics {
  documents: number
  documentsByStatus: Record<string, number>
  triples: number
  triplesByStatus: Record<string, number>
  claims: number
  claimsByStatus: Record<string, number>
  verdicts: number
  verdictsByType: Record<string, number>
  adjudicated: number
  contradictions: number
  contradictionsByStatus: Record<string, number>
  debates: number
  openDebates: number
  quarantined: number
}

// ---- documents ------------------------------------------------------------

export type DocumentStatus =
  | 'UPLOADED'
  | 'CHUNKING'
  | 'CHUNKED'
  | 'EXTRACTING'
  | 'AWAITING_APPROVAL'
  | 'READY'
  | 'FAILED'

export interface DocumentRow {
  id: number
  corpusId: number
  title: string
  status: DocumentStatus
  originalFilename?: string
  mimeType?: string
  contentLength: number
  createdAt: string
  updatedAt: string
}

export interface DocumentContent {
  id: number
  title: string
  contentText: string
}

export interface DocumentChunk {
  id: number
  chunkIndex: number
  startOffset: number
  endOffset: number
  tokenEstimate: number
  content: string
}

/** Mirrors the backend ExtractionStatus enum, reported as `runStatus`. */
export type ExtractionRunStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface DocumentProgress {
  documentId: number
  status: DocumentStatus
  /** Status of the extraction run, which can differ from the document's. */
  runStatus: ExtractionRunStatus
  extractionRunId?: number
  totalChunks: number
  processedChunks: number
  chunkCount: number
  triplesFound: number
  claimsFound: number
  quarantinedCount: number
  /** Which model produced this run. Absent before the run starts. */
  model?: string
  /** Set when the run failed. Absent when it succeeded -- see the NON_NULL note. */
  lastError?: string
}

export interface QuarantineRow {
  id: number
  documentId: number
  chunkId?: number
  chunkIndex?: number
  errorType: string
  validationMessage: string
  rawResponse?: string
  attempt: number
  createdAt: string
}

/**
 * The one collection envelope every paged endpoint returns.
 *
 * <p>Mirrors the backend's `com.prism.common.PageResponse`. `hasNext` is present
 * so a client never has to infer "is there more" from a short final page, which
 * is ambiguous when the final page happens to be full.
 */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

/**
 * The quarantine listing.
 *
 * <p>Aliased to the shared envelope rather than declaring its own `{total,
 * content}` shape. The alias is kept so call sites read clearly; the underlying
 * contract is identical to every other collection endpoint.
 */
export type QuarantinePage = PageResponse<QuarantineRow>

// ---- knowledge ------------------------------------------------------------

export type ProposalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type ClaimStatus = 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'VERIFIED' | 'ADJUDICATED'
export type ClaimPolarity = 'AFFIRMATIVE' | 'NEGATIVE' | 'NEUTRAL'
export type ResolutionState = 'KEEP_SEPARATE' | 'MERGE' | 'REVIEW'

export interface Triple {
  id: number
  corpusId: number
  subject: string
  predicate: string
  object: string
  sourceSentence: string
  sourceChunkId: number
  sourceDocumentTitle: string
  status: ProposalStatus
  decidedBy?: string
  decidedAt?: string
  decisionNote?: string
  evidenceChunkCount: number
  createdAt: string
}

export interface Claim {
  id: number
  corpusId: number
  subject: string
  claimText: string
  polarity: ClaimPolarity
  predicate?: string
  objectText?: string
  sourceSentence: string
  sourceChunkId: number
  sourceDocumentTitle: string
  status: ClaimStatus
  decidedBy?: string
  decidedAt?: string
  createdAt: string
}

export interface Entity {
  id: number
  corpusId: number
  displayName: string
  normalizedName: string
  type: string
  resolutionState: ResolutionState
  supportCount: number
  firstSeenChunkId?: number
}

export interface TriplePage {
  page: number
  total: number
  size: number
  content: Triple[]
}

export interface ClaimPage {
  page: number
  total: number
  size: number
  content: Claim[]
}

export interface ApprovalQueue {
  page: number
  size: number
  triples: Triple[]
  claims: Claim[]
  pendingTripleCount: number
  pendingClaimCount: number
}

// ---- verification ---------------------------------------------------------

export type VerdictType =
  | 'SUPPORTED'
  | 'CONTRADICTED'
  | 'INSUFFICIENT_EVIDENCE'
  | 'EXAGGERATED'
  | 'SOURCE_MISSING'

export type AdjudicationState = 'MACHINE_ONLY' | 'CONTESTED' | 'HUMAN_DECISION'
export type EvidenceStatus = 'EVIDENCE_FOUND' | 'NO_EVIDENCE'

export interface EvidencePassage {
  chunkId: number
  documentId: number
  documentTitle: string
  chunkText: string
  retrievalRank: number
  retrievalScore?: number
}

/**
 * A verdict projection.
 *
 * <p>`fusedScore` is a ranking score, not a calibrated probability. The four
 * measurements are deliberately separate fields and the UI must not collapse
 * them into one "confidence".
 */
export interface Verdict {
  id: number
  claimId: number
  corpusId: number
  claimText: string
  subject: string
  verdictType: VerdictType
  machineVerdictType: VerdictType
  humanVerdictType?: VerdictType
  adjudicationState: AdjudicationState
  adjudicator?: string
  adjudicatedAt?: string
  adjudicationNote?: string
  overridden: boolean
  llmScore?: number
  rulePenalty?: number
  fusedScore?: number
  evidenceStatus: EvidenceStatus
  ruleVersion?: string
  verdictReason?: string
  llmReasoning?: string
  model?: string
  promptVersion?: string
  retrievalQuery?: string
  traceRunId?: number
  createdAt: string
  evidence: EvidencePassage[]
}

export interface VerdictPage {
  page: number
  total: number
  size: number
  content: Verdict[]
}

export interface VerifyAllResult {
  attempted: number
  succeeded: number
  failed: number
  verdictIds: number[]
}

export interface VerificationOutcome {
  verdictId?: number
  claimId: number
  verdictType?: VerdictType
  llmScore?: number
  rulePenalty?: number
  fusedScore?: number
  evidenceStatus?: EvidenceStatus
  evidenceCount: number
  succeeded: boolean
  error?: string
}

// ---- graph ----------------------------------------------------------------

export type GraphScope = 'ALL_APPROVED' | 'VERIFIED_ONLY'

export interface GraphNode {
  id: number
  name: string
  inDegree: number
  outDegree: number
  pagerank: number
  community: number
}

export interface GraphEdge {
  from: number
  to: number
  predicate: string
  label: string
}

export interface GraphStats {
  nodeCount: number
  edgeCount: number
  density: number
  communityCount: number
  maxInDegree: number
  maxOutDegree: number
}

export interface GraphView {
  nodes: GraphNode[]
  edges: GraphEdge[]
  stats: GraphStats
  scope: GraphScope
}

export interface PageRankRow {
  /** 1-based position in the ranking. */
  rank: number
  entityId: number
  displayName: string
  pagerank: number
  inDegree: number
  outDegree: number
}

export interface CommunityRow {
  communityId: number
  size: number
  /** Machine ids, stable across renames. */
  entityIds: number[]
  /** Display names, for reading. */
  members: string[]
}

// ---- contradictions -------------------------------------------------------

export type ContradictionStatus = 'OPEN' | 'IN_DEBATE' | 'RESOLVED' | 'DISMISSED'
export type ContradictionType = 'RELATION_CONFLICT' | 'POLARITY_CONFLICT' | 'VERDICT_CONFLICT'

export interface Contradiction {
  id: number
  corpusId: number
  contradictionType: ContradictionType
  subjectText: string
  predicate: string
  /** Human-readable restatement of each side, with its document and chunk. */
  leftDescription: string
  rightDescription: string
  ruleCode: string
  ruleVersion: string
  explanation: string
  status: ContradictionStatus
  leftTripleId?: number
  rightTripleId?: number
  leftClaimId?: number
  rightClaimId?: number
  debateId?: number
  createdAt: string
  updatedAt: string
}

export interface ContradictionPage {
  page: number
  total: number
  size: number
  content: Contradiction[]
}

export interface ContradictionCounts {
  total: number
  open: number
  inDebate: number
  resolved: number
  dismissed: number
}

export interface ScanResult {
  created: number
  unchanged: number
  findings: number
  traceRunId?: number
}

// ---- debate ---------------------------------------------------------------

export type DebateState =
  | 'CREATED'
  | 'ROUND_ACTIVE'
  | 'AWAITING_CHAIR'
  | 'SYNTHESIZING'
  | 'COMPLETED'
  | 'ABORTED'

export type Persona = 'HAWK' | 'DOVE' | 'SKEPTIC'
export type CitationKind = 'CHUNK' | 'TRIPLE' | 'CLAIM' | 'MACHINE_FACT'

export interface ArgumentCitation {
  id: number
  kind: CitationKind
  chunkId?: number
  tripleId?: number
  claimId?: number
  machineFactId?: string
  excerpt?: string
}

export interface Argument {
  id: number
  round: number
  persona: Persona
  argumentText: string
  stance: string
  model?: string
  promptVersion?: string
  failed: boolean
  failureReason?: string
  durationMs?: number
  createdAt: string
  /** Latest chair weight only. The full sequence is append-only server-side. */
  chairWeight?: number
  weightedBy?: string
  citations: ArgumentCitation[]
}

export interface DebateRound {
  id: number
  roundNumber: number
  startedAt: string
  completedAt?: string
  argumentsCompleted: number
  argumentsFailed: number
  arguments: Argument[]
}

export interface Debate {
  id: number
  contradictionId: number
  corpusId: number
  state: DebateState
  stateDescription: string
  currentRound: number
  maxRounds: number
  topic: string
  chair: string
  createdAt: string
  startedAt?: string
  finishedAt?: string
  lastError?: string
  rounds: DebateRound[]
}

export interface DebatePage {
  total: number
  size: number
  content: Debate[]
}

/**
 * The published debate state machine.
 *
 * <p>Deliberately not a transition table. The endpoint publishes the state
 * vocabulary with a description per state, and the event vocabulary that drives
 * transitions, but not a from/to matrix — so the UI shows the two vocabularies
 * and marks the current state rather than inventing a graph of legal moves.
 * Inventing one client-side would be a second source of truth about the machine,
 * and it would drift from the engine.
 */
export interface FsmView {
  /** State name to human description. */
  states: Record<string, string>
  /** Event names the engine accepts, e.g. START, ARGUMENTS_DONE. */
  events: string[]
  maxRounds: number
  weightRange: { min: number; max: number }
  personas: string[]
}

/**
 * Response of `POST /debates/{id}/arguments/{id}/weight`.
 *
 * <p>Returns the new audit row, not the argument: weights are append-only, so
 * re-weighting creates a new row and this is what records that it happened.
 */
export interface WeightResult {
  weightId: number
  argumentId: number
  weight: number
  verifier: string
  note?: string
  createdAt: string
}

/**
 * Response of `POST /debates/{id}/synthesize`.
 *
 * <p>A receipt, not the report. The report itself is read from
 * `GET /debates/{id}/report`, which is idempotent and returns the same document
 * whether synthesis just ran or ran earlier.
 */
export interface SynthesizeResult {
  reportId: number
  debateId: number
  createdAt: string
  model: string
}

export interface DebateEvent {
  type: string
  at: string
  message: string
}

// ---- synthesis ------------------------------------------------------------

export type BlockType = 'EXECUTIVE_SUMMARY' | 'AGREEMENT' | 'DISAGREEMENT' | 'UNRESOLVED' | 'RECOMMENDATION'

export interface ReportBlockCitation {
  kind: CitationKind | string
  chunkId?: number
  tripleId?: number
  claimId?: number
  excerpt?: string
}

export interface ReportBlock {
  id: number
  blockType: BlockType
  heading: string
  body: string
  weight?: number
  citations: ReportBlockCitation[]
}

export interface SynthesisReport {
  id: number
  debateId: number
  corpusId: number
  conclusion: string
  confidence?: number
  model?: string
  promptVersion?: string
  createdAt: string
  blocks: ReportBlock[]
}

// ---- chat -----------------------------------------------------------------

export interface ChatSession {
  id: number
  corpusId: number
  title: string
  messageCount: number
  createdAt: string
  updatedAt: string
}

export interface ChatSessionDetail {
  id: number
  corpusId: number
  title: string
  createdAt: string
  updatedAt: string
  messages: ChatMessage[]
}

export interface ChatCitation {
  kind: string
  chunkId?: number
  documentId?: number
  documentTitle?: string
  chunkIndex?: number
  excerpt?: string
}

export interface ChatMessage {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  citations: ChatCitation[]
  grounded: boolean
  insufficientEvidence: boolean
  model?: string
  latencyMs?: number
  retrievalCount?: number
  traceRunId?: number
  createdAt: string
}

export interface ChatAnswer {
  messageId: number
  answer: string
  citations: ChatCitation[]
  grounded: boolean
  insufficientEvidence: boolean
  retrievalCount: number
  model?: string
}

// ---- glass box ------------------------------------------------------------

export type ActorType = 'ENGINE' | 'LLM' | 'HUMAN'

export interface TraceStepView {
  id: number
  parentStepId?: number
  seq: number
  actorType: ActorType
  eventType: string
  name: string
  status: string
  inputSummary?: string
  /** Comma-separated reference ids; not a structured array. */
  inputReferenceIds?: string
  outputSummary?: string
  outputReferenceIds?: string
  ruleVersion?: string
  promptVersion?: string
  model?: string
  durationMs?: number
  errorMessage?: string
  attempt?: number
  createdAt: string
  children: number[]
}

export interface TraceRunSummary {
  id: number
  corpusId: number
  operationType: string
  status: string
  operationKey: string
  actorSummary?: string
  startedAt: string
  finishedAt?: string
  durationMs?: number
  errorMessage?: string
  stepCount: number
}

export interface TraceRunPage {
  page: number
  total: number
  size: number
  content: TraceRunSummary[]
}

/**
 * A trace run with its full step DAG.
 *
 * <p>The run is NESTED under `run`, not flattened. This type used to extend
 * `TraceRunSummary`, which declared the run's fields at the top level — so the
 * trace page read `data.id`, `data.status` and `data.operationKey` from an
 * object where they do not exist and rendered "Trace #undefined" with a blank
 * status. Found by comparing live JSON against this file.
 */
export interface TraceRunDetail {
  run: TraceRunSummary
  steps: TraceStepView[]
}

export interface XRayNode {
  stepId: number
  name: string
  eventType: string
  status: string
  inputSummary?: string
  outputSummary?: string
  model?: string
  ruleVersion?: string
  durationMs?: number
}

export interface XRayView {
  runId: number
  operationType: string
  status: string
  startedAt: string
  finishedAt?: string
  durationMs?: number
  engine: XRayNode[]
  llm: XRayNode[]
  human: XRayNode[]
  /** Total steps across all three actor groups. */
  totalSteps: number
}

// ---- admin ----------------------------------------------------------------

export interface UserSummary {
  id: number
  username: string
  email: string
  role: Role
  enabled: boolean
  createdAt: string
}

export interface BackgroundJobView {
  id: number
  jobKey: string
  jobType: string
  status: string
  attemptCount: number
  maxAttempts: number
  lastError?: string
  heartbeatAt?: string
  createdAt: string
  updatedAt: string
}

export interface SystemStatus {
  activeCorpora: number
  totalDocuments: number
  totalChunks: number
  pendingApprovals: number
  openContradictions: number
  activeDebates: number
  quarantinedResponses: number
  uptimeSeconds: number
}
