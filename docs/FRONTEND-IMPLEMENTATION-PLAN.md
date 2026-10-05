# PRISM Frontend Implementation Plan

## Priority classes

- **P0** — must build first: broken behavior, structural shell/routing,
  foundation every phase needs. No P0, no product.
- **P1** — required for a complete product: every pipeline page working
  end-to-end with honest states.
- **P2** — polish: motion, responsive refinements, reconnect/backoff,
  search/sort upgrades, dead-code removal.
- **P3** — optional / blocked: forgot-reset (needs backend), admin audit log,
  subgraph endpoint, chat rename/delete.

## Phases (dependency-safe)

**PHASE 1 — Foundation (P0).** Design-token consolidation (dedup CSS,
DataTable/Pager/Modal/Drawer/SearchInput/PipelineStepper into ui.tsx);
`ProtectedRoute` + `/unauthorized` + `?returnTo=`; session countdown wired
to `POST /api/auth/refresh`; fix Abort wiring, `<a href>` reload, approval
note-namespace, per-item busy, upload error wrapping. Exit: no known-broken
interactions; every list paged.

**PHASE 2 — Public + auth (P0/P1).** Homepage `/` (8 sections, static);
extract AuthCard → `/login` + `/signup` routes; WelcomePage retired to
redirect. Exit: public site explains PRISM; auth is route-addressable.

**PHASE 3 — Dashboard + Source (P1).** `/dashboard` (statistics-driven,
analyst-degraded honestly); Documents pager + debounced search; Document
detail PipelineStepper. Exit: intake-to-queue path fully visible.

**PHASE 4 — Record (P1).** Approval 3-pane workspace; verification
verify-one wiring + batch progress; Knowledge dossier drawer + pager;
verdict adjudication unchanged. Exit: human gate hums; MODEL/SYSTEM
distinction unmistakable.

**PHASE 5 — Graph (P1).** Cytoscape canvas + dossier + scope-correct
metrics + table fallback. Exit: Lattice is explorable, not tabular.

**PHASE 6 — Council (P1).** Contradictions dismiss-confirm + pager;
debate motion + FSM hints; Reports one-step synthesise + ReportReader.
Exit: conflict → debate → report reads as one story.

**PHASE 7 — Use (P1).** Chat persistent evidence panel + corpus labelling;
Glass Box pager + ReplayPlayer (lanes, scrub, transcript). Exit: grounding
and replay are the product's showpieces.

**PHASE 8 — Admin + hardening (P1).** Admin pager; role-denied redirects
everywhere; 429/412/503 copy pass; empty/error state audit per page spec.
Exit: every page defines all nine states (spec §20).

**PHASE 9 — Responsive + a11y (P2).** Drawer/breakpoint pass, table
captions/scopes, live regions, focus management, contrast check on new
badges, keyboard map for debate + replay. Exit: desktop/tablet/mobile
verified per spec; reduced-motion verified.

**PHASE 10 — Motion + polish (P2).** Apply MOTION.md surface by surface;
homepage scroll motion; skeleton/progress consistency; remove dead code
(Reports dynamic import, unused streamRef reads, `require_debates` check).
Exit: motion supports comprehension; no decoration animation in app.

**P3 backlog (do not schedule):** forgot/reset UI (blocked on backend),
chat rename/delete/paging, user delete/audit log, entity subgraph endpoint,
SSE reconnect backoff (fold into P2 if time), server-side doc search.

## Sequencing rules

- Never break a working page mid-phase: land Pager/DataTable as
  drop-in replacements first, redesign after.
- One new dependency total: Cytoscape (Phase 5). Everything else is
  hand CSS + existing React.
- Backend changes required by this plan: none for P0–P2 except
  *optional* analyst-stats and doc-search (both have honest fallbacks).
  P3 backend items stay backend-owned.
