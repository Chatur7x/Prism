/**
 * One document: source text, deterministic chunks, live extraction progress,
 * and the quarantine record.
 *
 * <p>The quarantine panel is deliberately prominent. A model that fails to
 * produce valid output on a chunk is a fact about the system an operator needs,
 * and hiding it would make the system's apparent success unreliable.
 */
import { useEffect } from 'react'
import { Link, useParams } from 'react-router-dom'

import type { DocumentStatus } from '../api/types'
import { documentApi } from '../api/endpoints'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  Stat,
  StatusBadge,
  formatDate,
  useAction,
  useAsync,
} from '../components/ui'

const IN_FLIGHT: DocumentStatus[] = ['UPLOADED', 'CHUNKING', 'CHUNKED', 'EXTRACTING']

export function DocumentDetailPage() {
  const { id } = useParams<{ id: string }>()
  const documentId = Number(id)

  const doc = useAsync(() => documentApi.get(documentId), [documentId])
  const content = useAsync(() => documentApi.content(documentId), [documentId])
  const chunks = useAsync(() => documentApi.chunks(documentId), [documentId])
  const progress = useAsync(() => documentApi.progress(documentId), [documentId])
  const quarantine = useAsync(() => documentApi.quarantine(documentId), [documentId])

  const busy = (doc.data?.status ? IN_FLIGHT.includes(doc.data.status) : false) ||
    (progress.data?.status ? IN_FLIGHT.includes(progress.data.status) : false)

  // Poll only while the document is mid-flight, then stop.
  const reloadProgress = progress.reload
  const reloadQuarantine = quarantine.reload
  useEffect(() => {
    if (!busy) return
    const timer = setInterval(() => {
      reloadProgress()
      reloadQuarantine()
    }, 3000)
    return () => clearInterval(timer)
  }, [busy, reloadProgress, reloadQuarantine])

  const reprocess = useAction(async () => {
    await documentApi.reprocess(documentId)
    progress.reload()
  })

  if (doc.error != null) return <ErrorState error={doc.error} />
  if (doc.loading || !doc.data) return <Loading label="Loading document" />

  const p = progress.data

  return (
    <>
      <PageHeader
        title={doc.data.title}
        subtitle={
          <>
            <StatusBadge value={doc.data.status} />{' '}
            <span className="muted">
              {doc.data.contentLength.toLocaleString()} characters · created{' '}
              {formatDate(doc.data.createdAt)}
            </span>
          </>
        }
        actions={
          <>
            <Link className="btn" to="/documents">
              All documents
            </Link>
            <button className="btn" onClick={() => void reprocess.run()} disabled={reprocess.pending}>
              {reprocess.pending && <span className="spinner" aria-hidden="true" />} Re-run extraction
            </button>
          </>
        }
      />

      {reprocess.error != null && <ErrorState error={reprocess.error} />}
      {p?.lastError && <Alert kind="error">{p.lastError}</Alert>}

      {p && (
        <div className="grid cols-4" style={{ marginBottom: 'var(--space-4)' }}>
          <Stat
            label="Chunks"
            value={p.chunkCount}
            hint={p.totalChunks > 0 ? `${p.processedChunks}/${p.totalChunks} processed` : 'not started'}
          />
          <Stat label="Triples proposed" value={p.triplesFound} hint="awaiting human approval" />
          <Stat label="Claims proposed" value={p.claimsFound} hint="awaiting human approval" />
          <Stat
            label="Quarantined"
            value={p.quarantinedCount}
            accent={p.quarantinedCount > 0}
            hint={p.quarantinedCount > 0 ? 'rejected model output' : 'no rejections'}
          />
        </div>
      )}

      {busy && (
        <Alert kind="info">
          <span className="spinner" aria-hidden="true" /> Extraction is running in the background.
          This page updates automatically; you do not need to refresh.
        </Alert>
      )}

      {p && p.status === 'AWAITING_APPROVAL' && (
        <Alert kind="ok">
          Extraction finished. These are <strong>proposals</strong>, not knowledge — review them in
          the <Link to="/approval">approval queue</Link> before anything reaches the graph.
        </Alert>
      )}

      <div className="grid cols-2" style={{ marginTop: 'var(--space-4)' }}>
        <Card title="Chunks" flush>
          {chunks.loading && <Loading />}
          {chunks.error != null && <ErrorState error={chunks.error} />}
          {chunks.data && chunks.data.length === 0 && (
            <Empty title="No chunks yet">Chunking has not completed.</Empty>
          )}
          {chunks.data && chunks.data.length > 0 && (
            <div className="table-wrap" style={{ maxHeight: 520, overflowY: 'auto' }}>
              <table className="data">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Offsets</th>
                    <th className="num">~Tokens</th>
                    <th>Text</th>
                  </tr>
                </thead>
                <tbody>
                  {chunks.data.map((chunk) => (
                    <tr key={chunk.id}>
                      <td className="num tiny">{chunk.chunkIndex}</td>
                      <td className="tiny mono nowrap">
                        {chunk.startOffset}–{chunk.endOffset}
                      </td>
                      <td className="num tiny">{chunk.tokenEstimate}</td>
                      <td style={{ maxWidth: 480 }}>{chunk.content}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card
          title={`Quarantine (${quarantine.data?.total ?? 0})`}
          actions={
            <span className="tiny muted">
              Model output that failed validation, kept verbatim for audit
            </span>
          }
          flush
        >
          {quarantine.loading && <Loading />}
          {quarantine.error != null && <ErrorState error={quarantine.error} />}
          {quarantine.data && quarantine.data.total === 0 && (
            <Empty title="Nothing quarantined">
              Every model response in this document passed JSON parsing, schema validation, and
              semantic validation.
            </Empty>
          )}
          {quarantine.data && quarantine.data.total > 0 && (
            <div className="table-wrap" style={{ maxHeight: 520, overflowY: 'auto' }}>
              <table className="data">
                <thead>
                  <tr>
                    <th>Chunk</th>
                    <th>Reason</th>
                    <th>Detail</th>
                    <th>Attempt</th>
                  </tr>
                </thead>
                <tbody>
                  {quarantine.data.content.map((row) => (
                    <tr key={row.id}>
                      <td className="tiny num">{row.chunkIndex ?? '—'}</td>
                      <td>
                        <span className="badge rejected">{row.errorType.replace(/_/g, ' ')}</span>
                      </td>
                      <td className="tiny">
                        {row.validationMessage}
                        {row.rawResponse && (
                          <details className="disclosure" style={{ marginTop: 4 }}>
                            <summary>Raw response</summary>
                            <pre
                              className="tiny"
                              style={{ whiteSpace: 'pre-wrap', margin: 0, maxHeight: 220 }}
                            >
                              {row.rawResponse}
                            </pre>
                          </details>
                        )}
                      </td>
                      <td className="tiny num">{row.attempt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <div style={{ marginTop: 'var(--space-4)' }}>
        <Card title="Source text">
          {content.loading && <Loading />}
          {content.data && (
            <pre
              className="tiny"
              style={{ whiteSpace: 'pre-wrap', margin: 0, maxHeight: 400, overflowY: 'auto' }}
            >
              {content.data.contentText}
            </pre>
          )}
        </Card>
      </div>
    </>
  )
}
