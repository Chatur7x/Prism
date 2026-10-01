/**
 * The Glass Box: every operation the system performed, as an observable record.
 *
 * <p>This is the page that makes the system's claims checkable. It shows what
 * ran, in order, who did it, what went in, what came out, and which rules or
 * model versions were involved.
 *
 * <p>It deliberately does <em>not</em> show a model's hidden reasoning. What it
 * shows is the observable execution: the request, the response, the validation
 * applied, the state changes, and the human actions. That is the auditable part.
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'

import { traceApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import type { TraceRunSummary } from '../api/types'
import {
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  Stat,
  StatusBadge,
  formatDate,
  formatDuration,
  useAsync,
} from '../components/ui'

const OPERATION_LABEL: Record<string, string> = {
  DOCUMENT_INGESTION: 'Document ingestion',
  EXTRACTION: 'Model extraction',
  VERIFICATION: 'Claim verification',
  CHAT: 'Grounded chat',
  DEBATE: 'Council round',
  SYNTHESIS: 'Report synthesis',
  CONTRADICTION_SCAN: 'Contradiction scan',
  ADMIN: 'Human decision',
  SYSTEM_RECOVERY: 'Restart recovery',
}

export function GlassBoxPage() {
  const { selected } = useCorpus()
  const [operation, setOperation] = useState<string>('ALL')

  const traces = useAsync(
    () => (selected ? traceApi.list(selected.id, 0, 100) : Promise.resolve(null)),
    [selected?.id],
  )

  if (!selected) {
    return (
      <>
        <PageHeader title="Glass Box" />
        <Empty title="No corpus selected">
          Traces are corpus-scoped so one tenant cannot read another's execution history.
        </Empty>
      </>
    )
  }

  const rows = (traces.data?.content ?? []).filter(
    (run: TraceRunSummary) => operation === 'ALL' || run.operationType === operation,
  )
  const present = [...new Set((traces.data?.content ?? []).map((r) => r.operationType))].sort()
  const failed = (traces.data?.content ?? []).filter((r) => r.status === 'FAILED').length

  return (
    <>
      <PageHeader
        title="Glass Box"
        subtitle={
          <>
            Every operation PRISM performed on <strong>{selected.name}</strong>: what it read, what
            it produced, which rules and model versions it used, and every human action taken
            against it.
          </>
        }
      />

      {traces.error != null && <ErrorState error={traces.error} />}

      {traces.data && (
        <div className="grid cols-3" style={{ marginBottom: 'var(--space-4)' }}>
          <Stat label="Trace runs" value={traces.data.total} accent />
          <Stat label="Failed runs" value={failed} hint={failed > 0 ? 'each is inspectable' : 'none'} />
          <Stat
            label="Distinct operations"
            value={present.length}
            hint={present.map((p) => OPERATION_LABEL[p] ?? p).slice(0, 3).join(', ')}
          />
        </div>
      )}

      <div className="row" style={{ marginBottom: 'var(--space-3)' }}>
        <button
          className={operation === 'ALL' ? 'btn sm primary' : 'btn sm'}
          onClick={() => setOperation('ALL')}
        >
          All
        </button>
        {present.map((type) => (
          <button
            key={type}
            className={operation === type ? 'btn sm primary' : 'btn sm'}
            onClick={() => setOperation(type)}
          >
            {OPERATION_LABEL[type] ?? type}
          </button>
        ))}
      </div>

      <Card title={`Runs (${rows.length})`} flush>
        {traces.loading && <Loading />}
        {traces.data && traces.data.total === 0 && (
          <Empty title="No traces yet">
            Run an operation — upload a document, verify a claim, or ask a question — and it will
            appear here with its full step-by-step record.
          </Empty>
        )}
        {rows.length === 0 && traces.data && traces.data.total > 0 && (
          <Empty title="No runs of this operation" />
        )}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Operation</th>
                  <th>Status</th>
                  <th>Actors</th>
                  <th className="num">Steps</th>
                  <th>Started</th>
                  <th className="num">Duration</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((run: TraceRunSummary) => (
                  <tr key={run.id}>
                    <td>
                      <strong>{OPERATION_LABEL[run.operationType] ?? run.operationType}</strong>
                      <div className="tiny mono muted">{run.operationKey}</div>
                    </td>
                    <td>
                      <StatusBadge value={run.status} />
                      {run.errorMessage && (
                        <div className="tiny" style={{ color: 'var(--danger)' }}>
                          {run.errorMessage.slice(0, 80)}
                        </div>
                      )}
                    </td>
                    <td className="tiny">{run.actorSummary ?? '—'}</td>
                    <td className="num tiny">{run.stepCount}</td>
                    <td className="tiny nowrap">{formatDate(run.startedAt)}</td>
                    <td className="num tiny">{formatDuration(run.durationMs)}</td>
                    <td>
                      <Link className="btn sm" to={`/glassbox/${run.id}`}>
                        Inspect
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  )
}
