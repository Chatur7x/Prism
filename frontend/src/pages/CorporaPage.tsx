/** Corpus management: create, inspect, and switch the active corpus. */
import { useState, type FormEvent } from 'react'

import { corpusApi } from '../api/endpoints'
import type { Corpus } from '../api/types'
import { useCorpus } from '../corpus/CorpusContext'
import { useAuth } from '../auth/AuthContext'
import { Alert, Card, Empty, ErrorState, Loading, PageHeader, formatDate, useAction } from '../components/ui'

export function CorporaPage() {
  const { corpora, selected, loading, error, reload, select } = useCorpus()
  const { user } = useAuth()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const create = useAction(async () => {
    await corpusApi.create(name, description)
    setName('')
    setDescription('')
    await reload()
  })

  const archive = useAction(async (id: number) => {
    await corpusApi.archive(id)
    await reload()
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (name.trim()) void create.run()
  }

  return (
    <>
      <PageHeader
        title="Corpora"
        subtitle={
          <>
            A corpus is the isolation boundary for the whole system. Every document, extraction,
            verdict, debate, and chat answer belongs to exactly one corpus, and every query is
            scoped to it. There is no cross-corpus query anywhere in PRISM.
          </>
        }
      />

      {error != null && <ErrorState error={error} />}

      <div className="grid cols-2">
        <Card title="Existing corpora">
          {loading && <Loading />}
          {!loading && corpora.length === 0 && (
            <Empty title="No corpora yet">
              Create one to begin. Everything you upload lives inside a single corpus.
            </Empty>
          )}
          {!loading && corpora.length > 0 && (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Owner</th>
                    <th>Status</th>
                    <th>Created</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {corpora.map((corpus: Corpus) => (
                    <tr key={corpus.id}>
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
                          {selected?.id !== corpus.id && (
                            <button className="btn sm" onClick={() => select(corpus.id)}>
                              Select
                            </button>
                          )}
                          {selected?.id === corpus.id && (
                            <span className="badge approved">Selected</span>
                          )}
                          {corpus.ownerId === user?.id && corpus.status === 'ACTIVE' && (
                            <button
                              className="btn ghost sm"
                              onClick={() => void archive.run(corpus.id)}
                              disabled={archive.pending}
                            >
                              Archive
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {archive.error != null && <ErrorState error={archive.error} />}
        </Card>

        <Card title="Create a corpus">
          <form onSubmit={submit} className="stack">
            <div className="field">
              <label className="field-label" htmlFor="corpus-name">
                Name
              </label>
              <input
                id="corpus-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={200}
                required
                placeholder="e.g. Q3 board memos"
              />
            </div>
            <div className="field">
              <label className="field-label" htmlFor="corpus-desc">
                Description
              </label>
              <textarea
                id="corpus-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                maxLength={2000}
                placeholder="What this corpus contains and who it is for."
              />
            </div>
            {create.error != null && <ErrorState error={create.error} />}
            <button className="btn primary" type="submit" disabled={create.pending || !name.trim()}>
              {create.pending && <span className="spinner" aria-hidden="true" />} Create corpus
            </button>
          </form>

          <Alert kind="info">
            Archiving keeps every document, verdict, and trace. PRISM never deletes a document
            whose provenance an approved verdict depends on — that would silently break the audit
            chain.
          </Alert>
        </Card>
      </div>
    </>
  )
}
