/**
 * Application root: auth gate, corpus selection, and routing.
 *
 * <p>Route structure mirrors the pipeline itself, so the UI reads left-to-right
 * in the same order the data is produced: corpora -> documents -> approval ->
 * verification -> graph -> contradictions -> council -> reports -> chat ->
 * Glass Box.
 */
import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'

import { AppShell } from './components/AppShell'
import { AuthProvider, RequireRole, useAuth } from './auth/AuthContext'
import { CorpusProvider } from './corpus/CorpusContext'
import { BootPage } from './pages/BootPage'
import { LoginPage } from './pages/LoginPage'
import { Loading } from './components/ui'

// Every page is split out. The application has fourteen routes and a reviewer
// typically uses three or four of them, so bundling the whole pipeline's UI into
// the entry chunk would make the login screen wait on code it cannot use.
//
// BootPage and LoginPage stay eager: they are what an unauthenticated visitor
// needs immediately, and code-splitting them would only add a round trip to the
// first paint.
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
 * Gate: waits for the session check, then requires authentication.
 *
 * <p>Rendered while `ready` is false so a page refresh with a valid token does
 * not flash the login screen before the token is confirmed.
 */
function AuthGate() {
  const { user, ready } = useAuth()

  if (!ready) return <BootPage />
  if (!user) return <LoginPage />

  return (
    <CorpusProvider>
      <AppShell>
        {/* The boundary is deliberately inside AppShell, so the navigation and
            corpus selector stay on screen while a page chunk loads. Losing the
            whole shell on every route change would be a worse trade than the
            extra request. */}
        <Suspense fallback={<Loading label="Loading view" />}>
          <Routes>
            <Route path="/" element={<Navigate to="/documents" replace />} />
            <Route path="/corpora" element={<CorporaPage />} />
            <Route path="/documents" element={<DocumentsPage />} />
            <Route path="/documents/:id" element={<DocumentDetailPage />} />
            <Route
              path="/approval"
              element={
                <RequireRole roles={['VERIFIER', 'ADMIN']}>
                  <ApprovalQueuePage />
                </RequireRole>
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
                <RequireRole roles={['VERIFIER', 'ADMIN']}>
                  <DebatePage />
                </RequireRole>
              }
            />
            <Route path="/reports" element={<ReportsPage />} />
            <Route path="/chat" element={<ChatPage />} />
            <Route path="/glassbox" element={<GlassBoxPage />} />
            <Route path="/glassbox/:id" element={<TraceDetailPage />} />
            <Route
              path="/admin"
              element={
                <RequireRole roles={['ADMIN']}>
                  <AdminPage />
                </RequireRole>
              }
            />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </Suspense>
      </AppShell>
    </CorpusProvider>
  )
}

function NotFound() {
  return (
    <div className="empty">
      <div className="empty-title">Page not found</div>
      <p>That route does not exist in PRISM.</p>
    </div>
  )
}

export function App() {
  return (
    <AuthProvider>
      <AuthGate />
    </AuthProvider>
  )
}
