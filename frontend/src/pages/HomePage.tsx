/**
 * The public product page. Static by design: no API calls, no live data, no
 * numbers that could be mistaken for measurements. It narrates the pipeline
 * a visitor will operate inside the app.
 */
import { Link } from 'react-router-dom'

const PIPELINE = [
  ['Documents', 'Source material in'],
  ['Extraction', 'Models propose'],
  ['Knowledge', 'Humans approve'],
  ['Verification', 'Claims checked'],
  ['Contradictions', 'Conflicts found'],
  ['Council', 'Debate + chair'],
  ['Synthesis', 'Cited report'],
  ['Glass Box', 'Replay anything'],
] as const

export function HomePage() {
  return (
    <div className="home theme-dark">
      <header className="home-bar">
        <div className="home-brand">
          <div className="brand-mark" aria-hidden="true" />
          <span className="brand-text">PRISM</span>
        </div>
        <nav aria-label="Product">
          <a href="#pipeline">Pipeline</a>
          <a href="#council">Council</a>
          <a href="#glassbox">Glass Box</a>
          <Link to="/login" className="btn sm primary">
            Enter Prism
          </Link>
        </nav>
      </header>

      <main>
        <section className="home-hero">
          <p className="home-kicker">Auditable intelligence analysis</p>
          <h1>
            Every answer traceable
            <br />
            to the evidence behind it.
          </h1>
          <p className="home-lede">
            <strong>Deterministic code owns the record.</strong> Models only propose. A human
            verifier decides. PRISM turns source documents into checked knowledge — with the
            full audit trail attached to every conclusion.
          </p>
          <div className="btn-row home-ctas">
            <Link to="/login" className="btn primary">
              Enter Prism
            </Link>
            <a href="#pipeline" className="btn">
              See how it works
            </a>
          </div>
          <div className="home-hero-visual" aria-hidden="true">
            <div className="home-flow">
              {['SOURCE', 'RECORD', 'VERIFY', 'ANALYZE', 'DECIDE', 'USE'].map((stage, i) => (
                <span key={stage} className="home-flow-node" style={{ animationDelay: `${i * 120}ms` }}>
                  {stage}
                </span>
              ))}
            </div>
            <p className="tiny muted">The record only moves forward on human approval.</p>
          </div>
        </section>

        <section id="pipeline" className="home-section">
          <h2>The pipeline</h2>
          <p>Eight stages. Each one observable, each decision attributable.</p>
          <ol className="home-pipeline">
            {PIPELINE.map(([name, blurb], i) => (
              <li key={name}>
                <span className="home-pipeline-index">{String(i + 1).padStart(2, '0')}</span>
                <strong>{name}</strong>
                <span className="tiny muted">{blurb}</span>
              </li>
            ))}
          </ol>
        </section>

        <section className="home-section">
          <h2>Lattice</h2>
          <p>Raw documents become entities and relationships — approved one by one.</p>
          <div className="home-demo home-lattice" aria-hidden="true">
            <span className="home-node">Meridian Group</span>
            <span className="home-edge">controls</span>
            <span className="home-node">Kestrel Components</span>
            <span className="home-edge">supplies</span>
            <span className="home-node">Verity Foods</span>
          </div>
          <p className="tiny muted">Fictional example. Only approved triples become edges.</p>
        </section>

        <section className="home-section">
          <h2>Redline</h2>
          <p>Claim verification with the signals kept separate — never one blended score.</p>
          <div className="home-demo home-redline" aria-hidden="true">
            {['Claim', 'Retrieved evidence', 'Deterministic rules', 'LLM judgement', 'Fused verdict'].map(
              (s) => (
                <span key={s} className="home-chip">
                  {s}
                </span>
              ),
            )}
          </div>
          <p className="tiny muted">
            Model score, rule penalty, fused rank, evidence status: four fields, always shown apart.
          </p>
        </section>

        <section id="council" className="home-section">
          <h2>Council</h2>
          <p>Three personas argue the contradiction. The human chair decides the weight.</p>
          <div className="home-demo home-council" aria-hidden="true">
            {['HAWK', 'DOVE', 'SKEPTIC'].map((p) => (
              <div key={p} className="home-persona">
                <strong>{p}</strong>
                <span className="tiny muted">
                  {p === 'SKEPTIC' ? 'Armed with machine evidence' : 'Argues the position'}
                </span>
                <span className="home-weights" aria-hidden="true">
                  {[1, 2, 3, 4, 5].map((w) => (
                    <i key={w} className={w <= 3 ? 'on' : ''} />
                  ))}
                </span>
              </div>
            ))}
          </div>
          <p className="tiny muted">The chair weights each argument 1–5, then advances or synthesizes.</p>
        </section>

        <section id="glassbox" className="home-section">
          <h2>Glass Box</h2>
          <p>Observable execution, replayable. System events — never hidden reasoning.</p>
          <div className="home-demo home-trace" aria-hidden="true">
            {['REQUEST', 'RETRIEVAL', 'RULE ENGINE', 'LLM JUDGEMENT', 'FUSION', 'HUMAN DECISION'].map(
              (s, i) => (
                <span key={s}>
                  <span className="home-step">{s}</span>
                  {i < 5 && <span className="home-arrow">↓</span>}
                </span>
              ),
            )}
          </div>
          <p className="tiny muted">ENGINE, LLM and HUMAN steps carry inputs, outputs, timings, versions.</p>
        </section>

        <section className="home-section">
          <h2>Grounded chat</h2>
          <p>Answers cite their passages — or the system refuses instead of inventing.</p>
          <div className="home-demo home-chat" aria-hidden="true">
            <div className="home-bubble q">Which entities does Meridian control?</div>
            <div className="home-bubble a">
              Kestrel Components, per the Q1 board memo. <span className="home-cite">[1]</span>
            </div>
            <div className="home-bubble r">Cannot ground this in the corpus — refusing.</div>
          </div>
          <p className="tiny muted">Fictional example. Refusal is a first-class answer.</p>
        </section>

        <section className="home-section home-final">
          <h2>Decide like the record matters.</h2>
          <p>Proposals are cheap. Approval is the product.</p>
          <div className="btn-row home-ctas">
            <Link to="/login" className="btn primary">
              Enter Prism
            </Link>
            <Link to="/signup" className="btn">
              Create analyst account
            </Link>
          </div>
          <p className="tiny muted">
            Status: not a release candidate — demonstration runs use a deterministic fixture
            provider, never a real model. See the repository docs for the full limitations.
          </p>
        </section>
      </main>
    </div>
  )
}
