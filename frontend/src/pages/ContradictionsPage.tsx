/**
 * Contradiction findings and the Councils convened over them.
 *
 * <p>Detection is deterministic Java over approved records only, so the same
 * corpus always yields the same findings. Each finding shows both sides with the
 * document and chunk that produced it, because "these two facts conflict" is
 * only actionable if the reviewer can open the sources.
 */
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

import { contradictionApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import type { Contradiction, Debate } from '../api/types'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  Loading,
  Modal,
  PageHeader,
  Pager,
  Stat,
  StatusBadge,
  formatDate,
  useAction,
  useAsync,
} from '../components/ui'

type Filter = 'ALL' | 'OPEN' | 'IN_DEBATE' | 'RESOLVED' | 'DISMISSED'

export function ContradictionsPage() {
  const { selected, canVerify } = useCorpus()
  const navigate = useNavigate()
  const [filter, setFilter] = useState<Filter>('ALL')
  const [expanded, setExpanded] = useState<number | null>(null)
  const [dismissTarget, setDismissTarget] = useState<Contradiction | null>(null)

  const corpusId = selected?.id
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const gotoPage = (next: number) =>
    setParams(next <= 0 ? {} : { page: String(next) }, { replace: true })
  const prevCorpus = useRef(corpusId)
  useEffect(() => {
    if (prevCorpus.current !== corpusId) {
      prevCorpus.current = corpusId
      if (page !== 0) setParams({}, { replace: true })
    }
  }, [corpusId, page, setParams])
  const pickFilter = (next: Filter) => {
    setFilter(next)
    if (page !== 0) setParams({}, { replace: true })
  }
  const findings = useAsync(
    () =>
      corpusId
        ? contradictionApi.list(corpusId, filter === 'ALL' ? undefined : filter, page)
        : Promise.resolve(null),
    [corpusId, filter, page],
  )
  const debates = useAsync(
    () => (corpusId ? contradictionApi.debates(corpusId, 0) : Promise.resolve(null)),
    [corpusId],
  )
  const stats = useAsync(
    () => (corpusId ? contradictionApi.counts(corpusId) : Promise.resolve(null)),
    [corpusId],
  )

  const scan = useAction(async () => {
    if (!corpusId) return
    await contradictionApi.scan(corpusId)
    findings.reload()
    stats.reload()
  })

  const convene = useAction(async (contradictionId: number) => {
    if (!corpusId) return null
    const debate = await contradictionApi.convene(corpusId, contradictionId)
    findings.reload()
    debates.reload()
    if (debate) navigate(`/debates/${debate.id}`)
    return debate
  })

  const dismiss = useAction(async (contradictionId: number) => {
    await contradictionApi.dismiss(contradictionId)
    setDismissTarget(null)
    findings.reload()
    stats.reload()
  })

  if (!selected) {
    return (
      <>
        <PageHeader title="Contradictions" />
        <Empty title="No corpus selected">Select a corpus to scan it for contradictions.</Empty>
      </>
    )
  }

  const rows = findings.data?.content ?? []

  return (
    <>
      <PageHeader
        title="Contradictions"
        subtitle={
          <>
            Deterministic detectors over the approved record of <strong>{selected.name}</strong>.
            Only approved facts and claims are compared, and a predicate whose semantics are unknown
            resolves to a non-conflicting cardinality — so an unrecognised relation never
            manufactures a false contradiction.
          </>
        }
        actions={
          <button className="btn primary" onClick={() => void scan.run()} disabled={scan.pending}>
            {scan.pending && <span className="spinner" aria-hidden="true" />} Re-scan
          </button>
        }
      />

      {scan.error != null && <ErrorState error={scan.error} />}
      {convene.error != null && <ErrorState error={convene.error} />}
      {dismiss.error != null && <ErrorState error={dismiss.error} />}
      {findings.error != null && <ErrorState error={findings.error} />}

      {stats.data && (
        <div className="grid cols-4" style={{ marginBottom: 'var(--space-4)' }}>
          <Stat label="Total findings" value={stats.data.contradictions} accent />
          <Stat label="Open" value={stats.data.contradictionsByStatus?.OPEN ?? 0} hint="awaiting a decision" />
          <Stat label="In debate" value={stats.data.contradictionsByStatus?.IN_DEBATE ?? 0} />
          <Stat label="Resolved" value={stats.data.contradictionsByStatus?.RESOLVED ?? 0} />
        </div>
      )}

      <div className="row" style={{ marginBottom: 'var(--space-3)' }}>
        {(['ALL', 'OPEN', 'IN_DEBATE', 'RESOLVED', 'DISMISSED'] as Filter[]).map((f) => (
          <button
            key={f}
            className={filter === f ? 'btn sm primary' : 'btn sm'}
            onClick={() => pickFilter(f)}
          >
            {f.replace(/_/g, ' ').toLowerCase()}
          </button>
        ))}
      </div>

      <Card title={`Findings (${findings.data?.total ?? 0})`} flush>
        {findings.loading && <Loading />}
        {findings.data && findings.data.total === 0 && (
          <Empty title="No contradictions found">
            Either the approved record is internally consistent, or nothing has been approved yet.
            Detection only ever runs over approved facts — a pending proposal cannot create a
            finding.
          </Empty>
        )}
        {findings.data && findings.data.total > 0 && rows.length === 0 && (
          <Empty title="No findings of this status" />
        )}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Conflict</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Detected</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((row: Contradiction) => (
                  <tr key={row.id}>
                    <td style={{ maxWidth: 520 }}>
                      <div>
                        <strong>{row.subjectText}</strong>{' '}
                        <span className="mono tiny">{row.predicate}</span>
                      </div>
                      {row.explanation && <div className="tiny muted">{row.explanation}</div>}
                      <div className="tiny muted" style={{ marginTop: 2 }}>
                        rule {row.ruleCode} · {row.ruleVersion}
                      </div>
                      <button
                        className="btn ghost sm"
                        onClick={() => setExpanded(expanded === row.id ? null : row.id)}
                      >
                        {expanded === row.id ? 'Hide the two sides' : 'Show the two sides'}
                      </button>
                      {expanded === row.id && (
                        <div className="stack tight" style={{ marginTop: 6 }}>
                          {/* Each side names its own document and chunk, so a
                              reviewer can open the source rather than trusting
                              the detector's restatement of the conflict. */}
                          <div className="evidence">
                            <div className="tiny" style={{ fontWeight: 600 }}>Side A</div>
                            <div>{row.leftDescription}</div>
                            <div className="tiny muted">
                              {row.leftTripleId != null && `triple ${row.leftTripleId} `}
                              {row.leftClaimId != null && `claim ${row.leftClaimId}`}
                            </div>
                          </div>
                          <div className="evidence">
                            <div className="tiny" style={{ fontWeight: 600 }}>Side B</div>
                            <div>{row.rightDescription}</div>
                            <div className="tiny muted">
                              {row.rightTripleId != null && `triple ${row.rightTripleId} `}
                              {row.rightClaimId != null && `claim ${row.rightClaimId}`}
                            </div>
                          </div>
                        </div>
                      )}
                    </td>
                    <td className="tiny">{row.contradictionType.replace(/_/g, ' ').toLowerCase()}</td>
                    <td>
                      <StatusBadge value={row.status} />
                    </td>
                    <td className="tiny nowrap">{formatDate(row.createdAt)}</td>
                    <td style={{ minWidth: 210 }}>
                      <div className="btn-row">
                        {canVerify && row.status === 'OPEN' && (
                          <>
                            <button
                              className="btn primary sm"
                              onClick={() => void convene.run(row.id)}
                              disabled={convene.pending}
                            >
                              Convene Council
                            </button>
                            <button
                              className="btn ghost sm"
                              onClick={() => setDismissTarget(row)}
                              disabled={dismiss.pending}
                            >
                              Dismiss
                            </button>
                          </>
                        )}
                        {row.status === 'IN_DEBATE' && row.debateId != null && (
                          <button
                            className="btn sm"
                            onClick={() => navigate(`/debates/${row.debateId}`)}
                          >
                            Open debate
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {findings.data && findings.data.total > 0 && (
          <Pager
            page={page}
            totalPages={Math.ceil(findings.data.total / (findings.data.size || 50))}
            total={findings.data.total}
            onPrev={() => gotoPage(page - 1)}
            onNext={() => gotoPage(page + 1)}
          />
        )}
      </Card>

      {dismissTarget != null && (
        <Modal
          title="Dismiss this contradiction?"
          onClose={() => setDismissTarget(null)}
          actions={
            <>
              <button className="btn sm ghost" onClick={() => setDismissTarget(null)}>
                Cancel
              </button>
              <button
                className="btn sm primary"
                onClick={() => void dismiss.run(dismissTarget.id)}
                disabled={dismiss.pending}
              >
                {dismiss.pending ? 'Dismissing…' : 'Confirm dismiss'}
              </button>
            </>
          }
        >
          <p>
            <strong>{dismissTarget.subjectText}</strong>{' '}
            <span className="mono tiny">{dismissTarget.predicate}</span>
          </p>
          <p className="tiny muted">
            Dismissing keeps the record for audit; it does not delete the finding. Rule{' '}
            {dismissTarget.ruleCode} · {dismissTarget.ruleVersion}.
          </p>
        </Modal>
      )}

      {debates.data && debates.data.totalElements > 0 && (
        <div style={{ marginTop: 'var(--space-4)' }}>
          <Card title={`Councils (${debates.data.totalElements})`} flush>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Topic</th>
                    <th>State</th>
                    <th className="num">Round</th>
                    <th>Started</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {debates.data.content.map((debate: Debate) => (
                    <tr key={debate.id}>
                      <td>
                        <strong>{debate.topic}</strong>
                        <div className="tiny muted">chaired by {debate.chair}</div>
                      </td>
                      <td>
                        <StatusBadge value={debate.state} />
                        <div className="tiny muted">{debate.stateDescription}</div>
                      </td>
                      <td className="num tiny">
                        {debate.currentRound}/{debate.maxRounds}
                      </td>
                      <td className="tiny nowrap">{formatDate(debate.startedAt)}</td>
                      <td>
                        <button className="btn sm" onClick={() => navigate(`/debates/${debate.id}`)}>
                          Open
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </div>
      )}

      <div style={{ marginTop: 'var(--space-4)' }}>
        <Alert kind="info">
          <strong>Re-scanning is idempotent.</strong> Each conflict carries a stable hash derived
          from both sides in an order-independent way, so scanning twice never creates a duplicate
          and the same pair is never reported twice. Dismissing keeps the record; nothing is
          deleted.
        </Alert>
      </div>
    </>
  )
}
