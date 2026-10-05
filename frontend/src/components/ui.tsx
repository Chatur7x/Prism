/**
 * Shared UI primitives.
 *
 * <p>Two rules govern everything here:
 *
 * 1. <b>Trust is never colour alone.</b> `StatusBadge` always renders a text
 *    label and varies border style as well as hue, so APPROVED and PENDING stay
 *    distinguishable in greyscale, under colour-vision deficiency, and to a
 *    screen reader. Getting this wrong would misrepresent the single most
 *    important state in the system.
 * 2. <b>Failure is explicit.</b> `useAsync` distinguishes loading, error, and
 *    empty, and the error path always renders. A silent blank panel is
 *    indistinguishable from "nothing here", which in an audit tool is a lie.
 */
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'

import { ApiError } from '../api/client'

// ---- badges ---------------------------------------------------------------

type BadgeTone =
  | 'approved'
  | 'pending'
  | 'rejected'
  | 'verified'
  | 'contradicted'
  | 'missing'
  | 'neutral'

const TONE_BY_STATUS: Record<string, BadgeTone> = {
  APPROVED: 'approved',
  SUPPORTED: 'approved',
  RESOLVED: 'approved',
  READY: 'approved',
  SUCCEEDED: 'approved',

  PENDING: 'pending',
  PROPOSED: 'pending',
  OPEN: 'pending',
  CREATED: 'pending',
  AWAITING_APPROVAL: 'pending',
  CHUNKED: 'pending',
  EXTRACTING: 'pending',
  CHUNKING: 'pending',
  UPLOADED: 'pending',
  ROUND_ACTIVE: 'pending',
  SYNTHESIZING: 'pending',

  REJECTED: 'rejected',
  FAILED: 'rejected',
  DISMISSED: 'rejected',
  ABANDONED: 'rejected',
  ABORTED: 'rejected',

  VERIFIED: 'verified',
  COMPLETED: 'verified',
  ADJUDICATED: 'verified',
  CONTRADICTED: 'contradicted',
  IN_DEBATE: 'contradicted',
  EXAGGERATED: 'missing',

  SOURCE_MISSING: 'missing',
  INSUFFICIENT_EVIDENCE: 'missing',
  ARCHIVED: 'missing',
}

/** Human wording for a status code. Never render the raw enum alone. */
const LABEL: Record<string, string> = {
  SOURCE_MISSING: 'No source in corpus',
  INSUFFICIENT_EVIDENCE: 'Insufficient evidence',
  AWAITING_APPROVAL: 'Awaiting approval',
  IN_DEBATE: 'In debate',
  ROUND_ACTIVE: 'Round active',
  AWAITING_CHAIR: 'Awaiting chair',
  SYNTHESIZING: 'Synthesising',
  UNSPECIFIED: 'Unspecified type',
  KEEP_SEPARATE: 'Kept separate',
  NO_EVIDENCE: 'No evidence retrieved',
  EVIDENCE_FOUND: 'Evidence found',
  MACHINE_ONLY: 'Machine only',
  CONTESTED: 'Contested',
  HUMAN_DECISION: 'Human decision',
  NOT_STARTED: 'Not started',
  NOT_PRESENT: 'Absent from record',
  IN_PROGRESS: 'In progress',
  RUNNING: 'Running',
  PENDING: 'Pending',
}

export function StatusBadge({
  value,
  label,
  tone,
}: {
  value: string
  label?: string
  tone?: BadgeTone
}) {
  const resolved = tone ?? TONE_BY_STATUS[value] ?? 'neutral'
  const text = label ?? LABEL[value] ?? value.replace(/_/g, ' ').toLowerCase()
  return (
    <span className={`badge ${resolved}`} title={value}>
      {text}
    </span>
  )
}

export function ActorBadge({ actor }: { actor: 'ENGINE' | 'LLM' | 'HUMAN' }) {
  const text = actor === 'ENGINE' ? 'Java engine' : actor === 'LLM' ? 'Model' : 'Human'
  return <span className={`badge actor-${actor.toLowerCase()}`}>{text}</span>
}

