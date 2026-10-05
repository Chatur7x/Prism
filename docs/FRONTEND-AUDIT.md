# PRISM Frontend Audit

Source of truth: the repository as it stands. Backend capabilities are taken from
`backend/src/main/java/com/prism` (14 controllers). Frontend facts are taken from
`frontend/src` (33 files, React 18 + react-router-dom 6 + hand-written CSS, no UI
framework, no graph lib, no state lib). Product terms Lattice / Redline / Council /
Glass Box appear in code comments and some UI copy; only Council and Glass Box are
canonical user-facing terms in docs.

## 1. What already exists (working, keep)

- Auth: login + self-register (ANALYST-only) on a combined Welcome screen;
  JWT in `localStorage`, `GET /api/auth/me` session verify, 401 → signed-out swap,
  role-filtered nav, logout. Roles come from the server on every `me` call.
- Corpus-scoped app shell: sidebar (12 links, 4 pipeline groups), corpus picker
  persisted in `localStorage`, `canVerify` flag, header, footer with role hint.
- Pages (all authenticated, all functional against real endpoints): Corpora,
  Documents (+ detail with 5 parallel queries, 3s polling, quarantine table),
  Approval Queue, Knowledge (triples/claims/entities tabs), Verification,
  Verdicts (+ detail with adjudication + history), Graph (tables-only),
  Contradictions (scan/convene/dismiss + councils list), Debate chamber
  (REST + SSE live events, chair weights, synthesize), Reports (per-debate
  synthesis report), Grounded Chat (3 message states, citations, trace link),
  Glass Box (trace list + tree/X-Ray detail), Admin (users, roles, jobs, status).
- API layer: single `request()` chokepoint, `ApiError` with traceId/violations,
  30s timeout + abort, 429 retryable flag, `STATIC_ONLY` banner, dev proxy.
- Trust semantics never color-alone: badge text + border-style channel
  (pending dashed, contradicted double, missing dotted, verified 2px).
  Focus-visible rings, `prefers-reduced-motion`, `prefers-contrast`,
  print stylesheet. 6 CSS files, token file with dark-mode remap.
- `useAsync`/`useAction` helpers with stale-response guards; `ErrorBoundary`
  with traceId extraction; `StaticNotice` for the Pages build.

## 2. Partially implemented (works, but incomplete)

- Pagination: every list fetches page 0 once (sizes 50–200); `hasNext` never
  consumed. Large corpora silently truncated. Approval queue uses its own
  `{triples, claims}` envelope; triples/claims/verdicts/contradictions use
  legacy `{content,total}` while documents/debates/traces/admin use standard
  `PageResponse`. Frontend must branch per endpoint.
- Debate Abort button exists but calls reload instead of `abort.run()` (dead).
- Verification page defines verify-one action with no button wired to it.
- Reports "Synthesise" is a confusing two-step (select, then click again).
- Chat sessions list is not corpus-filtered though the page is corpus-scoped.
- Graph scope toggle re-queries edges but PageRank/communities always use ALL.
- Entity search fires per keystroke, no debounce/abort.
- Single `busyId` blocks concurrent approval decisions; triple/claim note
  inputs share one `Record<number,string>` (id-namespace collision).
- Upload bypasses `request()` (raw fetch for multipart) and downgrades errors
  to plain `Error` (no traceId in UI).
- `expiresInSeconds` received, never used: no refresh, no pre-expiry warning.
  `POST /api/auth/refresh` exists in backend and is unused by the frontend.

## 3. Missing (no frontend, backend status noted)

- Homepage `/`: currently a bare redirect to `/documents`. PROPOSED (spec §3).
- Dedicated `/login`, `/signup` routes: currently modes on WelcomePage.
  Backend supports both; routes are new, endpoints exist.
- `/forgot-password`, `/reset-password`: no UI and NO backend endpoints.
  Marked MISSING BACKEND SUPPORT (P3).
- `/dashboard`: no page. Backend `GET /api/corpora/{id}/statistics`
  (VERIFIER+) exists and covers most numbers; ANALYST has no stats endpoint.
- Graph canvas: no Cytoscape/d3/sigma. `GraphPage` is tables only. The single
  biggest visual gap. Library addition required (P1).
- Pager controls, global search, notifications, session list, remember-me,
  profile edit, corpus sharing UI (no backend for sharing at all).
- Report listing: no `GET /api/reports`; reports reachable only via debates.
- `GET /api/debates` list endpoint: backend has it; check current frontend use
  (ContradictionsPage lists debates via contradictionApi — verify during build).

## 4. Broken (fix before/with redesign)

1. Debate Abort button never aborts (`DebatePage.tsx:254-260`). One-line fix.
2. `<a href=/verdicts/id>` in VerificationPage causes full reload. Use `<Link>`.
3. Approval note input collision across triple/claim ids. Namespace the keys.
4. Graph scope leak (pagerank/communities ignore scope). Pass scope through.
5. `DocumentService.delete()` is a 409 stub: remove any delete affordance or
   keep the explicit "never deleted" copy. No UI delete exists — keep it so.
6. `docs/How-To-Use-PRISM.md` links `docs/real-evaluation.md` and
   `scripts/run-real-evaluation.ps1` — neither exists. Fix links or mark P3.
7. Test-count drift: How-To says 256, README/WelcomePage say 248, suite now
   runs 259. Single-source this number or drop it from UI copy.

## 5. Redesign (keep logic, replace presentation)

- WelcomePage: split marketing hero from auth card; add route-level
  `/login` + `/signup` reusing the same card component.
- GraphPage: tables → canvas-first explorer (keep tables as fallback/list view).
- ApprovalQueuePage: generic tables → 3-pane evidence review workspace.
- VerificationPage/VerdictsPage: merge into one claims-verification flow
  (`/verification` list → `/verdicts/:id` detail reads well; keep both routes,
  unify their data layer so verify-one works from the list).
- ReportsPage: two-step synthesise → single explicit action with state.
- ChatPage: generic two-column → grounded-research workspace with persistent
  evidence panel (same data, new layout).
- TraceDetailPage: add the animated REPLAY mode (data already present).
- CSS: deduplicate shell/sidebar/focus/mono rules (tokens.css vs
  app.css/components.css disagree: 248px vs 232px sidebar).

## 6. Do NOT touch

- `api/client.ts` request chokepoint semantics (auth header, 401 clearing,
  timeout/abort, error envelope). Extend, don't rewrite.
- `AuthContext` + `CorpusContext` ownership/role semantics; corpus isolation
  is a backend invariant, selection is UI default only.
- Trust-badge system (text + border-style, never color alone).
- `StaticNotice` + static-build behavior for GitHub Pages.
- Backend auth, roles, and rate limits. Frontend adapts (use `/refresh`,
  respect 429 + `Retry-After`).
- The deterministic-pipeline copy: MODEL PROPOSED vs SYSTEM VERIFIED wording,
  fake-provider warnings, NOT RELEASE CANDIDATE banners.
- No new state library, no CSS framework, no component kit. Hand CSS +
  Context + URL state is sufficient and already consistent.
