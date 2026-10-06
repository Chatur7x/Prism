/**
 * Application root: public routes, auth gate, corpus scope, and routing.
 *
 * <p>Route structure mirrors the pipeline itself, so the UI reads left-to-right
 * in the same order the data is produced: dashboard -> corpora -> documents ->
 * approval -> verification -> graph -> contradictions -> council -> reports ->
 * chat -> Glass Box.
 *
 * <p>Canonical decisions (see docs/FRONTEND-ROUTES.md): no /claims aliases —
 * claims live under /verification and /verdicts. /traces redirects to
 * /glassbox. Role denial redirects to /unauthorized instead of rendering an
 * in-place empty state, so the URL and the UI agree.
 */
import { Suspense, lazy } from 'react'
import { Link, Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom'

import { AppShell } from './components/AppShell'
import { AuthProvider, useAuth } from './auth/AuthContext'
import type { Role } from './api/types'
import { CorpusProvider } from './corpus/CorpusContext'
import { BootPage } from './pages/BootPage'
import { Loading } from './components/ui'

// Every page is split out. A reviewer typically uses three or four routes, so
// bundling the whole pipeline's UI into the entry chunk would make the login
// screen wait on code it cannot use. BootPage stays eager: it is what a page
// refresh needs immediately, and code-splitting it would only add a round trip
// to the first paint.
const HomePage = lazy(() => import('./pages/HomePage').then((m) => ({ default: m.HomePage })))
const LoginPage = lazy(() => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })))
const SignupPage = lazy(() => import('./pages/SignupPage').then((m) => ({ default: m.SignupPage })))
const UnauthorizedPage = lazy(() =>
  import('./pages/UnauthorizedPage').then((m) => ({ default: m.UnauthorizedPage })),
)
const DashboardPage = lazy(() =>
  import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })),
)
const CorporaPage = lazy(() => import('./pages/CorporaPage').then((m) => ({ default: m.CorporaPage })))
const DocumentsPage = lazy(() =>
  import('./pages/DocumentsPage').then((m) => ({ default: m.DocumentsPage })),
)
const DocumentDetailPage = lazy(() =>
  import('./pages/DocumentDetailPage').then((m) => ({ default: m.DocumentDetailPage })),
)
const ApprovalQueuePage = lazy(() =>
  import('./pages/ApprovalQueuePage').then((m) => ({ default: m.ApprovalQueuePage })),
)
const KnowledgePage = lazy(() => import('./pages/KnowledgePage').then((m) => ({ default: m.KnowledgePage })))
const VerificationPage = lazy(() =>
  import('./pages/VerificationPage').then((m) => ({ default: m.VerificationPage })),
)
const VerdictsPage = lazy(() => import('./pages/VerdictsPage').then((m) => ({ default: m.VerdictsPage })))
const VerdictDetailPage = lazy(() =>
  import('./pages/VerdictDetailPage').then((m) => ({ default: m.VerdictDetailPage })),
)
const GraphPage = lazy(() => import('./pages/GraphPage').then((m) => ({ default: m.GraphPage })))
const ContradictionsPage = lazy(() =>
  import('./pages/ContradictionsPage').then((m) => ({ default: m.ContradictionsPage })),
)
const DebatePage = lazy(() => import('./pages/DebatePage').then((m) => ({ default: m.DebatePage })))
const ReportsPage = lazy(() => import('./pages/ReportsPage').then((m) => ({ default: m.ReportsPage })))
const ChatPage = lazy(() => import('./pages/ChatPage').then((m) => ({ default: m.ChatPage })))
const GlassBoxPage = lazy(() =>
  import('./pages/GlassBoxPage').then((m) => ({ default: m.GlassBoxPage })),
)
const TraceDetailPage = lazy(() =>
  import('./pages/TraceDetailPage').then((m) => ({ default: m.TraceDetailPage })),
)
const AdminPage = lazy(() => import('./pages/AdminPage').then((m) => ({ default: m.AdminPage })))

/**
 * Signed-in visitors have no business on marketing/auth routes: send them to
 * the dashboard instead of showing a second login form.
 */
