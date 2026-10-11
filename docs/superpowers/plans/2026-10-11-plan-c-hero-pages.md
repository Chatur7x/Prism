# Plan C — Hero Pages Dark Restyle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Home, login/signup, and dashboard read as a cinematic dark product demo while every link, form, and number behaves exactly as before.

**Architecture:** Markup edits only (class additions, aurora layer divs, stat wiring to `useCountUp`, `useReveal` on below-fold blocks). All visuals come from Plan A's stylesheet; no new CSS files.

**Tech Stack:** React 18, TypeScript, existing CSS system.

**Spec:** `docs/superpowers/specs/2026-10-11-dark-shell-design.md` (§3, §5)

## Global Constraints

- No functionality change: same routes, links, forms, validation, role gates, API calls.
- Depends on Plan A (`.theme-dark` scope, `.aurora`, `.glass`, `.reveal`) and Plan B (`useReveal`, `useCountUp`); if those landed with different names, adapt call sites rather than duplicating styles.
- Trust/actor badges keep semantics; dashboard stat tiles animate but show exact values at rest.
- 390px mobile clean; reduced-motion respected via the shared hooks and CSS.
- `npx tsc --noEmit`, build, and the 17/17 rehearsal stay green.

## Review Focus

- Login/signup submit paths and error states behave identically (dark is paint only).
- Count-up tiles settle on the exact fetched numbers, including the analyst-view gated labels.
- No content hidden when JS motion is off — `.reveal` initial state must remain readable without the observer (visible fallback).
- Aurora does not sit behind the auth form's inputs at unreadable opacity.
- Home CTAs link to the same routes as before.

## File Structure

- Modify `frontend/src/pages/HomePage.tsx` — aurora layer, glass hero card, `.reveal` on below-fold sections, stagger helpers on feature grid.
- Modify `frontend/src/components/AuthCard.tsx` — glass card classes, dark input styling via scope (markup classes only where the scope needs a hook).
- Modify `frontend/src/pages/DashboardPage.tsx` — stat tiles wired to `useCountUp`, `.reveal` on rows, pipeline strip polish classes.
- Modify `frontend/src/pages/LoginPage.tsx`, `frontend/src/pages/SignupPage.tsx` — aurora layer behind the auth card if the card component cannot own it.

---

### Task 1: Home page dark composition

**Files:**
- Modify: `frontend/src/pages/HomePage.tsx`

**Interfaces:**
- Consumes: Plan A classes (`.aurora`, `.glass`, `.reveal`, `.stagger-*`); `useReveal` from Plan B.

- [ ] **Step 1: Compose aurora, glass hero, reveals, and staggers**
- [ ] **Step 2: Build and screenshot 1920px and 390px**

Run: `cd frontend && npm run build`
Expected: passes; both screenshots show composed dark hero, no overflow.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/HomePage.tsx
git commit -m "Restyle home as cinematic dark hero"
```

### Task 2: Auth pages dark glass

**Files:**
- Modify: `frontend/src/components/AuthCard.tsx`, `frontend/src/pages/LoginPage.tsx`, `frontend/src/pages/SignupPage.tsx`

**Interfaces:**
- Consumes: Plan A auth-scope styles.

- [ ] **Step 1: Glass card markup and aurora backdrop**
- [ ] **Step 2: Verify login still works against local backend**

Run: sign in via the built UI (or `scripts/smoke-test.ps1` login leg) and confirm a session establishes.
Expected: identical auth behaviour, dark presentation.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/AuthCard.tsx frontend/src/pages/LoginPage.tsx frontend/src/pages/SignupPage.tsx
git commit -m "Restyle auth as dark glass"
```

### Task 3: Dashboard dark with animated stats

**Files:**
- Modify: `frontend/src/pages/DashboardPage.tsx`

**Interfaces:**
- Consumes: `useCountUp` and `useReveal` from Plan B; Plan A scope.

- [ ] **Step 1: Wire stat tiles to useCountUp and reveals to rows**
- [ ] **Step 2: Build, screenshot, and run the rehearsal**

Run: `cd frontend && npm run build`, then the showcase rehearsal per `docs/SHOWCASE_GUIDE.md`.
Expected: build passes; rehearsal still 17/17.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/DashboardPage.tsx
git commit -m "Restyle dashboard dark with animated stats"
```
