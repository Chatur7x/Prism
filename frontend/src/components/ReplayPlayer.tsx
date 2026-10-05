import { useEffect, useMemo, useState } from 'react'

import type { TraceStepView } from '../api/types'
import { ActorBadge, Empty, formatDuration } from './ui'

interface ReplayPlayerProps {
  steps: TraceStepView[]
  onStep: (id: number) => void
}

function clampDelay(ms: number): number {
  return Math.min(1600, Math.max(800, ms))
}

export default function ReplayPlayer({ steps, onStep }: ReplayPlayerProps) {
  const sorted = useMemo(() => [...steps].sort((a, b) => a.seq - b.seq), [steps])
  const [currentIndex, setCurrentIndex] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)

  const total = sorted.length
  // Clamp the index when the step list changes (e.g. a new run loads).
  const safeIndex = total === 0 ? 0 : Math.min(currentIndex, total - 1)
  const current: TraceStepView | undefined = total === 0 ? undefined : sorted[safeIndex]

  useEffect(() => {
    if (!isPlaying || total === 0) return
    if (safeIndex >= total - 1) {
      setIsPlaying(false)
      return
    }
    const delay = clampDelay(sorted[safeIndex]?.durationMs ?? 1000)
    const timer = window.setTimeout(() => {
      const next = safeIndex + 1
      const nextStep = sorted[next]
      if (nextStep === undefined) {
        setIsPlaying(false)
        return
      }
      setCurrentIndex(next)
      onStep(nextStep.id)
    }, delay)
    return () => window.clearTimeout(timer)
  }, [isPlaying, safeIndex, sorted, total, onStep])

  if (total === 0) {
    return <Empty title="No steps recorded for this run." />
  }

  const goTo = (index: number): void => {
    const clamped = Math.min(total - 1, Math.max(0, index))
    const step = sorted[clamped]
    if (step === undefined) return
    setCurrentIndex(clamped)
    onStep(step.id)
  }

  const toggle = (): void => {
    // At the end, replay from the next tick goes back to the start is
    // surprising; instead restart from the beginning on play.
    if (!isPlaying && safeIndex >= total - 1) {
      goTo(0)
      setIsPlaying(true)
      return
    }
    setIsPlaying((p) => !p)
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    const target = event.target as HTMLElement | null
    if (target) {
      const tag = target.tagName
      // Never hijack text entry or selection controls.
      if (tag === 'TEXTAREA' || tag === 'SELECT') return
      if (target instanceof HTMLInputElement && target.type !== 'range') return
      // A focused button already toggles/advances via native click on Space
      // and Enter; handling it here too would double-fire.
      if (target instanceof HTMLButtonElement && (event.key === ' ' || event.key === 'Enter')) return
    }
    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      setIsPlaying(false)
      goTo(safeIndex - 1)
    } else if (event.key === 'ArrowRight') {
      event.preventDefault()
      setIsPlaying(false)
      goTo(safeIndex + 1)
    } else if (event.key === ' ') {
      event.preventDefault()
      toggle()
    }
  }

  return (
    <div className="replay-player" onKeyDown={handleKeyDown} tabIndex={0}>
      <div className="replay-controls">
        <button type="button" aria-label={isPlaying ? 'Pause' : 'Play'} onClick={toggle}>
          {isPlaying ? 'Pause' : 'Play'}
        </button>
        <button
          type="button"
          aria-label="Previous step"
          onClick={() => {
            setIsPlaying(false)
            goTo(safeIndex - 1)
          }}
          disabled={safeIndex <= 0}
        >
          Prev
        </button>
        <button
          type="button"
          aria-label="Next step"
          onClick={() => {
            setIsPlaying(false)
            goTo(safeIndex + 1)
          }}
          disabled={safeIndex >= total - 1}
        >
          Next
        </button>
        <input
          type="range"
          min={0}
          max={total - 1}
          value={safeIndex}
          aria-label="Scrub steps"
          onChange={(event) => {
            setIsPlaying(false)
            goTo(Number(event.target.value))
          }}
        />
      </div>

      <div aria-live="polite" role="status">
        {current !== undefined && `Step ${safeIndex + 1} of ${total}: ${current.name}`}
      </div>

      {current !== undefined && (
        <section aria-label="Current step">
          <h3>{current.name}</h3>
          <ActorBadge actor={current.actorType} />
          <p>{current.status}</p>
          <p>Duration: {formatDuration(current.durationMs)}</p>
          {current.model !== undefined && <p>Model: {current.model}</p>}
          {current.ruleVersion !== undefined && <p>Rule version: {current.ruleVersion}</p>}
          {current.promptVersion !== undefined && <p>Prompt version: {current.promptVersion}</p>}
          {current.inputSummary !== undefined && <p>Input: {current.inputSummary}</p>}
          {current.outputSummary !== undefined && <p>Output: {current.outputSummary}</p>}
          {current.errorMessage !== undefined && current.errorMessage !== '' && (
            <p role="alert">Error: {current.errorMessage}</p>
          )}
        </section>
      )}

      <ol aria-label="Run transcript">
        {sorted.map((step, index) => (
          <li key={step.id}>
            <button
              type="button"
              aria-current={index === safeIndex ? 'true' : undefined}
              onClick={() => {
                setIsPlaying(false)
                goTo(index)
              }}
            >
              <span>{step.seq}</span> <span>{step.name}</span> <ActorBadge actor={step.actorType} />{' '}
              <span>{step.status}</span>
            </button>
          </li>
        ))}
      </ol>
    </div>
  )
}
