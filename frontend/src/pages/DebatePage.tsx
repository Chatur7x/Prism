/**
 * The Council chamber.
 *
 * <p>Three things are deliberately kept apart, because conflating them is the
 * failure mode this system exists to prevent:
 *
 * <ol>
 *   <li><b>Arguments</b> are model output. They arrive with citations already
 *       validated; an argument whose citations did not resolve is recorded as
 *       failed and shown as such, never quietly omitted.</li>
 *   <li><b>Weights</b> are the only human contribution, and they are append-only
 *       server-side, so re-weighting never erases the earlier judgement.</li>
 *   <li><b>State</b> is owned by a deterministic finite-state machine in Java.
 *       The interface can only request transitions; an illegal one is refused
 *       server-side rather than hidden.</li>
 * </ol>
 */
import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { debateApi } from '../api/endpoints'
import { useCorpus } from '../corpus/CorpusContext'
import type {
  Argument,
  ArgumentCitation,
  Debate,
  DebateEvent,
  DebateRound,
  DebateState,
  FsmView,
  Persona,
} from '../api/types'
import {
  ActorBadge,
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

const PERSONA_LABEL: Record<Persona, string> = {
  HAWK: 'Hawk — argues the optimistic case',
  DOVE: 'Dove — argues the cautious case',
  SKEPTIC: 'Skeptic — interrogates the machine evidence',
}

const PERSONA_TONE: Record<Persona, string> = {
  HAWK: 'approved',
  DOVE: 'pending',
  SKEPTIC: 'contradicted',
}

/** States in which the chair may still act. */
const CHAIRABLE = new Set(['AWAITING_CHAIR'])

export function DebatePage() {
  const { id } = useParams<{ id: string }>()
  const debateId = Number(id)
  const { reload: reloadCorpora } = useCorpus()

  const debate = useAsync(() => debateApi.get(debateId), [debateId])
  const fsm = useAsync(() => debateApi.fsm(), [debateId])
  const [events, setEvents] = useState<DebateEvent[]>([])
  const [streamState, setStreamState] = useState<'connecting' | 'open' | 'closed' | 'error'>('connecting')
  const [draftWeights, setDraftWeights] = useState<Record<number, number>>({})
  const streamRef = useRef<EventSource | null>(null)
  const reloadDebate = debate.reload

  useEffect(() => {
    if (!debateId) return
    setEvents([])
    setStreamState('connecting')

    const source = new EventSource(debateApi.streamUrl(debateId))
    streamRef.current = source

    source.onopen = () => setStreamState('open')
    source.onerror = () => setStreamState('error')
    source.onmessage = (message) => {
      try {
        const parsed = JSON.parse(message.data) as DebateEvent
        setEvents((current) => [...current, parsed])
      } catch {
        // One malformed frame must not tear down the stream and cost the reader
        // the rest of the debate.
      }
    }

    return () => {
      source.close()
      streamRef.current = null
    }
  }, [debateId])

  // Poll while the Council is mid-round. The stream carries events; the REST
  // read carries the persisted state, which is the authority.
  const d = debate.data
  useEffect(() => {
    if (!d) return
    if (d.state !== 'ROUND_ACTIVE' && d.state !== 'SYNTHESIZING') return
    const timer = setInterval(reloadDebate, 4000)
    return () => clearInterval(timer)
  }, [d?.state, reloadDebate])

  const start = useAction(async () => {
    await debateApi.start(debateId)
    reloadDebate()
  })
  const advance = useAction(async () => {
    await debateApi.advance(debateId)
    reloadDebate()
  })
  const abort = useAction(async () => {
    await debateApi.abort(debateId)
    reloadDebate()
  })
  const synthesize = useAction(async () => {
    await debateApi.synthesize(debateId)
    reloadDebate()
    reloadCorpora()
  })
  const weight = useAction(async (argumentId: number) => {
    const value = draftWeights[argumentId]
    if (value == null) return
    await debateApi.weight(debateId, argumentId, value)
    reloadDebate()
  })

  if (debate.error != null) return <ErrorState error={debate.error} />
  if (debate.loading || !debate.data) return <Loading label="Loading debate" />

  const council: Debate = debate.data
  const rounds = council.rounds ?? []
  const chairable = CHAIRABLE.has(council.state)
  const allArguments = rounds.flatMap((round: DebateRound) => round.arguments ?? [])

  return (
    <>
      <PageHeader
        title={council.topic}
        subtitle={
          <>
            <StatusBadge value={council.state} />{' '}
            <span className="muted">
              round {council.currentRound} of {council.maxRounds} · chaired by {council.chair} ·{' '}
              {(() => {
                const count = allArguments.filter((a: Argument) => a.failed).length
                return `${count} failed argument${count !== 1 ? 's' : ''}`
              })()}
            </span>
          </>
        }
        actions={
          <>
            {council.state === 'CREATED' && (
              <button className="btn primary" onClick={() => void start.run()} disabled={start.pending}>
                {start.pending && <span className="spinner" aria-hidden="true" />} Start the debate
              </button>
            )}
            <button
              className="btn primary"
              onClick={() => void advance.run()}
              disabled={
                advance.pending ||
                council.state === 'CREATED' ||
                council.state === 'ROUND_ACTIVE' ||
                council.state === 'SYNTHESIZING' ||
                council.state === 'COMPLETED' ||
                council.state === 'ABORTED'
              }
            >
              {advance.pending && <span className="spinner" aria-hidden="true" />} Run next round
            </button>
            <button
              className="btn"
              onClick={() => void synthesize.run()}
              disabled={synthesize.pending || council.state !== 'AWAITING_CHAIR'}
            >
              {synthesize.pending && <span className="spinner" aria-hidden="true" />} Synthesise
            </button>
          </>
        }
      />

      {start.error != null && <ErrorState error={start.error} />}
      {advance.error != null && <ErrorState error={advance.error} />}
      {synthesize.error != null && <ErrorState error={synthesize.error} />}
      {weight.error != null && <ErrorState error={weight.error} />}
      {abort.error != null && <ErrorState error={abort.error} />}
      {fsm.error != null && <ErrorState error={fsm.error} />}

      {council.state === 'CREATED' && (
        <Alert kind="info">
          This Council has been convened but has not argued. Starting runs round one: every persona
          receives the same brief and the same machine evidence, and writes independently.
        </Alert>
      )}
      {council.state === 'AWAITING_CHAIR' && (
        <Alert kind="ok">
          The personas have stopped. Weight the arguments if you disagree with the Council, then
          synthesise. The report cites only what it can support.
        </Alert>
      )}
      {council.state === 'COMPLETED' && (
        <Alert kind="ok">
          This Council has concluded. The report is under <Link to="/reports">Synthesis reports</Link>.
        </Alert>
      )}
      {council.lastError && <Alert kind="error">{council.lastError}</Alert>}

      <div className="grid cols-2" style={{ margin: 'var(--space-4) 0' }}>
        <div className="stack">
          <Card title="Live events" actions={
            <span className={`badge ${streamState === 'open' ? 'approved' : 'missing'}`}>
              stream {streamState}
            </span>
          }>
            {streamState === 'error' && (
              <Alert kind="warn">
                The event stream dropped. Reload to reconnect. The debate itself is unaffected,
                because every state change is already persisted — the stream is a convenience, not
                the record.
              </Alert>
            )}
            {events.length === 0 ? (
              <Empty title="No events yet">
                Advancing a round streams each persona's argument as it is written and validated.
              </Empty>
            ) : (
              <div className="stream">
                {events.map((event) => (
                  <div className="stream-row" key={`${event.at}-${event.type}`}>
                    <span className="tiny mono nowrap">{event.at}</span>
                    <span className="stream-type">{String(event.type).replace(/_/g, ' ').toLowerCase()}</span>
                    <span className="tiny">{event.message}</span>
                  </div>
                ))}
              </div>
            )}
          </Card>

          {fsm.data && <FsmPanel fsm={fsm.data} current={council.state} />}

          <Card title="Council">
            <Stat
              label="Rounds concluded"
              value={rounds.filter((r: DebateRound) => r.completedAt != null).length}
              hint={`of a maximum ${council.maxRounds}`}
            />
            <div className="tiny muted" style={{ marginTop: 'var(--space-3)' }}>
              {council.stateDescription}
            </div>
            <div className="btn-row" style={{ marginTop: 'var(--space-3)' }}>
              <button
                className="btn ghost sm"
                onClick={() => void abort.run()}
                disabled={council.state === 'COMPLETED' || council.state === 'ABORTED'}
              >
                Abort
              </button>
            </div>
            {abort.error != null && <ErrorState error={abort.error} />}
          </Card>
        </div>

        <div className="stack">
          <Card title={`Rounds (${rounds.length})`}>
            {rounds.length === 0 && (
              <Empty title="No rounds yet">
                {council.state === 'CREATED' ? 'Start the debate to run round one.' : 'No rounds.'}
              </Empty>
            )}
            {rounds.map((round: DebateRound) => (
              <div key={round.id} className="round">
                <div className="round-head">
                  <strong>Round {round.roundNumber}</strong>
                  <span className="badge neutral">
                    {round.argumentsCompleted} argued · {round.argumentsFailed} failed
                  </span>
                  <span className="tiny muted">{formatDate(round.startedAt)}</span>
                </div>
                {(round.arguments ?? []).map((argument: Argument) => (
                  <ArgumentCard
                    key={argument.id}
                    argument={argument}
                    chairable={chairable}
                    draftWeight={draftWeights[argument.id] ?? argument.chairWeight ?? 3}
                    onDraftWeight={(value) =>
                      setDraftWeights((current) => ({ ...current, [argument.id]: value }))
                    }
                    onSubmit={() => void weight.run(argument.id)}
                    pending={weight.pending}
                  />
                ))}
              </div>
            ))}
          </Card>
        </div>
      </div>
    </>
  )
}

/**
 * The published state machine.
 *
 * <p>The endpoint publishes the state vocabulary with a description per state,
 * and the event vocabulary, but no from/to matrix. So that is what is shown:
 * the states, the current one marked, and the events — with the current state
 * highlighted. A transition table drawn here would be a second source of truth
 * about the engine, and it would drift from it.
 */
function FsmPanel({ fsm, current }: { fsm: FsmView; current: DebateState }) {
  const stateNames = Object.keys(fsm.states)
  return (
    <Card
      title="State machine"
      actions={<span className="tiny muted">the only authority on transitions</span>}
    >
      <p className="tiny muted">
        These are the states the engine recognises, each with its own meaning. The interface can
        only <em>request</em> a transition; an illegal one is refused server-side with a conflict
        rather than quietly applied.
      </p>

      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>State</th>
              <th>Meaning</th>
            </tr>
          </thead>
          <tbody>
            {stateNames.map((name) => (
              <tr key={name} style={name === current ? { background: 'var(--accent-soft)' } : undefined}>
                <td className="tiny">
                  {name === current && <span className="badge primary">now</span>} {name}
                </td>
                <td className="tiny muted">{fsm.states[name]}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="tiny muted" style={{ marginTop: 'var(--space-3)' }}>
        Events the engine accepts: {fsm.events.join(', ')}.
      </div>
      <div className="tiny muted">
        Chair weights run {fsm.weightRange.min}-{fsm.weightRange.max}. Personas:{' '}
        {fsm.personas.join(', ')}. Round ceiling: {fsm.maxRounds}.
      </div>
    </Card>
  )
}

function ArgumentCard({
  argument,
  chairable,
  draftWeight,
  onDraftWeight,
  onSubmit,
  pending,
}: {
  argument: Argument
  chairable: boolean
  draftWeight: number
  onDraftWeight: (value: number) => void
  onSubmit: () => void
  pending: boolean
}) {
  const initialWeight = argument.chairWeight ?? 3
  const [isDirty, setIsDirty] = useState(false)
  const handleWeightChange = (value: number) => {
    onDraftWeight(value)
    setIsDirty(value !== initialWeight)
  }
  return (
    <div className={`argument persona-${argument.persona.toLowerCase()} argument-enter`}>
      <div className="argument-head">
        <span className={`badge ${PERSONA_TONE[argument.persona] ?? 'neutral'}`}>
          {PERSONA_LABEL[argument.persona] ?? argument.persona}
        </span>
        {argument.failed ? (
          <span className="badge rejected">argument failed</span>
        ) : (
          <ActorBadge actor="LLM" />
        )}
        {argument.stance && <span className="tiny muted">stance: {argument.stance}</span>}
        <span className="tiny muted">{formatDate(argument.createdAt)}</span>
      </div>

      {argument.failed ? (
        <Alert kind="warn">
          This persona's argument could not be produced or failed validation, and the failure is
          recorded rather than hidden: {argument.failureReason ?? 'reason not recorded'}. A failed
          argument never counts as support for anything.
        </Alert>
      ) : (
        <p className="argument-text">{argument.argumentText}</p>
      )}

      {argument.citations.length > 0 && (
        <div className="citations">
          <span className="tiny muted">
            {argument.citations.length} validated citation
            {argument.citations.length === 1 ? '' : 's'} — each resolves to a real source in this
            corpus
          </span>
          {argument.citations.map((citation: ArgumentCitation) => (
            <div className="quote tiny" key={citation.id}>
              {citation.excerpt ?? `(${citation.kind})`}
              <div className="tiny muted">
                {citation.kind}
                {citation.chunkId != null && ` · chunk ${citation.chunkId}`}
                {citation.tripleId != null && ` · triple ${citation.tripleId}`}
                {citation.claimId != null && ` · claim ${citation.claimId}`}
                {citation.machineFactId != null && ` · ${citation.machineFactId}`}
              </div>
            </div>
          ))}
        </div>
      )}

      {argument.chairWeight != null && (
        <>
          <div className="tiny muted">
            Chair weight {argument.chairWeight}
            {argument.weightedBy && ` by ${argument.weightedBy}`}
          </div>
          <WeightBar value={argument.chairWeight} />
        </>
      )}

      {chairable && !argument.failed && (
        <div style={{ marginTop: 8 }}>
          <div className="row">
            <label className="field-label" htmlFor={`w-${argument.id}`}>
              Chair weight (1 weakest – 5 strongest)
            </label>
            <input
              id={`w-${argument.id}`}
              type="range"
              min={1}
              max={5}
              step={1}
              value={draftWeight}
              onChange={(e) => handleWeightChange(Number(e.target.value))}
              style={{ flex: 1 }}
            />
            <span className="badge neutral">{draftWeight}</span>
            <button className="btn sm" onClick={onSubmit} disabled={pending || !isDirty}>
              Record
            </button>
          </div>
          <WeightBar value={draftWeight} label={`Draft chair weight ${draftWeight} of 5`} />
        </div>
      )}
    </div>
  )
}

/**
 * Chair-weight bar (Plan D motion upgrade).
 *
 * Width is read from real weight state (1–5) set inline by the caller; the
 * stylesheet animates width on `--dur-enter`, so slider drags glide while a
 * recorded weight simply renders. Decorative next to its numeric label, which
 * is why the track carries the accessible name.
 */
function WeightBar({ value, max = 5, label }: { value: number; max?: number; label?: string }) {
  const percent = Math.max(0, Math.min(100, (value / max) * 100))
  return (
    <div className="weight-bar" role="img" aria-label={label ?? `Chair weight ${value} of ${max}`}>
      <div className="weight-bar-fill" style={{ width: `${percent}%` }} />
    </div>
  )
}
