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
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null, traceId: null }

  static getDerivedStateFromError(error: Error): State {
    // ApiError carries the backend's trace id; surface it so a failure is
    // correlatable with the Glass Box rather than a dead end.
    const traceId =
      typeof (error as { traceId?: string }).traceId === 'string'
        ? ((error as { traceId?: string }).traceId as string)
        : null
    return { error, traceId }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Deliberately logs only the message and component stack. Never the error
    // object itself, which could carry a response body containing document text.
    console.error('PRISM UI crashed:', error.message, info.componentStack)
  }

  render(): ReactNode {
    const { error, traceId } = this.state
    if (!error) return this.props.children

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
            <button className="btn primary" onClick={() => this.setState({ error: null, traceId: null })}>
              Try again
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
