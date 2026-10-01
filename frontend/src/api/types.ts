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
 * <p>Optional fields are modelled honestly with `| null` wherever the backend
 * can genuinely send null (an undecided verdict, a citation with no chunk), so
 * the compiler forces each call site to decide what to display.
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
  description: string | null
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
  originalFilename: string | null
  mimeType: string | null
  contentLength: number
  createdAt: string
  updatedAt: string
}

export interface DocumentContent {
  id: number
  title: string
  contentText: string
  contentLength: number
  originalFilename: string | null
}

export interface DocumentChunk {
  id: number
  chunkIndex: number
  startOffset: number
  endOffset: number
  tokenEstimate: number
  content: string
}

export interface DocumentProgress {
  documentId: number
  status: DocumentStatus
  corpusId: number
  extractionRunId: number | null
  totalChunks: number
  processedChunks: number
  chunkCount: number
  triplesFound: number
  claimsFound: number
  quarantinedCount: number
  lastError: string | null
}

export interface QuarantineRow {
  id: number
  documentId: number
  chunkId: number | null
  chunkIndex: number | null
  errorType: string
  validationMessage: string
  rawResponse: string | null
  attempt: number
  createdAt: string
}

export interface QuarantinePage {
  total: number
  content: QuarantineRow[]
}

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
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
  evidenceChunkCount: number
  createdAt: string
}

export interface Claim {
  id: number
  corpusId: number
  subject: string
  claimText: string
  polarity: ClaimPolarity
  predicate: string | null
  objectText: string | null
  sourceSentence: string
  sourceChunkId: number
  sourceDocumentTitle: string
  status: ClaimStatus
  decidedBy: string | null
  decidedAt: string | null
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
  firstSeenChunkId: number | null
}

export interface TriplePage {
  total: number
  size: number
  content: Triple[]
}

export interface ClaimPage {
  total: number
  size: number
  content: Claim[]
}

export interface ApprovalQueue {
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
  retrievalScore: number | null
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
  humanVerdictType: VerdictType | null
  adjudicationState: AdjudicationState
  adjudicator: string | null
  adjudicatedAt: string | null
  adjudicationNote: string | null
  overridden: boolean
  llmScore: number | null
  rulePenalty: number | null
  fusedScore: number | null
  evidenceStatus: EvidenceStatus
  ruleVersion: string | null
  verdictReason: string | null
  llmReasoning: string | null
  model: string | null
  promptVersion: string | null
  retrievalQuery: string | null
  traceRunId: number | null
  createdAt: string
  evidence: EvidencePassage[]
}

export interface VerdictPage {
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
  verdictId: number | null
  claimId: number
  verdictType: VerdictType | null
  llmScore: number | null
  rulePenalty: number | null
  fusedScore: number | null
  evidenceStatus: EvidenceStatus | null
  evidenceCount: number
  succeeded: boolean
  error: string | null
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
  entityId: number
  displayName: string
  pagerank: number
  inDegree: number
  outDegree: number
  community: number
}

export interface CommunityRow {
  communityId: number
  size: number
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
  leftTripleId: number | null
  rightTripleId: number | null
  leftClaimId: number | null
  rightClaimId: number | null
  debateId: number | null
  createdAt: string
  updatedAt: string
}

export interface ContradictionPage {
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
  traceRunId: number | null
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
  chunkId: number | null
  tripleId: number | null
  claimId: number | null
  machineFactId: string | null
  excerpt: string | null
}

export interface Argument {
  id: number
  round: number
  persona: Persona
  argumentText: string
  stance: string
  model: string | null
  promptVersion: string | null
  failed: boolean
  failureReason: string | null
  durationMs: number | null
  createdAt: string
  /** Latest chair weight only. The full sequence is append-only server-side. */
  chairWeight: number | null
  weightedBy: string | null
  citations: ArgumentCitation[]
}

export interface DebateRound {
  id: number
  roundNumber: number
  startedAt: string
  completedAt: string | null
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
  startedAt: string | null
  finishedAt: string | null
  lastError: string | null
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
  note: string | null
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
  chunkId: number | null
  tripleId: number | null
  claimId: number | null
  excerpt: string | null
}

export interface ReportBlock {
  id: number
  blockType: BlockType
  heading: string
  body: string
  weight: number | null
  citations: ReportBlockCitation[]
}

export interface SynthesisReport {
  id: number
  debateId: number
  corpusId: number
  conclusion: string
  confidence: number | null
  model: string | null
  promptVersion: string | null
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
  chunkId: number | null
  documentId: number | null
  documentTitle: string | null
  chunkIndex: number | null
  excerpt: string | null
}

export interface ChatMessage {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  citations: ChatCitation[]
  grounded: boolean
  insufficientEvidence: boolean
  model: string | null
  latencyMs: number | null
  retrievalCount: number | null
  traceRunId: number | null
  createdAt: string
}

export interface ChatAnswer {
  messageId: number
  answer: string
  citations: ChatCitation[]
  grounded: boolean
  insufficientEvidence: boolean
  retrievalCount: number
  model: string | null
}

// ---- glass box ------------------------------------------------------------

export type ActorType = 'ENGINE' | 'LLM' | 'HUMAN'

export interface TraceStepView {
  id: number
  parentStepId: number | null
  seq: number
  actorType: ActorType
  eventType: string
  name: string
  status: string
  inputSummary: string | null
  /** Comma-separated reference ids; not a structured array. */
  inputReferenceIds: string | null
  outputSummary: string | null
  outputReferenceIds: string | null
  ruleVersion: string | null
  promptVersion: string | null
  model: string | null
  durationMs: number | null
  errorMessage: string | null
  attempt: number | null
  createdAt: string
  children: number[]
}

export interface TraceRunSummary {
  id: number
  corpusId: number
  operationType: string
  status: string
  operationKey: string
  actorSummary: string | null
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
  errorMessage: string | null
  stepCount: number
}

export interface TraceRunPage {
  total: number
  size: number
  content: TraceRunSummary[]
}

export interface TraceRunDetail extends TraceRunSummary {
  metadata: Record<string, unknown> | null
  steps: TraceStepView[]
}

export interface XRayNode {
  stepId: number
  name: string
  eventType: string
  status: string
  inputSummary: string | null
  outputSummary: string | null
  model: string | null
  ruleVersion: string | null
  durationMs: number | null
}

export interface XRayView {
  runId: number
  engine: XRayNode[]
  llm: XRayNode[]
  human: XRayNode[]
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
  lastError: string | null
  heartbeatAt: string | null
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
