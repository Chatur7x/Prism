/**
 * Banner for a static build with no backend attached.
 *
 * <p>Rendered only when `VITE_STATIC_ONLY=true`, which the Pages workflow sets.
 * Its job is to stop a deployed bundle from reading as a working product: the
 * interface is real, the pipeline behind it is not running, and a visitor
 * signing in will get a network failure rather than a verdict. Saying so up
 * front is the difference between an honest demo and a broken-looking site.
 *
 * <p>The status word is text, not a coloured dot, so the notice survives a
 * greyscale rendering and a screen reader alike.
 */
import { STATIC_ONLY } from '../api/client'

export function StaticNotice() {
  if (!STATIC_ONLY) return null

  return (
    <div className="static-notice" role="status">
      <strong className="static-notice-tag">STATIC BUILD</strong>
      <span>
        This is the interface only — the PRISM backend is not attached to this
        deployment, so signing in and every pipeline action will fail. Run the
        stack locally (<span className="mono">docker compose up --build</span>)
        for the working system, or read{' '}
        <a href="https://github.com/Chatur7x/Prism#readme">the README</a> for
        what has and has not been measured.
      </span>
    </div>
  )
}