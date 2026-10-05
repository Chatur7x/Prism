/**
 * The trusted knowledge graph, canvas first.
 *
 * <p>Every edge here originates from an approved triple. That is enforced in
 * the query, not in the UI, so a pending or rejected extraction cannot appear
 * in the graph under any circumstance.
 *
 * <p>The canvas is the primary view because the thing worth reading on this page
 * is the *shape* of the record — which entities dominate, which clusters sit
 * apart, where the contested territory is. Tables answer that question only
 * after you already know what to look for, so they are kept intact but demoted
 * behind a "List view" toggle.
 */
import { useEffect, useMemo, useState } from 'react'

import { graphApi, knowledgeApi } from '../api/endpoints'
import type { GraphScope } from '../api/types'
import GraphCanvas, { type GraphConfidence } from '../components/GraphCanvas'
import {
  Alert,
  Card,
  ConfidenceBadge,
  Drawer,
  Empty,
  ErrorState,
  KeyValue,
  Loading,
  PageHeader,
  Stat,
  StatusBadge,
  useAction,
  useAsync,
} from '../components/ui'
import { useCorpus } from '../corpus/CorpusContext'

/** Direction of an edge relative to the entity being inspected. */
interface Neighbour {
  other: number
  predicate: string
  label: string
  direction: 'out' | 'in'
}

