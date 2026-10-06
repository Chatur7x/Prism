/**
 * Grounded chat.
 *
 * <p>The page's job is to make the grounding visible, not to feel like a chat
 * app. Two states are rendered very differently and never blurred together:
 *
 * <ul>
 *   <li><b>Grounded</b> — the answer carries citations that the backend resolved
 *       against the current corpus. Each one is expandable to the source text.</li>
 *   <li><b>Not grounded</b> — the corpus could not answer. The backend refuses
 *       without calling the model at all, and the message says so rather than
 *       producing something plausible.</li>
 * </ul>
 *
 * <p>The client does not decide which of these happened. It renders the
 * `grounded` and `insufficientEvidence` flags the server sent, because a client
 * that inferred grounding from the shape of the text would be exactly the
 * unverified layer this system is built to avoid.
 */
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

import { chatApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import type { ChatMessage, ChatSession } from '../api/types'
import {
  Alert,
  Card,
  Empty,
  ErrorState,
  Loading,
  PageHeader,
  formatDate,
  useAction,
  useAsync,
} from '../components/ui'

export function ChatPage() {
  const { selected } = useCorpus()
  const [sessionId, setSessionId] = useState<number | null>(null)
  const [question, setQuestion] = useState('')
  const [title, setTitle] = useState('')
  const bottomRef = useRef<HTMLDivElement | null>(null)

  const sessions = useAsync(
    () => (selected ? chatApi.sessions() : Promise.resolve([])),
    [selected?.id],
  )

  const activeId = sessionId ?? sessions.data?.[0]?.id ?? null
  const history = useAsync(
    () => (activeId ? chatApi.session(activeId) : Promise.resolve(null)),
    [activeId],
  )

  const newSession = useAction(async () => {
    if (!selected) throw new Error('Select a corpus first')
    const created = await chatApi.createSession(selected.id, title || 'New conversation')
    setTitle('')
    setSessionId(created.id)
    await sessions.reload()
    return created
  })

  const ask = useAction(async (text: string) => {
    if (activeId == null) throw new Error('Start a conversation first')
    const answer = await chatApi.ask(activeId, text)
    setQuestion('')
    await history.reload()
    return answer
  })

  // Keep the newest turn in view as messages arrive.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [history.data?.messages.length])

  if (!selected) {
    return (
      <>
        <PageHeader title="Grounded chat" />
        <Empty title="No corpus selected">
          Chat is scoped to a corpus. Select one to ask questions that can only be answered from
          that corpus's documents.
        </Empty>
      </>
    )
  }

  const messages = history.data?.messages ?? []

  return (
    <>
      <PageHeader
        title="Grounded chat"
        subtitle={
          <>
            Every answer is built only from <strong>{selected.name}</strong>. If retrieval finds
            nothing, PRISM refuses and says so — it does not fall back on the model's own knowledge.
          </>
        }
      />

      {sessions.error != null && <ErrorState error={sessions.error} />}
      {history.error != null && <ErrorState error={history.error} />}

      <div className="split-rail rail-narrow">
        <div className="stack">
          <Card title="Conversations">
            <form
              className="stack tight"
              onSubmit={(event) => {
                event.preventDefault()
                void newSession.run()
              }}
            >
              <input
                placeholder="Conversation title (optional)"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                maxLength={300}
              />
              {newSession.error != null && <ErrorState error={newSession.error} />}
              <button className="btn primary" type="submit" disabled={newSession.pending}>
                {newSession.pending && <span className="spinner" aria-hidden="true" />} New
                conversation
              </button>
            </form>
          </Card>

          <Card title="Recent" flush>
            {sessions.loading && <Loading />}
            {sessions.data && sessions.data.length === 0 && (
              <Empty title="No conversations yet" />
            )}
            <div className="list-select">
              {(sessions.data ?? []).map((session: ChatSession) => (
                <button
                  key={session.id}
                  className={session.id === activeId ? 'list-item active' : 'list-item'}
                  onClick={() => setSessionId(session.id)}
                >
                  <div className="small">{session.title}</div>
                  <div className="tiny muted">
                    {session.messageCount} messages · {formatDate(session.updatedAt)}
                  </div>
                </button>
              ))}
            </div>
          </Card>
        </div>

        <div className="stack">
          {activeId == null ? (
            <Empty title="Start a conversation">
              Create one on the left. Questions are answered only from the selected corpus, and
              every citation is checked against it before the answer is stored.
            </Empty>
          ) : (
            <>
              <Card title="Conversation" flush>
                <div className="chat-log">
                  {history.loading && <Loading />}
                  {!history.loading && messages.length === 0 && (
                    <Empty title="No messages yet">
                      Ask something the corpus should be able to answer — for example who holds a
                      controlling interest, or where an entity is registered.
                    </Empty>
                  )}
                  {messages.map((message: ChatMessage) => (
                    <MessageBubble key={message.id} message={message} />
                  ))}
                  <div ref={bottomRef} />
                </div>

                <form
                  className="row"
                  style={{ padding: 'var(--space-3)', borderTop: '1px solid var(--line)' }}
                  onSubmit={(event) => {
                    event.preventDefault()
                    const text = question.trim()
                    if (text) void ask.run(text)
                  }}
                >
                  <input
                    value={question}
                    onChange={(event) => setQuestion(event.target.value)}
                    placeholder="Ask a question about this corpus"
                    maxLength={500}
                    style={{ flex: 1 }}
                    disabled={ask.pending}
                    autoFocus
                  />
                  <button className="btn primary" type="submit" disabled={ask.pending || !question.trim()}>
                    {ask.pending && <span className="spinner" aria-hidden="true" />}
                    {ask.pending ? 'Thinking' : 'Ask'}
                  </button>
                </form>
              </Card>

              {ask.error != null && <ErrorState error={ask.error} />}

              <Alert kind="info">
                <strong>How grounding is enforced.</strong> The model is given only retrieved
                passages and approved triples from this corpus, and every citation it returns is
                checked against the ids it was actually given. A response citing anything else is
                discarded rather than shown. See <code className="inline">ChatService</code> for the
                four enforcement points.
              </Alert>
            </>
          )}
        </div>
      </div>
    </>
  )
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const [showCitations, setShowCitations] = useState(false)
  const isUser = message.role === 'USER'

  // Three genuinely different states. Rendering them the same way would
  // overstate what the system knows.
  const state = isUser
    ? null
    : message.insufficientEvidence
      ? 'refused'
      : message.grounded
        ? 'grounded'
        : 'unverified'

  return (
    <div className={`chat-msg ${isUser ? 'from-user' : 'from-assistant'} state-${state ?? 'user'}`}>
      <div className="chat-msg-head">
        <span className="tiny">{isUser ? 'You' : 'PRISM'}</span>
        <span className="tiny muted">{formatDate(message.createdAt)}</span>
        {!isUser && state === 'grounded' && (
          <span className="badge verified">grounded</span>
        )}
        {!isUser && state === 'refused' && (
          <span className="badge missing">no answer available</span>
        )}
        {!isUser && state === 'unverified' && (
          <span className="badge rejected">citations failed validation</span>
        )}
      </div>

      <div className="chat-msg-body">{message.content}</div>

      {!isUser && message.citations.length > 0 && (
        <>
          <button
            className="btn ghost sm"
            onClick={() => setShowCitations(!showCitations)}
          >
            {showCitations ? 'Hide' : 'Show'} {message.citations.length} citation
            {message.citations.length === 1 ? '' : 's'}
          </button>
          {showCitations && (
            <div className="citations">
              {message.citations.map((citation) => (
                <div className="quote tiny" key={`${citation.kind}-${citation.chunkId}`}>
                  {citation.excerpt}
                  <div className="tiny muted">
                    {citation.documentTitle} · chunk {citation.chunkId}
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      {!isUser && message.model && (
        <div className="tiny muted">
          {message.model}
          {message.latencyMs != null && ` · ${Math.round(message.latencyMs)} ms`}
          {message.retrievalCount != null && ` · ${message.retrievalCount} passages retrieved`}
          {message.traceRunId != null && (
            <>
              {' · '}
              <Link to={`/glassbox/${message.traceRunId}`}>trace #{message.traceRunId}</Link>
            </>
          )}
        </div>
      )}

      {isUser && null}
    </div>
  )
}
