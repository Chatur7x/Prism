/**
 * Application shell: sidebar navigation, corpus picker, and the signed-in user.
 *
 * <p>The nav order follows the pipeline, which is the main affordance this
 * layout provides: a reader can see where they are in the process at a glance.
 * Sections the current role cannot use are hidden rather than shown disabled,
 * because a permanently dead control is noise.
 *
 * <p>Icons are drawn, not typed. An earlier revision used fourteen Unicode
 * characters whose stroke weights came from whatever font happened to resolve
 * them, which is why the column looked assembled rather than drawn; see
 * `primitives.tsx` for the family and the reasoning.
 */
import {
  useCallback,
  useLayoutEffect,
  useState,
  type ReactNode,
} from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'

import { useAuth, useSessionCountdown } from '../auth/AuthContext'
import { useCorpus } from '../corpus/CorpusContext'
import { Icon, type IconName } from './primitives'

interface NavItem {
  to: string
  label: string
  icon: IconName
  /** Roles permitted to see the link. Absent means every authenticated user. */
  roles?: string[]
  group: string
}

const NAV: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: 'dashboard', group: 'Source' },
  { to: '/corpora', label: 'Corpora', icon: 'corpora', group: 'Source' },
  { to: '/documents', label: 'Documents', icon: 'documents', group: 'Source' },
  {
    to: '/approval',
    label: 'Approval queue',
    icon: 'approval',
    roles: ['VERIFIER', 'ADMIN'],
    group: 'Source',
  },

  { to: '/knowledge', label: 'Knowledge', icon: 'knowledge', group: 'Record' },
  { to: '/verification', label: 'Verification', icon: 'verification', group: 'Record' },
  { to: '/verdicts', label: 'Verdicts', icon: 'verdicts', group: 'Record' },

  { to: '/graph', label: 'Graph', icon: 'graph', group: 'Analysis' },
  {
    to: '/contradictions',
    label: 'Contradictions',
    icon: 'contradictions',
    group: 'Analysis',
  },
  { to: '/reports', label: 'Reports', icon: 'reports', group: 'Analysis' },

  { to: '/chat', label: 'Grounded chat', icon: 'chat', group: 'Use' },
  { to: '/glassbox', label: 'Glass Box', icon: 'glassbox', group: 'Use' },
  {
    to: '/admin',
    label: 'Administration',
    icon: 'admin',
    roles: ['ADMIN'],
    group: 'Use',
  },
]

/** Coarse session readout: minutes while healthy, a warning under five. */
function formatSession(ms: number): string {
  if (ms <= 0) return 'expired — re-sign in'
  const minutes = Math.floor(ms / 60_000)
  if (minutes < 1) return 'under a minute — refresh imminent'
  if (minutes < 60) return `${minutes}m left`
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m left`
}

/**
 * Sidebar navigation.
 *
 * On narrow screens the whole bar becomes a horizontal strip with wrapping
 * groups rather than collapsing behind a hamburger. Every one of PRISM's routes
 * has to stay reachable without a JavaScript-driven drawer, because a route
 * hidden behind a disclosure control is a route some reviewers never find.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth()
  const { corpora, selected, select, canVerify } = useCorpus()
  const sessionMs = useSessionCountdown()
  const navigate = useNavigate()
  const location = useLocation()

  const role = user?.role ?? 'ANALYST'
  const visible = NAV.filter((item) => !item.roles || item.roles.includes(role))

  // Group while preserving order, so the sidebar mirrors the pipeline stages.
  const groups: { name: string; items: NavItem[] }[] = []
  for (const item of visible) {
    const last = groups[groups.length - 1]
    if (last && last.name === item.group) last.items.push(item)
    else groups.push({ name: item.group, items: [item] })
  }

  const activePath = visible.find(
    (item) => location.pathname === item.to || location.pathname.startsWith(`${item.to}/`),
  )?.to

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
                Every query is scoped to this corpus. Access is re-checked
                server-side.
              </span>
            )}
          </div>
        )}

        {/*
          The rail is one element for the whole sidebar, measured from the live
          DOM rather than assumed. One moving element carries real information
          — where you are in the pipeline — whereas a per-link indicator would
          give four unrelated fades. It is hidden on narrow screens because the
          layout changes axis there and a vertical rail would be meaningless.
        */}
        <ActiveRail activePath={activePath} />

        {groups.map((group) => (
          <div className="nav-group" key={group.name}>
            <div className="nav-group-label">{group.name}</div>
            {group.items.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
              >
                <span className="nav-icon">
                  <Icon name={item.icon} />
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
          <button type="button" className="btn ghost sm" onClick={handleLogout}>
            Sign out
          </button>
        </div>
      </nav>

      <main className="main">
        {children}
      </main>
    </div>
  )
}

/**
 * The single sliding active indicator.
 *
 * Measured after layout from the rendered link rather than derived from the
 * nav array, because the link's real geometry depends on wrapping, on whether
 * the group collapsed, and on the icon column — none of which the data knows
 * about. A `ResizeObserver` re-measures so a viewport change moves it too.
 *
 * Transform-only, so the rail never triggers layout on the pages it moves
 * across.
 */
function ActiveRail({ activePath }: { activePath?: string }) {
  const [geometry, setGeometry] = useState<{ top: number; height: number } | null>(null)

  const measure = useCallback(() => {
    if (!activePath) {
      setGeometry(null)
      return
    }
    const sidebar = document.querySelector('.sidebar')
    const link = sidebar?.querySelector<HTMLElement>(`a[href="${activePath}"]`)
    if (!sidebar || !link) {
      setGeometry(null)
      return
    }
    // getBoundingClientRect rather than offsetTop: offsetTop is relative to
    // whichever ancestor is positioned, which is the nav group rather than the
    // sidebar, and silently produces a rail in the wrong place.
    const sidebarBox = sidebar.getBoundingClientRect()
    const linkBox = link.getBoundingClientRect()
    setGeometry({ top: linkBox.top - sidebarBox.top, height: linkBox.height })
  }, [activePath])

  useLayoutEffect(() => {
    measure()
    const sidebar = document.querySelector('.sidebar')
    if (!sidebar) return
    const observer = new ResizeObserver(measure)
    observer.observe(sidebar)
    return () => observer.disconnect()
  }, [measure])

  if (!geometry) return null

  return (
    <span
      className="nav-rail"
      aria-hidden="true"
      style={{ transform: `translateY(${geometry.top}px)`, height: geometry.height }}
    />
  )
}