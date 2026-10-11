# Plan D — Light-Route Motion Upgrades and Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Graph, debate, and chat feel alive in the existing light system, then the whole pass is proven green (types, build, rehearsal, screenshots, motion-safety).

**Architecture:** Additive classes and hook use inside the three pages; one shared typing-indicator style and weight-bar style added to `dark-shell.css`'s light-safe section or `motion.css` only if a token is missing — never restyling components.

**Tech Stack:** React 18, TypeScript, existing CSS system.

**Spec:** `docs/superpowers/specs/2026-10-11-dark-shell-design.md` (§4–§6)

## Global Constraints

- No functionality change on any of the three routes.
- Depends on Plan B hooks; class names from Plan A where shared.
- Existing motion vocabulary (`--dur-*`, easings) reused; no new keyframes except typing dots and weight-bar fill.
- Full suite green at the end: tsc, build, 17/17 rehearsal, screenshots.

## Review Focus

- Debate live updates still never steal scroll position.
- Chat citations and pending states behave identically with JS motion off.
- Graph hover-dim and layout behaviour unchanged (chrome only).
- Reduced-motion emulation leaves every page fully readable.
- The final diff contains no logic changes outside class/hook wiring.

## File Structure

- Modify `frontend/src/pages/ChatPage.tsx` — citation hover classes, typing indicator, `.reveal` on history sections.
- Modify `frontend/src/pages/DebatePage.tsx` — animated weight bars, live-badge polish classes.
- Modify `frontend/src/pages/GraphPage.tsx` — legend hover classes, `useCountUp` on node/edge stats.
- Possibly extend `frontend/src/styles/dark-shell.css` with a clearly-marked light-safe section (typing dots, weight bars) — or `motion.css` if the reviewer judges it belongs to the motion layer.

---

### Task 1: Chat, debate, graph motion upgrades

**Files:**
- Modify: `frontend/src/pages/ChatPage.tsx`, `frontend/src/pages/DebatePage.tsx`, `frontend/src/pages/GraphPage.tsx`, plus one stylesheet section.

**Interfaces:**
- Consumes: Plan B hooks; Plan A class names.

- [ ] **Step 1: Apply the three pages' upgrades per spec §4**
- [ ] **Step 2: Build and screenshot each route at 1920px and 390px**

Run: `cd frontend && npm run build`
Expected: passes; six screenshots show upgraded motion states with identical content.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/ChatPage.tsx frontend/src/pages/DebatePage.tsx frontend/src/pages/GraphPage.tsx frontend/src/styles/dark-shell.css
git commit -m "Motion upgrades for chat, debate, graph (light system intact)"
```

### Task 2: Full verification and evidence

**Files:**
- None (evidence only).

- [ ] **Step 1: Typecheck, lint, and production build**

Run: `cd frontend && npx tsc --noEmit && npx eslint src --max-warnings 0 && npm run build`
Expected: all clean.

- [ ] **Step 2: Full rehearsal 17/17**
- [ ] **Step 3: Reduced-motion and contrast spot-checks**

Emulate `prefers-reduced-motion` and confirm: aurora frozen, count-ups instant, reveals visible, pending dots static. Spot-check dark body/secondary contrast.

- [ ] **Step 4: Diff review and report**

Confirm the branch diff touches only styles, the two hooks, and class/hook wiring. Report counts: files changed, rehearsal result, screenshot set locations.
