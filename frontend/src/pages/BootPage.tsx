/** Shown while the stored session is being verified, so it does not flash. */
export function BootPage() {
  return (
    <div className="fatal">
      <div className="fatal-card" style={{ textAlign: 'center' }}>
        <div className="brand" style={{ justifyContent: 'center', marginBottom: 12 }}>
          <div className="brand-mark" aria-hidden="true" />
          <div className="brand-text">PRISM</div>
        </div>
        <p className="muted">
          <span className="spinner" aria-hidden="true" /> Verifying session…
        </p>
      </div>
    </div>
  )
}
