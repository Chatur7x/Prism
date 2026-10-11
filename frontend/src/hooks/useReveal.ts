/**
 * One-shot scroll-entrance reveal.
 *
 * Attach the returned callback ref to an element carrying the `.reveal` class
 * (defined in Plan A's stylesheet). On first intersection the hook adds
 * `.is-visible` and disconnects, so the entrance fires exactly once.
 * Elements stay visible when IntersectionObserver is unavailable — final
 * states render rather than leaving content hidden.
 */
import { useCallback, useEffect, useRef } from 'react'

export function useReveal<T extends HTMLElement>(): (node: T | null) => void {
  const observerRef = useRef<IntersectionObserver | null>(null)

  useEffect(() => {
    return () => {
      observerRef.current?.disconnect()
      observerRef.current = null
    }
  }, [])

  return useCallback((node: T | null) => {
    if (node === null) return
    if (typeof IntersectionObserver === 'undefined') {
      node.classList.add('is-visible')
      return
    }
    observerRef.current?.disconnect()
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-visible')
            observer.disconnect()
          }
        }
      },
      { rootMargin: '120px' },
    )
    observerRef.current = observer
    observer.observe(node)
  }, [])
}
