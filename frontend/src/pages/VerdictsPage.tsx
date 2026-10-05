/** Verdict list with type breakdown, mirroring the knowledge and graph tabs. */
import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import type { VerdictType } from '../api/types'
import { verificationApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import {
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  Pager,
  Stat,
  StatusBadge,
  formatDate,
  useAsync,
} from '../components/ui'

const TYPES: VerdictType[] = [
  'SUPPORTED',
  'CONTRADICTED',
  'INSUFFICIENT_EVIDENCE',
  'EXAGGERATED',
  'SOURCE_MISSING',
]

export function VerdictsPage() {
  const { selected } = useCorpus()
  const [filter, setFilter] = useState<VerdictType | 'ALL'>('ALL')

  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const gotoPage = (next: number) =>
    setParams(next <= 0 ? {} : { page: String(next) }, { replace: true })
  const corpusId = selected?.id
  const prevCorpus = useRef(corpusId)
  useEffect(() => {
    if (prevCorpus.current !== corpusId) {
      prevCorpus.current = corpusId
      if (page !== 0) setParams({}, { replace: true })
    }
  }, [corpusId, page, setParams])

  const verdicts = useAsync(
    () => (selected ? verificationApi.verdicts(selected.id, page) : Promise.resolve(null)),
    [selected?.id, page],
  )

  if (!selected) {
    return (
      <>
        <PageHeader title="Verdicts" />
        <Empty title="No corpus selected">Select a corpus to inspect its verdicts.</Empty>
      </>
    )
  }

  const rows = (verdicts.data?.content ?? []).filter(
    (v) => filter === 'ALL' || v.verdictType === filter,
  )
  const counts = new Map<VerdictType, number>()
  for (const v of verdicts.data?.content ?? []) {
    counts.set(v.verdictType, (counts.get(v.verdictType) ?? 0) + 1)
  }

  return (
    <>
      <PageHeader
        title="Verdicts"
        subtitle={
          <>
            One current verdict per claim. Where a human has adjudicated, both the machine result
            and the human decision are shown side by side — the machine verdict is never
            overwritten.
          </>
        }
      />

      {verdicts.error != null && <ErrorState error={verdicts.error} />}

      <div className="grid cols-4" style={{ marginBottom: 'var(--space-4)' }}>
        <Stat label="Total verdicts" value={verdicts.data?.total ?? 0} accent />
        {TYPES.slice(0, 3).map((type) => (
          <Stat key={type} label={type.replace(/_/g, ' ').toLowerCase()} value={counts.get(type) ?? 0} />
        ))}
      </div>

      <div className="row" style={{ marginBottom: 'var(--space-3)' }}>
        <button className={filter === 'ALL' ? 'btn sm primary' : 'btn sm'} onClick={() => setFilter('ALL')}>
          All
        </button>
        {TYPES.map((type) => (
          <button
            key={type}
            className={filter === type ? 'btn sm primary' : 'btn sm'}
            onClick={() => setFilter(type)}
          >
            {type.replace(/_/g, ' ').toLowerCase()} ({counts.get(type) ?? 0})
          </button>
        ))}
      </div>

      <Card title={`Verdicts (${rows.length})`} flush>
        {verdicts.loading && <Loading />}
        {verdicts.data && verdicts.data.total === 0 && (
          <Empty title="No verdicts yet">
            Approve claims in the approval queue, then run verification.
          </Empty>
        )}
        {verdicts.data && verdicts.data.total > 0 && rows.length === 0 && (
          <Empty title="No verdicts of this type" />
        )}
        {rows.length > 0 && (
          <>
            <div className="table-wrap">
              <table className="data">
              <thead>
                <tr>
                  <th>Claim</th>
                  <th>Verdict</th>
                  <th>Evidence</th>
                  <th>Adjudication</th>
                  <th>Recorded</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((v) => (
                  <tr key={v.id}>
                    <td style={{ maxWidth: 360 }}>
                      <strong>{v.subject}</strong>
                      <div className="tiny">{v.claimText}</div>
                    </td>
                    <td>
                      <StatusBadge value={v.verdictType} />
                      {v.overridden && (
                        <div className="tiny muted" style={{ marginTop: 2 }}>
                          machine said {v.machineVerdictType.replace(/_/g, ' ').toLowerCase()}
                        </div>
                      )}
                    </td>
                    <td>
                      <StatusBadge value={v.evidenceStatus} label={v.evidenceStatus === 'EVIDENCE_FOUND' ? 'found' : 'none'} />
                    </td>
                    <td className="tiny">
                      {v.adjudicationState.replace(/_/g, ' ').toLowerCase()}
                      {v.adjudicator && <div className="muted">by {v.adjudicator}</div>}
                    </td>
                    <td className="tiny nowrap">{formatDate(v.createdAt)}</td>
                    <td>
                      <Link className="btn sm" to={`/verdicts/${v.id}`}>
                        Inspect
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
            {verdicts.data && (
              <Pager
                page={page}
                totalPages={Math.ceil(verdicts.data.total / (verdicts.data.size || 50))}
                total={verdicts.data.total}
                onPrev={() => gotoPage(page - 1)}
                onNext={() => gotoPage(page + 1)}
              />
            )}
          </>
        )}
      </Card>
    </>
  )
}
