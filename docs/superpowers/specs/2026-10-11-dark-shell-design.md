# Dark Shell UI/UX Design — Spec

Date: 2026-10-11 · Scope: hero surfaces (Home, login/signup, dashboard) go
cinematic dark; graph, debate, chat keep the light system with motion
upgrades; all other routes untouched. Technique: CSS + two tiny hooks, zero
new dependencies. Functionality: unchanged. Mood: deep-navy/cobalt.

## 1. Route scoping and theme mechanism

- `AppShell` reads the route and toggles a `theme-dark` class on the shell
  wrapper for `/`, `/login`, `/signup`, `/dashboard` only.
- Every dark rule is scoped under `.theme-dark`. The light system is not
  edited (except additive utilities). Removing the class reverts everything.
- New file `frontend/src/styles/dark-shell.css`, imported after `motion.css`
  so it can reuse duration/easing tokens. Dark palette lives there as
  `.theme-dark` variable overrides, not in `tokens.css`, so the audit
  system's single-source tokens stay canonical.

## 2. Dark shell tokens

- Backgrounds: abyss `#070b16`, deep `#0a1122`, panel `#0e1628` (translucent
  variants for glass). Ink: `#eef2fa` primary, `#a9b4cc` secondary,
  `#6d7a94` metadata. Lines: `rgba(148,170,220,0.14)` / `0.22`.
- Accent: cobalt `#5b8cff` (light-lifted sibling of the product `#2f5fd0`),
  hover `#7aa5ff`, soft `rgba(91,140,255,0.14)`. LLM violet and human green
  keep their hues, re-picked for dark (witness: actor badges on dashboard).
- Trust badges keep ink + border-style semantics on dark fills; contradiction
  keeps double weight. No badge becomes colour-alone.

## 3. Signature elements (dark routes)

- **Aurora background.** Two large radial-gradient blobs (cobalt, violet)
  drifting on 24s/32s loops, transform-only, `prefers-reduced-motion`
  freezes them. Sits behind content at low opacity; never behind dense
  tables (dark routes have none).
- **Glass cards.** `backdrop-filter: blur(16px)`, translucent panel fill,
  1px gradient border via `border-image` or double-background trick.
  Falls back to solid panel under `prefers-reduced-transparency`.
- **Staggered entrances.** Hero/dashboard blocks enter with the existing
  `--dur-enter`/`--ease-enter` vocabulary, delays in `--dur-stagger-step`
  multiples, capped at 8 items.
- **Count-up stats** (dashboard tiles). New `useCountUp` hook:
  IntersectionObserver-gated, rAF-driven, 900ms, ease-out; reduced-motion
  renders the final value with no animation. Respects commas/decimals.
- **Reveal hook.** New `useReveal` hook adds `is-visible` when an element
  enters the viewport (once, 120px root margin); CSS `.reveal` handles
  fade+8px rise. Used for below-fold home sections and dashboard rows.
- **Primary buttons.** Cobalt gradient, glow shadow on hover
  (`0 0 24px rgba(91,140,255,0.35)`), existing 0.97 press compression kept.
- **Auth card.** Glass panel on aurora, inputs get dark inset fills with
  cobalt focus rings (2px + offset, visible on dark).

## 4. Light-route motion upgrades (graph, debate, chat)

- Chat: assistant messages keep entrance; add citation-chip hover lift and a
  three-dot typing indicator (opacity pulse, transform-free) for pending.
- Debate: keep `argument-enter`; add animated weight bars (width transition
  from `--dur-enter`) and a live-round pulse on the IN_DEBATE badge
  (existing breathe token, no new loop).
- Graph: keep stage entrance and hover-dim; add legend swatch hover and a
  count-up on the node/edge stats reusing `useCountUp`.
- Shared: `.reveal` utility available to all light pages for below-fold
  content; no page is forced to use it.

## 5. Files touched

- New: `styles/dark-shell.css`, `hooks/useReveal.ts`, `hooks/useCountUp.ts`.
- Edited: `components/AppShell.tsx` (route class toggle),
  `pages/HomePage.tsx`, `pages/LoginPage.tsx`, `pages/SignupPage.tsx`
  (via `components/AuthCard.tsx`), `pages/DashboardPage.tsx` (stats +
  classes), `pages/ChatPage.tsx`, `pages/DebatePage.tsx`,
  `pages/GraphPage.tsx` (class additions + hook use only), `main.tsx`
  (stylesheet import if not globbed).
