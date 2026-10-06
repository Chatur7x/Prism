/**
 * One trace run, in full: the step tree, and the X-Ray that separates the three
 * actors.
 *
 * <p>The X-Ray exists because "the system decided" is not an answer. It splits
 * the same run into what deterministic Java did, what a model proposed, and what
 * a human decided, so a reader can see instantly whether a given outcome rested
 * on rules, on a model, or on a person.
 */
import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'

import { traceApi } from '../api/endpoints'
import type { ActorType, TraceStepView, XRayNode } from '../api/types'
import ReplayPlayer from '../components/ReplayPlayer'
import {
  ActorBadge,
  Alert,
  Card,
  Empty,
  ErrorState,
  KeyValue,
  Loading,
  PageHeader,
  Stat,
  StatusBadge,
  formatDate,
  formatDuration,
  useAsync,
} from '../components/ui'

const ACTOR_ORDER: ActorType[] = ['ENGINE', 'LLM', 'HUMAN']

const ACTOR_EXPLANATION: Record<ActorType, string> = {
  ENGINE:
    'Deterministic Java. Pure functions and fixed rule versions: the same input always produces the same output.',
  LLM:
    'Model output. Untrusted until parsed, schema-validated, semantically validated, and approved by a human. Nothing here is a decision.',
  HUMAN: 'A person acting in the interface. The final authority; recorded permanently.',
}

