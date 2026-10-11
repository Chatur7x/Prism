/**
 * Pointer + scroll effects layer: cursor aura, magnetic controls, spotlight
 * cards, hero parallax, scroll progress. One rAF loop, transform and opacity
 * only, event-delegated so it survives route remounts with zero per-component
 * wiring. Inert under reduced motion or coarse pointers (returns a noop).
 *
 * Nothing here changes behaviour: it reads pointer/scroll positions and
 * writes presentation styles. Direct DOM writes, never React state, so no
 * component re-renders because the mouse moved.
 */

const LERP = 0.18
const MAGNET_RADIUS = 24
const MAGNET_PULL = 6

function finePointer(): boolean {
  if (typeof window === 'undefined' || typeof matchMedia === 'undefined') return false
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return false
  if (matchMedia('(pointer: coarse)').matches) return false
  return true
}

export function initPointerFx(): () => void {
  if (!finePointer()) return () => {}

  // Cursor aura: one fixed div chasing the pointer with lerped follow.
  const aura = document.createElement('div')
  aura.className = 'pointer-aura'
  aura.setAttribute('aria-hidden', 'true')
  document.body.appendChild(aura)

  let tx = -400
  let ty = -400
  let x = tx
  let y = ty
  let scrollY = window.scrollY
  let queued = false
  let dead = false
  let raf = 0

  const HOT = 'a, button, input, select, textarea, [role="button"], summary'

  function frame() {
    if (dead) return
    // Lerp toward the pointer: fast catch-up, slow settle, never jitter.
    x += (tx - x) * LERP
    y += (ty - y) * LERP
    aura.style.transform = `translate3d(${x - 160}px, ${y - 160}px, 0)`
    if (queued) {
      queued = false
      applyScroll(scrollY)
    }
    raf = requestAnimationFrame(frame)
  }

  function applyScroll(top: number) {
    // Hero parallax: elements declare their own speed; small travel only.
    for (const el of document.querySelectorAll<HTMLElement>('[data-parallax]')) {
      const speed = Number(el.getAttribute('data-parallax') ?? '0.08')
      el.style.transform = `translate3d(0, ${(top * speed).toFixed(1)}px, 0)`
    }
    const bar = document.querySelector<HTMLElement>('[data-scroll-progress]')
    if (bar) {
      const max = document.documentElement.scrollHeight - window.innerHeight
      bar.style.transform = `scaleX(${max > 0 ? Math.min(1, top / max) : 0})`
    }
  }

  function onMove(e: PointerEvent) {
    tx = e.clientX
    ty = e.clientY
    const hot = (e.target as HTMLElement | null)?.closest?.(HOT)
    aura.classList.toggle('is-hot', hot != null)

    // Spotlight cards track the cursor position as custom properties.
    const spot = (e.target as HTMLElement | null)?.closest?.<HTMLElement>('[data-spotlight]')
    if (spot) {
      const box = spot.getBoundingClientRect()
      spot.style.setProperty('--mx', `${(e.clientX - box.left).toFixed(1)}px`)
      spot.style.setProperty('--my', `${(e.clientY - box.top).toFixed(1)}px`)
    }

    // Magnetic controls: pull toward the cursor inside an inflated rect.
    for (const el of document.querySelectorAll<HTMLElement>('[data-magnetic]')) {
      const box = el.getBoundingClientRect()
      const cx = box.left + box.width / 2
      const cy = box.top + box.height / 2
      const dx = e.clientX - cx
      const dy = e.clientY - cy
      const inside =
        Math.abs(dx) < box.width / 2 + MAGNET_RADIUS &&
        Math.abs(dy) < box.height / 2 + MAGNET_RADIUS
      if (inside) {
        const pull = MAGNET_PULL / (MAGNET_RADIUS + Math.max(box.width, box.height) / 2)
        el.style.transform = `translate3d(${(dx * pull).toFixed(2)}px, ${(dy * pull).toFixed(2)}px, 0)`
      } else if (el.style.transform) {
        el.style.transform = ''
      }
    }
  }

  function onScroll() {
    scrollY = window.scrollY
    queued = true
  }

  // Magnetic release: clearing the inline transform lets the CSS transition
  // settle the control home instead of snapping it.
  function onLeave(e: PointerEvent) {
    const mag = (e.target as HTMLElement | null)?.closest?.<HTMLElement>('[data-magnetic]')
    if (mag) mag.style.transform = ''
  }

  document.addEventListener('pointermove', onMove, { passive: true })
  document.addEventListener('pointerleave', onLeave, { passive: true })
  document.addEventListener('scroll', onScroll, { passive: true, capture: true })
  raf = requestAnimationFrame(frame)

  return () => {
    dead = true
    cancelAnimationFrame(raf)
    document.removeEventListener('pointermove', onMove)
    document.removeEventListener('pointerleave', onLeave)
    document.removeEventListener('scroll', onScroll)
    aura.remove()
  }
}
