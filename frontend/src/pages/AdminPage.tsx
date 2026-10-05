/**
 * Administration: users, roles, and background jobs.
 *
 * <p>Two things here are not decoration. Role changes are how a person becomes
 * able to approve their own colleagues' extractions, so every change is written
 * through the API and attributed. And the job list is where an operator looks
 * when a document seems stuck — a job left RUNNING with a stale heartbeat is the
 * signature of a worker that died, and it should be visible rather than inferred.
 */
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { adminApi } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import { useCorpus } from '../corpus/CorpusContext'
import type { BackgroundJobView, Role, UserSummary } from '../api/types'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  Pager,
  Stat,
  StatusBadge,
  formatDate,
  useAction,
  useAsync,
} from '../components/ui'

const ROLE_MEANING: Record<Role, string> = {
  ANALYST: 'Uploads documents and proposes knowledge. Cannot approve.',
  VERIFIER: 'Approves proposals, adjudicates verdicts, chairs Councils.',
  ADMIN: 'Everything a verifier can do, plus user and system administration.',
}

export function AdminPage() {
  const { refresh } = useAuth()
  const { reload } = useCorpus()
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<Role>('VERIFIER')

  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const gotoPage = (next: number) =>
    setParams(next <= 0 ? {} : { page: String(next) }, { replace: true })
  const users = useAsync(async () => await adminApi.users(page), [page])
  const jobs = useAsync(() => adminApi.jobs(), [])
  const status = useAsync(() => adminApi.status(), [])

  const createUser = useAction(async () => {
    await adminApi.createUser(username, email, password, role)
    setUsername('')
    setEmail('')
    setPassword('')
    await users.reload()
  })

  const changeRole = useAction(async (id: number, next: Role) => {
    await adminApi.updateRole(id, next)
    await users.reload()
    // The caller's own role may have just changed, so re-read it rather than
    // leaving the sidebar showing a stale one.
    await refresh()
  })

  const stuck = (jobs.data ?? []).filter(
    (job: BackgroundJobView) => job.status === 'RUNNING' || job.status === 'ABANDONED',
  )

  return (
    <>
      <PageHeader
        title="Administration"
        subtitle={
          <>
            User accounts, roles, and the durable job queue. Self-registration always produces an{' '}
            <code className="inline">ANALYST</code>: nobody can grant themselves the right to
            approve their own extractions.
          </>
        }
      />

      {users.error != null && <ErrorState error={users.error} />}
      {jobs.error != null && <ErrorState error={jobs.error} />}
      {createUser.error != null && <ErrorState error={createUser.error} />}
      {changeRole.error != null && <ErrorState error={changeRole.error} />}

      {status.data && (
        <div className="grid cols-4" style={{ marginBottom: 'var(--space-4)' }}>
          <Stat label="Active corpora" value={status.data.activeCorpora} />
          <Stat label="Documents" value={status.data.totalDocuments} />
          <Stat
            label="Pending approvals"
            value={status.data.pendingApprovals}
            accent={status.data.pendingApprovals > 0}
          />
          <Stat
            label="Quarantined responses"
            value={status.data.quarantinedResponses}
            hint={status.data.quarantinedResponses > 0 ? 'model misbehaviour, kept on record' : 'none'}
          />
        </div>
      )}

      <div className="grid cols-2">
        <Card title={`Users (${users.data?.totalElements ?? 0})`} flush>
          {users.loading && <Loading />}
          {users.data && users.data.totalElements === 0 && <Empty title="No users" />}
          {users.data && users.data.content.length > 0 && (
            <>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th>User</th>
                      <th>Role</th>
                      <th>Enabled</th>
                      <th>Created</th>
                    </tr>
                  </thead>
                  <tbody>
                    {users.data.content.map((user: UserSummary) => (
                      <tr key={user.id}>
                        <td>
                          <strong>{user.username}</strong>
                          <div className="tiny muted">{user.email}</div>
                        </td>
                        <td style={{ minWidth: 160 }}>
                          <select
                            value={user.role}
                            onChange={(event) =>
                              void changeRole.run(user.id, event.target.value as Role)
                            }
                            disabled={changeRole.pending}
                            title={ROLE_MEANING[user.role]}
                          >
                            {(['ANALYST', 'VERIFIER', 'ADMIN'] as Role[]).map((r) => (
                              <option key={r} value={r}>
                                {r}
                              </option>
                            ))}
                          </select>
                          <div className="tiny muted">{ROLE_MEANING[user.role]}</div>
                        </td>
                        <td>
                          <span className={`badge ${user.enabled ? 'approved' : 'missing'}`}>
                            {user.enabled ? 'enabled' : 'disabled'}
                          </span>
                        </td>
                        <td className="tiny nowrap">{formatDate(user.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager
                page={page}
                totalPages={users.data.totalPages}
                total={users.data.totalElements}
                onPrev={() => gotoPage(page - 1)}
                onNext={() => gotoPage(page + 1)}
              />
            </>
          )}
        </Card>

        <div className="stack">
          <Card title="Create a user">
            <form
              className="stack"
              onSubmit={(event) => {
                event.preventDefault()
                void createUser.run()
              }}
            >
              <div className="field">
                <label className="field-label" htmlFor="new-username">
                  Username
                </label>
                <input
                  id="new-username"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  minLength={3}
                  maxLength={64}
                  required
                />
              </div>
              <div className="field">
                <label className="field-label" htmlFor="new-email">
                  Email
                </label>
                <input
                  id="new-email"
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  maxLength={320}
                  required
                />
              </div>
              <div className="field">
                <label className="field-label" htmlFor="new-password">
                  Password
                </label>
                <input
                  id="new-password"
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  minLength={12}
                  maxLength={128}
                  required
                />
                <span className="field-hint">At least 12 characters. Hashed with BCrypt.</span>
              </div>
              <div className="field">
                <label className="field-label" htmlFor="new-role">
                  Role
                </label>
                <select
                  id="new-role"
                  value={role}
                  onChange={(event) => setRole(event.target.value as Role)}
                >
                  {(['ANALYST', 'VERIFIER', 'ADMIN'] as Role[]).map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </select>
                <span className="field-hint">{ROLE_MEANING[role]}</span>
              </div>
              <button className="btn primary" type="submit" disabled={createUser.pending}>
                {createUser.pending && <span className="spinner" aria-hidden="true" />} Create user
              </button>
            </form>
          </Card>

          <Card title={`Background jobs (${jobs.data?.length ?? 0})`} flush>
            {jobs.loading && <Loading />}
            {jobs.data && jobs.data.length === 0 && <Empty title="No jobs recorded" />}
            {jobs.data && jobs.data.length > 0 && (
              <div className="table-wrap" style={{ maxHeight: 320, overflowY: 'auto' }}>
                <table className="data">
                  <thead>
                    <tr>
                      <th>Key</th>
                      <th>Status</th>
                      <th className="num">Attempts</th>
                      <th>Heartbeat</th>
                    </tr>
                  </thead>
                  <tbody>
                    {jobs.data.map((job: BackgroundJobView) => (
                      <tr key={job.id}>
                        <td className="tiny mono">{job.jobKey}</td>
                        <td>
                          <StatusBadge value={job.status} />
                          {job.lastError && (
                            <div className="tiny" style={{ color: 'var(--danger)' }}>
                              {job.lastError.slice(0, 70)}
                            </div>
                          )}
                        </td>
                        <td className="num tiny">
                          {job.attemptCount}/{job.maxAttempts}
                        </td>
                        <td className="tiny nowrap">{formatDate(job.heartbeatAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </div>
      </div>

      {stuck.length > 0 && (
        <div style={{ marginTop: 'var(--space-4)' }}>
          <Alert kind="warn">
            <strong>{stuck.length} job(s) are RUNNING or ABANDONED.</strong> A RUNNING job with a
            stale heartbeat means a worker died mid-operation. On the next restart, recovery
            requeues it in place — preserving its attempt count so a crash loop is still bounded and
            eventually surfaces here rather than retrying forever.
          </Alert>
        </div>
      )}

      <div style={{ marginTop: 'var(--space-4)' }}>
        <div className="btn-row">
          <button
            className="btn ghost sm"
            onClick={() => {
              void users.reload()
              void jobs.reload()
              void status.reload()
              void reload()
            }}
          >
            Refresh
          </button>
        </div>
      </div>
    </>
  )
}