export function GraphPage() {
  const { selected } = useCorpus()
  const [scope, setScope] = useState<GraphScope>('ALL_APPROVED')
  const [refreshKey, setRefreshKey] = useState(0)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [listOpen, setListOpen] = useState(false)

  const graph = useAsync(
    () => (selected ? graphApi.get(selected.id, scope) : Promise.resolve(null)),
    [selected?.id, scope, refreshKey],
  )
  const pagerank = useAsync(
    () => (selected ? graphApi.pagerank(selected.id, scope) : Promise.resolve([])),
    [selected?.id, scope, refreshKey],
  )
  const communities = useAsync(
    () => (selected ? graphApi.communities(selected.id, scope) : Promise.resolve([])),
    [selected?.id, scope, refreshKey],
  )

  /**
   * The dossier's real record, fetched per selection.
   *
   * <p>`GraphView` is a structure projection and carries nothing about an
   * individual entity beyond its name and degrees, so the drawer used to be
   * built entirely from graph numbers. `GET /api/entities/{id}` is the endpoint
   * that actually owns the record: type, resolution state, support count,
   * aliases and the approved relations where the entity is the subject. It is
   * access-checked server-side against the entity's own corpus, so a selection
   * outside the caller's reach fails with a real error rather than rendering
   * empty.
   *
   * <p>Keyed on `selectedId` alone and deliberately outside `refreshKey`: the
   * graph metrics are a cache rebuild, whereas this is the durable entity row.
   * Re-reading it on every rebuild would be a request the response could not
   * change. A failed fetch stays confined to the drawer — the canvas, the stats
   * and the list view are all rendered from data already in hand.
   */
  const detail = useAsync(
    () => (selectedId === null ? Promise.resolve(null) : knowledgeApi.entity(selectedId)),
    [selectedId],
  )

  // No invalidate call is needed, and deliberately so. The server's graph cache
  // is keyed on corpus, scope, and a hash of the trusted-knowledge ids, and it
  // is evicted whenever an approval or adjudication changes that set. A plain
  // re-read therefore returns exactly the graph the current record implies.
  const refresh = useAction(async () => {
    setRefreshKey((k) => k + 1)
  })

  const g = graph.data
  const top = (pagerank.data ?? []).slice(0, 15)
  const comms = communities.data ?? []

  // Edges reference nodes by id. Resolving the names here rather than shipping
  // duplicated text keeps one source of truth for an entity's display name, and
  // an edge whose endpoint is missing from the node list renders as an
  // explicit gap instead of a blank cell.
  const nameById = useMemo(() => {
    const map = new Map<number, string>()
    for (const node of g?.nodes ?? []) map.set(node.id, node.name)
    return map
  }, [g])
  const nameOf = (id: number): string => nameById.get(id) ?? `entity ${id}`

  /**
   * Per-entity trust, derived from nothing but what the endpoints actually
   * return.
   *
   * <p>`GraphView` carries structure only — id, name, in/out degree, PageRank,
   * community. It carries no verdict type, no adjudication state and no
   * per-entity support count, and neither `pagerank` nor `communities` adds one.
   *
   * <p>Adding `GET /api/entities/{id}` to the dossier did not close that gap,
   * and that is worth being precise about rather than quietly hoping it did.
   * The entity detail adds type, resolution state, aliases, approved relations
   * and a `supportCount` — and two of those look trust-shaped without being
   * trust states. `resolutionState` is an entity-resolution outcome
   * (KEEP_SEPARATE / MERGE / REVIEW: did the resolver think two mentions are the
   * same thing), not a verdict on whether the entity is corroborated. And
   * `supportCount` counts supporting extractions, so a high value means the fact
   * recurs, not that anything verified it — treating it as corroboration would
   * be precisely the inference this page refuses to make. The endpoint carries
   * no `verdictType`, no `adjudicationState` and no claim status, so there is
   * still nothing to key VERIFIED or CONTRADICTED off.
   *
   * <p>Guessing from degree, community size or support count would manufacture
   * a trust claim the audit trail does not support, which in this system is
   * worse than admitting the gap: a node that looks verified and is not is the
   * exact failure mode the whole product is built to prevent.
   *
   * <p>So every node is declared SINGLE_SOURCE, and the legend under the canvas
   * says so in the UI. Lifting this needs a backend change — verdicts keyed by
   * entity — not a client-side inference.
   */
  const confidence = useMemo<Record<number, GraphConfidence>>(() => {
    const map: Record<number, GraphConfidence> = {}
    for (const node of g?.nodes ?? []) map[node.id] = 'SINGLE_SOURCE'
    return map
  }, [g])

  const selectedNode = useMemo(
    () => (selectedId === null ? null : (g?.nodes ?? []).find((n) => n.id === selectedId) ?? null),
    [g, selectedId],
  )

  const neighbours = useMemo<Neighbour[]>(() => {
    if (selectedId === null) return []
    const out: Neighbour[] = []
    for (const edge of g?.edges ?? []) {
      if (edge.from === selectedId) {
        out.push({ other: edge.to, predicate: edge.predicate, label: edge.label, direction: 'out' })
      } else if (edge.to === selectedId) {
        out.push({ other: edge.from, predicate: edge.predicate, label: edge.label, direction: 'in' })
      }
    }
    return out
  }, [g, selectedId])

  // A selection belongs to one corpus and one scope. Changing either retires it
  // rather than leaving a drawer open on an entity the new view may not contain.
  useEffect(() => {
    setSelectedId(null)
  }, [selected?.id, scope])

  /**
   * The entity record, but only once it belongs to the current selection.
   *
   * <p>`useAsync` guards against a slow response overwriting a newer one, yet it
   * deliberately keeps the previous `data` in place while the next request is in
   * flight. Reading `detail.data` directly while switching entities would
   * therefore render the *previous* entity's type, aliases and relations under
   * the new entity's name. Collapsing to null here makes the loading state
   * honest instead of briefly wrong.
   */
  const entityDetail =
    detail.loading || detail.error != null || detail.data === null ? null : detail.data

  if (!selected) {
    return (
      <>
        <PageHeader title="Knowledge graph" />
        <Empty title="No corpus selected">Select a corpus to inspect its graph.</Empty>
      </>
    )
  }

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
        {(['ALL_APPROVED', 'VERIFIED_ONLY'] as GraphScope[]).map((s) => (
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

      {graph.loading && g === null && <Loading label="Computing graph" />}

      {g !== null && (
        <>
          <div className="grid cols-4" style={{ marginBottom: 'var(--space-4)' }}>
            <Stat label="Nodes" value={g.stats.nodeCount} />
            <Stat label="Edges" value={g.stats.edgeCount} accent />
            <Stat label="Density" value={g.stats.density.toFixed(4)} />
            <Stat label="Communities" value={comms.length} hint="label propagation" />
          </div>

          <Card
            title="Graph canvas"
            actions={
              <span className="tiny muted">
                {selectedId === null
                  ? 'Select a node to inspect it. Drag to pan, scroll to zoom.'
                  : 'Showing 1 selected node and its edges. Click empty space to clear.'}
              </span>
            }
          >
            <GraphCanvas
              nodes={g.nodes}
              edges={g.edges}
              confidence={confidence}
              selectedId={selectedId}
              onSelect={setSelectedId}
            />

            <div className="stack tight" style={{ marginTop: 'var(--space-3)' }}>
              <span className="tiny muted">
                Node size follows PageRank · edge label follows the predicate · arrow points to the
                object.
              </span>

              <div className="row">
                <span className="tiny muted">Trust shown:</span>
                <ConfidenceBadge state="SINGLE_SOURCE" />
                <span className="tiny muted">on every node</span>
              </div>

              <p className="tiny muted" style={{ margin: 0 }}>
                Per-entity <strong>Verified</strong> and <strong>Contradicted</strong> states are not
                derivable on this view. The graph endpoints return degree, PageRank and community
                only, and the entity record behind the drawer adds type, resolution state, aliases and
                approved relations — but no verdict type, claim status or adjudication state on either.
                The two fields that look trust-shaped are not: <strong>resolution state</strong> records
                whether the resolver merged two mentions, and{' '}
                <strong>support count</strong> counts supporting extractions, so a higher value means
                the fact recurs rather than that it was verified. Nothing is inferred from degree,
                community size or support count, so every node is reported as a single source rather
                than given a verdict the record cannot support.
              </p>
            </div>
          </Card>

          <div className="row" style={{ margin: 'var(--space-4) 0' }}>
            <button
              className="btn sm"
              onClick={() => setListOpen((open) => !open)}
              aria-expanded={listOpen}
              aria-controls="graph-list-view"
            >
              {listOpen ? 'Hide list view' : 'Show list view'}
            </button>
            <span className="tiny muted">
              The same graph as tables: every edge, the top 15 by PageRank, and every community.
            </span>
          </div>

          {listOpen && (
            <div id="graph-list-view" className="grid cols-2">
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
                              <td>
                                <button
                                  className="btn sm ghost"
                                  onClick={() => setSelectedId(row.entityId)}
                                >
                                  {row.displayName}
                                </button>
                              </td>
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
          )}
        </>
      )}

      {selectedId !== null && (
        <Drawer
          title={
            entityDetail?.entity.displayName ??
            (selectedNode ? selectedNode.name : `Entity ${selectedId}`)
          }
          onClose={() => setSelectedId(null)}
        >
          <div className="stack">
            {/*
              Group 1 — the entity's own record, read from GET /api/entities/{id}.
              Loading and failure are handled here rather than at page level: an
              entity-scoped problem must not take the canvas or the tables down
              with it, so ErrorState carries the trace id inline and everything
              below keeps rendering from the graph data already in hand.
            */}
            <div className="stack tight">
              <span className="stat-label">Entity record · from the backend</span>

              {detail.loading && <Loading label="Loading entity" />}

              {detail.error != null && (
                <>
                  <ErrorState error={detail.error} />
                  <span className="tiny muted">
                    The graph context below still comes from the corpus graph already loaded, so it
                    remains accurate.
                  </span>
                </>
              )}

              {!detail.loading && detail.error == null && entityDetail === null && (
                <span className="tiny muted">No entity record loaded for this selection.</span>
              )}

              {entityDetail !== null && (
                <>
                  <KeyValue
                    rows={[
                      ['Display name', entityDetail.entity.displayName],
                      ['Type', entityDetail.entity.type],
                      [
                        'Resolution state',
                        <StatusBadge value={entityDetail.entity.resolutionState} />,
                      ],
                      ['Supporting extractions', entityDetail.entity.supportCount],
                      ['First seen in chunk', entityDetail.entity.firstSeenChunkId ?? '—'],
                    ]}
                  />

                  <span className="tiny muted">
                    <strong>Resolution state</strong> records how the entity resolver handled competing
                    mentions of this name, and <strong>support count</strong> is how many extractions
                    support it. Neither is a verdict on whether the entity is corroborated — the
                    endpoint carries no verdict type.
                  </span>

                  <div className="stack tight">
                    <span className="stat-label">Aliases · {entityDetail.aliases.length}</span>
                    {entityDetail.aliases.length === 0 ? (
                      <span className="tiny muted">
                        No known aliases. Only this display name was resolved to this entity.
                      </span>
                    ) : (
                      entityDetail.aliases.map((alias) => (
                        <span key={alias} className="tiny">
                          {alias}
                        </span>
                      ))
                    )}
                  </div>

                  <div className="stack tight">
                    <span className="stat-label">
                      Approved relations (subject → object) · {entityDetail.approvedRelations.length}
                    </span>
                    {entityDetail.approvedRelations.length === 0 ? (
                      <span className="tiny muted">
                        No approved triple has this entity as its subject. The endpoint returns the
                        subject's outgoing relations only; incoming edges are in the connected list
                        below.
                      </span>
                    ) : (
                      entityDetail.approvedRelations.map((relation) => (
                        <div key={relation.tripleId} className="row" style={{ gap: 6 }}>
                          <span className="tiny mono muted" aria-hidden="true">
                            →
                          </span>
                          <span className="tiny mono">{relation.predicate}</span>
                          <span className="tiny">{relation.object}</span>
                        </div>
                      ))
                    )}
                  </div>
                </>
              )}
            </div>

            {/*
              Group 2 — computed from the graph projection, which has no
              per-entity backend field for any of it. Kept separate and
              labelled so a reader is never left wondering whether a number
              came from the record or from the layout.
            */}
            <div className="stack tight">
              <span className="stat-label">Graph context · computed from the corpus graph</span>

              {selectedNode === null ? (
                <Empty title="Not in this view">
                  This entity is not part of the current scope, so the graph has no node for it.
                  Change the scope or rebuild the metrics and try again.
                </Empty>
              ) : (
                <>
                  <div className="row">
                    <ConfidenceBadge state={confidence[selectedNode.id] ?? 'SINGLE_SOURCE'} />
                    <span className="tiny muted">
                      declared state — no verdict is attached to this entity
                    </span>
                  </div>

                  <KeyValue
                    rows={[
                      ['In-degree', selectedNode.inDegree],
                      ['Out-degree', selectedNode.outDegree],
                      ['PageRank', selectedNode.pagerank.toFixed(6)],
                      ['Community', `COM-${selectedNode.community}`],
                    ]}
                  />

                  <div className="stack tight">
                    <span className="stat-label">Connected · {neighbours.length}</span>
                    {neighbours.length === 0 && (
                      <span className="tiny muted">
                        No edge touches this entity in the current scope. A node with edges is
                        reachable from the corpus; one without is isolated here.
                      </span>
                    )}
                    {neighbours.map((n, index) => (
                      <div
                        key={`${n.direction}-${n.other}-${index}`}
                        className="row"
                        style={{ gap: 6 }}
                      >
                        <span className="tiny mono muted" aria-hidden="true">
                          {n.direction === 'out' ? '→' : '←'}
                        </span>
                        <button className="btn sm ghost" onClick={() => setSelectedId(n.other)}>
                          {nameOf(n.other)}
                        </button>
                        <span className="tiny muted">
                          {n.label !== n.predicate ? `${n.predicate} · ${n.label}` : n.predicate}
                        </span>
                      </div>
                    ))}
                  </div>
                </>
              )}
            </div>
          </div>
        </Drawer>
      )}

      <div style={{ marginTop: 'var(--space-4)' }}>
        <Alert kind="info">
          Cached graph metrics are invalidated whenever an approval or a verdict changes, so this
          view never shows a stale structure. <strong>Rebuild metrics</strong> forces the computation
          explicitly. Communities come from deterministic label propagation: the same corpus always
          produces the same partition.
        </Alert>
      </div>
    </>
  )
}