/**
 * Public welcome screen: the front door for signed-out visitors.
 *
 * <p>Two jobs: earn the demo (animated hero stating the invariant) and get out
 * of the way (the sign-in card on the right). Registration stays where it was —
 * this screen signs in; it does not onboard.
 */
import { useState, type FormEvent } from 'react'

import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/ui'

const STAGES = [
  ['Source', 'Documents in, chunked and proposed'],
  ['Record', 'A human approves; contradiction found by rules'],
  ['Analysis', 'Council debates, chair weights, report cites'],
  ['Use', 'Grounded chat and the Glass Box replay'],
] as const

export function WelcomePage() {
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
    <div className="welcome">
      <div className="welcome-orb welcome-orb-a" aria-hidden="true" />
      <div className="welcome-orb welcome-orb-b" aria-hidden="true" />

      <header className="welcome-hero">
        <div className="welcome-mark" aria-hidden="true">
          <div className="brand-mark welcome-mark-gem" />
        </div>
        <p className="welcome-kicker">Auditable AI analysis</p>
        <h1 className="welcome-title">
          Every answer is traceable
          <br />
          to the <span className="welcome-sheen">evidence behind it</span>.
        </h1>
        <p className="welcome-creed">
          <strong>Deterministic code owns the record.</strong> Models only propose.
          A human verifier decides.
        </p>

        <div className="welcome-stages" aria-label="Pipeline stages">
          {STAGES.map(([name, blurb], i) => (
            <div
              className="welcome-stage"
              style={{ animationDelay: `calc(${i} * 90ms)` }}
              key={name}
            >
              <span className="welcome-stage-index">{String(i + 1).padStart(2, '0')}</span>
              <span className="welcome-stage-name">{name}</span>
              <span className="welcome-stage-blurb">{blurb}</span>
            </div>
          ))}
        </div>

        <div className="welcome-marquee" aria-hidden="true">
          <div className="welcome-marquee-track">
            {Array.from({ length: 2 }).map((_, copy) => (
              <span key={copy}>
                {['28 predicates', 'ENGINE / LLM / HUMAN', 'quarantine, not silent drop',
                  'contradiction by rule', 'chair-weighted council', 'cited synthesis',
                  'Glass Box replay', '248 tests, 0 skipped'].join('  ·  ') + '  ·  '}
              </span>
            ))}
          </div>
        </div>
      </header>

      <main className="welcome-signin">
        <form onSubmit={submit} className="fatal-card welcome-card">
          <h2>{mode === 'login' ? 'Sign in' : 'Create analyst account'}</h2>
          {error != null && (
            <Alert kind="error">
              <ErrorText error={error} />
            </Alert>
          )}
          <div className="field">
            <label className="field-label" htmlFor="welcome-username">
              Username
            </label>
            <input
              id="welcome-username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              required
            />
          </div>
          {mode === 'register' && (
            <div className="field">
              <label className="field-label" htmlFor="welcome-email">
                Email
              </label>
              <input
                id="welcome-email"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="email"
                required
              />
            </div>
          )}
          <div className="field">
            <label className="field-label" htmlFor="welcome-password">
              Password
            </label>
            <input
              id="welcome-password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              required
            />
          </div>
          <button className="btn primary" type="submit" disabled={pending}>
            {pending ? 'Working…' : mode === 'login' ? 'Sign in' : 'Register'}
          </button>
          <p className="tiny muted" style={{ marginBottom: 0 }}>
            {mode === 'login' ? (
              <>
                Demo operator: <span className="mono">demo_operator_1790944169073</span>
                {' · '}
                <a
                  href="#register"
                  onClick={(event) => {
                    event.preventDefault()
                    setMode('register')
                    setError(null)
                  }}
                >
                  New here? Register
                </a>
              </>
            ) : (
              <>
                Registration produces an analyst account, which can propose but
                not approve.{' '}
                <a
                  href="#login"
                  onClick={(event) => {
                    event.preventDefault()
                    setMode('login')
                    setError(null)
                  }}
                >
                  Back to sign in
                </a>
              </>
            )}
          </p>
        </form>
      </main>
    </div>
  )
}

function ErrorText({ error }: { error: unknown }) {
  if (error instanceof Error) return <>{error.message}</>
  if (typeof error === 'object' && error !== null && 'message' in error) {
    return <>{String((error as { message: unknown }).message)}</>
  }
  return <>Sign-in failed.</>
}
