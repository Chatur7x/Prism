# Plan A — Dark Shell Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dark theme chrome (tokens, aurora, glass, buttons, auth, stagger, reveal base) loads last in the cascade and activates on exactly four routes, with light routes byte-identical.

**Architecture:** One new stylesheet (`styles/dark-shell.css`, final import in `main.tsx`) holding `.theme-dark`-scoped overrides; route toggles are single className additions at four existing roots. No component logic changes.

**Tech Stack:** React 18, TypeScript, hand-written CSS, Vite 5.

**Spec:** `docs/superpowers/specs/2026-10-11-dark-shell-design.md` (§1–§3, §5)

## Global Constraints

- Zero new dependencies.
- No light-route visual change: every dark rule is scoped under `.theme-dark`.
- Trust/actor badge ink and border-style semantics unchanged (only fills adapt).
- `prefers-reduced-motion` freezes aurora and converts entrances to fades.
- `prefers-reduced-transparency` falls glass back to solid panel.
- 390px mobile: no horizontal overflow introduced.
- `npx tsc --noEmit` and `npm run build` stay green.

## Review Focus

- A light route (e.g. /graph) renders pixel-identical before/after — verified by screenshot diff eyeball, because no test asserts it.
- `%VITE_API_BASE%`-style build placeholders are not used here; all values literal.
- Keyboard focus ring stays visible on dark surfaces — checked on login inputs.
- Aurora blobs never overlay dense text — they sit behind hero chrome only.
- The `theme-dark` class never leaks onto light routes via AppShell pathname check.

## File Structure

- Create `frontend/src/styles/dark-shell.css` — all dark tokens, aurora, glass, dark button/auth overrides, stagger helpers, `.reveal` base, media-query fallbacks.
- Modify `frontend/src/main.tsx` — append `import './styles/dark-shell.css'` after the `home.css` import (final layer wins ties).
- Modify `frontend/src/components/AppShell.tsx` — `theme-dark` on `.app-shell` iff `location.pathname === '/dashboard'`.
- Modify `frontend/src/pages/HomePage.tsx` — `theme-dark` on `.home` root.
- Modify `frontend/src/pages/LoginPage.tsx`, `frontend/src/pages/SignupPage.tsx` — `theme-dark` on `.auth-page` root.

---

### Task 1: Dark stylesheet with tokens, aurora, glass, controls

**Files:**
- Create: `frontend/src/styles/dark-shell.css`
- Modify: `frontend/src/main.tsx` (append one import)

**Interfaces:**
- Consumes: existing token names (`--dur-enter`, `--ease-enter`, `--dur-stagger-step`, `--radius-md/lg`, `--z-*`) — read, never redefined.
- Produces: `.theme-dark` scope; `.aurora` + `.aurora-blob` background layer; `.glass` card; `.theme-dark .btn.primary` glow treatment; `.theme-dark .auth-card` + input overrides; `.reveal` / `.reveal.is-visible`; `.stagger-1..8` delay helpers; reduced-motion and reduced-transparency blocks.

- [ ] **Step 1: Create `dark-shell.css` with the full scope from spec §2–§3**
- [ ] **Step 2: Append the import as the final stylesheet import in `main.tsx`**
- [ ] **Step 3: Run typecheck and production build**

Run: `cd frontend && npx tsc --noEmit && npm run build`
Expected: both pass, `dist/` emitted.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/styles/dark-shell.css frontend/src/main.tsx
git commit -m "Add dark shell stylesheet (scoped, inert without theme-dark)"
```

### Task 2: Route toggles on the four dark roots

**Files:**
- Modify: `frontend/src/components/AppShell.tsx` (one className expression)
- Modify: `frontend/src/pages/HomePage.tsx`, `frontend/src/pages/LoginPage.tsx`, `frontend/src/pages/SignupPage.tsx` (one className each)

**Interfaces:**
- Consumes: `.theme-dark` scope from Task 1; `useLocation` already in AppShell.
- Produces: dark chrome visible on `/`, `/login`, `/signup`, `/dashboard` and nowhere else.

- [ ] **Step 1: Add the conditional class in the four roots**
- [ ] **Step 2: Rebuild and screenshot three routes**

Run: `cd frontend && npm run build`
Expected: build passes; screenshots of `/`, `/login`, `/dashboard` show dark chrome (content restyle lands in Plan C).

- [ ] **Step 3: Confirm a light route is untouched**

Run: screenshot `/graph` and eyeball against master — no visual change.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/AppShell.tsx frontend/src/pages/HomePage.tsx frontend/src/pages/LoginPage.tsx frontend/src/pages/SignupPage.tsx
git commit -m "Toggle theme-dark on the four hero routes"
```
