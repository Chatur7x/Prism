/**
 * Dashboard: one-screen pipeline truth — where the corpus stands, what needs a human.
 *
 * <p>ANALYST sees a degraded dashboard (documents + honest labels) because
 * `GET /api/corpora/{id}/statistics` is verifier-gated; that 403 produces a
 * degraded view, never an error page.
 */
import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '../api/client'
import { contradictionApi, corpusApi, documentApi, knowledgeApi, traceApi } from '../api/endpoints'
import type { CorpusStatistics } from '../api/types'
import { useCorpus } from '../corpus/CorpusContext'
import { Alert, Card, Empty, ErrorState, Loading, PageHeader, Stat } from '../components/ui'
import { useCountUp } from '../hooks/useCountUp'
import { useReveal } from '../hooks/useReveal'

interface DashboardData {
  statistics: CorpusStatistics | null
  statisticsForbidden: boolean
  documents: number | null
  pendingTriples: number | null
  pendingClaims: number | null
  openContradictions: number | null
  recentTraces: number | null
}

const STAGES = [
  { label: 'Source', to: '/documents' },
  { label: 'Record', to: '/approval' },
  { label: 'Verify', to: '/verification' },
  { label: 'Analyze', to: '/contradictions' },
  { label: 'Use', to: '/chat' },
] as const

export function DashboardPage() {
  const { selected, canVerify } = useCorpus()
  const [data, setData] = useState<DashboardData | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const revealStages = useReveal<HTMLElement>()
  const revealStats = useReveal<HTMLElement>()
  const revealCards = useReveal<HTMLElement>()

  useEffect(() => {
    if (!selected) {
      setData(null)
      setError(null)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)

    async function load() {
      try {
        const corpusId = selected!.id

        const settled = await Promise.allSettled([
          canVerify ? corpusApi.statistics(corpusId) : Promise.reject(new ApiError('degraded', 403, null)),
          documentApi.list(corpusId, 1),
          canVerify ? knowledgeApi.approvalQueue(corpusId, 0, 1) : Promise.reject(new ApiError('degraded', 403, null)),
          contradictionApi.list(corpusId, 'OPEN', 1),
          traceApi.list(corpusId, 0, 5),
        ])

        if (cancelled) return

        const [stats, docs, queue, contradictions, traces] = settled

        setData({
          statistics: stats.status === 'fulfilled' ? stats.value : null,
          statisticsForbidden:
            stats.status === 'rejected' &&
            stats.reason instanceof ApiError &&
            stats.reason.status === 403,
          documents:
            docs.status === 'fulfilled' ? (docs.value.totalElements ?? null) : null,
          pendingTriples:
            queue.status === 'fulfilled' ? (queue.value.pendingTripleCount ?? null) : null,
          pendingClaims:
            queue.status === 'fulfilled' ? (queue.value.pendingClaimCount ?? null) : null,
          openContradictions:
            contradictions.status === 'fulfilled' ? (contradictions.value.total ?? null) : null,
          recentTraces:
            traces.status === 'fulfilled' ? (traces.value.totalElements ?? null) : null,
        })
      } catch (cause) {
        if (!cancelled) setError(cause)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void load()
    return () => {
      cancelled = true
    }
  }, [selected, canVerify])

  if (!selected) {
    return (
      <>
        <PageHeader
          title="Dashboard"
          subtitle="One-screen pipeline truth: where the corpus stands, what needs a human."
        />
        <Empty title="No corpus selected">
          <p>
            <Link to="/corpora">Select or create a corpus</Link> to see its pipeline state.
          </p>
        </Empty>
      </>
    )
  }

  return (
    <>
      <PageHeader
        title="Dashboard"
        subtitle={
          <>
            Corpus <strong>{selected.name}</strong>. Every query is scoped to it.
          </>
        }
        actions={<Link to="/corpora" className="btn sm">Switch corpus</Link>}
      />

      <nav ref={revealStages} className="pipeline-strip reveal" aria-label="Pipeline stages">
        {STAGES.map((stage) => (
          <Link key={stage.label} to={stage.to} className="pipeline-stage">
            {stage.label}
          </Link>
        ))}
      </nav>

      {loading && <Loading label="Loading dashboard" />}
      {error != null && <ErrorState error={error} />}

      {!loading && data && (
        <div className="stack">
          {data.statisticsForbidden && (
            <Alert kind="info">
              Analyst view: corpus statistics need a verifier role, so counts below come
              from the endpoints your role may read. Nothing is hidden — the gated
              numbers are labelled, not guessed.
            </Alert>
          )}

          <div ref={revealStats} className="grid cols-4 reveal">
            <CountedStat label="Documents" value={data.documents} />
            <Stat
              label="Pending approvals"
              value={
                data.pendingTriples != null || data.pendingClaims != null
                  ? `${data.pendingTriples ?? '—'} triples · ${data.pendingClaims ?? '—'} claims`
                  : '—'
              }
              hint={canVerify ? undefined : 'Verifier only'}
            />
            <CountedStat label="Open contradictions" value={data.openContradictions} />
            <CountedStat label="Glass Box traces" value={data.recentTraces} />
          </div>

          <div ref={revealCards} className="grid cols-2 reveal">
            <Card title="Needs a human">
              {data.pendingTriples == null &&
              data.pendingClaims == null &&
              data.openContradictions == null ? (
                <Empty title="Nothing needs you">
                  {canVerify
                    ? 'The queue, contradictions, and debates are clear for this corpus.'
                    : 'Your role can propose but not approve. Upload documents to add proposals.'}
                </Empty>
              ) : (
                <div className="btn-row">
                  {(data.pendingTriples ?? 0) + (data.pendingClaims ?? 0) > 0 && (
                    <Link to="/approval" className="btn primary sm">Review queue</Link>
                  )}
                  {(data.openContradictions ?? 0) > 0 && (
                    <Link to="/contradictions" className="btn sm">Open contradictions</Link>
                  )}
                  <Link to="/glassbox" className="btn ghost sm">Open trace</Link>
                </div>
              )}
            </Card>

            <Card title="Pipeline">
              {data.statistics ? (
                <div className="stack">
                  <CountedStat label="Triples" value={data.statistics.triples} />
                  <CountedStat label="Claims" value={data.statistics.claims} />
                  <CountedStat label="Verdicts" value={data.statistics.verdicts} />
                  <CountedStat label="Quarantined" value={data.statistics.quarantined} />
                </div>
              ) : (
                <Empty title={canVerify ? 'No statistics yet' : 'Degraded by role'}>
                  {canVerify
                    ? 'Statistics appear once documents have been ingested and approved.'
                    : 'Corpus statistics need VERIFIER or ADMIN. Document and trace counts above remain accurate.'}
                </Empty>
              )}
            </Card>
          </div>
        </div>
      )}
    </>
  )
}

/**
 * A `Stat` whose numeric value counts up from zero on arrival and settles on
 * the exact fetched number. Missing values render the same '—' placeholder
 * as before; the hook runs unconditionally so hook order stays stable.
 */
function CountedStat({ label, value, hint }: { label: string; value: number | null; hint?: ReactNode }) {
  const display = useCountUp(value ?? 0)
  return <Stat label={label} value={value == null ? '—' : display} hint={hint} />
}
