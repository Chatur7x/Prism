/**
 * Dedicated sign-in route. Honors `?returnTo=` so a deep link that met an
 * expired session lands back where it was going after re-authentication.
 */
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useEffect } from 'react'

import { useAuth } from '../auth/AuthContext'
import { AuthCard } from '../components/AuthCard'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const returnTo = params.get('returnTo') ?? '/dashboard'
  const expired = params.get('expired') === '1'

  // Public route renders outside AppShell — see HomePage for why the page
  // owns the document-level dark scope.
  useEffect(() => {
    document.body.classList.add('theme-dark')
    return () => document.body.classList.remove('theme-dark')
  }, [])

  return (
    <div className="auth-page theme-dark aurora">
      <div className="aurora-blob cobalt" aria-hidden="true" />
      <div className="aurora-blob violet" aria-hidden="true" />
      <div className="auth-brand">
        <div className="brand-mark" aria-hidden="true" />
        <div className="brand-text">PRISM</div>
        <div className="brand-sub">Auditable analysis</div>
      </div>
      {expired && (
        <p className="tiny auth-expired" role="status">
          Your session expired. Sign in again — you will return to where you were.
        </p>
      )}
      <AuthCard
        mode="login"
        onSubmit={async ({ username, password }) => {
          await login(username, password)
          navigate(returnTo, { replace: true })
        }}
      />
      <p className="tiny muted">
        <Link to="/">← Back to the product page</Link>
      </p>
    </div>
  )
}
