/**
 * Document list, upload, and per-document extraction progress.
 *
 * <p>Progress is polled while any document is mid-flight. Polling stops as soon
 * as everything settles, so an idle page issues no further requests — a
 * background poller that never stops is a slow leak.
 */
import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'

import type { DocumentStatus } from '../api/types'
import { documentApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  StatusBadge,
  formatDate,
  useAction,
  useAsync,
} from '../components/ui'

const IN_FLIGHT: DocumentStatus[] = ['UPLOADED', 'CHUNKING', 'CHUNKED', 'EXTRACTING']

export function DocumentsPage() {
  const { selected } = useCorpus()
  const [mode, setMode] = useState<'text' | 'file'>('text')
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const docs = useAsync(
    // The API returns the page envelope; the page wants the rows.
    async () => (selected ? (await documentApi.list(selected.id)).content : []),
    [selected?.id],
  )

  const hasInFlight = (docs.data ?? []).some((d) => IN_FLIGHT.includes(d.status))

  // Poll only while work is outstanding, and only for the active corpus.
  const reload = docs.reload
  useEffect(() => {
    if (!hasInFlight) return
    const timer = setInterval(reload, 3000)
    return () => clearInterval(timer)
  }, [hasInFlight, reload])

  const create = useAction(async () => {
    if (!selected) throw new Error('Select a corpus first')
    setNotice(null)
    if (mode === 'text') {
      await documentApi.createText(selected.id, title, body)
      setNotice(`Queued "${title}" for extraction.`)
      setTitle('')
      setBody('')
    } else {
      if (!file) throw new Error('Choose a file first')
      await documentApi.upload(selected.id, title, file)
      setNotice(`Queued "${file.name}" for extraction.`)
      setTitle('')
      setFile(null)
    }
    docs.reload()
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    void create.run()
  }

  if (!selected) {
    return (
      <>
        <PageHeader title="Documents" />
        <Empty title="No corpus selected">
          Create or select a corpus first. Documents always belong to exactly one corpus.
        </Empty>
      </>
    )
  }

  const list = docs.data ?? []

  return (
    <>
      <PageHeader
        title="Documents"
        subtitle={
          <>
            Everything in <strong>{selected.name}</strong>. Uploading starts deterministic chunking
            and then model-backed extraction in the background. Nothing becomes trusted knowledge
            until a verifier approves it.
          </>
        }
      />

      {docs.error != null && <ErrorState error={docs.error} />}

      <div className="grid cols-2" style={{ marginBottom: 'var(--space-4)' }}>
        <Card title="Add a document">
          <div className="row" style={{ marginBottom: 'var(--space-3)' }}>
            <button
              className={mode === 'text' ? 'btn sm primary' : 'btn sm'}
              onClick={() => setMode('text')}
            >
              Paste text
            </button>
            <button
              className={mode === 'file' ? 'btn sm primary' : 'btn sm'}
              onClick={() => setMode('file')}
            >
              Upload file
            </button>
          </div>

          <form onSubmit={submit} className="stack">
            <div className="field">
              <label className="field-label" htmlFor="doc-title">
                Title
              </label>
              <input
                id="doc-title"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                maxLength={500}
                required
              />
            </div>

            {mode === 'text' ? (
              <div className="field">
                <label className="field-label" htmlFor="doc-body">
                  Text
                </label>
                <textarea
                  id="doc-body"
                  value={body}
                  onChange={(e) => setBody(e.target.value)}
                  rows={10}
                  required
                  placeholder="Paste the source text. Sentences are chunked deterministically, then a model proposes triples and claims from each chunk."
                />
                <span className="field-hint">
                  Source text is passed to the model as untrusted data, never as instructions.
                </span>
              </div>
            ) : (
              <div className="field">
                <label className="field-label" htmlFor="doc-file">
                  File
                </label>
                <input
                  id="doc-file"
                  type="file"
                  accept=".txt,.md,.csv,.pdf,.docx"
                  onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                  required
                />
                <span className="field-hint">Accepted: .txt, .md, .csv, .pdf, .docx.</span>
              </div>
            )}

            {notice && <Alert kind="ok">{notice}</Alert>}
            {create.error != null && <ErrorState error={create.error} />}

            <button
              className="btn primary"
              type="submit"
              disabled={create.pending || (mode === 'file' ? !file : !body.trim())}
            >
              {create.pending && <span className="spinner" aria-hidden="true" />} Queue for extraction
            </button>
          </form>
        </Card>

        <Card title="How ingestion behaves">
          <div className="stack tight">
            <Alert kind="info">
              <strong>Chunking is deterministic.</strong> Identical text always produces identical
              chunks with the same character offsets, so a citation always points at the same span.
            </Alert>
            <Alert kind="info">
              <strong>Extraction proposes only.</strong> Every triple and claim is written{' '}
              <code className="inline">PENDING</code>. No model output can reach the knowledge
              graph or a verdict.
            </Alert>
            <Alert kind="info">
              <strong>Malformed output is quarantined, not dropped.</strong> A response that fails
              JSON parsing, schema validation, or semantic validation is stored with its reason so
              an operator can see how often the provider misbehaves.
            </Alert>
            <Alert kind="info">
              <strong>Re-uploading identical text is a no-op.</strong> The content hash makes
              ingestion idempotent, so a retried job cannot double every proposal.
            </Alert>
          </div>
        </Card>
      </div>

      <Card title={`Documents (${list.length})`} flush>
        {docs.loading && <Loading />}
        {!docs.loading && list.length === 0 && (
          <Empty title="No documents yet">
            Add one above. Extraction runs in the background; the table updates as it progresses.
          </Empty>
        )}
        {!docs.loading && list.length > 0 && (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Title</th>
                  <th>Status</th>
                  <th>Size</th>
                  <th>Filename</th>
                  <th>Created</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {list.map((doc) => (
                  <tr key={doc.id}>
                    <td>
                      <Link to={`/documents/${doc.id}`}>{doc.title}</Link>
                    </td>
                    <td>
                      <StatusBadge value={doc.status} />
                    </td>
                    <td className="num tiny">{doc.contentLength.toLocaleString()} chars</td>
                    <td className="tiny muted">{doc.originalFilename ?? '—'}</td>
                    <td className="tiny nowrap">{formatDate(doc.createdAt)}</td>
                    <td>
                      <Link className="btn sm" to={`/documents/${doc.id}`}>
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
