/**
 * Role-denied landing. Replaces the old in-place "Not permitted" empty
 * states so the URL and the UI agree about what happened.
 */
import { Link } from 'react-router-dom'

import { useAuth } from '../auth/AuthContext'

export function UnauthorizedPage() {
  const { user } = useAuth()
  return (
    <div className="auth-page">
      <div className="empty">
        <div className="empty-title">Not permitted</div>
        <p>
          {user
            ? `Signed in as ${user.username} (${user.role}). This area needs a higher role — ask an admin.`
            : 'This area needs a signed-in account with a higher role.'}
        </p>
        <p>
          <Link to="/dashboard">Go to the dashboard</Link>
        </p>
      </div>
    </div>
  )
}