function PublicOnly({ children }: { children: React.ReactNode }) {
  const { user, ready } = useAuth()
  if (!ready) return <BootPage />
  if (user) return <Navigate to="/dashboard" replace />
  return <>{children}</>
}

/** Role gate that redirects (URL stays truthful) instead of empty states. */
function RequireRoleRoute({ roles, children }: { roles: Role[]; children: React.ReactNode }) {
  const { user } = useAuth()
  if (!user || !roles.includes(user.role)) return <Navigate to="/unauthorized" replace />
  return <>{children}</>
}

/** Legacy /traces deep links keep working: the canonical route is /glassbox. */
function TraceRedirect() {
  const { id } = useParams()
  return <Navigate to={`/glassbox/${id}`} replace />
}

/** Everything behind the auth gate: corpus scope, shell, and the routes. */
function Authenticated() {
  const { ready } = useAuth()
  const location = useLocation()
  if (!ready) return <BootPage />

  return (
    <CorpusProvider>
      <AppShell>
        <Suspense fallback={<Loading label="Loading view" />}>
          {/*
            Keyed on the pathname so each route remounts and replays its enter
            transition. Deliberately the pathname and not the whole location:
            changing a query string — a filter, a page number, a search term —
            is a state change within a page, not a navigation, and animating it
            would make every keystroke in a search box feel like a page load.
          */}
          <div className="route-view" key={location.pathname}>
            <Routes>
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/corpora" element={<CorporaPage />} />
            <Route path="/documents" element={<DocumentsPage />} />
            <Route path="/documents/:id" element={<DocumentDetailPage />} />
            <Route
              path="/approval"
              element={
                <RequireRoleRoute roles={['VERIFIER', 'ADMIN']}>
                  <ApprovalQueuePage />
                </RequireRoleRoute>
              }
            />
            <Route path="/knowledge" element={<KnowledgePage />} />
            <Route path="/verification" element={<VerificationPage />} />
            <Route path="/verdicts" element={<VerdictsPage />} />
            <Route path="/verdicts/:id" element={<VerdictDetailPage />} />
            <Route path="/graph" element={<GraphPage />} />
            <Route path="/contradictions" element={<ContradictionsPage />} />
            <Route
              path="/debates/:id"
              element={
                <RequireRoleRoute roles={['VERIFIER', 'ADMIN']}>
                  <DebatePage />
                </RequireRoleRoute>
              }
            />
            <Route path="/reports" element={<ReportsPage />} />
            <Route path="/chat" element={<ChatPage />} />
            <Route path="/glassbox" element={<GlassBoxPage />} />
            <Route path="/glassbox/:id" element={<TraceDetailPage />} />
            <Route path="/traces" element={<Navigate to="/glassbox" replace />} />
            <Route path="/traces/:id" element={<TraceRedirect />} />
            <Route
              path="/admin"
              element={
                <RequireRoleRoute roles={['ADMIN']}>
                  <AdminPage />
                </RequireRoleRoute>
              }
            />
            <Route path="*" element={<NotFound />} />
            </Routes>
          </div>
        </Suspense>
      </AppShell>
    </CorpusProvider>
  )
}

function NotFound() {
  return (
    <div className="empty">
      <div className="empty-title">Page not found</div>
      <p>
        That route does not exist in PRISM. <Link to="/dashboard">Go to the dashboard</Link> or{' '}
        <Link to="/">read about the product</Link>.
      </p>
    </div>
  )
}

export function App() {
  return (
    <AuthProvider>
      <Suspense fallback={<Loading label="Loading" />}>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route
            path="/login"
            element={
              <PublicOnly>
                <LoginPage />
              </PublicOnly>
            }
          />
          <Route
            path="/signup"
            element={
              <PublicOnly>
                <SignupPage />
              </PublicOnly>
            }
          />
          <Route path="/unauthorized" element={<UnauthorizedPage />} />
          <Route
            path="/*"
            element={<Authenticated />}
          />
        </Routes>
      </Suspense>
    </AuthProvider>
  )
}