# Plan B — Reveal and Count-Up Hooks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two tiny, dependency-free hooks (`useReveal`, `useCountUp`) that Plans C and D consume for scroll entrances and dashboard/graph stat animations.

**Architecture:** `src/hooks/` is new; each hook is one focused file with no imports beyond React. CSS classes they toggle (`.reveal`, `.is-visible`) are defined in Plan A's stylesheet.

**Tech Stack:** React 18, TypeScript, IntersectionObserver, requestAnimationFrame.

**Spec:** `docs/superpowers/specs/2026-10-11-dark-shell-design.md` (§3, §5)

## Global Constraints

- Zero new dependencies; no file over ~60 lines.
- `useReveal` fires once per element and disconnects; SSR-unsafe APIs guarded (no `window`/`IntersectionObserver` access during render).
- `useCountUp` renders the exact final value when `prefers-reduced-motion` matches, with no animation frames.
- `npx tsc --noEmit` and `npm run build` stay green; `npx eslint` clean on new files.

## Review Focus

- Observer cleanup on unmount (no leaked observers across route remounts).
- Count-up never shows a wrong intermediate as final (rAF overshoot clamps).
- Formatting keeps thousands separators and honours the `decimals` argument.
- Hooks do nothing when JS-driven motion is disabled — final states render.
- No `any` types; strict TS passes.

## File Structure

- Create `frontend/src/hooks/useReveal.ts` — `useReveal<T extends HTMLElement>(): (node: T | null) => void`; adds `is-visible` to the node on first intersection (120px root margin), then disconnects.
- Create `frontend/src/hooks/useCountUp.ts` — `useCountUp(target: number, decimals?: number): string`; rAF count over 900ms ease-out once mounted, exact final string under reduced motion.

---

### Task 1: useReveal hook

**Files:**
- Create: `frontend/src/hooks/useReveal.ts`

**Interfaces:**
- Consumes: `.reveal` / `.is-visible` classes from Plan A (class names only, no import).
- Produces: `useReveal` with the signature above for Plans C and D.

- [ ] **Step 1: Implement `useReveal` per the signature**
- [ ] **Step 2: Typecheck and lint the new file**

Run: `cd frontend && npx tsc --noEmit && npx eslint src/hooks/useReveal.ts --max-warnings 0`
Expected: both clean.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/hooks/useReveal.ts
git commit -m "Add useReveal scroll-entrance hook"
```

### Task 2: useCountUp hook

**Files:**
- Create: `frontend/src/hooks/useCountUp.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `useCountUp(target, decimals?)` returning the display string for Plans C and D.

- [ ] **Step 1: Implement `useCountUp` per the signature**
- [ ] **Step 2: Typecheck and lint the new file**

Run: `cd frontend && npx tsc --noEmit && npx eslint src/hooks/useCountUp.ts --max-warnings 0`
Expected: both clean.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/hooks/useCountUp.ts
git commit -m "Add useCountUp animated-number hook"
```
