/**
 * The synthesis report.
 *
 * <p>Three input layers are shown separately rather than merged, because the
 * distinction is the point: a block the model wrote, a block derived from the
 * machine record, and a block the chair wrote are not the same kind of claim
 * and must not read identically.
 */
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

import { contradictionApi, debateApi, reportApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import type { BlockType, Debate, ReportBlock } from '../api/types'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  Pager,
  StatusBadge,
  formatDate,
  useAsync,
} from '../components/ui'

const BLOCK_MEANING: Record<BlockType, string> = {
  EXECUTIVE_SUMMARY: "The model's own reading of the debate.",
  AGREEMENT: 'Points every persona conceded.',
  DISAGREEMENT: 'Points the personas did not resolve.',
  UNRESOLVED: 'Open questions, including anything the Skeptic could not source.',
  RECOMMENDATION: 'What the Council proposes, with its citations.',
}

const BLOCK_ORDER: BlockType[] = [
  'EXECUTIVE_SUMMARY',
  'AGREEMENT',
  'DISAGREEMENT',
  'UNRESOLVED',
  'RECOMMENDATION',
]

export function ReportsPage() {
  const { selected } = useCorpus()
  const navigate = useNavigate()
  const [selectedDebateId, setSelectedDebateId] = useState<number | null>(null)

  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const gotoPage = (next: number) =>
    setParams(next <= 0 ? {} : { page: String(next) }, { replace: true })
  const corpusId = selected?.id
  const prevCorpus = useRef(corpusId)
  useEffect(() => {
    if (prevCorpus.current !== corpusId) {
      prevCorpus.current = corpusId
      setSelectedDebateId(null)
      if (page !== 0) setParams({}, { replace: true })
    }
  }, [corpusId, page, setParams])

  const debates = useAsync(
    () => (selected ? contradictionApi.debates(selected.id, page) : Promise.resolve(null)),
    [selected?.id, page],
  )

  const rows = debates.data?.content ?? []
  const activeId = selectedDebateId ?? rows[0]?.id ?? null
  const report = useAsync(
    () => (activeId ? reportApi.forDebate(activeId) : Promise.resolve(null)),
    [activeId],
  )
  const synthesize = useSynthesize(activeId, () => report.reload())

  if (!selected) {
    return (
      <>
        <PageHeader title="Reports" />
        <Empty title="No corpus selected">Select a corpus to read its synthesis reports.</Empty>
      </>
    )
  }

  return (
    <>
      <PageHeader
        title="Synthesis reports"
        subtitle={
          <>
            A report is the end of a Council, not a summary of the corpus. Every block carries the
            citations it rests on, and a block with no citations is shown as unsupported rather
            than quietly presented as fact.
          </>
        }
      />

      {debates.error != null && <ErrorState error={debates.error} />}
      {synthesize.error != null && <ErrorState error={synthesize.error} />}

      {debates.loading && rows.length === 0 ? (
        <Loading label="Loading Councils" />
      ) : rows.length === 0 ? (
        <Empty title="No Councils yet">
          Detect a contradiction in the approved record, then convene a Council over it. A report
          exists only once a debate has finished arguing.
        </Empty>
      ) : (
        <div className="grid" style={{ gridTemplateColumns: '280px 1fr', gap: 'var(--space-4)' }}>
          <Card title={`Councils (${debates.data?.totalElements ?? rows.length})`} flush>
            <div className="list-select">
              {rows.map((debate: Debate) => (
                <div
                  key={debate.id}
                  className={debate.id === activeId ? 'list-item active' : 'list-item'}
                >
                  <button
                    className="list-item-main"
                    onClick={() => setSelectedDebateId(debate.id)}
                    style={{ all: 'unset', cursor: 'pointer', display: 'block', width: '100%' }}
                  >
                    <div className="tiny muted">#{debate.id}</div>
                    <div className="small">{debate.topic}</div>
                    <div className="row" style={{ marginTop: 4 }}>
                      <StatusBadge value={debate.state} />
                      <span className="tiny muted">
                        round {debate.currentRound}/{debate.maxRounds}
                      </span>
                    </div>
                  </button>
                  {debate.state === 'AWAITING_CHAIR' && (
                    <div style={{ marginTop: 8 }}>
                      <button
                        className="btn sm primary"
                        onClick={() => {
                          setSelectedDebateId(debate.id)
                          void synthesize.run(debate.id)
                        }}
                        disabled={synthesize.pending}
                      >
                        {synthesize.pending && <span className="spinner" aria-hidden="true" />}
                        Synthesise
                      </button>
                    </div>
                  )}
                </div>
              ))}
            </div>
            {debates.data && (
              <Pager
                page={page}
                totalPages={debates.data.totalPages}
                total={debates.data.totalElements}
                onPrev={() => gotoPage(page - 1)}
                onNext={() => gotoPage(page + 1)}
              />
            )}
          </Card>

          <div className="stack">
            {report.loading && <Loading label="Loading report" />}
            {report.data == null && !report.loading && (
              <Empty title="No report for this Council yet">
                Synthesis runs once the Council reaches{' '}
                <code className="inline">AWAITING_CHAIR</code>. Use{' '}
                <strong>Synthesise</strong> above, or open the debate and synthesise from there.
              </Empty>
            )}

            {report.data && (
              <>
                <Card title="Conclusion">
                  <p>{report.data.conclusion}</p>
                  <div className="tiny muted" style={{ marginTop: 8 }}>
                    {report.data.model} · {report.data.promptVersion} ·{' '}
                    {formatDate(report.data.createdAt)}
                  </div>
                </Card>

                {BLOCK_ORDER.map((type) => {
                  const blocks = (report.data?.blocks ?? []).filter(
                    (b: ReportBlock) => b.blockType === type,
                  )
                  if (blocks.length === 0) return null
                  return (
                    <Card key={type} title={type.replace(/_/g, ' ').toLowerCase()}>
                      <p className="tiny muted" style={{ marginBottom: 'var(--space-3)' }}>
                        {BLOCK_MEANING[type]}
                      </p>
                      {blocks.map((block: ReportBlock) => (
                        <div key={block.id} className="stack tight">
                          {block.heading && <strong>{block.heading}</strong>}
                          <div style={{ whiteSpace: 'pre-wrap' }}>{block.body}</div>
                          {block.citations.length === 0 ? (
                            <Alert kind="warn">
                              This block carries no citation. It is a model assertion about the
                              debate itself, not a sourced fact about the corpus.
                            </Alert>
                          ) : (
                            <div className="citations">
                              {block.citations.map((citation, index) => (
                                <div className="quote tiny" key={index}>
                                  {citation.excerpt ?? `(${citation.kind})`}
                                  <div className="tiny muted">
                                    {citation.kind}
                                    {citation.chunkId != null && ` · chunk ${citation.chunkId}`}
                                    {citation.tripleId != null && ` · triple ${citation.tripleId}`}
                                    {citation.claimId != null && ` · claim ${citation.claimId}`}
                                  </div>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      ))}
                    </Card>
                  )
                })}

                <div className="btn-row">
                  {activeId != null && (
                    <button className="btn ghost sm" onClick={() => navigate(`/debates/${activeId}`)}>
                      Open the Council
                    </button>
                  )}
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </>
  )
}

function useSynthesize(debateId: number | null, onDone: () => void) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function run(targetId?: number) {
    const id = targetId ?? debateId
    if (id == null) return
    setPending(true)
    setError(null)
    try {
      // Synthesize returns a receipt, not the report. The report is read
      // separately because it is idempotent: if one already existed, this call
      // is a no-op and the existing report is what should be shown.
      await debateApi.synthesize(id)
      onDone()
    } catch (cause) {
      setError(cause)
    } finally {
      setPending(false)
    }
  }

  return { run, pending, error }
}
