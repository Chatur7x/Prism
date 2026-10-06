/**
 * Shared interaction primitives.
 *
 * Each of these exists because three or more call sites would otherwise grow
 * their own copy — not because it looks like something a design system ought
 * to contain. When a page needs something genuinely local, it keeps it local;
 * promoting it here is a decision, not a default.
 *
 * Three rules govern the whole file:
 *
 * 1. <b>Trust is never colour alone.</b> Every status surface pairs colour with
 *    a text label and a distinct border treatment. In an audit tool that is not
 *    an accessibility nicety, it is the difference between reading the record
 *    correctly and not.
 * 2. <b>Nothing moves that is not a state change.</b> Animation lives in
 *    `motion.css`; this file only decides <em>which</em> state a thing is in.
 * 3. <b>Focus is never lost.</b> Overlays trap focus while open and restore it
 *    to the element that opened them on close, so a keyboard reviewer is never
 *    dropped back at the top of the document.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'

// ---------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------

/**
 * One glyph set, drawn rather than imported.
 *
 * These are 24x24 paths on a 1.75 stroke with round caps — a single stroke
 * weight and cap style, which is what makes them read as one family. The
 * earlier sidebar used fourteen unrelated Unicode characters whose stroke
 * weights came from whatever font happened to resolve them, which is why the
 * navigation looked assembled rather than drawn.
 *
 * `aria-hidden` is correct here because every icon in PRISM sits beside a text
 * label or inside a control that carries its own `aria-label`.
 */
export type IconName =
  | 'dashboard'
  | 'corpora'
  | 'documents'
  | 'approval'
  | 'knowledge'
  | 'verification'
  | 'verdicts'
  | 'graph'
  | 'contradictions'
  | 'reports'
  | 'chat'
  | 'glassbox'
  | 'admin'
  | 'check'
  | 'close'
  | 'chevron-right'
  | 'play'
  | 'pause'
  | 'step-forward'
  | 'step-back'
  | 'refresh'
  | 'search'
  | 'info'
  | 'warning'
  | 'error'

const PATHS: Record<IconName, string> = {
  dashboard: 'M4 13h6V4H4v9Zm0 7h6v-5H4v5Zm10 0h6v-9h-6v9Zm0-16v5h6V4h-6Z',
  corpora: 'M4 7c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Zm0 0v10c0 1.7 3.6 3 8 3s8-1.3 8-3V7',
  documents: 'M7 3h7l5 5v11a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Zm7 0v5h5',
  approval: 'M20 6 9 17l-5-5',
  knowledge: 'M12 3 3 8v8l9 5 9-5V8l-9-5Zm0 0v18M3 8l9 5',
  verification: 'M12 3v18M5 8l7-5 7 5M5 16l7 5 7-5',
  verdicts: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Zm-4 9 3 3 5-6',
  graph: 'M6 18a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm12-13a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm0 14a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM8 15.5l8-11M8 16l8 1',
  contradictions: 'M8 4v6a4 4 0 0 0 4 4h8m0 0-3-3m3 3-3 3M16 20v-6a4 4 0 0 0-4-4H4m0 0 3-3m-3 3 3 3',
  reports: 'M6 3h9l4 4v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Zm9 0v4h4M9 12h7M9 16h5',
  chat: 'M20 15a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v9Z',
  glassbox: 'M4 6h16M4 12h16M4 18h10M3 3h18v18H3V3Z',
  admin: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm8-3a8 8 0 0 0-.2-1.7l2-1.5-2-3.4-2.3 1a8 8 0 0 0-2.9-1.7L14.2 2H9.8l-.4 2.4a8 8 0 0 0-3 1.7l-2.2-1-2 3.4 2 1.5a8 8 0 0 0 0 3.4l-2 1.5 2 3.4 2.2-1a8 8 0 0 0 3 1.7l.4 2.4h4.4l.4-2.4a8 8 0 0 0 2.9-1.7l2.3 1 2-3.4-2-1.5c.1-.6.2-1.1.2-1.7Z',
  check: 'M20 6 9 17l-5-5',
  close: 'M6 6l12 12M18 6 6 18',
  'chevron-right': 'm9 6 6 6-6 6',
  play: 'M7 4v16l13-8L7 4Z',
  pause: 'M9 4v16M15 4v16',
  'step-forward': 'm5 4 10 8-10 8V4Zm12 0v16',
  'step-back': 'm19 4-10 8 10 8V4ZM7 4v16',
  refresh: 'M20 12a8 8 0 1 1-2.3-5.6M20 4v5h-5',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Zm5-2 5 5',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-13v.5m0 3.5v5',
  warning: 'M12 4 2 20h20L12 4Zm0 6v5m0 3v.5',
  error: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM9 9l6 6m0-6-6 6',
}

