/**
 * The human gate, as an evidence-review workspace.
 *
 * <p>Three panes: proposal list, selected proposition, evidence context. An
 * approval here is what turns a model proposal into trusted knowledge, so the
 * center pane shows the exact source sentence the proposal came from — a
 * reviewer decides on evidence, not on the model's phrasing. Decisions
 * advance the selection automatically; keyboard A/R decides when not typing.
 */
import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { knowledgeApi } from '../api/endpoints'
import type { Claim, Triple } from '../api/types'
import { useCorpus } from '../corpus/CorpusContext'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  Loading,
  Pager,
  PageHeader,
  Stat,
  useAction,
  useAsync,
} from '../components/ui'

type Kind = 'triple' | 'claim'
interface Selection {
  kind: Kind
  id: number
}

const PAGE_SIZE = 50

export function ApprovalQueuePage() {
  const { selected } = useCorpus()
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const [kindFilter, setKindFilter] = useState<'ALL' | Kind>('ALL')
  // Notes and in-flight decisions are keyed by proposal KIND plus id.
  // Triples and claims share one numeric id sequence, so a bare numeric key
  // lets a triple's note leak into a claim's input (and blocks the wrong row
  // while a decision is in flight).
  const [note, setNote] = useState<Record<string, string>>({})
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const [selection, setSelection] = useState<Selection | null>(null)
  const noteKey = (kind: Kind, id: number): string => `${kind}:${id}`

  useEffect(() => {
    setParams({}, { replace: true })
    setSelection(null)
    // Reset page + selection when the corpus changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected?.id])

  const queue = useAsync(
    () => (selected ? knowledgeApi.approvalQueue(selected.id, page, PAGE_SIZE) : Promise.resolve(null)),
    [selected?.id, page],
  )

  const decide = useAction(async (kind: Kind, id: number, approve: boolean) => {
    const key = noteKey(kind, id)
    setBusyKey(key)
    try {
      const decisionNote = note[key] ?? ''
      if (kind === 'triple') {
        if (approve) await knowledgeApi.approveTriple(id, decisionNote)
        else await knowledgeApi.rejectTriple(id, decisionNote)
      } else {
        if (approve) await knowledgeApi.approveClaim(id)
        else await knowledgeApi.rejectClaim(id, decisionNote)
      }
      queue.reload()
    } finally {
      setBusyKey(null)
    }
  })

  const items = useMemo(() => {
    const data = queue.data
    if (!data) return []
    const triples = kindFilter === 'claim' ? [] : data.triples.map((t) => ({ kind: 'triple' as Kind, triple: t as Triple, claim: null as Claim | null }))
    const claims = kindFilter === 'triple' ? [] : data.claims.map((c) => ({ kind: 'claim' as Kind, triple: null as Triple | null, claim: c as Claim }))
    return [...triples, ...claims]
  }, [queue.data, kindFilter])

  // Keep the selection on the current page: default to the first item, drop
  // it when the decided item disappears after reload.
  useEffect(() => {
    if (items.length === 0) {
      setSelection(null)
      return
    }
    setSelection((current) => {
      if (current && items.some((i) => i.kind === current.kind && (i.triple?.id ?? i.claim?.id) === current.id)) {
        return current
      }
      const first = items[0]
      if (!first) return null
      const firstId = first.triple?.id ?? first.claim?.id
      return firstId == null ? null : { kind: first.kind, id: firstId }
    })
  }, [items])

  function goToPage(next: number) {
    setParams(next === 0 ? {} : { page: String(next) }, { replace: true })
    setSelection(null)
  }

  if (!selected) {
    return (
      <>
        <PageHeader title="Approval queue" />
        <Empty title="No corpus selected">Select a corpus to review its proposals.</Empty>
      </>
    )
  }

  const data = queue.data
  const active =
    selection != null
      ? items.find((i) => i.kind === selection.kind && (i.triple?.id ?? i.claim?.id) === selection.id) ?? null
      : null
  const totalPages = data ? Math.max(1, Math.ceil(Math.max(data.pendingTripleCount, data.pendingClaimCount, 1) / PAGE_SIZE)) : 1

  function onWorkspaceKey(event: React.KeyboardEvent) {
    const target = event.target as HTMLElement
    if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') return
    if (!active) return
    const id = active.triple?.id ?? active.claim?.id
    if (id == null) return
    if (event.key === 'a' || event.key === 'A') void decide.run(active.kind, id, true)
    if (event.key === 'r' || event.key === 'R') void decide.run(active.kind, id, false)
  }

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

      {data && (data.pendingTripleCount > 0 || data.pendingClaimCount > 0) && (
        <div className="approval-workspace" onKeyDown={onWorkspaceKey}>
          <div className="approval-list">
            <div className="btn-row" role="group" aria-label="Proposal kind filter">
              {(['ALL', 'triple', 'claim'] as const).map((k) => (
                <button
                  key={k}
                  className={`btn sm${kindFilter === k ? ' primary' : ''}`}
                  onClick={() => {
                    setKindFilter(k)
                    setSelection(null)
                  }}
                  aria-pressed={kindFilter === k}
                >
                  {k === 'ALL' ? 'All' : k === 'triple' ? 'Triples' : 'Claims'}
                </button>
              ))}
            </div>
            <ul className="proposal-list" role="listbox" aria-label="Proposals">
              {items.map((item) => {
                const id = item.triple?.id ?? item.claim?.id ?? 0
                const label =
                  item.kind === 'triple'
                    ? `${item.triple?.subject} → ${item.triple?.predicate} → ${item.triple?.object}`
                    : (item.claim?.claimText ?? '')
                const isActive = selection?.kind === item.kind && selection?.id === id
                return (
                  <li key={`${item.kind}:${id}`} role="option" aria-selected={isActive}>
                    <button
                      className={`proposal-item${isActive ? ' active' : ''}`}
                      onClick={() => setSelection({ kind: item.kind, id })}
                    >
                      <span className={`badge ${item.kind === 'triple' ? 'neutral' : 'pending'}`}>
                        {item.kind}
                      </span>
                      <span className="proposal-label">{label}</span>
                    </button>
                  </li>
                )
              })}
            </ul>
            <Pager page={page} totalPages={totalPages} onPrev={() => goToPage(page - 1)} onNext={() => goToPage(page + 1)} />
          </div>

          <div className="approval-detail">
            {active == null ? (
              <Empty title="No proposal selected">Pick a proposal from the list.</Empty>
            ) : (
              <ProposalDetail
                kind={active.kind}
                triple={active.triple}
                claim={active.claim}
                note={note[noteKey(active.kind, active.triple?.id ?? active.claim?.id ?? 0)] ?? ''}
                onNote={(value) => {
                  const id = active.triple?.id ?? active.claim?.id ?? 0
                  setNote({ ...note, [noteKey(active.kind, id)]: value })
                }}
                busy={busyKey === noteKey(active.kind, active.triple?.id ?? active.claim?.id ?? 0)}
                onDecide={(approve) => {
                  const id = active.triple?.id ?? active.claim?.id
                  if (id != null) void decide.run(active.kind, id, approve)
                }}
              />
            )}
          </div>

          <div className="approval-evidence">
            {active == null ? (
              <Empty title="No evidence">Evidence appears with the selection.</Empty>
            ) : (
              <EvidencePane triple={active.triple} claim={active.claim} />
            )}
          </div>
        </div>
      )}

      <div style={{ marginTop: 'var(--space-4)' }}>
        <Alert kind="info">
          Approving a triple also triggers a contradiction scan and invalidates the cached graph
          metrics, so the knowledge graph never shows a stale view. A second approval of the same
          item is rejected with <code className="inline">409 Conflict</code> rather than silently
          overwriting the first decision. Shortcuts: <kbd>A</kbd> approve, <kbd>R</kbd> reject.
        </Alert>
      </div>
    </>
  )
}

function ProposalDetail({
  kind,
  triple,
  claim,
  note,
  onNote,
  busy,
  onDecide,
}: {
  kind: Kind
  triple: Triple | null
  claim: Claim | null
  note: string
  onNote: (value: string) => void
  busy: boolean
  onDecide: (approve: boolean) => void
}) {
  return (
    <Card title={kind === 'triple' ? 'Triple proposal' : 'Claim proposal'}>
      {triple && (
        <div className="stack">
          <div className="fact-line">
            <strong>{triple.subject}</strong>
            <span className="badge neutral">{triple.predicate}</span>
            <strong>{triple.object}</strong>
          </div>
          <div className="quote">{triple.sourceSentence}</div>
        </div>
      )}
      {claim && (
        <div className="stack">
          <div>
            <strong>{claim.subject}</strong>
          </div>
          <div>{claim.claimText}</div>
          <div className="tiny muted">polarity: {claim.polarity.toLowerCase()}</div>
          <div className="quote">{claim.sourceSentence}</div>
        </div>
      )}
      <div className="field" style={{ marginTop: 'var(--space-3)' }}>
        <label className="field-label" htmlFor="approval-note">
          Decision note (optional, kept with the audit row)
        </label>
        <input
          id="approval-note"
          value={note}
          onChange={(e) => onNote(e.target.value)}
          placeholder="Why this decision is correct"
        />
      </div>
      <div className="btn-row">
        <button className="btn approve" disabled={busy} onClick={() => onDecide(true)}>
          {busy ? 'Working…' : kind === 'triple' ? 'Approve' : 'Approve for verification'}
        </button>
        <button className="btn reject" disabled={busy} onClick={() => onDecide(false)}>
          Reject
        </button>
      </div>
    </Card>
  )
}

function EvidencePane({ triple, claim }: { triple: Triple | null; claim: Claim | null }) {
  const title = triple?.sourceDocumentTitle ?? claim?.sourceDocumentTitle ?? '—'
  const chunk = triple?.sourceChunkId ?? claim?.sourceChunkId
  const sentence = triple?.sourceSentence ?? claim?.sourceSentence ?? ''
  return (
    <Card title="Evidence context">
      <div className="stack">
        <div>
          <div className="field-label">Source document</div>
          <div>{title}</div>
        </div>
        <div>
          <div className="field-label">Chunk</div>
          <div className="mono">#{chunk}</div>
        </div>
        <div>
          <div className="field-label">Exact source sentence</div>
          <div className="quote">{sentence}</div>
        </div>
        <p className="tiny muted" style={{ marginBottom: 0 }}>
          The decision is recorded against this sentence and chunk, with the verifier and
          timestamp, in the Glass Box trace.
        </p>
      </div>
    </Card>
  )
}