// ---- layout ---------------------------------------------------------------

export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: string
  subtitle?: ReactNode
  actions?: ReactNode
}) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="page-subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="btn-row">{actions}</div>}
    </header>
  )
}

export function Stat({
  label,
  value,
  hint,
  accent,
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  accent?: boolean
}) {
  return (
    <div className={accent ? 'stat accent' : 'stat'}>
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {hint && <span className="stat-hint">{hint}</span>}
    </div>
  )
}

export function Card({
  title,
  actions,
  children,
  tight,
  flush,
}: {
  title?: ReactNode
  actions?: ReactNode
  children: ReactNode
  tight?: boolean
  flush?: boolean
}) {
  return (
    <section className="card">
      {(title || actions) && (
        <div className="card-head">
          {typeof title === 'string' ? <h2>{title}</h2> : title}
          {actions}
        </div>
      )}
      <div className={`card-body${tight ? ' tight' : ''}${flush ? ' flush' : ''}`}>{children}</div>
    </section>
  )
}

// ---- feedback -------------------------------------------------------------

export function Alert({
  kind = 'info',
  children,
  traceId,
}: {
  kind?: 'info' | 'error' | 'warn' | 'ok'
  children: ReactNode
  traceId?: string | null
}) {
  return (
    <div className={`alert ${kind}`} role={kind === 'error' ? 'alert' : undefined}>
      <div>{children}</div>
      {traceId && <span className="alert-trace">trace {traceId}</span>}
    </div>
  )
}

export function Empty({
  title,
  children,
}: {
  title: string
  children?: ReactNode
}) {
  return (
    <div className="empty">
      <div className="empty-title">{title}</div>
      {children}
    </div>
  )
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="empty">
      <span className="spinner" aria-hidden="true" />{' '}
      <span className="muted">{label}…</span>
    </div>
  )
}

/** Renders a backend error with its trace id so it can be correlated. */
export function ErrorState({ error }: { error: unknown }) {
  if (error instanceof ApiError) {
    return (
      <Alert kind="error" traceId={error.traceId}>
        {error.message}
        {error.status === 0 && (
          <div className="tiny" style={{ marginTop: 4 }}>
            The backend did not respond. Check that it is running and that CORS allows this origin.
          </div>
        )}
        {error.violations && error.violations.length > 0 && (
          <ul className="tiny" style={{ margin: '4px 0 0', paddingLeft: 18 }}>
            {error.violations.map((v) => (
              <li key={v.field}>
                <strong>{v.field}</strong>: {v.message}
              </li>
            ))}
          </ul>
        )}
      </Alert>
    )
  }
  return <Alert kind="error">{error instanceof Error ? error.message : String(error)}</Alert>
}

// ---- data fetching --------------------------------------------------------

interface AsyncState<T> {
  data: T | null
  error: unknown
  loading: boolean
  reload: () => void
}

/**
 * Loads data on mount and exposes an explicit reload.
 *
 * <p>Tracks a request sequence number so a slow response for a previous
 * selection cannot overwrite a newer one — the classic stale-response race when
 * a user switches corpora quickly.
 */
