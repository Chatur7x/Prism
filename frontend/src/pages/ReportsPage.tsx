/**
 * The synthesis report.
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

function CitationItem({ citation, index }: { citation: ReportBlock['citations'][0]; index: number }) {
  return (
    <div className="quote tiny" key={index}>
      {citation.excerpt ?? `(${citation.kind})`}
      <div className="tiny muted">
        {citation.kind}
        {citation.chunkId != null && ` · chunk ${citation.chunkId}`}
        {citation.tripleId != null && ` · triple ${citation.tripleId}`}
        {citation.claimId != null && ` · claim ${citation.claimId}`}
      </div>
    </div>
  )
}

function BlockCitations({ citations }: { citations: ReportBlock['citations'] }) {
  return (
    <div className="citations">
      {citations.map((citation, index) => (
        <CitationItem key={index} citation={citation} index={index} />
      ))}
    </div>
  )
}

function BlockContent({ block }: { block: ReportBlock }) {
  return (
    <div key={block.id} className="stack tight">
      {block.heading && <strong>{block.heading}</strong>}
      <div style={{ whiteSpace: 'pre-wrap' }}>{block.body}</div>
      {block.citations.length === 0 ? (
        <Alert kind="warn">
          This block carries no citation. It is a model assertion about the
          debate itself, not a sourced fact about the corpus.
        </Alert>
      ) : (
        <BlockCitations citations={block.citations} />
      )}
    </div>
  )
}

function BlockCard({ type, blocks }: { type: BlockType; blocks: ReportBlock[] }) {
  return (
    <Card key={type} title={type.replace(/_/g, ' ').toLowerCase()}>
      <p className="tiny muted" style={{ marginBottom: 'var(--space-3)' }}>
        {BLOCK_MEANING[type]}
      </p>
      {blocks.map((block) => (
        <BlockContent key={block.id} block={block} />
      ))}
    </Card>
  )
}

function renderReportBlocks(reportData: { blocks?: ReportBlock[] } | null) {
  const allBlocks = reportData?.blocks ?? []
  return (
    <>
      {BLOCK_ORDER.map((type) => {
        const blocks = allBlocks.filter((b: ReportBlock) => b.blockType === type)
        if (blocks.length === 0) return null
        return <BlockCard key={type} type={type} blocks={blocks} />
      })}
    </>
  )
}

function renderMainContent(
  debates: { data: { content: Debate[]; totalElements: number; totalPages: number } | null; error: unknown; loading: boolean },
  synthesize: { run: (id?: number) => Promise<void>; pending: boolean; error: unknown },
  report: { data: import('../api/types').SynthesisReport | null; error: unknown; loading: boolean },
  rows: Debate[],
  activeId: number | null,
  page: number,
  gotoPage: (next: number) => void,
  setSelectedDebateId: (id: number) => void,
  navigate: (path: string) => void,
) {
  if (debates.loading && debates.data?.content?.length === 0) return <Loading label="Loading Councils" />
  if (debates.error != null) return <ErrorState error={debates.error} />
  if (rows.length === 0) return (
    <Empty title="No Councils yet">
      Detect a contradiction in the approved record, then convene a Council over it. A report
      exists only once a debate has finished arguing.
    </Empty>
  )

  return (
    <div className="grid" style={{ gridTemplateColumns: '280px minmax(0, 1fr)', gap: 'var(--space-4)' }}>
      <Card title={`Councils (${debates.data?.totalElements ?? rows.length})`} flush>
        <div className="list-select">
          {rows.map((debate: Debate) => (
            <div
              key={debate.id}
              className={debate.id === activeId ? 'list-item active' : 'list-item'}
            >
              {/* The row itself is the button. An earlier revision wrapped the
                  button in a padded <div>, which left the padding outside the
                  button's hit area — a real dead zone in a list the reviewer
                  clicks repeatedly. One control, full row. */}
              <button
                type="button"
                className="list-item-main"
                aria-current={debate.id === activeId ? 'true' : undefined}
                onClick={() => setSelectedDebateId(debate.id)}
              >
                <span className="list-item-id tiny muted">#{debate.id}</span>
                <span className="list-item-title small">{debate.topic}</span>
                <span className="list-item-meta">
                  <StatusBadge value={debate.state} />
                  <span className="tiny muted">
                    round {debate.currentRound}/{debate.maxRounds}
                  </span>
                </span>
              </button>
              {debate.state === 'AWAITING_CHAIR' && (
                <div className="list-item-actions">
                  <button
                    type="button"
                    className="btn sm primary"
                    onClick={() => {
                      setSelectedDebateId(debate.id)
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
              <p style={{ whiteSpace: 'pre-wrap' }}>{report.data.conclusion}</p>
              <div className="tiny muted" style={{ marginTop: 8 }}>
                {report.data.model} · {report.data.promptVersion} ·{' '}
                {formatDate(report.data.createdAt)}
              </div>
            </Card>

            {renderReportBlocks(report.data)}

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
  )
}

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
    } else if (page !== 0) {
      setSelectedDebateId(null)
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
      {report.error != null && <ErrorState error={report.error} />}

      {renderMainContent(
        debates,
        synthesize,
        report,
        rows,
        activeId,
        page,
        gotoPage,
        setSelectedDebateId,
        navigate,
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