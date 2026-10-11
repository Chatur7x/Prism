/**
 * Top-level error boundary.
 *
 * PRISM's value is that a user can always see what the system actually did. A
 * white screen breaks that promise, so a crash is reported explicitly with the
 * trace id when one is available — which is the thread to pull when auditing
 * what went wrong.
 */
import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
  traceId: string | null
  reloading: boolean
}

/**
 * Matches a failed code-split import: the tab holds an index.html from a
 * previous deployment whose chunk hashes no longer exist on the host. The
 * fix is a fresh index.html, i.e. a reload — retrying the same import would
 * fail identically, and "Try again" without it strands the user.
 */
export function isChunkLoadFailure(message: string): boolean {
  return /Failed to fetch dynamically imported module|Importing a module script failed|ChunkLoadError|Loading chunk \d+ failed/i.test(
    message,
  )
}

/** One auto-reload per tab: a second failure needs a human decision, not a loop. */
const RELOAD_FLAG = 'prism.chunk-reload'

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null, traceId: null, reloading: false }

  static getDerivedStateFromError(error: Error): State {
    // ApiError carries the backend's trace id; surface it so a failure is
    // correlatable with the Glass Box rather than a dead end.
    const traceId =
      typeof (error as { traceId?: string }).traceId === 'string'
        ? ((error as { traceId?: string }).traceId as string)
        : null
    return { error, traceId, reloading: false }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Deliberately logs only the message and component stack. Never the error
    // object itself, which could carry a response body containing document text.
    console.error('PRISM UI crashed:', error.message, info.componentStack)
    if (isChunkLoadFailure(error.message)) {
      let reloaded = false
      try {
        reloaded = sessionStorage.getItem(RELOAD_FLAG) === '1'
        if (!reloaded) sessionStorage.setItem(RELOAD_FLAG, '1')
      } catch {
        // Storage failure: fall through to the manual fallback below.
      }
      if (!reloaded) {
        this.setState({ reloading: true })
        window.location.reload()
      }
    }
  }

  render(): ReactNode {
    const { error, traceId, reloading } = this.state
    if (!error) return this.props.children

    if (reloading) {
      return (
        <div className="fatal">
          <div className="fatal-card">
            <h1>Updating to the latest version</h1>
            <p className="muted">
              A new deployment replaced this page while it was open. Reloading to fetch it.
            </p>
          </div>
        </div>
      )
    }

    return (
      <div className="fatal">
        <div className="fatal-card">
          <h1>Something went wrong</h1>
          <p className="muted">
            The interface hit an unexpected error. The backend may still be running; this is a
            rendering failure, not a statement about your data.
          </p>
          <pre className="fatal-detail">{error.message}</pre>
          {traceId && (
            <p className="tiny muted">
              Trace id: <code className="inline">{traceId}</code>
            </p>
          )}
          <div className="btn-row">
            <button className="btn primary" onClick={() => this.setState({ error: null, traceId: null, reloading: false })}>
              Try again
            </button>
            <button className="btn" onClick={() => window.location.reload()}>
              Reload app
            </button>
            <button className="btn" onClick={() => window.location.assign('/')}>
              Return to corpora
            </button>
          </div>
        </div>
      </div>
    )
  }
}