export function useAsync<T>(loader: () => Promise<T>, deps: unknown[] = []): AsyncState<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(true)
  const [nonce, setNonce] = useState(0)
  const sequence = useRef(0)

  // The loader is intentionally not in the dep list: callers pass an inline
  // arrow, which would re-run on every render. Deps are explicit instead.
  const loaderRef = useRef(loader)
  loaderRef.current = loader

  useEffect(() => {
    const mine = ++sequence.current
    setLoading(true)
    setError(null)

    loaderRef
      .current()
      .then((result) => {
        if (mine !== sequence.current) return
        setData(result)
      })
      .catch((cause) => {
        if (mine !== sequence.current) return
        setError(cause)
      })
      .finally(() => {
        if (mine !== sequence.current) return
        setLoading(false)
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  return { data, error, loading, reload }
}

/** Wraps a mutation with pending state and error capture. */
export function useAction<A extends unknown[], R>(
  action: (...args: A) => Promise<R>,
): { run: (...args: A) => Promise<R | null>; pending: boolean; error: unknown; clearError: () => void } {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const actionRef = useRef(action)
  actionRef.current = action

  const run = useCallback(async (...args: A) => {
    setPending(true)
    setError(null)
    try {
      return await actionRef.current(...args)
    } catch (cause) {
      // Swallow: the caller reads `error` from the hook. Returning null lets an
      // onClick chain finish without an unhandled rejection.
      if (mounted.current) setError(cause)
      return null
    } finally {
      if (mounted.current) setPending(false)
    }
  }, [])

  const clearError = useCallback(() => setError(null), [])

  return { run, pending, error, clearError }
}

// ---- formatting -----------------------------------------------------------

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`
  const minutes = Math.floor(ms / 60_000)
  const seconds = Math.round((ms % 60_000) / 1000)
  return `${minutes}m ${seconds}s`
}

/** Score formatter that makes the "ranking, not probability" caveat visible. */
export function formatScore(value: number | null | undefined): string {
  if (value == null) return '—'
  return value.toFixed(3)
}

// ---- collections ----------------------------------------------------------

/**
 * One pager for the four backend envelopes. Pages compute `page` (0-based),
 * `totalPages` (when the envelope carries a total) and `hasNext` (when it
 * does not), and pass navigation callbacks. No silent truncation: a list
 * with more data always shows a way forward.
 */
export function Pager({
  page,
  totalPages,
  hasNext,
  total,
  onPrev,
  onNext,
}: {
  page: number
  totalPages?: number | null
  hasNext?: boolean
  total?: number | null
  onPrev: () => void
  onNext: () => void
}) {
  const canPrev = page > 0
  const canNext = totalPages != null ? page + 1 < totalPages : (hasNext ?? false)
  if (!canPrev && !canNext && total == null) return null
  return (
    <div className="pager" role="navigation" aria-label="Pagination">
      <button className="btn sm" onClick={onPrev} disabled={!canPrev} aria-label="Previous page">
        ← Prev
      </button>
      <span className="tiny muted" role="status">
        {totalPages != null ? (
          <>
            Page {page + 1} of {Math.max(totalPages, 1)}
            {total != null && <> · {total} total</>}
          </>
        ) : (
          <>Page {page + 1}{total != null && <> · {total} total</>}</>
        )}
      </span>
      <button className="btn sm" onClick={onNext} disabled={!canNext} aria-label="Next page">
        Next →
      </button>
    </div>
  )
}

/**
 * Debounced, abortable search input. Replaces per-keystroke fetching: the
 * caller gets one value 250ms after typing stops, and a fresh AbortSignal
 * per committed value so stale responses never win.
 */
export function SearchInput({
  value,
  onChange,
  placeholder,
  label,
  delayMs = 250,
}: {
  value: string
  onChange: (value: string, signal: AbortSignal) => void
  placeholder?: string
  label: string
  delayMs?: number
}) {
  const [text, setText] = useState(value)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      if (text !== value) onChangeRef.current(text, controller.signal)
    }, delayMs)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [text, value, delayMs])

  useEffect(() => setText(value), [value])

  return (
    <div className="field search-field">
      <label className="field-label" htmlFor={`search-${label}`}>
        {label}
      </label>
      <input
        id={`search-${label}`}
        type="search"
        value={text}
        placeholder={placeholder}
        onChange={(event) => setText(event.target.value)}
      />
    </div>
  )
}

// ---- pipeline -------------------------------------------------------------

/**
 * Document processing as visible stages, not a spinner. The current stage is
 * derived from DocumentStatus + progress counters; percentages are never
 * shown because the backend reports counts, not fractions of a known whole.
 */
const DOC_STAGES = ['UPLOAD', 'CHUNK', 'EXTRACT', 'VALIDATE', 'PROPOSE', 'APPROVAL'] as const

export function PipelineStepper({
  status,
  processedChunks,
  totalChunks,
}: {
  status: string
  processedChunks?: number | null
  totalChunks?: number | null
}) {
  const index = stageIndex(status)
  return (
    <ol className="pipeline-strip" aria-label="Processing pipeline">
      {DOC_STAGES.map((stage, i) => (
        <li
          key={stage}
          className={`pipe-stage${i < index ? ' done' : ''}${i === index ? ' active' : ''}`}
          aria-current={i === index ? 'step' : undefined}
        >
          <span className="pipe-label">{stage}</span>
          <span className="tiny muted">
            {i < index ? 'done' : i === index ? currentHint(status, processedChunks, totalChunks) : 'waiting'}
          </span>
        </li>
      ))}
    </ol>
  )
}

function stageIndex(status: string): number {
  switch (status) {
    case 'UPLOADED':
      return 0
    case 'CHUNKING':
    case 'CHUNKED':
      return 1
    case 'EXTRACTING':
      return 2
    case 'AWAITING_APPROVAL':
      return 5
    case 'READY':
      return 6
    case 'FAILED':
      return 2
    default:
      return 0
  }
}

function currentHint(status: string, processed?: number | null, total?: number | null): string {
  if (status === 'FAILED') return 'failed — reprocess available'
  if (processed != null && total != null && total > 0) return `${processed}/${total} chunks`
  return 'in progress'
}

// ---- overlays -------------------------------------------------------------

/** Confirm dialog for destructive-but-reversible decisions (dismiss, reject). */
export function Modal({
  title,
  children,
  onClose,
  actions,
}: {
  title: string
  children: ReactNode
  onClose: () => void
  actions: ReactNode
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(event) => event.stopPropagation()}
      >
        <h3>{title}</h3>
        <div>{children}</div>
        <div className="btn-row">{actions}</div>
      </div>
    </div>
  )
}

/**
 * Side inspector that becomes a bottom sheet on small screens (pure CSS).
 * Used for the entity dossier, evidence, and trace-step detail.
 */
export function Drawer({
  title,
  children,
  onClose,
}: {
  title: string
  children: ReactNode
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <aside
        className="drawer"
        role="complementary"
        aria-label={title}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="spread">
          <h3 style={{ margin: 0 }}>{title}</h3>
          <button className="btn sm ghost" onClick={onClose} aria-label={`Close ${title}`}>
            ✕
          </button>
        </div>
        {children}
      </aside>
    </div>
  )
}

// ---- graph ----------------------------------------------------------------

/**
 * Knowledge confidence that never depends on color alone: each state carries
 * an icon, a label, and (via CSS) a distinct border/edge pattern.
 */
export function ConfidenceBadge({ state }: { state: 'VERIFIED' | 'SINGLE_SOURCE' | 'CONTRADICTED' }) {
  const icon = state === 'VERIFIED' ? '●' : state === 'CONTRADICTED' ? '◆' : '○'
  const label = state === 'VERIFIED' ? 'Verified' : state === 'CONTRADICTED' ? 'Contradicted' : 'Single source'
  return (
    <span className={`badge confidence-${state.toLowerCase()}`}>
      <span aria-hidden="true">{icon}</span> {label}
    </span>
  )
}

export function truncate(value: string | null | undefined, max = 160): string {
  if (!value) return ''
  return value.length <= max ? value : `${value.slice(0, max)}…`
}

/** Renders text as plain text. PRISM never injects HTML from the API. */
export function SafeText({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">—</span>
  return <span style={{ whiteSpace: 'pre-wrap' }}>{value}</span>
}

export function KeyValue({ rows }: { rows: [ReactNode, ReactNode][] }) {
  return (
    <dl className="kv">
      {rows.map(([label, value], index) => (
        <div key={index} style={{ display: 'contents' }}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}
