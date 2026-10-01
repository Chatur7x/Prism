/**
 * The knowledge record: approved triples, claims, and resolved entities.
 *
 * <p>Default view is APPROVED only, because that is what the graph is built
 * from. Pending proposals are visible but visually separated — showing them
 * alongside trusted knowledge without distinction would misrepresent what the
 * system actually holds.
 */
import { useState } from 'react'

import { knowledgeApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import {
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  Stat,
  StatusBadge,
  formatDate,
  useAsync,
} from '../components/ui'

type Tab = 'triples' | 'claims' | 'entities'

export function KnowledgePage() {
  const { selected } = useCorpus()
  const [tab, setTab] = useState<Tab>('triples')
  const [entitySearch, setEntitySearch] = useState('')

  const triples = useAsync(
    () => (selected ? knowledgeApi.triples(selected.id, 'APPROVED') : Promise.resolve(null)),
    [selected?.id],
  )
  const claims = useAsync(
    () => (selected ? knowledgeApi.claims(selected.id, 'APPROVED') : Promise.resolve(null)),
    [selected?.id],
  )
  const entities = useAsync(
    () =>
      selected
        ? knowledgeApi.entities(selected.id, entitySearch || undefined)
        : Promise.resolve([]),
    [selected?.id, entitySearch],
  )

  if (!selected) {
    return (
      <>
        <PageHeader title="Knowledge" />
        <Empty title="No corpus selected">Select a corpus to inspect its knowledge record.</Empty>
      </>
    )
  }

  return (
    <>
      <PageHeader
        title="Knowledge record"
        subtitle={
          <>
            Trusted knowledge in <strong>{selected.name}</strong>. Only approved facts appear by
            default, because only approved facts become graph edges. Every row keeps its source
            sentence and chunk, so any assertion can be traced back to the text that supports it.
          </>
        }
      />

      <div className="row" style={{ marginBottom: 'var(--space-4)' }}>
        {(['triples', 'claims', 'entities'] as Tab[]).map((key) => (
          <button
            key={key}
            className={tab === key ? 'btn sm primary' : 'btn sm'}
            onClick={() => setTab(key)}
          >
            {key === 'triples' ? 'Triples' : key === 'claims' ? 'Claims' : 'Entities'}
          </button>
        ))}
      </div>

      {tab === 'triples' && (
        <Card title="Approved triples" flush>
          {triples.loading && <Loading />}
          {triples.error != null && <ErrorState error={triples.error} />}
          {triples.data && triples.data.total === 0 && (
            <Empty title="No approved triples">
              Approve proposals in the approval queue to populate the knowledge graph.
            </Empty>
          )}
          {triples.data && triples.data.total > 0 && (
            <>
              <div className="card-body tight">
                <Stat label="Approved triples" value={triples.data.total} accent />
              </div>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th>Subject</th>
                      <th>Predicate</th>
                      <th>Object</th>
                      <th>Source sentence</th>
                      <th>Decided by</th>
                      <th>Decided</th>
                    </tr>
                  </thead>
                  <tbody>
                    {triples.data.content.map((t) => (
                      <tr key={t.id}>
                        <td>
                          <strong>{t.subject}</strong>
                        </td>
                        <td className="tiny mono">{t.predicate}</td>
                        <td>{t.object}</td>
                        <td style={{ maxWidth: 360 }}>
                          <div className="quote tiny">{t.sourceSentence}</div>
                          <div className="tiny muted" style={{ marginTop: 2 }}>
                            {t.sourceDocumentTitle} · chunk {t.sourceChunkId} ·{' '}
                            {t.evidenceChunkCount} chunk
                            {t.evidenceChunkCount === 1 ? '' : 's'}
                          </div>
                        </td>
                        <td className="tiny">{t.decidedBy ?? '—'}</td>
                        <td className="tiny nowrap">
                          {formatDate(t.decidedAt)}
                          {t.decisionNote && <div className="muted">{t.decisionNote}</div>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </Card>
      )}

      {tab === 'claims' && (
        <Card title="Approved claims" flush>
          {claims.loading && <Loading />}
          {claims.error != null && <ErrorState error={claims.error} />}
          {claims.data && claims.data.total === 0 && (
            <Empty title="No approved claims">
              Approve claim proposals, then verify them in the Verification tab.
            </Empty>
          )}
          {claims.data && claims.data.total > 0 && (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Subject</th>
                    <th>Claim</th>
                    <th>Polarity</th>
                    <th>Status</th>
                    <th>Source</th>
                  </tr>
                </thead>
                <tbody>
                  {claims.data.content.map((c) => (
                    <tr key={c.id}>
                      <td>
                        <strong>{c.subject}</strong>
                      </td>
                      <td style={{ maxWidth: 420 }}>{c.claimText}</td>
                      <td className="tiny">{c.polarity.toLowerCase()}</td>
                      <td>
                        <StatusBadge value={c.status} />
                      </td>
                      <td className="tiny muted">
                        {c.sourceDocumentTitle}
                        <br />chunk {c.sourceChunkId}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}

      {tab === 'entities' && (
        <Card
          title="Resolved entities"
          actions={
            <input
              placeholder="Filter by name"
              value={entitySearch}
              onChange={(e) => setEntitySearch(e.target.value)}
              style={{ width: 240 }}
            />
          }
          flush
        >
          {entities.loading && <Loading />}
          {entities.error != null && <ErrorState error={entities.error} />}
          {entities.data && entities.data.length === 0 && (
            <Empty title="No entities yet">
              Entities are created when a triple's endpoints resolve. Resolution is conservative:
              ambiguous cases are flagged for review rather than merged.
            </Empty>
          )}
          {entities.data && entities.data.length > 0 && (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Normalized key</th>
                    <th>Type</th>
                    <th>Resolution</th>
                    <th className="num">Support</th>
                  </tr>
                </thead>
                <tbody>
                  {entities.data.map((e) => (
                    <tr key={e.id}>
                      <td>
                        <strong>{e.displayName}</strong>
                      </td>
                      <td className="tiny mono">{e.normalizedName}</td>
                      <td className="tiny muted">{e.type.toLowerCase()}</td>
                      <td>
                        <StatusBadge value={e.resolutionState} />
                      </td>
                      <td className="num tiny">{e.supportCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </>
  )
}