export function Icon({ name, size = 16 }: { name: IconName; size?: number }) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ flex: '0 0 auto', display: 'block' }}
    >
      <path d={PATHS[name]} />
    </svg>
  )
}

// ---------------------------------------------------------------------------
// Tooltip
// ---------------------------------------------------------------------------

/**
 * Opens on hover and on keyboard focus, entirely in CSS.
 *
 * Doing it in CSS rather than JavaScript is not a shortcut: it cannot desync
 * from its trigger, it cannot strand an open tooltip after the trigger
 * unmounts, and it is inert for anyone who cannot hover. The content is always
 * in the DOM, so this is a convenience and never the only place the information
 * exists — which is the rule that stops tooltips becoming a hiding place for
 * meaning.
 */
export function Tooltip({
  label,
  children,
}: {
  label: string
  children: ReactNode
}) {
  return (
    <span className="tooltip" tabIndex={0} role="note" aria-label={label}>
      {children}
      <span className="tooltip-content" role="tooltip">
        {label}
      </span>
    </span>
  )
}

// ---------------------------------------------------------------------------
// Toasts
// ---------------------------------------------------------------------------

export type ToastKind = 'ok' | 'error' | 'warn' | 'info'

export interface ToastMessage {
  id: number
  kind: ToastKind
  title: string
  detail?: string
  /** ms; 0 keeps it until dismissed. Errors default longer because they are
   *  the ones a reviewer needs to have actually read. */
  ttl: number
}

interface ToastApi {
  toasts: ToastMessage[]
  push: (message: { kind: ToastKind; title: string; detail?: string; ttl?: number }) => number
  dismiss: (id: number) => void
}

const ToastContext = createContext<ToastApi | null>(null)

/**
 * Region-based so a notification can be raised from anywhere — including from
 * an event handler deep in a page — without threading a callback down. The
 * region is `aria-live="polite"` rather than `assertive` on purpose: an
 * approval succeeding is not something that should interrupt a reviewer
 * mid-decision, whereas an error is. Errors are marked `role="alert"`
 * individually so assistive tech treats them accordingly.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([])
  const nextId = useRef(1)

  const dismiss = useCallback((id: number) => {
    // Mark rather than remove, so motion.css can play the exit before the node
    // disappears. Immediate removal would make every toast vanish mid-air.
    setToasts((current) =>
      current.map((t) => (t.id === id ? { ...t, ttl: 0 } : t)),
    )
    window.setTimeout(
      () => setToasts((current) => current.filter((t) => t.id !== id)),
      200,
    )
  }, [])

  const push = useCallback<ToastApi['push']>(
    ({ kind, title, detail, ttl }) => {
      const id = nextId.current++
      const life =
        ttl ?? (kind === 'error' ? 9000 : kind === 'warn' ? 6000 : 4000)
      setToasts((current) => [...current.slice(-3), { id, kind, title, detail, ttl: life }])
      if (life > 0) window.setTimeout(() => dismiss(id), life)
      return id
    },
    [dismiss],
  )

  const api = useMemo(() => ({ toasts, push, dismiss }), [toasts, push, dismiss])

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-region" role="region" aria-label="Notifications">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={`toast ${toast.kind}`}
            role={toast.kind === 'error' ? 'alert' : 'status'}
          >
            <Icon name={toast.kind === 'ok' ? 'check' : toast.kind === 'error' ? 'error' : toast.kind === 'warn' ? 'warning' : 'info'} />
            <span className="toast-body">
              <span className="toast-title">{toast.title}</span>
              {toast.detail && <span className="toast-detail">{toast.detail}</span>}
            </span>
            <button
              type="button"
              className="toast-close"
              onClick={() => dismiss(toast.id)}
              aria-label={`Dismiss: ${toast.title}`}
            >
              <Icon name="close" size={14} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

/**
 * Returns null outside a provider rather than throwing.
 *
 * A toast is a convenience. A convenience that can crash the page it was
 * added to is worse than no toast at all, so a missing provider degrades to
 * nothing instead of an exception.
 */
export function useToast(): ToastApi {
  const context = useContext(ToastContext)
  const noop = useMemo<ToastApi>(
    () => ({ toasts: [], push: () => 0, dismiss: () => {} }),
    [],
  )
  return context ?? noop
}

