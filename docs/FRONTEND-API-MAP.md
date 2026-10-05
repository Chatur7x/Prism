# PRISM Frontend API Map

Envelope legend: `[P]` standard PageResponse `{content,page,size,
totalElements,totalPages,hasNext}` · `[L]` legacy `{content,total,page,size}` ·
`[B]` bespoke/bare. All `/api/**` need `Authorization: Bearer`.

## Auth / shell

| Screen → Component | Endpoint · Method | Data used / UI state / Errors |
|---|---|---|
| Login card | `POST /api/auth/login {username,password}` | `accessToken,user,expiresInSeconds` → store, `?returnTo=` nav. 401 invalid creds; 429 wait-60s |
| Signup card | `POST /api/auth/register {username,email,password}` | Same shape, always ANALYST. 409 duplicate; 422 weak password |
| Session verify, header, admin refresh | `GET /api/auth/me` | `UserSummary` (role re-read). 401 → signed-out |
| Session countdown (NEW) | `POST /api/auth/refresh` (no body) | Fresh `AuthResponse` before expiry. Currently unused — wire it |
| Corpus picker, Corpora page | `GET /api/corpora` [B array] | `CorpusResponse[]`. 500-class = bug |
| Create/archive corpus | `POST /api/corpora`, `DELETE /api/corpora/{id}` (archive-only) | Owner-only archive; 403 otherwise |
| Dashboard (NEW) | `GET /api/corpora/{id}/statistics` (VERIFIER+) | Pending/verified/contradiction counts. ANALYST: MISSING BACKEND SUPPORT (read-only stats for analyst) — dashboard degrades to corpus+document counts via `GET /api/corpora`, `GET /api/documents` |
| Admin users/jobs/status | `GET /api/admin/users` [P], `POST/PATCH /api/admin/users`, `GET /api/admin/jobs`, `GET /api/admin/system/status` | ADMIN only; 403 → `/unauthorized` |

## Source (Documents, Approval)

| Screen → Component | Endpoint · Method | Data used / UI state / Errors |
|---|---|---|
| Documents list | `GET /api/documents?corpusId&page&size` [P] | Add pager (`hasNext`). Search/filter/sort: NO backend support — client-side over loaded pages, label honestly, or propose `?search=` backend addition (P2) |
| Upload | `POST /api/documents` multipart `{corpusId,title?,file}` | Keep raw-fetch (boundary); wrap errors into `ApiError` (fix). 413 oversize; 422 bad type |
| Paste-text | `POST /api/documents` JSON `{corpusId,title,contentText}` | Standard chokepoint |
| Document detail | `GET /api/documents/{id}`, `/content`, `/chunks` [B], `/progress`, `/quarantine` [P], `POST /api/documents/{id}/reprocess` | `PipelineStepper` from `progress` + `status`; poll 3s while transient |
| Approval queue | `GET /api/approval-queue?corpusId&page&size` [B `{triples,claims,…}`] | Namespace note inputs per kind+id (fix). Add pager |
| Approve/reject | `POST /api/triples/{id}/approve` (JSON `{}` body — bodiless 415s), `/reject` (body required), `POST /api/claims/{id}/approve` (no body), `/reject` (optional body) | 409 already-decided → refetch row; VERIFIER+ only |

## Record (Knowledge, Verification, Verdicts, Graph data)

| Screen → Component | Endpoint · Method | Data used / UI state / Errors |
|---|---|---|
| Knowledge tabs | `GET /api/triples?corpusId&status` [L], `GET /api/claims?…` [L], `GET /api/entities?corpusId&search` [B] | Debounce entity search (fix). Entity dossier: `GET /api/entities/{id}` |
| Verification | `GET /api/verdicts?corpusId` [L] (current source), `POST /api/claims/verify {claimId}` (wire the dead button), `POST /api/claims/verify-all?corpusId&limit` (900s timeout) | 503 LLM_UNAVAILABLE → loud failure, never fake |
| Verdict detail | `GET /api/verdicts/{id}`, `/history` [B], `POST /api/verdicts/{id}/adjudicate {verdict,note?}` | 409 already HUMAN_DECISION. Keep 4 fields separate in UI |
| Graph explorer | `GET /api/graph/{corpusId}?scope`, `/pagerank?scope&limit`, `/communities?scope` | Pass scope to ALL three (fix). No Cytoscape data transform beyond id/label maps |

## Analysis (Contradictions, Council, Reports)

| Screen → Component | Endpoint · Method | Data used / UI state / Errors |
|---|---|---|
| Contradictions | `GET /api/contradictions?corpusId&status` [L], `POST /api/contradictions/scan?corpusId`, `POST /api/contradictions/{id}/dismiss`, `POST …/debate {topic?}` (VERIFIER+) | Scan result counts; convene → nav `/debates/{id}` |
| Debate chamber | `GET /api/debates/{id}`, `POST …/start|advance|abort|synthesize`, `POST …/arguments/{aid}/weight {weight 1..5,note?}`, `GET /api/debates/fsm`, `GET /api/debates/{id}/stream` SSE (`?access_token=`) | SSE = convenience, REST poll 4s authoritative. Fix Abort wiring. Synthesize → `{reportId}` |
| Reports | `GET /api/debates?corpusId` [P] (pick debate), `GET /api/debates/{id}/report` | One-step synthesise (fix). No report-list endpoint: MISSING, by design (fetch via debate) |

## Use (Chat, Glass Box)

| Screen → Component | Endpoint · Method | Data used / UI state / Errors |
|---|---|---|
| Chat | `GET /api/chat/sessions` [B], `POST /api/chat/sessions {corpusId,title?}`, `GET /api/chat/sessions/{id}` (full history), `POST …/messages {question}` (120s) | No streaming (unary). No rename/delete/paging: MISSING BACKEND SUPPORT — UI must not promise them. Filter sessions per corpus client-side + label |
| Glass Box list | `GET /api/traces?corpusId&page&size` [P] | Client op-filter exists; add pager |
| Trace detail/replay | `GET /api/traces/{id}` (`{run,steps}` tree), `/steps` [B], `/xray` (actor lanes) | Replay animates existing steps. Never render hidden CoT — only step summaries I/O |

## Global error contract

`{status,code,message,path,traceId}` + `X-Trace-Id`. UI shows message +
traceId; 401 → re-login (with returnTo + "session expired" copy);
403 → `/unauthorized`; 409 → refetch + explain; 412 REAL_MODEL… (evals only);
429 → honor `Retry-After`; 500 → "bug, report traceId".
