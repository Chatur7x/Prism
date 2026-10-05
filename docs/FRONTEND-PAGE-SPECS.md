# PRISM Page Specs

Format per page is fixed (see brief §29). ACCESS values: public /
authenticated / VERIFIER / ADMIN. Verdict on current code:
PRESERVE (keep as-is) / MODIFY (keep logic, change presentation) /
REBUILD (new layout, same endpoints).

---

PAGE: Homepage
ROUTE: `/`
ACCESS: public
PURPOSE: Explain PRISM in seconds; route visitors into the platform.
USER GOAL: Understand what PRISM does and enter the app.
LAYOUT:
- top: minimal brand bar (PRISM mark, Enter Prism CTA)
- center: HERO (headline, 2-line explainer, Enter Prism / See how it works)
  + static product visual (pipeline diagram, CSS only)
- center: PIPELINE strip (Documents→Extraction→Knowledge→Verification→
  Contradictions→Council→Synthesis→Glass Box)
- center: LATTICE / REDLINE / COUNCIL / GLASS BOX / CHAT sections (each:
  60-word explainer + small UI visualization, no live data)
- footer: final CTA + status line (NOT RELEASE CANDIDATE, links to docs)
PRIMARY ACTIONS: Enter Prism (→/login), See how it works (→#pipeline anchor)
SECONDARY ACTIONS: Read the docs (GitHub link)
COMPONENTS: purpose-built static sections; no app components
DATA REQUIRED: none (static; numbers only if quoted from docs with caveats)
API ENDPOINTS: none
LOADING STATE: none (static HTML, instant)
EMPTY STATE: n/a
ERROR STATE: n/a
SUCCESS STATE: n/a
ANIMATIONS: subtle technical motion only (fade/translate entrances,
line-draw on pipeline diagram); honors reduced-motion
INTERACTIONS: anchor scroll, CTA hover
RESPONSIVE BEHAVIOR: single column ≤960px; hero type scales down
ACCESSIBILITY: semantic h1/h2 order, OG tags, favicon, keyboard CTAs
DEPENDENCIES: none
CURRENT IMPLEMENTATION: redirect to /documents — REBUILD as real page
MISSING BACKEND SUPPORT: none (static)

---

PAGE: Login
ROUTE: `/login`
ACCESS: public (signed-in → redirect app, honoring `?returnTo=`)
PURPOSE: Sign in with username/email + password.
USER GOAL: Reach my workspace in one step.
LAYOUT:
- center: auth card (username, password + show/hide, submit, error area)
- below card: forgot-password link, signup link
PRIMARY ACTIONS: Sign in
SECONDARY ACTIONS: Forgot password, Create account
COMPONENTS: AuthCard (shared with signup), Alert, Loading
DATA REQUIRED: none
API ENDPOINTS: `POST /api/auth/login {username,password}` → AuthResponse
LOADING STATE: button spinner + disabled, 30s timeout
EMPTY STATE: n/a
ERROR STATE: 401 "Invalid credentials" inline; 429 "Too many attempts,
wait 60s"; 0 "Cannot reach backend" + StaticNotice hint
SUCCESS STATE: navigate to `returnTo` or `/dashboard`
ANIMATIONS: card entrance only
INTERACTIONS: Enter submits; show/hide toggles; autocomplete on
RESPONSIVE BEHAVIOR: full-width card ≤480px
ACCESSIBILITY: labelled inputs, aria-invalid + describedby on errors,
focus moves to error summary
DEPENDENCIES: session countdown starts here (stores expiresInSeconds)
CURRENT IMPLEMENTATION: mode on WelcomePage — MODIFY (extract card, add route)
MISSING BACKEND SUPPORT: none

---

PAGE: Signup
ROUTE: `/signup`
ACCESS: public (signed-in → redirect app)
PURPOSE: Self-register as ANALYST.
USER GOAL: Create an account without admin help.
LAYOUT: same AuthCard frame as login
- fields: username, email, password (+strength hint), submit
PRIMARY ACTIONS: Create account
SECONDARY ACTIONS: Sign in link
COMPONENTS: AuthCard, Alert
DATA REQUIRED: none
API ENDPOINTS: `POST /api/auth/register {username,email,password}` →
AuthResponse (role always ANALYST; copy must say so)
LOADING STATE: same as login
EMPTY STATE: n/a
ERROR STATE: 409 username/email taken; 422 rule list (12+ chars,
letter+digit, username pattern); inline per-field where mappable
SUCCESS STATE: signed in → `/dashboard` + "ANALYST can propose, not approve"
ANIMATIONS: card entrance only
INTERACTIONS: live password-rule checklist
RESPONSIVE BEHAVIOR: same as login
ACCESSIBILITY: same as login + password rules as list, not placeholder
DEPENDENCIES: none
CURRENT IMPLEMENTATION: mode on WelcomePage — MODIFY (extract card, add route)
MISSING BACKEND SUPPORT: none

---

PAGE: Forgot / Reset password
ROUTE: `/forgot-password`, `/reset-password?token=`
ACCESS: public
PURPOSE: Recover account access. (Specified, not built.)
USER GOAL: Regain access via email link.
LAYOUT: single-field card (email) → confirmation state; reset: new-password
card with token validation state
PRIMARY ACTIONS: Send reset link / Set new password
SECONDARY ACTIONS: Back to login
COMPONENTS: AuthCard, Alert
DATA REQUIRED: none
API ENDPOINTS: NONE EXIST — MISSING BACKEND SUPPORT
(needs `POST /api/auth/forgot-password {email}` + `POST /api/auth/reset-password {token,newPassword}` plus mail sender)
LOADING / EMPTY / ERROR / SUCCESS: standard card states; invalid-token state
ANIMATIONS: none
INTERACTIONS: none beyond submit
RESPONSIVE BEHAVIOR: same as login
ACCESSIBILITY: same as login
DEPENDENCIES: backend P3 — DO NOT BUILD UI until endpoints exist
CURRENT IMPLEMENTATION: absent — NEW, blocked
MISSING BACKEND SUPPORT: entire flow (endpoint + email + token store)

---

PAGE: Dashboard
ROUTE: `/dashboard`
ACCESS: authenticated (all roles; VERIFIER+ sees more)
PURPOSE: One-screen pipeline truth: where the corpus stands, what needs a human.
USER GOAL: Answer "what needs me?" in 10 seconds.
LAYOUT:
- top: corpus picker context + pipeline strip SOURCE→RECORD→VERIFY→
  ANALYZE→USE with live counts per stage
- left/center: Pending actions (approvals, contradictions OPEN, debates
  AWAITING_CHAIR), Recent documents, Recent traces
- right: Key metrics (Stat tiles), Pipeline health (jobs/failed via admin
  status if ADMIN)
PRIMARY ACTIONS: jump links (Review queue, Convene, Open trace)
SECONDARY ACTIONS: corpus switch, refresh
COMPONENTS: PipelineStrip (NEW), Stat, DataTable (compact), Alert
DATA REQUIRED: per-stage counts, pending lists, recent items
API ENDPOINTS: `GET /api/corpora/{id}/statistics` (VERIFIER+);
`GET /api/approval-queue` (counts); `GET /api/contradictions?status=OPEN`;
`GET /api/debates` (filter AWAITING_CHAIR client-side);
`GET /api/traces` (recent 5); ANALYST fallback: `GET /api/corpora` +
`GET /api/documents` counts only (labelled)
LOADING STATE: skeleton strip + tiles
EMPTY STATE: "No corpus selected" / "Nothing needs you"
ERROR STATE: statistics 403 (ANALYST) → degraded dashboard, not error page
SUCCESS STATE: n/a (read-only page)
ANIMATIONS: CountUp on tiles; pipeline strip fill
INTERACTIONS: click stage → navigate to that page
RESPONSIVE BEHAVIOR: strip scrolls horizontally ≤900px; right rail stacks
ACCESSIBILITY: strip is nav with aria-current stage
DEPENDENCIES: Pager/DataTable shared work
CURRENT IMPLEMENTATION: absent (`/` redirects to /documents) — NEW
MISSING BACKEND SUPPORT: analyst-visible stats (minor; degrade honestly)

---

PAGE: Corpora
ROUTE: `/corpora`
ACCESS: authenticated
PURPOSE: List, create, archive isolation boundaries.
USER GOAL: Pick a workspace; manage its lifecycle.
LAYOUT:
- top: PageHeader + New corpus button (ANALYST+)
- center: DataTable (Name/Owner/Status/Created) + row Archive (owner-only)
- modal: create form (name ≤200, description ≤2000)
PRIMARY ACTIONS: Select corpus (row click → sets picker), Create
SECONDARY ACTIONS: Archive (with provenance-preserved copy)
COMPONENTS: DataTable, Modal, Alert
DATA REQUIRED: `CorpusResponse[]`
API ENDPOINTS: `GET /api/corpora` [bare array]; `POST /api/corpora`;
`DELETE /api/corpora/{id}` (archive)
LOADING STATE: table skeleton
EMPTY STATE: "No corpora — create the first one"
ERROR STATE: ErrorState + traceId; 403 on foreign archive
SUCCESS STATE: toast-inline confirmation (no toast lib — Alert ok)
ANIMATIONS: modal reveal only
INTERACTIONS: create→auto-select new corpus
RESPONSIVE BEHAVIOR: table scrolls; modal full-screen ≤480px
ACCESSIBILITY: dialog roles on modal, focus trap-lite (return focus)
DEPENDENCIES: none
CURRENT IMPLEMENTATION: working — PRESERVE, swap table to DataTable
MISSING BACKEND SUPPORT: none (sharing/transfer deliberately absent)

---

PAGE: Documents
ROUTE: `/documents`
ACCESS: authenticated
PURPOSE: Corpus intake: browse, search, upload, track processing.
USER GOAL: Get source material in and see it move.
LAYOUT:
- top: PageHeader + Upload (file) / Paste-text toggle
- center: FilterBar (search, status, sort) + DataTable (Title/Status/
  Size/Updated) + Pager
PRIMARY ACTIONS: Upload file, Paste text
SECONDARY ACTIONS: Open detail, Reprocess (from detail)
COMPONENTS: DataTable, Pager, SearchInput (debounced NEW), UploadZone
DATA REQUIRED: `PageResponse<DocumentResponse>`
API ENDPOINTS: `GET /api/documents?corpusId&page&size` (ADD pager, consume
`hasNext`); `POST /api/documents` (multipart + JSON variants)
LOADING STATE: table skeleton; upload progress state (no % — indeterminate)
EMPTY STATE: drop-hint empty state with format list
ERROR STATE: 413/422 upload errors mapped to copy; ErrorState+traceId
SUCCESS STATE: new row appears, auto-navigate option to detail
ANIMATIONS: row entrance for new upload; polling shimmer on transient rows
INTERACTIONS: 3s poll only while rows transient (exists — keep)
RESPONSIVE BEHAVIOR: table scroll; upload panel stacks
ACCESSIBILITY: file input labelled, status announced via aria-live
DEPENDENCIES: Pager, SearchInput
CURRENT IMPLEMENTATION: working, page-0-only — MODIFY (pager + search)
MISSING BACKEND SUPPORT: server search/filter/sort (client-side until P2)

---

PAGE: Document detail
ROUTE: `/documents/:id`
ACCESS: authenticated
PURPOSE: Make processing visible as a pipeline, not a spinner.
USER GOAL: Trust what happened to my document.
LAYOUT:
- top: title + StatusBadge + PipelineStepper
  (UPLOAD→CHUNK→EXTRACT→VALIDATE→PROPOSE→APPROVAL)
- stats row: chunks / triples / claims / quarantined
- tabs: Source (pre) | Chunks (offsets/tokens) | Triples | Claims |
  Quarantine (raw response details) | Run history (`progress`)
PRIMARY ACTIONS: Reprocess, Jump to approval queue
SECONDARY ACTIONS: Open chunk source, Copy traceId
COMPONENTS: PipelineStepper (NEW), Stat, DataTable, Alert
DATA REQUIRED: DocumentResponse + content + chunks[] + progress map +
quarantine page
API ENDPOINTS: `GET /api/documents/{id}`, `/content`, `/chunks`,
`/progress`, `/quarantine`, `POST /api/documents/{id}/reprocess`
LOADING STATE: stepper skeleton + tab skeletons
EMPTY STATE: per-tab empties (e.g. "No quarantined chunks — clean run")
ERROR STATE: FAILED status → lastError panel + Reprocess CTA
SUCCESS STATE: AWAITING_APPROVAL Alert-ok linking /approval (exists — keep)
ANIMATIONS: stepper progress fill (400–1000ms); poll updates fade
INTERACTIONS: poll progress+quarantine 3s while busy (exists — keep)
RESPONSIVE BEHAVIOR: tabs scroll; stepper compacts to dots ≤700px
ACCESSIBILITY: stepper as ol with aria-current="step"
DEPENDENCIES: PipelineStepper
CURRENT IMPLEMENTATION: working — MODIFY (add stepper, keep 5-query fan-out)
MISSING BACKEND SUPPORT: none

---

PAGE: Approval queue
ROUTE: `/approval`
ACCESS: VERIFIER, ADMIN (others → `/unauthorized`)
PURPOSE: The human gate. Nothing downstream trusts unapproved proposals.
USER GOAL: Decide fast without losing auditability.
LAYOUT (3-pane workspace):
- left: proposal list (triples + claims, filterable, count badges)
- center: selected proposition (fact, source sentence, provenance,
  note input, APPROVE / REJECT)
- right: evidence context (chunk excerpt, document link, entity info)
PRIMARY ACTIONS: APPROVE, REJECT (keyboard: A / R)
SECONDARY ACTIONS: note input, skip, filter, pager
COMPONENTS: ProposalCard, EvidencePanel, Pager, Alert
DATA REQUIRED: `{triples,claims,pendingTripleCount,pendingClaimCount}`
API ENDPOINTS: `GET /api/approval-queue`; triple approve (JSON `{}` body!),
triple reject (body required), claim approve (no body), claim reject
(optional body). 409 → refetch + explain (exists as copy — keep)
LOADING STATE: list skeleton + detail skeleton
EMPTY STATE: "Queue clear — every proposal decided"
ERROR STATE: decision failure keeps item selected with inline error
SUCCESS STATE: item leaves list with subtle exit; counts tick down
ANIMATIONS: selection fade (100–180ms); decide-and-advance slide
INTERACTIONS: single `busyId` → per-item busy (fix); namespace notes
per kind+id (fix); decision advances selection
RESPONSIVE BEHAVIOR: panes → stacked with bottom-sheet evidence ≤900px
ACCESSIBILITY: listbox/option roles, A/R shortcuts announced + disabled
with reduced-motion off? (shortcuts always available, focus visible)
DEPENDENCIES: EvidencePanel, Pager
CURRENT IMPLEMENTATION: two generic tables — REBUILD layout, same endpoints
MISSING BACKEND SUPPORT: none

---

PAGE: Knowledge
ROUTE: `/knowledge`
ACCESS: authenticated
PURPOSE: Browse the approved record: triples, claims, entities.
USER GOAL: See what the corpus has established.
LAYOUT:
- top: tabs Triples | Claims | Entities (+ APPROVED default note)
- triples/claims: DataTable + status filter + Pager
- entities: SearchInput + list → EntityDossier drawer
PRIMARY ACTIONS: tab switch, entity inspect
SECONDARY ACTIONS: open source document from a row
COMPONENTS: DataTable, Pager, SearchInput, EntityDossier (drawer)
DATA REQUIRED: legacy `{content,total}` triples/claims; entity array + detail
API ENDPOINTS: `GET /api/triples`, `GET /api/claims` (ADD pager);
`GET /api/entities?search` (debounce — fix); `GET /api/entities/{id}`
LOADING STATE: tab skeleton
EMPTY STATE: "Nothing approved yet — the queue is the way in"
ERROR STATE: ErrorState + traceId
SUCCESS STATE: n/a
ANIMATIONS: tab fade only
INTERACTIONS: debounced search with abort (fix)
RESPONSIVE BEHAVIOR: drawer → bottom sheet ≤900px
ACCESSIBILITY: tabs with roles + arrow keys
DEPENDENCIES: SearchInput, EntityDossier
CURRENT IMPLEMENTATION: working — MODIFY (pager, debounce, dossier)
MISSING BACKEND SUPPORT: none

---

PAGE: Verification
ROUTE: `/verification`
ACCESS: authenticated (verify actions: any role on approved claims;
adjudication stays VERIFIER+ on detail page)
PURPOSE: Turn approved claims into checked verdicts.
USER GOAL: Run checks; see MODEL PROPOSED vs SYSTEM VERIFIED.
LAYOUT:
- top: stats (verdicts, by-type) + Verify-all button (limit picker)
- center: claims/verdicts DataTable with per-row Verify button + link
PRIMARY ACTIONS: Verify (row), Verify all
SECONDARY ACTIONS: Open verdict detail
COMPONENTS: DataTable, Pager, VerdictBadge
DATA REQUIRED: verdict list [L]; verify outcome map
API ENDPOINTS: `GET /api/verdicts?corpusId` (ADD pager);
`POST /api/claims/verify {claimId}` (WIRE the dead button);
`POST /api/claims/verify-all?corpusId&limit` (900s timeout, progress state)
LOADING STATE: row-levelbusy + batch progress ("12/50")
EMPTY STATE: "No approved claims to verify"
ERROR STATE: 503 LLM_UNAVAILABLE → loud Alert (never silent); per-row errors
SUCCESS STATE: outcome rendered inline (fused verdict + evidence count)
ANIMATIONS: outcome reveal; batch progress bar
INTERACTIONS: verify-all is serial server-side — show it as a queue
RESPONSIVE BEHAVIOR: table scroll; stats wrap
ACCESSIBILITY: progress as progressbar role; model-vs-system labels as text
DEPENDENCIES: Pager
CURRENT IMPLEMENTATION: works, verify-one dead, `<a href>` reload bug —
MODIFY (wire button, Link, pager)
MISSING BACKEND SUPPORT: none

---

PAGE: Verdicts + Verdict detail
ROUTE: `/verdicts`, `/verdicts/:id`
ACCESS: authenticated (adjudicate: VERIFIER+)
PURPOSE: Read decided verdicts; adjudicate contested ones.
USER GOAL: Understand and, if authorized, overrule with a note.
LAYOUT (detail):
- header: claim text + VerdictBadge + adjudication state
- KeyValues outcome (4 fields separate: llmScore, rulePenalty, fusedScore,
  evidenceStatus) + Why (verdictReason, retrievalQuery, trace link)
- evidence list (rank/score/chunk links)
- adjudication form (VERIFIER+, hidden once HUMAN_DECISION)
- history table
PRIMARY ACTIONS: Adjudicate (verdict select + note)
SECONDARY ACTIONS: Open trace, Open chunk, Copy citation
COMPONENTS: VerdictBadge, EvidencePanel, SourceCitation, DataTable
DATA REQUIRED: VerdictResponse + history array
API ENDPOINTS: `GET /api/verdicts` [L] + filter (ADD pager);
`GET /api/verdicts/{id}`, `/history`; `POST …/adjudicate {verdict,note?}`
(409 if already HUMAN_DECISION)
LOADING / EMPTY / ERROR / SUCCESS: standard; contested → adjudication CTA
ANIMATIONS: none beyond entrance (deliberately sober)
INTERACTIONS: history row → superseded-view note
RESPONSIVE BEHAVIOR: evidence stacks below outcome ≤900px
ACCESSIBILITY: the 4-field separation is text, never color-only (keep)
DEPENDENCIES: none new
CURRENT IMPLEMENTATION: solid — PRESERVE, add pager to list
MISSING BACKEND SUPPORT: none

---

PAGE: Graph explorer
ROUTE: `/graph`
ACCESS: authenticated
PURPOSE: See the Lattice. Primary visual, not a table page.
USER GOAL: Explore entities and relations; find what matters.
LAYOUT:
- full-bleed canvas (Cytoscape) with toolbar (scope toggle
  ALL_APPROVED/VERIFIED_ONLY, verified-only view, filter box, rebuild)
- left overlay: legend (confidence patterns) + stats
- right: EntityDossier inspector on selection (relations, chunk links)
- fallback tab: current tables (edge list, PageRank top-15, communities)
PRIMARY ACTIONS: select node, filter, scope toggle, open dossier
SECONDARY ACTIONS: zoom/pan/focus, highlight neighborhood, table view
COMPONENTS: GraphCanvas (NEW), EntityDossier, ConfidenceBadge,
GraphTableFallback (current page, kept)
DATA REQUIRED: GraphView{nodes,edges,stats,scope} + pagerank[] +
communities[]
API ENDPOINTS: `GET /api/graph/{corpusId}?scope`,
`/pagerank?scope&limit`, `/communities?scope` (PASS scope to all three — fix)
LOADING STATE: canvas skeleton with node-count shimmer
EMPTY STATE: "No approved triples yet — graph needs approvals"
ERROR STATE: ErrorState + fallback to tables if canvas fails
SUCCESS STATE: n/a
ANIMATIONS: layout settle (≤1000ms), neighborhood highlight/dim,
selection pulse (single, subtle)
INTERACTIONS: hover tooltip, click select, double-click focus, Esc clear
RESPONSIVE BEHAVIOR: full-screen canvas ≤900px; dossier → bottom sheet
ACCESSIBILITY: canvas has text fallback (tables); keyboard node list;
confidence never color-alone (edge dash + badge + tooltip)
DEPENDENCIES: cytoscape (new dep — the one justified addition)
CURRENT IMPLEMENTATION: tables only — REBUILD visual layer, keep data layer
MISSING BACKEND SUPPORT: single-entity subgraph (nice-to-have P2; client
neighborhood filter suffices)

---

PAGE: Contradictions
ROUTE: `/contradictions`
ACCESS: authenticated (convene/dismiss: VERIFIER+)
PURPOSE: Confront conflicts; route them to Council.
USER GOAL: Decide: dismiss, or convene a debate.
LAYOUT:
- top: stats (OPEN/IN_DEBATE/RESOLVED/DISMISSED) + Scan button + filter
- center: findings table, expandable Side A / Side B (triple+claim ids,
  rule code, explanation)
- below: Councils table (existing debates)
PRIMARY ACTIONS: Convene Debate (→/debates/:id), Dismiss (with confirm)
SECONDARY ACTIONS: Scan now, expand sides, open related verdicts
COMPONENTS: DataTable, Modal (confirm dismiss), VerdictBadge
DATA REQUIRED: legacy `{content,total}` + statistics counts + debates
API ENDPOINTS: `GET /api/contradictions?status` (ADD pager);
`POST …/scan?corpusId`; `POST …/{id}/dismiss`;
`POST …/{id}/debate {topic?}` → DebateResponse
LOADING STATE: table skeleton; scan progress
EMPTY STATE: "No contradictions — the corpus agrees with itself (so far)"
ERROR STATE: 409 dismiss-IN_DEBATE → explain + link debate
SUCCESS STATE: convene navigates; dismiss collapses row
ANIMATIONS: expand/collapse only
INTERACTIONS: single-expanded accordion (exists — keep)
RESPONSIVE BEHAVIOR: sides stack ≤700px
ACCESSIBILITY: expand buttons aria-expanded; status text badges
DEPENDENCIES: none new
CURRENT IMPLEMENTATION: working — MODIFY (pager, dismiss confirm)
MISSING BACKEND SUPPORT: none

---

PAGE: Council chamber
ROUTE: `/debates/:id`
ACCESS: VERIFIER, ADMIN
PURPOSE: Adversarial sense-making with the human visibly in charge.
USER GOAL: Hear HAWK/DOVE/SKEPTIC, weight, advance, synthesize.
LAYOUT:
- header: topic, FSM state badge, stream badge, Abort (fixed)
- FsmPanel (states + current + allowed-next hints)
- 3 persona columns (stack ≤900px): ArgumentCards arrive live
- SKEPTIC cards expose machine-evidence block (verdict, confidence,
  PageRank, evidence count)
- chair panel: per-argument 1–5 sliders (when AWAITING_CHAIR) → ADVANCE /
  SYNTHESIZE
PRIMARY ACTIONS: Start, Advance, Synthesize, Weight 1–5, Abort
SECONDARY ACTIONS: open citation, view FSM, open report
COMPONENTS: DebateColumn, ArgumentCard, ChairWeight, FsmPanel
DATA REQUIRED: DebateResponse (rounds+arguments+citations) + FSM map + SSE
API ENDPOINTS: `GET /api/debates/{id}`; `POST …/start|advance|abort|
synthesize`; `POST …/arguments/{aid}/weight {weight,note?}`;
`GET /api/debates/fsm`; SSE `GET …/stream?access_token=` (REST poll 4s
authoritative — keep)
LOADING STATE: chamber skeleton; round-pending shimmer
EMPTY STATE: CREATED → Start CTA
ERROR STATE: argument failure card (exists — keep); stream error → "Reload
to reconnect" + auto REST continuity note
SUCCESS STATE: SYNTHESIZING → report link appears
ANIMATIONS: argument enter (translate+fade ≤400ms); evidence attach;
verdict badge pop; round transition; synthesis transition. No loops.
INTERACTIONS: weights persist per argument; advance disabled until weights
present (guide, don't block — server decides)
RESPONSIVE BEHAVIOR: columns stack/swipe ≤900px; weights stay usable
ACCESSIBILITY: live region (polite) announces new arguments; sliders labelled
DEPENDENCIES: SSE cleanup discipline (exists — keep + add backoff P2)
CURRENT IMPLEMENTATION: strong — MODIFY (fix Abort, motion, FSM hints)
MISSING BACKEND SUPPORT: none

---

PAGE: Reports
ROUTE: `/reports`
ACCESS: authenticated
PURPOSE: Read synthesis like an intelligence report, not a dashboard.
USER GOAL: Consume the Council's conclusion with inspectable citations.
LAYOUT:
- left (280px): councils list (state badges)
- right: ReportReader (executive summary, Hawk/Dove/Skeptic positions,
  machine verdicts, what-corpus-establishes, uncertainty, recommendation,
  confidence; clickable citations → evidence drawer)
PRIMARY ACTIONS: Select council, Synthesise (ONE step — fix the two-step)
SECONDARY ACTIONS: open debate, open trace (traceRunId), print
COMPONENTS: ReportReader, SourceCitation, EvidencePanel
DATA REQUIRED: debates [P] + ReportResponse{blocks[],citations[]}
API ENDPOINTS: `GET /api/debates?corpusId`; `GET /api/debates/{id}/report`;
`POST …/synthesize` (single click with progress → report appears)
LOADING STATE: reader skeleton with block shapes
EMPTY STATE: "No councils yet — convene one from Contradictions"
ERROR STATE: no-report-yet → Synthesise CTA (not an error)
SUCCESS STATE: synthesis transition into reader
ANIMATIONS: block stagger-in (subtle, respects reduced-motion)
INTERACTIONS: citation click → drawer with chunk + verdict context
RESPONSIVE BEHAVIOR: list → select dropdown ≤900px
ACCESSIBILITY: document outline (h2 per section), citation links named
DEPENDENCIES: EvidencePanel drawer
CURRENT IMPLEMENTATION: works, two-step confusing — MODIFY (one-step)
MISSING BACKEND SUPPORT: report listing (by design — select via debates)

---

PAGE: Grounded chat
ROUTE: `/chat`
ACCESS: authenticated
PURPOSE: Corpus-grounded answers or honest refusal. Not a chatbot toy.
USER GOAL: Ask; see the answer AND what grounds it.
LAYOUT:
- left (260px): conversations (per-corpus filtered + labelled — fix)
- center: thread (3 bubble states exist: grounded / refused /
  unverified — keep + make visually distinct by layout, not just badge)
- right (persistent): evidence panel (passages, related claims/verdicts,
  confidence, trace link) — NEW persistence (exists as toggle — keep open)
PRIMARY ACTIONS: Ask, New conversation, Toggle citations
SECONDARY ACTIONS: open trace, open chunk, retry (120s timeout note)
COMPONENTS: ChatThread, MessageBubble, EvidencePanel (persistent)
DATA REQUIRED: sessions[] + {session,messages[]} + ask result
API ENDPOINTS: `GET /api/chat/sessions` (filter per corpus client-side,
label the limitation); `POST /api/chat/sessions {corpusId,title?}`;
`GET …/sessions/{id}`; `POST …/messages {question}` (unary, no streaming)
LOADING STATE: "Retrieving evidence…" state distinct from "Reasoning…"
(reflects grounded pipeline honestly)
EMPTY STATE: suggested grounded questions (static, corpus-aware copy)
ERROR STATE: refusal is a FIRST-CLASS state (exists — keep prominent);
timeout → retry CTA
SUCCESS STATE: answer + citations + trace link
ANIMATIONS: message entrance only; evidence panel sync highlight
INTERACTIONS: click citation → evidence panel scrolls + highlights
RESPONSIVE BEHAVIOR: evidence → drawer ≤900px; sessions → select
ACCESSIBILITY: thread as log role; refusal announced
DEPENDENCIES: none new
CURRENT IMPLEMENTATION: good bones — MODIFY layout (persistent evidence)
MISSING BACKEND SUPPORT: rename/delete/paging (don't promise in UI)

---

PAGE: Glass Box list + Trace detail
ROUTE: `/glassbox`, `/glassbox/:id` (alias `/traces` → redirect)
ACCESS: authenticated
PURPOSE: The signature experience: observable execution, replayable.
USER GOAL: See exactly what the system did, step by step, and replay it.
LAYOUT (detail):
- header: operation, status, duration, actor summary
- mode tabs: Tree | X-Ray (actor lanes) | REPLAY (NEW)
- replay: actor-lane timeline with play/pause/scrub; steps light in order
  REQUEST→RETRIEVAL→RULE ENGINE→LLM JUDGEMENT→FUSION→HUMAN DECISION
  (lanes shown only for steps the run actually has)
- step detail drawer: summaries, versions, model, timings, refs, errors
PRIMARY ACTIONS: Play/pause/scrub replay; switch Tree/X-Ray; open refs
SECONDARY ACTIONS: filter runs by operation; copy traceId
COMPONENTS: TraceTimeline, TraceStep, ReplayPlayer (NEW), ActorBadge
DATA REQUIRED: RunSummary page [P]; {run,steps} tree; xray lanes
API ENDPOINTS: `GET /api/traces` (ADD pager); `GET /api/traces/{id}`,
`/steps`, `/xray`
LOADING STATE: timeline skeleton
EMPTY STATE: "No runs yet — verify a claim or convene a debate"
ERROR STATE: RUNNING-stale note (heartbeat age); FAILED → errorMessage panel
SUCCESS STATE: n/a
ANIMATIONS: replay stepper (800–1600ms per phase, scrubbable, pausable);
step highlight, NOT auto-play on load (user presses play)
INTERACTIONS: click step → detail; scrub → jump; keyboard space/arrows
RESPONSIVE BEHAVIOR: lanes → vertical timeline ≤700px
ACCESSIBILITY: replay has text transcript (step list = transcript);
no autoplay; reduced-motion → instant jumps
DEPENDENCIES: ReplayPlayer
CURRENT IMPLEMENTATION: list+tree+xray solid — MODIFY (add replay + pager)
MISSING BACKEND SUPPORT: none. CRITICAL: render only observable step
summaries/IO — never invent chain-of-thought.

---

PAGE: Admin
ROUTE: `/admin`
ACCESS: ADMIN (others → `/unauthorized`)
PURPOSE: Users, jobs, system truth. Nothing else.
USER GOAL: Operate the platform.
LAYOUT:
- stats (corpora/docs/pending/quarantined)
- users table (inline role select, enabled badge) + create-user form
- jobs table (key/status/attempts/heartbeat) + RUNNING/ABANDONED alert
- system status card (provider, counts, checkedAt)
PRIMARY ACTIONS: Create user, Change role, Enable/disable, Refresh all
SECONDARY ACTIONS: inspect job errors
COMPONENTS: DataTable, Alert
DATA REQUIRED: users [P], jobs list, status map
API ENDPOINTS: `GET /api/admin/users` (ADD pager); `POST/PATCH …/users`;
`GET /api/admin/jobs?status`; `GET /api/admin/system/status`
LOADING / EMPTY / ERROR / SUCCESS: standard admin-table states
ANIMATIONS: none (deliberately plain)
INTERACTIONS: self-role-change → refresh session (exists — keep)
RESPONSIVE BEHAVIOR: tables scroll; form stacks
ACCESSIBILITY: role select labelled per row
DEPENDENCIES: none
CURRENT IMPLEMENTATION: working — PRESERVE, add pager
MISSING BACKEND SUPPORT: user delete, audit log (don't build UI for these)
