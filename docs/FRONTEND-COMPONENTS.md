# PRISM Frontend Components

Single tree. No duplication across pages: if two pages need it, it lives here.

## Shell

- `AppShell` (MODIFY): `Sidebar` + `Header` + `CorpusPicker` + `MainWorkspace`.
  Sidebar groups: Source (Corpora, Documents, Approval queue [VERIFIER+]),
  Record (Knowledge, Verification, Verdicts), Analysis (Graph, Contradictions,
  Reports), Use (Grounded chat, Glass Box), Admin (Administration [ADMIN]).
  Sidebar → drawer ≤900px (new behavior, CSS + one toggle button).
- `Header`: breadcrumb (corpus name + page), session-expiry indicator (NEW —
  reads `expiresInSeconds` via `useSessionCountdown`), user menu (logout).
- `ProtectedRoute` (NEW): wraps auth + role gates, handles `?returnTo=`,
  redirects to `/unauthorized` on role-denied.
- `RequireRole` (exists): keep as primitive inside `ProtectedRoute`.

## Shared primitives (extend `components/ui.tsx`, keep names)

`PageHeader, Stat, Card, Alert, Empty, Loading, ErrorState, DataTable`
(NEW — wraps `.table-wrap>table.data` + pager slot), `Pager` (NEW),
`StatusBadge` (trust states), `ActorBadge` (ENGINE/LLM/HUMAN),
`VerdictBadge` (fused verdict + tooltip of 4 fields), `ConfidenceBadge`
(NEW — VERIFIED / SINGLE_SOURCE / CONTRADICTED with icon + pattern, never
color-alone), `EvidencePanel`, `SourceCitation` (clickable →
opens chunk in DocumentDetail or evidence drawer), `KeyValue`,
`PipelineStepper` (NEW — UPLOAD→CHUNK→EXTRACT→VALIDATE→PROPOSE→APPROVAL),
`SearchInput` (debounced, abortable — NEW, replaces raw inputs),
`FilterBar`, `Modal`, `Drawer` (NEW — mobile inspector/bottom-sheet),
`Tooltip`, `CountUp` (NEW — motion).

## Domain components (one owner each)

- `ClaimCard`: claim text, polarity, status, source sentence, actions
  (verify / adjudicate links). Used by Verification, Verdicts, Chat evidence.
- `TripleRow` / approval `ProposalCard`: subject→predicate→object,
  source sentence, provenance, note input, APPROVE/REJECT.
- `GraphCanvas` (NEW, Cytoscape): nodes/edges, verified-only filter,
  confidence styling (edge style + opacity + badge, not color), selection →
  `EntityDossier` drawer (entity detail + approved relations + chunk links).
  `GraphTableFallback` (current tables) stays as the list view.
- `DebateColumn` (per persona) + `ArgumentCard` (citations, machine-evidence
  block for SKEPTIC, failure state) + `ChairWeight` (1–5 slider) +
  `FsmPanel` (states + current highlight; add allowed-transition hints from
  `/debates/fsm`).
- `TraceTimeline` + `TraceStep` + `ReplayPlayer` (NEW — play/pause/scrub,
  step highlight, actor lane colors + labels).
- `ReportReader`: executive summary, positions, machine verdicts, uncertainty,
  recommendation, clickable citations.
- `ChatThread` + `MessageBubble` (3 states exist) + `EvidencePanel`
  (persistent right rail: passages, related claims/verdicts, confidence).

## Page composition rule

Pages compose shell + shared + domain components only. No page-local
re-implementation of badges, tables, pagers, or evidence display. The current
`ui.tsx` set is the seed — grow it, don't fork it.
