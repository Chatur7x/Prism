/**
 * Sign in and registration.
 *
 * <p>Registration always produces an ANALYST. That is stated on the form
 * rather than discovered later: an analyst cannot approve their own
 * extractions, so a user who needs approval rights must ask an administrator.
 * Hiding that at registration time would produce a confusing dead end.
 */
import { useState, type FormEvent } from 'react'

import { useAuth } from '../auth/AuthContext'
import { Alert, ErrorState } from '../components/ui'

export function LoginPage() {
  const { login, register } = useAuth()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setPending(true)
    setError(null)
    try {
      if (mode === 'login') {
        await login(username, password)
      } else {
        await register(username, email, password)
      }
    } catch (cause) {
      setError(cause)
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="fatal">
      <div className="fatal-card" style={{ maxWidth: 420 }}>
        <div className="brand" style={{ justifyContent: 'center', marginBottom: 16 }}>
          <div className="brand-mark" aria-hidden="true" />
          <div>
            <div className="brand-text">PRISM</div>
            <div className="brand-sub">Auditable AI analysis</div>
          </div>
        </div>

        <p className="muted" style={{ fontSize: 13, textAlign: 'center' }}>
          Every answer is traceable to the evidence behind it. Deterministic code owns
          the record; models only propose; a human verifier decides.
        </p>

        <form onSubmit={submit} className="stack">
          <div className="field">
            <label className="field-label" htmlFor="username">
              Username
            </label>
            <input
              id="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
              autoFocus
            />
          </div>

          {mode === 'register' && (
            <div className="field">
              <label className="field-label" htmlFor="email">
                Email
              </label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                required
              />
            </div>
          )}

          <div className="field">
            <label className="field-label" htmlFor="password">
              Password
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              required
            />
          </div>

          {error != null && <ErrorState error={error} />}

          <button className="btn primary" type="submit" disabled={pending}>
            {pending && <span className="spinner" aria-hidden="true" />}
            {mode === 'login' ? 'Sign in' : 'Create account'}
          </button>
        </form>

        <div className="row" style={{ justifyContent: 'center', marginTop: 12 }}>
          <button
            className="btn ghost sm"
            onClick={() => {
              setMode(mode === 'login' ? 'register' : 'login')
              setError(null)
            }}
          >
            {mode === 'login' ? 'Create an account' : 'Back to sign in'}
          </button>
        </div>

        {mode === 'register' && (
          <Alert kind="info">
            New accounts receive the <strong>ANALYST</strong> role: they can upload documents and
            propose knowledge, but cannot approve it. <strong>VERIFIER</strong> and{' '}
            <strong>ADMIN</strong> are granted by an administrator.
          </Alert>
        )}
      </div>
    </div>
  )
}
