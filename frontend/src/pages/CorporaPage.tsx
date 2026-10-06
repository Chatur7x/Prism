/**
 * Corpus management: create, inspect, and switch the active corpus.
 *
 * <p>Creating a corpus is a modal rather than an always-visible panel. Two
 * reasons, and the second is the real one: the inline form pushed the corpus
 * *list* off to a second column, so the thing the reviewer came to see was
 * smaller than the form they had already finished with; and a form whose
 * submit button is disabled until a name is typed reads as broken when clicked.
 * The modal keeps the list the subject, and the button only ever opens something.
 */
import { useState, type FormEvent } from 'react'

import { corpusApi } from '../api/endpoints'
import type { Corpus } from '../api/types'
import { useCorpus } from '../corpus/CorpusContext'
import { useAuth } from '../auth/AuthContext'
import { Alert, Card, ErrorState, Loading, Modal, PageHeader, formatDate, useAction } from '../components/ui'
import { EmptyState, Icon, useToast } from '../components/primitives'

export function CorporaPage() {
  const { corpora, selected, loading, error, reload, select } = useCorpus()
  const { user } = useAuth()
  const toast = useToast()
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const create = useAction(async () => {
    const corpus = await corpusApi.create(name, description)
    await reload()
    // The corpus is in the list because `reload` returned before this runs, and
    // because the backend confirmed it. Nothing is shown optimistically.
    return corpus
  })

  const archive = useAction(async (id: number) => {
    await corpusApi.archive(id)
    await reload()
  })

  function openCreate() {
    setName('')
    setDescription('')
    create.clearError()
    setCreating(true)
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim()) return
    const created = await create.run()
    // Close only on success. A failed create leaves the dialog open with the
    // typed values intact so the reviewer can correct one field rather than
    // retype the form — and never shows a corpus that does not exist.
    if (created) {
      setCreating(false)
      toast.push({
        kind: 'ok',
        title: 'Corpus created',
        detail: (created as Corpus | null)?.name,
      })
    }
  }

  async function runArchive(corpus: Corpus) {
    const result = await archive.run(corpus.id)
    if (result !== null) {
      toast.push({ kind: 'ok', title: 'Corpus archived', detail: corpus.name })
    }
  }

  return (
    <div className="route-view">
      <PageHeader
        title="Corpora"
        subtitle="A corpus is the isolation boundary for the whole system. Every document, extraction, verdict, debate, and chat answer belongs to exactly one corpus, and every query is scoped to it. There is no cross-corpus query anywhere in PRISM."
        actions={
          <button type="button" className="btn primary" onClick={openCreate}>
            <Icon name="corpora" />
            Create corpus
          </button>
        }
      />

      {error != null && <ErrorState error={error} />}

      {loading && <Loading shape="rows" label="Loading corpora" />}

      {!loading && corpora.length === 0 && (
        <Card>
          <EmptyState
            glyph="▤"
            title="No corpora yet"
            hint="A corpus holds one body of source material. Create one, then upload documents into it — everything else in PRISM is scoped to whichever corpus is selected."
            action={
              <button type="button" className="btn primary" onClick={openCreate}>
                <Icon name="corpora" />
                Create the first corpus
              </button>
            }
          />
        </Card>
      )}

      {!loading && corpora.length > 0 && (
        <Card
          title={`${corpora.length} ${corpora.length === 1 ? 'corpus' : 'corpora'}`}
          actions={
            <span className="tiny muted">
              Archived corpora keep every document, verdict, and trace.
            </span>
          }
          flush
        >
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Owner</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {corpora.map((corpus: Corpus) => {
                  const isSelected = selected?.id === corpus.id
                  return (
                    <tr key={corpus.id} className={isSelected ? 'row-entering' : undefined}>
                      <td>
                        <strong>{corpus.name}</strong>
                        {corpus.description && (
                          <div className="tiny muted">{corpus.description}</div>
                        )}
                      </td>
                      <td className="tiny">{corpus.ownerUsername}</td>
                      <td>
                        <span
                          className={`badge ${corpus.status === 'ACTIVE' ? 'approved' : 'missing'}`}
                        >
                          {corpus.status === 'ACTIVE' ? 'Active' : 'Archived'}
                        </span>
                      </td>
                      <td className="tiny nowrap">{formatDate(corpus.createdAt)}</td>
                      <td>
                        <div className="btn-row">
                          {isSelected ? (
                            <span className="badge approved">Selected</span>
                          ) : (
                            <button
                              type="button"
                              className="btn sm"
                              onClick={() => void select(corpus.id)}
                            >
                              Select
                            </button>
                          )}
                          {corpus.ownerId === user?.id && corpus.status === 'ACTIVE' && (
                            <button
                              type="button"
                              className="btn ghost sm"
                              onClick={() => void runArchive(corpus)}
                              disabled={archive.pending}
                            >
                              Archive
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {archive.error != null && <ErrorState error={archive.error} />}

      {creating && (
        <Modal
          title="Create a corpus"
          description="A corpus is the isolation boundary for documents, extractions, verdicts, debates, and chat. Every query is scoped to whichever corpus is selected."
          onClose={() => setCreating(false)}
          busy={create.pending}
          actions={
            <>
              <button
                type="button"
                className="btn ghost"
                onClick={() => setCreating(false)}
                disabled={create.pending}
              >
                Cancel
              </button>
              <button
                type="submit"
                form="create-corpus-form"
                className="btn primary"
                disabled={create.pending || !name.trim()}
              >
                {create.pending && <span className="spinner" aria-hidden="true" />}
                {create.pending ? 'Creating…' : 'Create corpus'}
              </button>
            </>
          }
        >
          <form id="create-corpus-form" onSubmit={submit} className="stack">
            <div className="field">
              <label className="field-label" htmlFor="corpus-name">
                Name
              </label>
              <input
                id="corpus-name"
                name="name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                maxLength={200}
                required
                autoComplete="off"
                placeholder="e.g. Q3 board memos"
                aria-invalid={create.error != null}
              />
            </div>
            <div className="field">
              <label className="field-label" htmlFor="corpus-desc">
                Description
              </label>
              <textarea
                id="corpus-desc"
                name="description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                maxLength={2000}
                rows={3}
                placeholder="What this corpus contains and who it is for."
              />
              <span className="field-hint">Optional. Shown in the corpus list.</span>
            </div>
            {create.error != null && <ErrorState error={create.error} />}
          </form>
        </Modal>
      )}

      {corpora.length > 0 && (
        <div style={{ marginTop: 'var(--space-5)' }}>
          <Alert kind="info">
            Archiving keeps every document, verdict, and trace. PRISM never deletes a
            document whose provenance an approved verdict depends on — that would
            silently break the audit chain.
          </Alert>
        </div>
      )}
    </div>
  )
}