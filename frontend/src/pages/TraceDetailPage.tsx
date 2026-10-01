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
import { Link, useParams } from 'react-router-dom'

import { traceApi } from '../api/endpoints'
import type { ActorType, TraceStepView, XRayNode } from '../api/types'
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
  const [view, setView] = useState<'tree' | 'xray'>('tree')

  const run = useAsync(() => traceApi.get(runId), [runId])
  const xray = useAsync(() => traceApi.xray(runId), [runId])

  const roots = useMemo(() => {
    const steps = run.data?.steps ?? []
    return steps.filter((step) => step.parentStepId == null)
  }, [run.data])

  if (run.error != null) return <ErrorState error={run.error} />
  if (run.loading || !run.data) return <Loading label="Loading trace" />

  const r = run.data
  const byId = new Map<number, TraceStepView>()
  for (const step of r.steps) byId.set(step.id, step)

  const childrenOf = (id: number): TraceStepView[] =>
    r.steps.filter((step) => step.parentStepId === id)

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
        <Stat label="Steps" value={r.steps.length} />
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
      </div>

      {view === 'tree' && (
        <div className="grid" style={{ gridTemplateColumns: '320px 1fr', gap: 'var(--space-4)' }}>
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
                  <TreeNode key={step.id} step={step} depth={0} byId={byId} childrenOf={childrenOf} />
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
    </>
  )
}

function TreeNode({
  step,
  depth,
  byId,
  childrenOf,
}: {
  step: TraceStepView
  depth: number
  byId: Map<number, TraceStepView>
  childrenOf: (id: number) => TraceStepView[]
}) {
  const [open, setOpen] = useState(depth < 2)
  const kids = childrenOf(step.id)

  return (
    <div>
      <div className={`tree-row depth-${Math.min(depth, 6)}`}>
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
          <TreeNode key={kid.id} step={kid} depth={depth + 1} byId={byId} childrenOf={childrenOf} />
        ))}
    </div>
  )
}