- Not touched: tokens.css values, trust/actor semantics, any API call,
  any route, any role gate, backend.

## 6. Verification

- `npx tsc --noEmit` + `npm run build` green.
- Full-route Playwright rehearsal still 17/17; screenshots at 1920px and
  390px for Home, login, dashboard, graph, debate, chat.
- Reduced-motion emulation: aurora frozen, count-ups instant, reveals
  visible, no content hidden.
- Contrast spot-check on dark body/secondary text (7:1 / 4.5:1 targets).
- Diff review: styles + 2 hooks + class toggles only; no logic changes.

## 9. Beast v2 — verification-console identity (approved 2026-10-11)

Supersedes the cobalt mood: the dark shell becomes a distinctive
"verification console" drawn from the product's own subject (the
document → verdict pipeline). Cobalt remains as a secondary/ice tone;
the primary accent is signal amber, spent only on verdict moments.

### Tokens (dark shell scope)

- `--signal: #ffb224` (primary accent: CTAs, verified ticks, active states)
- `--signal-hover: #ffc44d`, `--signal-soft: rgba(255,178,36,0.14)`
- `--ice: #7cc7ff` (secondary: links, info highlights, ribbon secondary pulse)
- `--abyss: #060913` (page base, deeper than before)

### Class contracts (Agent 1 implements CSS, Agent 2 consumes these exact names)

- `.display-hero` — oversized tight-tracked hero headline
  (`clamp(2.5rem, 6vw, 4.5rem)`, `-0.04em`, line-height 1.02).
- `.ribbon` — pipeline conduit container (position relative, gradient
  track line); `.ribbon-pulse` — traveling pulse dot (transform-only
  keyframe along the track, 3s loop, staggered delays via inline style).
- `.tile-glow` — console tile with status glow (amber for action-needed,
  ice for info, none for neutral); glow via box-shadow only.
- `.edge-light` — active nav indicator: 2px amber inset rule (replaces
  the neutral rail colour on dark routes only).
- `.btn-signal` — signal-amber primary button (dark bg, `#1a1206` ink);
  existing `.btn.primary` untouched for light routes.
- `.stat-tabular` — `font-variant-numeric: tabular-nums` for all animated stats.

### Markup (Agent 2)

- Home: left-aligned hero — kicker, `.display-hero` headline, lede,
  CTA row (`.btn-signal` primary), right side: `.ribbon` with the 10
  pipeline stages as nodes and 3 traveling `.ribbon-pulse` dots.
  Below-fold sections unchanged in content, keep `.reveal`.
- Dashboard: pipeline strip stays; stat tiles become `.tile-glow`
  variants by status; numbers keep `useCountUp` + `.stat-tabular`.
- All copy, routes, links, forms, gates unchanged. Reduced-motion:
  ribbon pulses frozen, count-ups instant (existing hooks/CSS blocks).
- 390px: ribbon stacks below headline, pulses keep transform-only motion.

## 8. Apple-design applications (apple-design skill)

Applied where an audit tool honestly has gesture-free surfaces; nothing
gesture-driven is introduced to justify springs.

- **Press-down feedback.** Already the codebase rule (`:active` scale on
  `.btn`/`.nav-link`); extend the audit to every new dark control and keep
  it on pointer-down via `:active` (no JS delay).
- **Materialize, don't fade.** Glass/modal entrances animate blur radius
  and scale together (`.materialize` utility: `filter: blur(8px)` +
  `scale(0.97)` → sharp + `scale(1)`), so surfaces arrive as material.
  Blur animation is confined to small surfaces (cards, dialogs), never
  full-page backgrounds.
- **Scroll-edge effects.** Sidebar nav and chat history use a fade/blur
  mask where content meets sticky chrome instead of hard divider lines.
- **Vibrancy text on glass.** Text over glass uses higher-contrast ink
  (`--ink-0` never `--ink-2`), slightly heavier weight, +0.01em tracking.
- **Mirrored easings.** Add `--ease-enter-inverse` (inverse control points
  of `--ease-enter`) and use it for reversible pairs (toast in/out,
  drawer in/out) so return paths mirror outbound paths.
- **Interruptibility.** All new keyframe entrances are non-gesture and
  fire once per mount (`both` fill); nothing locks input mid-transition.
  No gesture-driven animation is added, so no spring driver is needed —
  the critically-damped bézier equivalents in tokens stay.
