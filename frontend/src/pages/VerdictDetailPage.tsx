/**
 * A single verdict with its evidence and the human decision.
 *
 * <p>Both verdicts are always shown when they differ. A human override is
 * recorded <em>alongside</em> the machine result, never in place of it, so a
 * reader can always see what the model originally claimed.
 */
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import type { VerdictType } from '../api/types'
import { verificationApi } from '../api/endpoints'
import { useAuth } from '../auth/AuthContext'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  KeyValue,
  Loading,
  PageHeader,
  formatDate,
  formatScore,
  useAction,
  useAsync,
} from '../components/ui'

const VERDICT_TONE: Record<VerdictType, string> = {
  SUPPORTED: 'approved',
  CONTRADICTED: 'contradicted',
  INSUFFICIENT_EVIDENCE: 'missing',
  EXAGGERATED: 'rejected',
  SOURCE_MISSING: 'missing',
}

const VERDICT_MEANING: Record<VerdictType, string> = {
  SUPPORTED: 'The retrieved evidence supports this claim.',
  CONTRADICTED: 'The retrieved evidence disagrees with this claim.',
  INSUFFICIENT_EVIDENCE:
    'Evidence was found but does not settle the claim either way. This is NOT the same as no source existing.',
  EXAGGERATED: 'The claim overstates what the evidence supports.',
  SOURCE_MISSING: 'No passage in this corpus matched the claim. The model was not consulted.',
}

