# PRISM Frontend Architecture

## Shell + layout

`AppShell`: sidebar (pipeline-grouped nav, role-filtered) + header
(breadcrumb, corpus picker context, session countdown, user menu) +
`MainWorkspace`. Sidebar → drawer ≤900px (new toggle; only new JS in shell).
Right inspector = per-page `Drawer` (entity dossier, evidence, step detail),
bottom-sheet ≤900px. No global right rail.

Grid: CSS only. Breakpoints: desktop >1200px (full 3-pane where specified),
tablet 700–1200px (2-pane / stacked), mobile ≤700px (single column,
drawers, full-screen graph canvas, stacked debate columns, vertical trace
timeline). Tables scroll horizontally; never shrink content into unreadability.

## Design system (extends current tokens.css — no framework, no kit)

- Color: keep surfaces/ink/lines/accent `#2f5fd0` + trust palette
  (approved/pending/rejected/verified/contradicted/missing) + actor palette
  (ENGINE/LLM/HUMAN). Rule (existing, keep): trust colors are never altered
  by theme layers; status always carries text + border-style/pattern/icon.
- Typography: current sans/mono stacks; add document-reading scale for
  ReportReader (measure ≤70ch, section rhythm). Type scale + spacing +
  radii (3/5/8) + shadows stay as tokenized.
- Components: grow `ui.tsx` (DataTable, Pager, Modal, Drawer, SearchInput,
  PipelineStepper, CountUp, ConfidenceBadge). One owner per component (§23).
- Dark mode: keep media-query remap; verify new canvas + replay against it.
- Identity: intelligent, precise, calm, investigative. No purple-AI look, no
  glassmorphism, no gradients-for-decoration, no particle/3D in app
  (homepage hero may use restrained technical motion only).

## State management (no new library)

- Server state: `useAsync` per view (exists). Add: pager state in URL
  (`?page=`), filters in URL where shareable; SSE state local to DebatePage
  with REST as authority; corpus selection in `CorpusContext` + localStorage
  (exists). Session countdown from `expiresInSeconds` (new, tiny).
- Cache: none beyond in-flight polling. No Redux/Zustand — unjustified at
  this size; Context + URL + local state covers every spec'd interaction.
- Mutations: `useAction` (exists) extended with per-item busy + optimistic
  list removal only where server confirms (approval queue).

## Routing

`BrowserRouter`, lazy pages + `Suspense`, `ErrorBoundary`, `ProtectedRoute`
(auth + role + `returnTo`), `/unauthorized`, `*` → NotFound (add dashboard
link). Keep `404.html` SPA fallback for Pages. Full map: FRONTEND-ROUTES.md.

## Performance (targeted, not blind)

- Graph: Cytoscape canvas (not DOM nodes); cap initial render (~500 nodes,
  then filter/neighborhood); memoize dossier; scope toggle re-queries.
- Lists: pager everywhere (fixes silent truncation AND payload size);
  entity search debounced + aborted.
- SSE: single `EventSource` per debate mount, `close()` on unmount (exists);
  REST poll 4s only while active states (exists). Add reconnect backoff P2.
- Chat: full-history fetch is backend-shaped; cap render window client-side
  (e.g. last 100 messages + "show earlier") to bound DOM.
- Route splitting exists (lazy pages) — keep. No virtualization needed once
  pagers land (page sizes ≤200).
- Abort in-flight fetches on unmount/param change (client supports `signal` —
  use it everywhere new).

## Responsive + accessibility (global rules)

- Every page spec lists both; shared rules: keyboard-reachable actions,
  visible focus (exists), `aria-live` for async changes (poll results, SSE
  arrivals, replay position), non-color status everywhere (exists — extend
  to canvas via legend + patterns), `prefers-reduced-motion` disables replay
  autoplay/argument animation (exists infra — honor in new motion),
  contrast check on new ConfidenceBadge, tables get `scope`/captions (gap —
  fix in DataTable once).
- Authenticated app stays SPA; SEO (title/OG/favicon/semantic headings)
  applies to the public homepage only.

## What is preserved vs redesigned

Preserved: API chokepoint, auth/corpus contexts, trust-badge language,
`useAsync`/`useAction`, StaticNotice, polling discipline, SSE+REST authority
split, 3-state chat bubbles, X-Ray lanes, role copy in footer.
Redesigned: homepage (new), auth routes (extract), approval layout, graph
visual, verification wiring, reports one-step, chat evidence persistence,
trace replay, CSS dedup, dashboard (new).
