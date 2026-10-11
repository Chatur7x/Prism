/**
 * Dedicated registration route. Self-registration always yields ANALYST —
 * the card says so, and the backend enforces it regardless of what any
 * client requests.
 */
import { Link, useNavigate } from 'react-router-dom'
import { useEffect } from 'react'

import { useAuth } from '../auth/AuthContext'
import { AuthCard } from '../components/AuthCard'

export function SignupPage() {
  const { register } = useAuth()
  const navigate = useNavigate()

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
      <AuthCard
        mode="register"
        onSubmit={async ({ username, email, password }) => {
          await register(username, email, password)
          navigate('/dashboard', { replace: true })
        }}
      />
      <p className="tiny muted">
        <Link to="/">← Back to the product page</Link>
      </p>
    </div>
  )
}
