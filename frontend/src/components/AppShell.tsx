/**
 * Application shell: sidebar navigation, corpus picker, and the signed-in user.
 *
 * <p>The nav order follows the pipeline, which is the main affordance this
 * layout provides: a reader can see where they are in the process at a glance.
 * Sections the current role cannot use are hidden rather than shown disabled,
 * because a permanently dead control is noise.
 */
import type { ReactNode } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'

import { useAuth, useSessionCountdown } from '../auth/AuthContext'
import { useCorpus } from '../corpus/CorpusContext'

interface NavItem {
  to: string
  label: string
  icon: string
  /** Roles permitted to see the link. Absent means every authenticated user. */
  roles?: string[]
  group: string
}

const NAV: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: '◉', group: 'Source' },
  { to: '/corpora', label: 'Corpora', icon: '▤', group: 'Source' },
  { to: '/documents', label: 'Documents', icon: '▦', group: 'Source' },
  { to: '/approval', label: 'Approval queue', icon: '✓', roles: ['VERIFIER', 'ADMIN'], group: 'Source' },

  { to: '/knowledge', label: 'Knowledge', icon: '⬡', group: 'Record' },
  { to: '/verification', label: 'Verification', icon: '⚖', group: 'Record' },
  { to: '/verdicts', label: 'Verdicts', icon: '⊘', group: 'Record' },

  { to: '/graph', label: 'Graph', icon: '◈', group: 'Analysis' },
  { to: '/contradictions', label: 'Contradictions', icon: '⇄', group: 'Analysis' },
  { to: '/reports', label: 'Reports', icon: '▥', group: 'Analysis' },

  { to: '/chat', label: 'Grounded chat', icon: '◍', group: 'Use' },
  { to: '/glassbox', label: 'Glass Box', icon: '⌬', group: 'Use' },
  { to: '/admin', label: 'Administration', icon: '⚙', roles: ['ADMIN'], group: 'Use' },
]

/** Coarse session readout: minutes while healthy, a warning under five. */
function formatSession(ms: number): string {
  if (ms <= 0) return 'expired — re-sign in'
  const minutes = Math.floor(ms / 60_000)
  if (minutes < 1) return 'under a minute — refresh imminent'
  if (minutes < 60) return `${minutes}m left`
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m left`
}

export function AppShell({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth()
  const { corpora, selected, select, canVerify } = useCorpus()
  const sessionMs = useSessionCountdown()
  const navigate = useNavigate()

  const role = user?.role ?? 'ANALYST'
  const visible = NAV.filter((item) => !item.roles || item.roles.includes(role))

  // Group while preserving order, so the sidebar mirrors the pipeline stages.
  const groups: { name: string; items: NavItem[] }[] = []
  for (const item of visible) {
    const last = groups[groups.length - 1]
    if (last && last.name === item.group) last.items.push(item)
    else groups.push({ name: item.group, items: [item] })
  }

  function handleLogout() {
    logout()
    navigate('/')
  }

  return (
    <div className="app-shell">
      <nav className="sidebar" aria-label="Primary">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true" />
          <div>
            <div className="brand-text">PRISM</div>
            <div className="brand-sub">Auditable analysis</div>
          </div>
        </div>

        {corpora.length > 0 && (
          <div className="field">
            <label className="field-label" htmlFor="corpus-picker">
              Corpus
            </label>
            <select
              id="corpus-picker"
              value={selected?.id ?? ''}
              onChange={(event) => select(Number(event.target.value))}
            >
              {corpora.map((corpus) => (
                <option key={corpus.id} value={corpus.id}>
                  {corpus.name}
                </option>
              ))}
            </select>
            {selected && (
              <span className="field-hint">
                Every query is scoped to this corpus. Access is re-checked server-side.
              </span>
            )}
          </div>
        )}

        {groups.map((group) => (
          <div className="nav-group" key={group.name}>
            <div className="nav-group-label">{group.name}</div>
            {group.items.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
              >
                <span className="nav-icon" aria-hidden="true">
                  {item.icon}
                </span>
                {item.label}
              </NavLink>
            ))}
          </div>
        ))}

        <div className="sidebar-footer">
          <div className="who">
            <span className="who-name">{user?.username}</span>
            <span className="who-role">
              {role}
              {role === 'ANALYST' && ' — can propose, not approve'}
              {canVerify && role !== 'ADMIN' && ' — can approve and chair'}
            </span>
            {sessionMs != null && (
              <span className="who-session" role="status">
                Session: {formatSession(sessionMs)}
              </span>
            )}
          </div>
          <button className="btn ghost sm" onClick={handleLogout}>
            Sign out
          </button>
        </div>
      </nav>

      <main className="main">{children}</main>
    </div>
  )
}