export function TraceDetailPage() {
  const { id } = useParams<{ id: string }>()
  const runId = Number(id)
  const [view, setView] = useState<'tree' | 'xray' | 'replay'>('tree')
  // Which step the replay is currently on. Lives here rather than inside
  // ReplayPlayer so the tree can highlight the same step, so flipping back to
  // "Step tree" mid-replay does not lose the reader's place.
  const [selectedStepId, setSelectedStepId] = useState<number | null>(null)

  const run = useAsync(() => traceApi.get(runId), [runId])
  const xray = useAsync(() => traceApi.xray(runId), [runId])

  const roots = useMemo(() => {
    const allSteps = run.data?.steps ?? []
    return allSteps.filter((step) => step.parentStepId == null)
  }, [run.data])

  if (run.error != null) return <ErrorState error={run.error} />
  if (run.loading || !run.data) return <Loading label="Loading trace" />

  // The run is nested under `run`, not flattened onto the response. Reading
  // `id`/`status`/`operationKey` off the top level yields undefined, which is
  // how this page used to render "Trace #undefined".
  //
  // Destructured into locals rather than referenced as `run.data.x`: TypeScript
  // does not carry a narrowing of a property access into a closure, so
  // `run.data.steps` inside `childrenOf` below would be `possibly null`.
  const { run: r, steps } = run.data
  const byId = new Map<number, TraceStepView>()
  for (const step of steps) byId.set(step.id, step)

  const childrenOf = (id: number): TraceStepView[] =>
    steps.filter((step) => step.parentStepId === id)

  return (
    <>
      <PageHeader
        title={`Trace #${r.id}`}
        subtitle={
          <>
            <StatusBadge value={r.status} />{' '}
            <span className="mono tiny muted">{r.operationKey}</span>
          </>
        }
        actions={
          <Link className="btn" to="/glassbox">
            All runs
          </Link>
        }
      />

      {r.errorMessage && <Alert kind="error">{r.errorMessage}</Alert>}

      <div className="grid cols-4" style={{ margin: 'var(--space-4) 0' }}>
        <Stat label="Steps" value={steps.length} />
        <Stat label="Duration" value={formatDuration(r.durationMs)} />
        <Stat
          label="Model steps"
          value={(xray.data?.llm.length ?? 0)}
          hint="proposals only"
        />
        <Stat
          label="Human steps"
          value={(xray.data?.human.length ?? 0)}
          hint={xray.data?.human.length ? 'a person decided' : 'none recorded'}
        />
      </div>

      <div className="row" style={{ marginBottom: 'var(--space-3)' }}>
        <button className={view === 'tree' ? 'btn sm primary' : 'btn sm'} onClick={() => setView('tree')}>
          Step tree
        </button>
        <button className={view === 'xray' ? 'btn sm primary' : 'btn sm'} onClick={() => setView('xray')}>
          X-Ray by actor
        </button>
        <button className={view === 'replay' ? 'btn sm primary' : 'btn sm'} onClick={() => setView('replay')}>
          Replay
        </button>
      </div>

      {view === 'tree' && (
        <div className="grid" style={{ gridTemplateColumns: '320px minmax(0, 1fr)', gap: 'var(--space-4)' }}>
          <Card title="Run">
            <KeyValue
              rows={[
                ['Operation', r.operationType],
                ['Status', r.status],
                ['Corpus', r.corpusId],
                ['Started', formatDate(r.startedAt)],
                ['Finished', formatDate(r.finishedAt)],
                ['Duration', formatDuration(r.durationMs)],
                ['Actors', r.actorSummary ?? '—'],
              ]}
            />
            <Alert kind="info">
              Steps are nested by parent/child so a call's substeps stay with it. Each row names its
              actor: deterministic engine, model, or human.
            </Alert>
          </Card>

          <Card title="Steps" flush>
            {roots.length === 0 ? (
              <Empty title="No steps recorded">This run has no observable steps.</Empty>
            ) : (
              <div className="tree">
                {roots.map((step) => (
                  <TreeNode
                    key={step.id}
                    step={step}
                    depth={0}
                    byId={byId}
                    childrenOf={childrenOf}
                    currentStepId={selectedStepId}
                  />
                ))}
              </div>
            )}
          </Card>
        </div>
      )}

      {view === 'xray' && (
        <>
          {xray.loading && <Loading label="Loading X-Ray" />}
          {xray.error != null && <ErrorState error={xray.error} />}
          {xray.data &&
            ACTOR_ORDER.map((actor) => {
              const nodes: XRayNode[] =
                actor === 'ENGINE' ? xray.data!.engine : actor === 'LLM' ? xray.data!.llm : xray.data!.human
              return (
                <div key={actor} style={{ marginBottom: 'var(--space-4)' }}>
                  <Card
                    title={
                      <span className="row">
                        <ActorBadge actor={actor} />
                        <span>{nodes.length} step{nodes.length === 1 ? '' : 's'}</span>
                      </span>
                    }
                  >
                    <p className="tiny muted" style={{ marginBottom: 'var(--space-3)' }}>
                      {ACTOR_EXPLANATION[actor]}
                    </p>
                    {nodes.length === 0 ? (
                      <Empty title={`No ${actor.toLowerCase()} steps in this run`} />
                    ) : (
                      <div className="table-wrap">
                        <table className="data">
                          <thead>
                            <tr>
                              <th>Step</th>
                              <th>Event</th>
                              <th>Input</th>
                              <th>Output</th>
                              <th>Attribution</th>
                              <th className="num">Ms</th>
                            </tr>
                          </thead>
                          <tbody>
                            {nodes.map((node) => (
                              <tr key={node.stepId}>
                                <td className="tiny">{node.name}</td>
                                <td className="tiny mono">{node.eventType}</td>
                                <td className="tiny" style={{ maxWidth: 220 }}>
                                  {node.inputSummary ?? '—'}
                                </td>
                                <td className="tiny" style={{ maxWidth: 220 }}>
                                  {node.outputSummary ?? '—'}
                                </td>
                                <td className="tiny mono">
                                  {/* Attribution is the point of the X-Ray: which
                                      rule version or model produced this step. */}
                                  {node.ruleVersion ?? node.model ?? '—'}
                                </td>
                                <td className="num tiny">{node.durationMs ?? '—'}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </Card>
                </div>
              )
            })}
        </>
      )}

      {view === 'replay' && (
        <>
          {steps.length === 0 ? (
            <Empty title="No steps recorded for this run." />
          ) : (
            <div
              className="grid"
              style={{ gridTemplateColumns: 'minmax(0, 1fr) 340px', gap: 'var(--space-4)' }}
            >
              <Card title="Replay">
                {/* Keyed on the run so switching runs resets the player to the
                    first step rather than keeping a stale index. */}
                <ReplayPlayer
                  key={r.id}
                  steps={steps}
                  onStep={(stepId) => setSelectedStepId(stepId)}
                />
                <Alert kind="info">
                  Replay steps the run in recorded order and shows only what the system logged —
                  system events, never hidden reasoning.
                </Alert>
              </Card>

              <StepDetailCard stepId={selectedStepId} byId={byId} />
            </div>
          )}
        </>
      )}
    </>
  )
}

/**
 * The step currently under the replay cursor, in full.
 *
 * <p>Every field here is something the system recorded at execution time: the
 * actor, the event, timings, attribution, and the input/output summaries. There
 * is deliberately no field for a model's private reasoning — that is not in the
 * trace and this page must not imply it is.
 */
function StepDetailCard({
  stepId,
  byId,
}: {
  stepId: number | null
  byId: Map<number, TraceStepView>
}) {
  // Before the reader touches the player it has not reported a step yet, so fall
  // back to the first step by sequence — the same one the player is showing.
  // `byId` is rebuilt each render upstream, so this is a plain scan, not a memo.
  const fallback = (() => {
    let first: TraceStepView | undefined
    for (const step of byId.values()) {
      if (first === undefined || step.seq < first.seq) first = step
    }
    return first
  })()

  const step = (stepId === null ? undefined : byId.get(stepId)) ?? fallback

  if (step === undefined) {
    return (
      <Card title="Step detail">
        <Empty title="No step selected." />
      </Card>
    )
  }

  const rows: [ReactNode, ReactNode][] = [
    ['Sequence', <span className="mono">{step.seq}</span>],
    ['Event', <span className="mono">{step.eventType}</span>],
    ['Actor', <ActorBadge actor={step.actorType} />],
    ['Status', <StatusBadge value={step.status} />],
    ['Duration', step.durationMs != null ? formatDuration(step.durationMs) : '—'],
    ['Recorded', formatDate(step.createdAt)],
  ]

  if (step.model !== undefined) rows.push(['Model', <span className="mono">{step.model}</span>])
  if (step.ruleVersion !== undefined) {
    rows.push(['Rule version', <span className="mono">{step.ruleVersion}</span>])
  }
  if (step.promptVersion !== undefined) {
    rows.push(['Prompt version', <span className="mono">{step.promptVersion}</span>])
  }
  if (step.attempt !== undefined) rows.push(['Attempt', step.attempt])

  return (
    <Card title="Step detail">
      <KeyValue rows={rows} />

      {step.inputSummary != null && (
        <div style={{ marginTop: 'var(--space-3)' }}>
          <div className="tiny muted">input</div>
          <div className="tiny" style={{ whiteSpace: 'pre-wrap' }}>
            {step.inputSummary}
          </div>
        </div>
      )}
      {step.inputReferenceIds != null && (
        <div className="tiny muted mono">input refs: {step.inputReferenceIds}</div>
      )}

      {step.outputSummary != null && (
        <div style={{ marginTop: 'var(--space-3)' }}>
          <div className="tiny muted">output</div>
          <div className="tiny" style={{ whiteSpace: 'pre-wrap' }}>
            {step.outputSummary}
          </div>
        </div>
      )}
      {step.outputReferenceIds != null && (
        <div className="tiny muted mono">output refs: {step.outputReferenceIds}</div>
      )}

      {step.errorMessage != null && step.errorMessage !== '' && (
        <Alert kind="error">
          <span className="tiny">{step.errorMessage}</span>
        </Alert>
      )}
    </Card>
  )
}

function TreeNode({
  step,
  depth,
  byId,
  childrenOf,
  currentStepId,
}: {
  step: TraceStepView
  depth: number
  byId: Map<number, TraceStepView>
  childrenOf: (id: number) => TraceStepView[]
  currentStepId: number | null
}) {
  const [open, setOpen] = useState(depth < 2)
  const kids = childrenOf(step.id)
  const isCurrent = currentStepId === step.id

  return (
    <div>
      <div
        className={`tree-row depth-${Math.min(depth, 6)}${isCurrent ? ' current' : ''}`}
        aria-current={isCurrent ? 'step' : undefined}
        // No `.current` rule exists in the stylesheet yet; the class is the hook
        // for one, and this inline background makes the replay position visible
        // today without touching CSS.
        style={isCurrent ? { background: 'var(--accent-soft)' } : undefined}
      >
        <button
          className="btn ghost sm"
          onClick={() => setOpen(!open)}
          disabled={kids.length === 0}
          aria-label={open ? 'Collapse' : 'Expand'}
        >
          {kids.length > 0 ? (open ? '▾' : '▸') : '·'}
        </button>
        <ActorBadge actor={step.actorType} />
        <span className="tree-name">{step.name}</span>
        <span className="tree-event tiny mono">{step.eventType}</span>
        <StatusBadge value={step.status} />
        {step.durationMs != null && (
          <span className="tiny muted num">{formatDuration(step.durationMs)}</span>
        )}
        <span className="tiny mono muted">
          {step.ruleVersion ?? step.model ?? ''}
        </span>
      </div>

      {(step.inputSummary || step.outputSummary) && (
        <div className="tree-detail" style={{ paddingLeft: 28 + Math.min(depth, 6) * 14 }}>
          {step.inputSummary && (
            <div className="tiny">
              <span className="muted">in:</span> {step.inputSummary}
            </div>
          )}
          {step.outputSummary && (
            <div className="tiny">
              <span className="muted">out:</span> {step.outputSummary}
            </div>
          )}
          {step.inputReferenceIds && (
            <div className="tiny muted">input refs: {step.inputReferenceIds}</div>
          )}
          {step.outputReferenceIds && (
            <div className="tiny muted">output refs: {step.outputReferenceIds}</div>
          )}
          {step.errorMessage && (
            <div className="tiny" style={{ color: 'var(--danger)' }}>
              error: {step.errorMessage}
            </div>
          )}
        </div>
      )}

      {open &&
        kids.map((kid) => (
          <TreeNode
            key={kid.id}
            step={kid}
            depth={depth + 1}
            byId={byId}
            childrenOf={childrenOf}
            currentStepId={currentStepId}
          />
        ))}
    </div>
  )
}
