# PRISM Frontend Routes

Conventions: `*` = exists today. `(NEW)` = to build. `(BACKEND GAP)` =
UI specified, endpoint missing — build UI only if phase plan says so.

## Public

| Route | Access | Purpose | Status |
|---|---|---|---|
| `/` | public | Product homepage (hero, pipeline, Lattice, Redline, Council, Glass Box, chat, CTA) | NEW — today a redirect to `/documents` |
| `/login` | public (redirects to app if signed in) | Sign-in card | NEW route — reuses existing card |
| `/signup` | public (redirects to app if signed in) | Self-register as ANALYST | NEW route — reuses existing card |
| `/forgot-password` | public | Request reset link | BACKEND GAP — no endpoint. P3, build only with backend |
| `/reset-password` | public (token query param) | Set new password | BACKEND GAP — no endpoint. P3 |
| `/unauthorized` | authenticated | Role-denied explanation (replaces in-place `<Empty>`) | NEW, trivial |

Deep-link rule: unauthenticated visit to an app route remembers the target
(`?returnTo=`) and the login flow navigates back after success. (Fixes the
current always-Welcome behavior.)

## Authenticated app (any role unless noted)

| Route | Access | Purpose | Status |
|---|---|---|---|
| `/dashboard` | any | Pipeline overview: SOURCE→RECORD→VERIFY→ANALYZE→USE | NEW |
| `/corpora` | any | Corpus list / create / archive | * |
| `/documents` | any | Document list, search/filter/sort/pager, upload | * MODIFY (add pager) |
| `/documents/:id` | any | Pipeline stepper, chunks, triples, claims, quarantine, run history | * MODIFY (stepper) |
| `/approval` | VERIFIER, ADMIN | 3-pane evidence review workspace | * REBUILD layout, same endpoints |
| `/knowledge` | any | Approved triples / claims / entities | * |
| `/verification` | any | Claims awaiting + verified list, verify actions | * MODIFY (wire verify-one) |
| `/verdicts` | any | Verdict list with type filter | * |
| `/verdicts/:id` | any | Verdict detail: evidence, 4-field outcome, adjudication | * |
| `/graph` | any | Canvas graph explorer (+ table fallback) | * REBUILD visual, same endpoints + Cytoscape |
| `/contradictions` | any | Findings, scan, dismiss; convene (VERIFIER+) | * |
| `/debates/:id` | VERIFIER, ADMIN | Council chamber: HAWK/DOVE/SKEPTIC live, weights, advance, synthesize | * MODIFY (fix abort, motion) |
| `/reports` | any | Council list + synthesis report reader | * MODIFY (one-step synthesise) |
| `/chat` | any | Grounded chat + persistent evidence panel | * MODIFY layout |
| `/glassbox` (`/traces` alias, see below) | any | Trace runs list | * |
| `/glassbox/:id` | any | Trace tree/X-Ray + REPLAY mode | * MODIFY (add replay) |
| `/admin` | ADMIN | Users, roles, jobs, system status | * |

Notes:
- The brief asked for `/claims`, `/claims/:id`, `/traces`, `/traces/:id`,
  `/reports/:id`. Mapping to reality: claims live under `/verification`
  (pending) + `/verdicts` (decided) — do NOT add alias `/claims` routes;
  it would fork the data layer. Keep canonical `/glassbox` (existing links
  point there) and add a `/traces`→`/glassbox` redirect alias for API parity.
  Reports have no list endpoint, so `/reports/:id` cannot exist; the Reports
  page selects a debate and reads `GET /api/debates/{id}/report`.
- `/documents/:id`, `/verdicts/:id`, `/debates/:id`, `/glassbox/:id` stay out
  of the sidebar (reachable via in-page links only) — current behavior, keep.
- Role-denied app routes render `/unauthorized` (redirect), not in-place
  `<Empty>` — keeps URL and UI truthful.
- Unknown routes: keep the `NotFound` page, add a link back to `/dashboard`.