// ---------------------------------------------------------------------------
// Skeletons
// ---------------------------------------------------------------------------

/**
 * Shapes that match the content they stand in for.
 *
 * A generic bar where a table will be makes the layout jump when the data
 * arrives, and the reviewer's eye has to re-find what it was reading — which is
 * worse than showing nothing for longer.
 */
export function Skeleton({
  variant = 'line',
  width,
}: {
  variant?: 'line' | 'line-short' | 'title' | 'block' | 'circle' | 'text'
  width?: string | number
}) {
  return <span className={`skeleton ${variant}`} style={width ? { width } : undefined} />
}

/** A table-shaped skeleton: header band plus rows at the real row height. */
export function SkeletonTable({ rows = 6 }: { rows?: number }) {
  return (
    <div className="skeleton-table" aria-hidden="true">
      <Skeleton variant="line-short" />
      {Array.from({ length: rows }, (_, index) => (
        <div className="skeleton-row" key={index}>
          <Skeleton variant="line" />
          <Skeleton variant="line" />
          <Skeleton variant="line-short" />
          <Skeleton variant="line-short" />
        </div>
      ))}
    </div>
  )
}

/** Wraps a group so it is announced once, not per bar. */
export function SkeletonGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div role="status" aria-live="polite" aria-busy="true">
      <span className="sr-only">{label}</span>
      {children}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------

export interface TabDef {
  id: string
  label: string
  count?: number | null
}

/**
 * Sliding indicator rather than each tab painting itself.
 *
 * The one moving element per tab bar carries real information — these are
 * peers, and this is which one is active — so the motion is earning its place.
 * Arrow keys move between tabs per the WAI-ARIA tabs pattern, because a tab
 * bar that only responds to clicks is unusable without a pointer.
 */
export function Tabs({
  tabs,
  active,
  onChange,
  label,
}: {
  tabs: TabDef[]
  active: string
  onChange: (id: string) => void
  label: string
}) {
  const listRef = useRef<HTMLDivElement>(null)
  const indicatorRef = useRef<HTMLSpanElement>(null)

  // Position the indicator from real measured geometry after layout, so it
  // lands on the actual tab width rather than assuming every tab is the same
  // size — which they are not once a count badge is added.
  useEffect(() => {
    const list = listRef.current
    const indicator = indicatorRef.current
    if (!list || !indicator) return
    const move = () => {
      const index = tabs.findIndex((tab) => tab.id === active)
      const button = list.querySelectorAll<HTMLButtonElement>('[role=tab]')[index]
      if (!button) return
      indicator.style.transform = `translateX(${button.offsetLeft}px)`
      indicator.style.width = `${button.offsetWidth}px`
    }
    move()
    const observer = new ResizeObserver(move)
    observer.observe(list)
    return () => observer.disconnect()
  }, [active, tabs])

  function onKeyDown(event: React.KeyboardEvent) {
    const delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
    if (delta === 0) return
    event.preventDefault()
    const index = tabs.findIndex((tab) => tab.id === active)
    const next = tabs[(index + delta + tabs.length) % tabs.length]
    if (next) {
      onChange(next.id)
      listRef.current?.querySelectorAll<HTMLButtonElement>('[role=tab]')[tabs.indexOf(next)]?.focus()
    }
  }

  return (
    <div className="tab-list" role="tablist" aria-label={label} ref={listRef} onKeyDown={onKeyDown}>
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          id={`tab-${tab.id}`}
          aria-selected={tab.id === active}
          aria-controls={`panel-${tab.id}`}
          tabIndex={tab.id === active ? 0 : -1}
          className="tab"
          onClick={() => onChange(tab.id)}
        >
          {tab.label}
          {tab.count != null && <span className="tab-count">{tab.count}</span>}
        </button>
      ))}
      <span className="tab-indicator" ref={indicatorRef} aria-hidden="true" />
    </div>
  )
}

