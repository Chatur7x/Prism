/**
 * The knowledge record: approved triples, claims, and resolved entities.
 *
 * <p>Default view is APPROVED only, because that is what the graph is built
 * from. Pending proposals are visible but visually separated — showing them
 * alongside trusted knowledge without distinction would misrepresent what the
 * system actually holds.
 */
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { knowledgeApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import { Tabs } from '../components/primitives'
import {
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  Pager,
  SearchInput,
  Stat,
  StatusBadge,
  formatDate,
  useAsync,
} from '../components/ui'

type Tab = 'triples' | 'claims' | 'entities'

export function KnowledgePage() {
  const { selected } = useCorpus()
  const [tab, setTab] = useState<Tab>('triples')

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
  const switchTab = (next: Tab) => {
    setTab(next)
    if (page !== 0) setParams({}, { replace: true })
  }

  const triples = useAsync(
    () => (selected ? knowledgeApi.triples(selected.id, 'APPROVED', page) : Promise.resolve(null)),
    [selected?.id, page],
  )
  const claims = useAsync(
    () => (selected ? knowledgeApi.claims(selected.id, 'APPROVED', page) : Promise.resolve(null)),
    [selected?.id, page],
  )
  const [entityQuery, setEntityQuery] = useState('')
  const entities = useAsync(
    () =>
      selected
        ? knowledgeApi.entities(selected.id, entityQuery || undefined)
        : Promise.resolve([]),
    [selected?.id, entityQuery],
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

      {/*
        The shared Tabs primitive rather than three loose buttons.

        This was previously a row of `btn sm primary` toggles, which meant the
        control had no `role="tablist"`, no `aria-selected`, and no arrow-key
        navigation — a screen-reader user was told nothing about which of the
        three views was showing, and a keyboard user had to Tab through the
        group instead of arrowing within it. The primitive already existed for
        exactly this; it just was never wired up.
      */}
      <Tabs
        label="Knowledge record view"
        active={tab}
        onChange={(next) => switchTab(next as Tab)}
        tabs={[
          { id: 'triples', label: 'Triples', count: triples.data?.total ?? null },
          { id: 'claims', label: 'Claims', count: claims.data?.total ?? null },
          { id: 'entities', label: 'Entities', count: entities.data?.length ?? null },
        ]}
      />

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
              <Pager
                page={page}
                totalPages={Math.ceil(triples.data.total / (triples.data.size || 50))}
                total={triples.data.total}
                onPrev={() => gotoPage(page - 1)}
                onNext={() => gotoPage(page + 1)}
              />
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
            <>
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
      )}

      {tab === 'entities' && (
        <Card
          title="Resolved entities"
          actions={
            <SearchInput
              value={entityQuery}
              onChange={(value) => setEntityQuery(value)}
              placeholder="Filter by name"
              label="Search entities"
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
