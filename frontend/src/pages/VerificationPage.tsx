/**
 * The Redline: run deterministic rule analysis, then verify claims against
 * retrieved evidence.
 *
 * <p>The UI is built around the separation of signals, because that is the
 * substantive claim PRISM makes: the model's score, the deterministic rule
 * penalty, the fused ranking score, and the evidence status are four different
 * things and are never collapsed into one "confidence".
 */
import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { verificationApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  Pager,
  Stat,
  useAction,
  useAsync,
} from '../components/ui'

export function VerificationPage() {
  const { selected } = useCorpus()
  const [lastOutcome, setLastOutcome] = useState<Record<string, unknown> | null>(null)

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

  const claims = useAsync(
    () => (selected ? verificationApi.verdicts(selected.id, page) : Promise.resolve(null)),
    [selected?.id, page],
  )

  const verifyAll = useAction(async () => {
    if (!selected) return
    const result = await verificationApi.verifyAll(selected.id)
    setLastOutcome(result as unknown as Record<string, unknown>)
    claims.reload()
  })

  const verifyOne = useAction(async (claimId: number) => {
    if (!selected) return
    const result = await verificationApi.verify(claimId)
    setLastOutcome(result as unknown as Record<string, unknown>)
    claims.reload()
  })

  if (!selected) {
    return (
      <>
        <PageHeader title="Verification" />
        <Empty title="No corpus selected">Select a corpus to verify its claims.</Empty>
      </>
    )
  }

  return (
    <>
      <PageHeader
        title="Verification"
        subtitle={
          <>
            Deterministic rule analysis runs first and does not depend on any model. Retrieval is
            scoped to <strong>{selected.name}</strong>. If nothing is retrieved, the verdict is{' '}
            <code className="inline">SOURCE_MISSING</code> and the model is never consulted — a
            verdict that means "no source exists here" is never confused with one that means
            "the source disagrees".
          </>
        }
        actions={
          <button className="btn primary" onClick={() => void verifyAll.run()} disabled={verifyAll.pending}>
            {verifyAll.pending && <span className="spinner" aria-hidden="true" />} Verify all approved
            claims
          </button>
        }
      />

      {verifyAll.error != null && <ErrorState error={verifyAll.error} />}
      {verifyOne.error != null && <ErrorState error={verifyOne.error} />}

      {lastOutcome != null && (
        <Alert kind="ok">
          Last run: {String(lastOutcome.verdictType ?? lastOutcome.succeeded ?? '—')}
          {lastOutcome.evidenceCount != null && (
            <> · {String(lastOutcome.evidenceCount)} evidence passages linked</>
          )}
          {lastOutcome.error != null && <> · {String(lastOutcome.error)}</>}
        </Alert>
      )}

      <div className="grid cols-3" style={{ margin: 'var(--space-4) 0' }}>
        <Stat
          label="Verdicts recorded"
          value={claims.data?.total ?? 0}
          accent
          hint="one current verdict per claim"
        />
        <Stat
          label="Model score"
          hint="what the judge said, in [0,1]"
          value={<span style={{ fontSize: 18 }}>separate</span>}
        />
        <Stat
          label="Rule penalty"
          hint="deterministic deduction, independent of any model"
          value={<span style={{ fontSize: 18 }}>separate</span>}
        />
      </div>

      <Card title="Verdicts" flush>
        {claims.loading && <Loading />}
        {claims.error != null && <ErrorState error={claims.error} />}
        {claims.data && claims.data.total === 0 && (
          <Empty title="No verdicts yet">
            Approve some claims in the approval queue, then verify them. Verification performs its
            own corpus-scoped retrieval and model call per claim.
          </Empty>
        )}
        {claims.data && claims.data.total > 0 && (
          <>
            <div className="table-wrap">
              <table className="data">
              <thead>
                <tr>
                  <th>Claim</th>
                  <th>Verdict</th>
                  <th>Evidence</th>
                  <th className="num">Model</th>
                  <th className="num">Rule</th>
                  <th className="num">Fused</th>
                  <th>Adjudication</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {claims.data.content.map((v) => (
                  <tr key={v.id}>
                    <td style={{ maxWidth: 300 }}>
                      <strong>{v.subject}</strong>
                      <div className="tiny">{v.claimText}</div>
                    </td>
                    <td>
                      <span
                        className={`badge ${
                          v.verdictType === 'SUPPORTED'
                            ? 'approved'
                            : v.verdictType === 'CONTRADICTED'
                              ? 'contradicted'
                              : v.verdictType === 'EXAGGERATED'
                                ? 'rejected'
                                : 'missing'
                        }`}
                      >
                        {v.verdictType.replace(/_/g, ' ').toLowerCase()}
                      </span>
                      {v.overridden && (
                        <div className="tiny muted">
                          human: {v.humanVerdictType?.replace(/_/g, ' ').toLowerCase()}
                        </div>
                      )}
                    </td>
                    <td className="tiny">
                      <span
                        className={`badge ${v.evidenceStatus === 'EVIDENCE_FOUND' ? 'verified' : 'missing'}`}
                      >
                        {v.evidenceStatus === 'EVIDENCE_FOUND' ? 'found' : 'none'}
                      </span>
                    </td>
                    <td className="num tiny mono">{v.llmScore ?? '—'}</td>
                    <td className="num tiny mono">{v.rulePenalty ?? '—'}</td>
                    <td className="num tiny mono">{v.fusedScore ?? '—'}</td>
                    <td>
                      <span
                        className={`badge ${
                          v.adjudicationState === 'HUMAN_DECISION'
                            ? 'verified'
                            : v.adjudicationState === 'CONTESTED'
                              ? 'pending'
                              : 'neutral'
                        }`}
                      >
                        {v.adjudicationState.replace(/_/g, ' ').toLowerCase()}
                      </span>
                      {v.adjudicator && <div className="tiny muted">by {v.adjudicator}</div>}
                    </td>
                    <td>
                      <div className="btn-row">
                        <button
                          className="btn sm ghost"
                          onClick={() => void verifyOne.run(v.claimId)}
                          disabled={verifyOne.pending}
                          title={`Re-verify claim ${v.claimId}`}
                        >
                          Verify
                        </button>
                        <Link className="btn sm" to={`/verdicts/${v.id}`}>
                          Inspect
                        </Link>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
            <Pager
              page={page}
              totalPages={Math.ceil(claims.data.total / (claims.data.size || 50))}
              total={claims.data.total}
              onPrev={() => gotoPage(page - 1)}
              onNext={() => gotoPage(page + 1)}
            />
          </>
        )}
      </Card>

      <div style={{ marginTop: 'var(--space-4)' }}>
        <Alert kind="info">
          <strong>The fused score is a ranking, not a probability.</strong> It is an arithmetic
          combination of the model score and the deterministic rule penalty. No calibration
          experiment has been run, so a fused score of 0.8 does not mean "80% likely to be true" —
          it means this claim ranked above others with a lower fused score. Read the verdict type
          and the evidence, not the number.
        </Alert>
      </div>
    </>
  )
}