export function TabPanel({
  id,
  active,
  children,
}: {
  id: string
  active: string
  children: ReactNode
}) {
  if (id !== active) return null
  return (
    <div role="tabpanel" id={`panel-${id}`} aria-labelledby={`tab-${id}`} tabIndex={0}>
      {children}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Segmented filter
// ---------------------------------------------------------------------------

export interface SegmentDef {
  id: string
  label: string
  count?: number | null
}

/**
 * For filters over one list, where the options are peers and the choice is not
 * destructive. Carries a count so the shape of the data is visible before it
 * is filtered — a filter whose options are all "0" is a filter that cannot help.
 */
export function Segmented({
  segments,
  active,
  onChange,
  label,
}: {
  segments: SegmentDef[]
  active: string
  onChange: (id: string) => void
  label: string
}) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {segments.map((segment) => (
        <button
          key={segment.id}
          type="button"
          aria-pressed={segment.id === active}
          onClick={() => onChange(segment.id)}
        >
          {segment.label}
          {segment.count != null && <span className="count">({segment.count})</span>}
        </button>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------

/**
 * Determinate only.
 *
 * `value` and `total` are required. An indeterminate bar over a task with a
 * known total would be a claim about work in progress that PRISM cannot make —
 * so the indeterminate variant does not exist here. A spinner is for duration
 * that is genuinely unknown, and `Loading` covers that.
 */
export function Progress({
  value,
  total,
  label,
  tone,
}: {
  value: number
  total: number
  label: string
  tone?: 'done' | 'failed'
}) {
  const pct = total > 0 ? Math.min(100, Math.round((value / total) * 100)) : 0
  return (
    <div className="progress-labelled">
      <div
        className={`progress${tone ? ` ${tone}` : ''}`}
        role="progressbar"
        aria-valuenow={value}
        aria-valuemin={0}
        aria-valuemax={total}
        aria-label={label}
      >
        <div className="progress-fill" style={{ width: `${pct}%` }} />
      </div>
      <div className="progress-meta">
        <span>{label}</span>
        <span>
          {value}/{total}
        </span>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Empty and error states
// ---------------------------------------------------------------------------

/**
 * Deliberate empty state: says what is empty and what would fill it.
 *
 * The action is a real control when one exists and plain text when one does
 * not. A disabled button in an empty state is worse than a sentence, because
 * it looks like something is broken.
 *
 * `hint` is a node rather than a string because the most useful thing an empty
 * state can do is offer the *next* step, and that step is very often a link to
 * the page where it happens. Typing it as a string would push that affordance
 * out of every empty state in the product.
 */
export function EmptyState({
  glyph = '▤',
  title,
  hint,
  action,
}: {
  glyph?: string
  title: string
  hint?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="empty-state">
      <span className="empty-glyph" aria-hidden="true">
        {glyph}
      </span>
      <span className="empty-title">{title}</span>
      {hint && <span className="empty-hint">{hint}</span>}
      {action}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Attribution
// ---------------------------------------------------------------------------

/**
 * Who produced this text.
 *
 * The architectural invariant is that deterministic Java owns the record, the
 * model only proposes, and a human verifier decides. Rendering that next to
 * every passage is the cheapest way to keep it true all day — a reviewer should
 * never have to ask whether a sentence came from a document, a model, or a
 * person. Colour is reinforced by the word itself, never substituted for it.
 */
export function Attribution({
  actor,
  children,
}: {
  actor: 'ENGINE' | 'LLM' | 'HUMAN'
  children: ReactNode
}) {
  const role = actor === 'ENGINE' ? 'Engine' : actor === 'LLM' ? 'Model' : 'Human'
  return (
    <span className={`attribution actor-${actor.toLowerCase()}`}>
      <span className="attribution-role">{role}</span>
      {children}
    </span>
  )
}

// ---------------------------------------------------------------------------
// Weight scale — the chair's 1-5 judgement
// ---------------------------------------------------------------------------

/**
 * The chair's evidence-weight control.
 *
 * Square buttons with a filled selected state, exposed as a radio group, so the
 * choice is visible at a glance and legible to a screen reader as one control
 * rather than five. Deliberately not a slider or a star rating: this is a
 * recorded judgement about how much weight evidence carries, and it should
 * look like a decision rather than a preference.
 */
export function WeightScale({
  value,
  onChange,
  disabled,
  labels = ['Marginal', 'Minor', 'Moderate', 'Strong', 'Decisive'],
}: {
  value: number | null
  onChange: (value: number) => void
  disabled?: boolean
  labels?: string[]
}) {
  const name = useId()
  return (
    <div className="weight-scale" role="radiogroup" aria-label="Evidence weight">
      {[1, 2, 3, 4, 5].map((weight) => (
        <button
          key={weight}
          type="button"
          role="radio"
          aria-checked={value === weight}
          aria-pressed={value === weight}
          aria-label={`Weight ${weight} — ${labels[weight - 1]}`}
          name={name}
          disabled={disabled}
          onClick={() => onChange(weight)}
        >
          {weight}
        </button>
      ))}
      {value != null && <span className="weight-scale-label">{labels[value - 1]}</span>}
    </div>
  )
}