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
