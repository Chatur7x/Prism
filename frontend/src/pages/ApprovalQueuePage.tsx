/**
 * The human gate.
 *
 * <p>This is the most consequential screen in PRISM: an approval here is what
 * turns a model proposal into trusted knowledge. Each row therefore shows the
 * exact source sentence the proposal came from, so a reviewer decides on
 * evidence rather than on the model's phrasing.
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'

import { knowledgeApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  Stat,
  useAction,
  useAsync,
} from '../components/ui'

export function ApprovalQueuePage() {
  const { selected } = useCorpus()
  const [note, setNote] = useState<Record<number, string>>({})
  const [busyId, setBusyId] = useState<number | null>(null)

  const queue = useAsync(
    () => (selected ? knowledgeApi.approvalQueue(selected.id, 0, 50) : Promise.resolve(null)),
    [selected?.id],
  )

  const decide = useAction(async (kind: 'triple' | 'claim', id: number, approve: boolean) => {
    setBusyId(id)
    try {
      if (kind === 'triple') {
        if (approve) await knowledgeApi.approveTriple(id, note[id] ?? '')
        else await knowledgeApi.rejectTriple(id, note[id] ?? '')
      } else {
        if (approve) await knowledgeApi.approveClaim(id)
        else await knowledgeApi.rejectClaim(id, note[id] ?? '')
      }
      queue.reload()
    } finally {
      setBusyId(null)
    }
  })

  if (!selected) {
    return (
      <>
        <PageHeader title="Approval queue" />
        <Empty title="No corpus selected">Select a corpus to review its proposals.</Empty>
      </>
    )
  }

  const data = queue.data

  return (
    <>
      <PageHeader
        title="Approval queue"
        subtitle={
          <>
            Model output enters the record only through this screen. Approving a triple adds it to
            the trusted knowledge graph; approving a claim makes it eligible for verification.
            A rejection is kept permanently — nothing is deleted.
          </>
        }
      />

      {decide.error != null && <ErrorState error={decide.error} />}
      {queue.error != null && <ErrorState error={queue.error} />}

      {data && (
        <div className="grid cols-3" style={{ marginBottom: 'var(--space-4)' }}>
          <Stat label="Triples awaiting review" value={data.pendingTripleCount} accent />
          <Stat label="Claims awaiting review" value={data.pendingClaimCount} accent />
          <Stat
            label="Corpus"
            value={<span style={{ fontSize: 16 }}>{selected.name}</span>}
            hint={`${data.triples.length + data.claims.length} shown on this page`}
          />
        </div>
      )}

      {queue.loading && <Loading label="Loading proposals" />}

      {data && data.pendingTripleCount === 0 && data.pendingClaimCount === 0 && (
        <Empty title="Nothing awaiting review">
          Every proposal in this corpus has been decided. Upload another document, or revisit{' '}
          <Link to="/knowledge">the knowledge record</Link>.
        </Empty>
      )}

      {data && data.triples.length > 0 && (
        <Card
          title={`Triple proposals (${data.triples.length})`}
          actions={<span className="tiny muted">Verifying role required</span>}
          flush
        >
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Fact</th>
                  <th>Source sentence</th>
                  <th>Provenance</th>
                  <th>Decision</th>
                </tr>
              </thead>
              <tbody>
                {data.triples.map((triple) => (
                  <tr key={triple.id}>
                    <td style={{ minWidth: 220 }}>
                      <div>
                        <strong>{triple.subject}</strong>
                      </div>
                      <div className="tiny mono" style={{ color: 'var(--ink-2)' }}>
                        {triple.predicate}
                      </div>
                      <div>{triple.object}</div>
                    </td>
                    <td style={{ maxWidth: 380 }}>
                      <div className="quote tiny">{triple.sourceSentence}</div>
                    </td>
                    <td className="tiny muted" style={{ minWidth: 170 }}>
                      {triple.sourceDocumentTitle}
                      <br />
                      chunk {triple.sourceChunkId}
                    </td>
                    <td style={{ minWidth: 230 }}>
                      <input
                        placeholder="Decision note (optional)"
                        value={note[triple.id] ?? ''}
                        onChange={(e) => setNote({ ...note, [triple.id]: e.target.value })}
                        style={{ marginBottom: 6 }}
                      />
                      <div className="btn-row">
                        <button
                          className="btn approve sm"
                          disabled={busyId === triple.id}
                          onClick={() => void decide.run('triple', triple.id, true)}
                        >
                          Approve
                        </button>
                        <button
                          className="btn reject sm"
                          disabled={busyId === triple.id}
                          onClick={() => void decide.run('triple', triple.id, false)}
                        >
                          Reject
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {data && data.claims.length > 0 && (
        <div style={{ marginTop: 'var(--space-4)' }}>
          <Card title={`Claim proposals (${data.claims.length})`} flush>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Claim</th>
                    <th>Source sentence</th>
                    <th>Provenance</th>
                    <th>Decision</th>
                  </tr>
                </thead>
                <tbody>
                  {data.claims.map((claim) => (
                    <tr key={claim.id}>
                      <td style={{ minWidth: 220 }}>
                        <strong>{claim.subject}</strong>
                        <div className="tiny">{claim.claimText}</div>
                        <div className="tiny muted">
                          polarity: {claim.polarity.toLowerCase()}
                        </div>
                      </td>
                      <td style={{ maxWidth: 380 }}>
                        <div className="quote tiny">{claim.sourceSentence}</div>
                      </td>
                      <td className="tiny muted" style={{ minWidth: 170 }}>
                        {claim.sourceDocumentTitle}
                        <br />
                        chunk {claim.sourceChunkId}
                      </td>
                      <td style={{ minWidth: 230 }}>
                        <input
                          placeholder="Decision note (optional)"
                          value={note[claim.id] ?? ''}
                          onChange={(e) => setNote({ ...note, [claim.id]: e.target.value })}
                          style={{ marginBottom: 6 }}
                        />
                        <div className="btn-row">
                          <button
                            className="btn approve sm"
                            disabled={busyId === claim.id}
                            onClick={() => void decide.run('claim', claim.id, true)}
                          >
                            Approve for verification
                          </button>
                          <button
                            className="btn reject sm"
                            disabled={busyId === claim.id}
                            onClick={() => void decide.run('claim', claim.id, false)}
                          >
                            Reject
                          </button>
                        </div>
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
          Approving a triple also triggers a contradiction scan and invalidates the cached graph
          metrics, so the knowledge graph never shows a stale view. A second approval of the same
          item is rejected with <code className="inline">409 Conflict</code> rather than silently
          overwriting the first decision.
        </Alert>
      </div>
    </>
  )
}
