/**
 * The trusted knowledge graph with PageRank and community structure.
 *
 * <p>Every edge here originates from an approved triple. That is enforced in
 * the query, not in the UI, so a pending or rejected extraction cannot appear
 * in the graph under any circumstance.
 */
import { useState } from 'react'

import { graphApi } from '../api/endpoints'
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

type Scope = 'ALL_APPROVED' | 'VERIFIED_ONLY'

export function GraphPage() {
  const { selected } = useCorpus()
  const [scope, setScope] = useState<Scope>('ALL_APPROVED')
  const [refreshKey, setRefreshKey] = useState(0)

  const graph = useAsync(
    () => (selected ? graphApi.get(selected.id, scope) : Promise.resolve(null)),
    [selected?.id, scope, refreshKey],
  )
  const pagerank = useAsync(
    () => (selected ? graphApi.pagerank(selected.id) : Promise.resolve([])),
    [selected?.id, refreshKey],
  )
  const communities = useAsync(
    () => (selected ? graphApi.communities(selected.id) : Promise.resolve([])),
    [selected?.id, refreshKey],
  )

  // No invalidate call is needed, and deliberately so. The server's graph cache
  // is keyed on corpus, scope, and a hash of the trusted-knowledge ids, and it
  // is evicted whenever an approval or adjudication changes that set. A plain
  // re-read therefore returns exactly the graph the current record implies.
  const refresh = useAction(async () => {
    setRefreshKey((k) => k + 1)
  })

  if (!selected) {
    return (
      <>
        <PageHeader title="Knowledge graph" />
        <Empty title="No corpus selected">Select a corpus to inspect its graph.</Empty>
      </>
    )
  }

  const g = graph.data
  const top = (pagerank.data ?? []).slice(0, 15)
  const comms = communities.data ?? []

  // Edges reference nodes by id. Resolving the names here rather than shipping
  // duplicated text keeps one source of truth for an entity's display name, and
  // an edge whose endpoint is missing from the node list renders as an
  // explicit gap instead of a blank cell.
  const nameById = new Map<number, string>()
  for (const node of g?.nodes ?? []) nameById.set(node.id, node.name)
  const nameOf = (id: number): string => nameById.get(id) ?? `entity ${id}`

  return (
    <>
      <PageHeader
        title="Knowledge graph"
        subtitle={
          <>
            Built only from approved triples in <strong>{selected.name}</strong>. PageRank uses a
            fixed damping factor of 0.85 over a fixed 50 iterations, so the ranking is reproducible
            across runs — it is a structural signal, not a popularity estimate.
          </>
        }
        actions={
          <button className="btn" onClick={() => void refresh.run()} disabled={refresh.pending}>
            {refresh.pending && <span className="spinner" aria-hidden="true" />} Rebuild metrics
          </button>
        }
      />

      {refresh.error != null && <ErrorState error={refresh.error} />}
      {graph.error != null && <ErrorState error={graph.error} />}

      <div className="row" style={{ marginBottom: 'var(--space-4)' }}>
        {(['ALL_APPROVED', 'VERIFIED_ONLY'] as Scope[]).map((s) => (
          <button
            key={s}
            className={scope === s ? 'btn sm primary' : 'btn sm'}
            onClick={() => setScope(s)}
          >
            {s === 'ALL_APPROVED' ? 'All approved facts' : 'Verified claims only'}
          </button>
        ))}
        <span className="tiny muted">
          {scope === 'ALL_APPROVED'
            ? 'Every human-approved triple is an edge.'
            : 'Only edges whose subject-and-predicate matches a SUPPORTED verdict are edges.'}
        </span>
      </div>

      {graph.loading && <Loading label="Computing graph" />}

      {g && (
        <>
          <div className="grid cols-4" style={{ marginBottom: 'var(--space-4)' }}>
            <Stat label="Nodes" value={g.stats.nodeCount} />
            <Stat label="Edges" value={g.stats.edgeCount} accent />
            <Stat label="Density" value={g.stats.density.toFixed(4)} />
            <Stat label="Communities" value={comms.length} hint="label propagation" />
          </div>

          <div className="grid cols-2">
            <Card title="Edges" flush>
              {g.edges.length === 0 ? (
                <Empty title="No edges yet">
                  Approve triples in the approval queue. A graph edge exists only once a human has
                  approved the fact it comes from.
                </Empty>
              ) : (
                <div className="table-wrap" style={{ maxHeight: 560, overflowY: 'auto' }}>
                  <table className="data">
                    <thead>
                      <tr>
                        <th>Subject</th>
                        <th>Predicate</th>
                        <th>Object</th>
                      </tr>
                    </thead>
                    <tbody>
                      {g.edges.map((edge) => (
                        <tr key={`${edge.from}-${edge.predicate}-${edge.to}`}>
                          <td>
                            <strong>{nameOf(edge.from)}</strong>
                          </td>
                          <td className="tiny mono">
                            {edge.predicate}
                            {edge.label && edge.label !== edge.predicate && (
                              <div className="muted">{edge.label}</div>
                            )}
                          </td>
                          <td>{nameOf(edge.to)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            <div className="stack">
              <Card title="PageRank" flush>
                {pagerank.loading && <Loading />}
                {top.length === 0 && !pagerank.loading && (
                  <Empty title="No ranking available">Needs at least one edge.</Empty>
                )}
                {top.length > 0 && (
                  <div className="table-wrap">
                    <table className="data">
                      <thead>
                        <tr>
                          <th>Entity</th>
                          <th className="num">Score</th>
                          <th className="num">In</th>
                          <th className="num">Out</th>
                        </tr>
                      </thead>
                      <tbody>
                        {top.map((row) => (
                          <tr key={row.entityId}>
                            <td>{row.displayName}</td>
                            <td className="num tiny mono">{row.pagerank.toFixed(5)}</td>
                            <td className="num tiny">{row.inDegree}</td>
                            <td className="num tiny">{row.outDegree}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </Card>

              <Card title="Communities" flush>
                {communities.loading && <Loading />}
                {comms.length === 0 && !communities.loading && (
                  <Empty title="No communities detected">
                    Label propagation found no structure — usually because the graph is a single
                    connected cluster or has too few edges.
                  </Empty>
                )}
                {comms.length > 0 && (
                  <div className="table-wrap">
                    <table className="data">
                      <thead>
                        <tr>
                          <th>Community</th>
                          <th className="num">Size</th>
                          <th>Members</th>
                        </tr>
                      </thead>
                      <tbody>
                        {comms.map((c) => (
                          <tr key={c.communityId}>
                            <td className="tiny mono">COM-{c.communityId}</td>
                            <td className="num tiny">{c.size}</td>
                            <td className="tiny">{c.members.join(', ')}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </Card>
            </div>
          </div>

          <div style={{ marginTop: 'var(--space-4)' }}>
            <Alert kind="info">
              Cached graph metrics are invalidated whenever an approval or a verdict changes, so this
              view never shows a stale structure. <strong>Rebuild metrics</strong> forces the
              computation explicitly. Communities come from deterministic label propagation: the
              same corpus always produces the same partition.
            </Alert>
          </div>
        </>
      )}
    </>
  )
}