export function VerdictDetailPage() {
  const { id } = useParams<{ id: string }>()
  const verdictId = Number(id)
  const { user } = useAuth()
  // The corpus selector is deliberately not consulted. A verdict is addressed by
  // its own id and the backend re-checks access against that verdict's corpus, so
  // a link to a verdict stays valid even if the current selection is elsewhere.
  const [verdict, setVerdict] = useState<VerdictType | ''>('')
  const [note, setNote] = useState('')

  const detail = useAsync(() => verificationApi.verdict(verdictId), [verdictId])
  const history = useAsync(() => verificationApi.history(verdictId), [verdictId])

  const adjudicate = useAction(async () => {
    if (!verdict) return
    const updated = await verificationApi.adjudicate(verdictId, verdict, note)
    setNote('')
    detail.reload()
    history.reload()
    return updated
  })

  const canVerify = user?.role === 'VERIFIER' || user?.role === 'ADMIN'
  const v = detail.data

  if (detail.loading || !v) return <Loading label="Loading verdict" />

  return (
    <>
      {detail.error != null && <ErrorState error={detail.error} />}
      <PageHeader
        title="Verdict"
        subtitle={
          <>
            Claim #{v.claimId} · <span className="muted">{v.subject}</span>
          </>
        }
        actions={
          <Link className="btn" to="/verdicts">
            All verdicts
          </Link>
        }
      />

      <div className="grid cols-2">
        <div className="stack">
          <Card title="Outcome">
            <div className="row" style={{ marginBottom: 'var(--space-3)' }}>
              <span className={`badge ${VERDICT_TONE[v.verdictType]}`} style={{ fontSize: 13 }}>
                {v.verdictType.replace(/_/g, ' ').toLowerCase()}
              </span>
              {v.overridden && <span className="badge verified">Human override</span>}
            </div>
            <p className="muted tiny">{VERDICT_MEANING[v.verdictType]}</p>

            <KeyValue
              rows={[
                ['Claim', v.claimText],
                [
                  'Machine verdict',
                  <span key="m">{v.machineVerdictType.replace(/_/g, ' ').toLowerCase()}</span>,
                ],
                [
                  'Human verdict',
                  v.humanVerdictType ? (
                    v.humanVerdictType.replace(/_/g, ' ').toLowerCase()
                  ) : (
                    <span className="muted">not decided</span>
                  ),
                ],
                [
                  'Adjudication',
                  v.adjudicationState.replace(/_/g, ' ').toLowerCase() +
                    (v.adjudicator ? ` by ${v.adjudicator}` : ''),
                ],
                ['Adjudicated at', formatDate(v.adjudicatedAt)],
                ['Note', v.adjudicationNote ?? <span className="muted">—</span>],
              ]}
            />
          </Card>

          <Card title="The four signals, kept separate">
            <KeyValue
              rows={[
                [
                  'Model score',
                  <span key="a" className="mono">
                    {formatScore(v.llmScore)}
                  </span>,
                ],
                [
                  'Rule penalty',
                  <span key="b" className="mono">
                    {formatScore(v.rulePenalty)}
                  </span>,
                ],
                [
                  'Fused score',
                  <span key="c" className="mono">
                    {formatScore(v.fusedScore)}
                  </span>,
                ],
                [
                  'Evidence status',
                  <span key="d">
                    {v.evidenceStatus === 'EVIDENCE_FOUND' ? 'evidence found' : 'no evidence'}
                  </span>,
                ],
                ['Rule version', <span key="e" className="mono">{v.ruleVersion ?? '—'}</span>],
              ]}
            />
            <Alert kind="info">
              These are four independent measurements. The fused score is a{' '}
              <strong>ranking</strong> value, not a calibrated probability — see{' '}
              <code className="inline">docs/evaluation.md</code>.
            </Alert>
          </Card>

          <Card title="Why">
            {v.verdictReason && <p className="tiny">{v.verdictReason}</p>}
            {v.llmReasoning && (
              <details className="disclosure">
                <summary>Judge explanation</summary>
                <p className="tiny" style={{ marginTop: 4 }}>
                  {v.llmReasoning}
                </p>
              </details>
            )}
            <KeyValue
              rows={[
                ['Model', v.model ?? '—'],
                ['Prompt version', <span className="mono">{v.promptVersion ?? '—'}</span>],
                ['Retrieval query', <span className="tiny">{v.retrievalQuery ?? '—'}</span>],
                ['Trace run', v.traceRunId ?? '—'],
                ['Recorded', formatDate(v.createdAt)],
              ]}
            />
          </Card>
        </div>

        <div className="stack">
          <Card title={`Evidence (${v.evidence.length})`}>
            {v.evidence.length === 0 ? (
              <Empty title="No evidence linked">
                This verdict rests on nothing in the corpus. That is why its type is{' '}
                <code className="inline">{v.verdictType}</code>.
              </Empty>
            ) : (
              <div className="stack tight">
                {v.evidence.map((passage, index) => (
                  <div className="evidence" key={`${passage.chunkId}-${index}`}>
                    {passage.chunkText}
                    <div className="evidence-meta">
                      <span>rank {passage.retrievalRank}</span>
                      <span>{passage.documentTitle}</span>
                      <span>chunk {passage.chunkId}</span>
                      {passage.retrievalScore != null && (
                        <span>score {passage.retrievalScore.toFixed(4)}</span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Card>

          {canVerify && v.adjudicationState !== 'HUMAN_DECISION' && (
            <Card title="Human adjudication">
              <p className="muted tiny">
                A human decision is final authority. The machine verdict is preserved alongside it,
                never overwritten.
              </p>
              <div className="stack">
                <div className="field">
                  <label className="field-label" htmlFor="human-verdict">
                    Your verdict
                  </label>
                  <select
                    id="human-verdict"
                    value={verdict}
                    onChange={(e) => setVerdict(e.target.value as VerdictType)}
                  >
                    {(
                      [
                        'SUPPORTED',
                        'CONTRADICTED',
                        'INSUFFICIENT_EVIDENCE',
                        'EXAGGERATED',
                        'SOURCE_MISSING',
                      ] as VerdictType[]
                    ).map((type) => (
                      <option key={type} value={type}>
                        {type.replace(/_/g, ' ').toLowerCase()}
                      </option>
                    ))}
                  </select>
                  <span className="field-hint">{verdict ? VERDICT_MEANING[verdict] : 'Select a verdict type above'}</span>
                </div>
                <div className="field">
                  <label className="field-label" htmlFor="adjudication-note">
                    Reasoning (recorded permanently)
                  </label>
                  <textarea
                    id="adjudication-note"
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                    maxLength={2000}
                    placeholder="Why you reached this decision. This is part of the audit record."
                  />
                </div>
                {adjudicate.error != null && <ErrorState error={adjudicate.error} />}
                <button
                  className="btn primary"
                  onClick={() => void adjudicate.run()}
                  disabled={adjudicate.pending || !verdict}
                >
                  {adjudicate.pending && <span className="spinner" aria-hidden="true" />} Record
                  decision
                </button>
              </div>
            </Card>
          )}

          <Card title="Superseded machine verdicts">
            {history.loading && <Loading />}
            {history.error != null && <ErrorState error={history.error} />}
            {history.data && history.data.length === 0 && (
              <Empty title="No history">
                This claim has been verified once. Re-verifying moves the prior result here rather
                than overwriting it.
              </Empty>
            )}
            {history.data && history.data.length > 0 && (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th>Verdict</th>
                      <th>Evidence</th>
                      <th className="num">Fused</th>
                      <th>Model</th>
                      <th>Superseded</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.data.map((row, index) => {
                      const r = row as Record<string, string | number>
                      return (
                        <tr key={index}>
                          <td className="tiny">{String(r.verdictType).replace(/_/g, ' ').toLowerCase()}</td>
                          <td className="tiny">{String(r.evidenceStatus).replace(/_/g, ' ').toLowerCase()}</td>
                          <td className="num tiny mono">{String(r.fusedScore ?? '—')}</td>
                          <td className="tiny">{String(r.model ?? '—')}</td>
                          <td className="tiny nowrap">{formatDate(String(r.supersededAt))}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </div>
      </div>
    </>
  )
}
