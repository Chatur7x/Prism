/**
 * The single sign-in / registration card, shared by /login and /signup.
 *
 * <p>Validation here is a courtesy (required, length, pattern hints); the
 * backend is the authority and its 409/422 answers are rendered per-field
 * where they map, generically otherwise. There is deliberately no
 * forgot-password affordance: no reset endpoint exists, and a form that
 * pretends otherwise would be a lie. The muted note says so honestly.
 */
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '../api/client'
import { Alert } from './ui'

export function AuthCard({
  mode,
  onSubmit,
}: {
  mode: 'login' | 'register'
  onSubmit: (fields: { username: string; email: string; password: string }) => Promise<unknown>
}) {
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (pending) return
    if (mode === 'register') {
      if (username.trim().length < 3) {
        setError('Username needs at least 3 characters (letters, digits, _ . -).')
        return
      }
      if (password.length < 12) {
        setError('Password needs at least 12 characters, with a letter and a digit.')
        return
      }
    }
    setPending(true)
    setError(null)
    try {
      await onSubmit({ username: username.trim(), email: email.trim(), password })
    } catch (cause) {
      if (cause instanceof ApiError) {
        if (cause.status === 401) setError('Invalid credentials. Check the username and password.')
        else if (cause.status === 409) setError('That username or email is already taken.')
        else if (cause.status === 422) setError(friendlyValidation(cause))
        else if (cause.status === 429) setError('Too many attempts. Wait a minute and try again.')
        else if (cause.status === 0) setError('Cannot reach the PRISM backend. Is it running?')
        else setError(cause.message)
      } else if (cause instanceof Error) {
        setError(cause.message)
      } else {
        setError(mode === 'login' ? 'Sign-in failed.' : 'Registration failed.')
      }
    } finally {
      setPending(false)
    }
  }

  return (
    <form onSubmit={submit} className="fatal-card auth-card glass materialize">
      <h2 className="glass-text">{mode === 'login' ? 'Sign in' : 'Create analyst account'}</h2>
      {error != null && <Alert kind="error">{error}</Alert>}
      <div className="field">
        <label className="field-label" htmlFor={`auth-username-${mode}`}>
          Username {mode === 'login' && <span className="tiny muted">(or email)</span>}
        </label>
        <input
          id={`auth-username-${mode}`}
          name="username"
          value={username}
          onChange={(event) => setUsername(event.target.value)}
          autoComplete="username"
          required
          aria-invalid={error != null}
        />
      </div>
      {mode === 'register' && (
        <div className="field">
          <label className="field-label" htmlFor="auth-email">
            Email
          </label>
          <input
            id="auth-email"
            name="email"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
            required
          />
          <p className="tiny muted" style={{ marginBottom: 0 }}>
            Registration produces an analyst account, which can propose but not approve.
          </p>
        </div>
      )}
      <div className="field">
        <label className="field-label" htmlFor={`auth-password-${mode}`}>
          Password
        </label>
        <div className="auth-password-row">
          <input
            id={`auth-password-${mode}`}
            name="password"
            type={showPassword ? 'text' : 'password'}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            required
            minLength={mode === 'register' ? 12 : undefined}
          />
          <button
            type="button"
            className="btn sm"
            onClick={() => setShowPassword((v) => !v)}
            aria-pressed={showPassword}
            aria-label={showPassword ? 'Hide password' : 'Show password'}
          >
            {showPassword ? 'Hide' : 'Show'}
          </button>
        </div>
        {mode === 'register' && (
          <ul className="tiny muted auth-rules">
            <li>At least 12 characters</li>
            <li>Contains a letter and a digit</li>
          </ul>
        )}
      </div>
      <button className="btn primary" type="submit" disabled={pending}>
        {pending ? 'Working…' : mode === 'login' ? 'Sign in' : 'Register'}
      </button>
      <p className="tiny muted" style={{ marginBottom: 0 }}>
        {mode === 'login' ? (
          <>
            Demo operator: <span className="mono">demo_operator_1790944169073</span>
            {' · '}
            <Link to="/signup">New here? Register</Link>
            <br />
            Password reset is not available in this build — contact your admin.
          </>
        ) : (
          <>
            <Link to="/login">Back to sign in</Link>
          </>
        )}
      </p>
    </form>
  )
}

function friendlyValidation(cause: ApiError): string {
  const messages = cause.violations.map((v) => `${v.field}: ${v.message}`).filter(Boolean)
  if (messages.length > 0) return messages.join(' ')
  return cause.message || 'The server rejected the submission. Check the fields and try again.'
}
