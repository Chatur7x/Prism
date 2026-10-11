/**
 * Animated number that counts from zero to `target` once mounted.
 *
 * Counts over 900ms with an ease-out curve via requestAnimationFrame. The
 * final frame always renders the exact formatted target (overshoot is
 * clamped), thousands separators are kept, and `decimals` controls fraction
 * digits. Under `prefers-reduced-motion` no frames run — the exact final
 * string renders immediately.
 */
import { useEffect, useState } from 'react'

const DURATION_MS = 900

function formatValue(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })
}

export function useCountUp(target: number, decimals = 0): string {
  const [display, setDisplay] = useState(() => formatValue(0, decimals))

  useEffect(() => {
    const finalText = formatValue(target, decimals)
    if (
      typeof window === 'undefined' ||
      (typeof window.matchMedia === 'function' &&
        window.matchMedia('(prefers-reduced-motion: reduce)').matches)
    ) {
      setDisplay(finalText)
      return
    }
    let frame = 0
    const start = performance.now()
    const tick = (now: number): void => {
      const progress = Math.min((now - start) / DURATION_MS, 1)
      if (progress >= 1) {
        setDisplay(finalText)
        return
      }
      const eased = 1 - Math.pow(1 - progress, 3)
      setDisplay(formatValue(target * eased, decimals))
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => {
      cancelAnimationFrame(frame)
    }
  }, [target, decimals])

  return display
}
