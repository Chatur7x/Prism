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

## 7. Open assumption

Accent mood deep-navy/cobalt chosen by designer; one-line token swap to
emerald if the reviewer prefers. Confirm during spec review.
