/**
 * Cinematic landing (STAGE 2). One file: the 8-section scroll story.
 *
 * Task contract — objective: rebuild this page into the approved cinematic
 * landing (hero, pinned story, graph, verification, council, glass box,
 * workspace, final CTA) reusing the cinematic.css class contracts.
 * Success criteria: all 8 sections render; GSAP owns only hero-zoom,
 * hero-copy, headline lines, and the story filmstrip; everything else uses
 * .reveal; reduced-motion and <=640px get the static stacked layout with
 * all content visible; `tsc --noEmit` + build green.
 * Non-goals: routes, API calls, other pages, stylesheet edits (existing
 * classes + inline styles only).
 * Assumptions: gsap is installed; the global pointer layer (aura,
 * spotlight, magnetic, parallax, progress) is already mounted by the app.
 * Ask/abort: no invented stats — stat cards count structures already on
 * this page (stages, checkpoints, personas), never measurements.
 */
import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { gsap } from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

import { useReveal } from '../hooks/useReveal'

/** The 8 pipeline stages narrated across the page (existing page data). */
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

/**
 * Ribbon checkpoints: the 8 pipeline stages plus the two verdict-moment
 * surfaces already named in page copy (grounded chat, verdict).
 * Decorative only — aria-hidden at the usage site.
 */
const RIBBON_STAGES = [
  'Documents',
  'Extraction',
  'Knowledge',
  'Verification',
  'Contradictions',
  'Council',
  'Synthesis',
  'Glass Box',
  'Chat',
  'Verdict',
] as const

/** The 7 beats of the pinned document-to-verdict story: one continuous record. */
const STORY_BEATS = [
  {
    index: '01',
    stage: 'Document',
    title: 'The source arrives unchanged.',
    body: 'A document enters the corpus and stays immutable. Everything downstream cites this original, never a copy of a copy.',
  },
  {
    index: '02',
    stage: 'Analyzed',
    title: 'Models propose candidate claims.',
    body: 'Extraction suggests entities, relations, and claims. They stay proposals until a human approves them.',
  },
  {
    index: '03',
    stage: 'Concepts',
    title: 'A verifier approves each concept.',
    body: 'Nothing enters the record on a model vote alone. Approval is the product.',
  },
  {
    index: '04',
    stage: 'Graph',
    title: 'Approved triples become edges.',
    body: 'Concepts connect into a knowledge graph you can open, inspect, and challenge — edge by edge.',
  },
  {
    index: '05',
    stage: 'Checked',
    title: 'Each claim is checked against evidence.',
    body: 'Retrieved passages, deterministic rules, and model judgement stay separate — never one blended score.',
  },
  {
    index: '06',
    stage: 'Council',
    title: 'Contradictions are argued, then weighed.',
    body: 'Hawk, dove, and skeptic argue the conflict. The human chair weights each argument and decides.',
  },
  {
    index: '07',
    stage: 'Report',
    title: 'A cited report closes the loop.',
    body: 'Synthesis carries citations to every source. Replay any step in the glass box.',
  },
] as const

const COUNCIL = [
  {
    name: 'HAWK',
    line: 'Presses the strongest case for action.',
    weight: 4,
  },
  {
    name: 'DOVE',
    line: 'Presses the strongest case for restraint.',
    weight: 3,
  },
  {
    name: 'SKEPTIC',
    line: 'Armed with machine evidence, trusts neither.',
    weight: 5,
  },
] as const

const TRACE_STEPS = [
  { step: 'REQUEST', actor: 'HUMAN', tone: 'var(--trust-approved)' },
  { step: 'RETRIEVAL', actor: 'ENGINE', tone: 'var(--trust-verified)' },
  { step: 'RULE ENGINE', actor: 'ENGINE', tone: 'var(--trust-verified)' },
  { step: 'LLM JUDGEMENT', actor: 'LLM', tone: 'var(--spect-violet)' },
  { step: 'FUSION', actor: 'ENGINE', tone: 'var(--trust-verified)' },
  { step: 'HUMAN DECISION', actor: 'HUMAN', tone: 'var(--trust-approved)' },
] as const

const WORKSPACES = [
  {
    route: '/dashboard',
    chip: '/dashboard',
    title: 'Dashboard',
    body: 'Pipeline status and current counts across the record.',
    cta: 'Open dashboard',
  },
  {
    route: '/graph',
    chip: '/graph',
    title: 'Graph',
    body: 'Entities, relations, and the evidence behind each edge.',
    cta: 'Open graph',
  },
  {
    route: '/chat',
    chip: '/chat',
    title: 'Chat',
    body: 'Grounded answers with citations — or a refusal when nothing grounds the claim.',
    cta: 'Open chat',
  },
] as const

function Ribbon({ id }: { id: string }) {
  return (
    <div className="ribbon" aria-hidden="true" id={id}>
      {RIBBON_STAGES.map((stage) => (
        <span key={stage} className="ribbon-node">
          {stage}
        </span>
      ))}
      <span className="ribbon-pulse" style={{ animationDelay: '0s' }} />
      <span className="ribbon-pulse ice" style={{ animationDelay: '1s' }} />
      <span className="ribbon-pulse" style={{ animationDelay: '2s' }} />
    </div>
  )
}

export function HomePage() {
  const rootRef = useRef<HTMLDivElement>(null)

  // Public route renders outside AppShell, so the page owns the document-level
  // dark scope: body's var-driven background resolves to abyss, painting the
  // gutters around the centred column. Removed on unmount — light restores.
  useEffect(() => {
    document.body.classList.add('theme-dark')
    return () => document.body.classList.remove('theme-dark')
  }, [])

  // Cinematic scroll layer: hero camera zoom + pinned story filmstrip.
  // GSAP owns ONLY [data-hero-zoom], [data-hero-copy], [data-mask-line] lines,
  // [data-hero-fade], and the story track — never .reveal elements.
  // Reduced-motion and narrow viewports skip GSAP entirely: no initial hidden
  // state is ever set in CSS, so the static stacked layout shows everything.
  useEffect(() => {
    const root = rootRef.current
    if (!root) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    if (window.matchMedia('(max-width: 640px)').matches) return

    gsap.registerPlugin(ScrollTrigger)
    const ctx = gsap.context(() => {
      // (1) Hero camera: the ribbon-world visual pushes in and fades as the
      // hero scrolls out; copy drifts up gently. Scrubbed, transform/opacity.
      gsap.to('[data-hero-zoom]', {
        scale: 1.25,
        opacity: 0.2,
        ease: 'none',
        scrollTrigger: { trigger: '[data-hero]', start: 'top top', end: 'bottom top', scrub: true },
      })
      gsap.to('[data-hero-copy]', {
        y: -60,
        opacity: 0,
        ease: 'none',
        scrollTrigger: { trigger: '[data-hero]', start: 'top top', end: 'bottom top', scrub: true },
      })

      // (1) Headline entrance: masked lines rise once on mount.
      gsap.from('[data-mask-line] > span', {
        yPercent: 115,
        duration: 0.9,
        ease: 'power3.out',
        stagger: 0.12,
      })
      gsap.from('[data-hero-fade]', {
        y: 20,
        opacity: 0,
        duration: 0.7,
        ease: 'power2.out',
        stagger: 0.1,
        delay: 0.3,
      })

      // (2) Pinned story: one continuous filmstrip. The viewport takes the
      // locking height only here, so the fallback stays a normal stacked flow.
      const viewport = root.querySelector<HTMLElement>('[data-story-viewport]')
      const track = root.querySelector<HTMLElement>('[data-story-track]')
      if (viewport && track) {
        gsap.set(viewport, { height: '100vh', overflow: 'hidden' })
        const distance = () => Math.max(0, track.scrollHeight - viewport.clientHeight)
        gsap.to(track, {
          y: () => -distance(),
          ease: 'none',
          scrollTrigger: {
            trigger: '[data-story]',
            start: 'top top',
            end: () => `+=${distance()}`,
            pin: true,
            scrub: 1,
            invalidateOnRefresh: true,
          },
        })
      }
    }, root)
    // Route remount rebuilds triggers from the fresh layout.
    ScrollTrigger.refresh()
    return () => {
      ctx.revert()
    }
  }, [])

  const revealGraph = useReveal<HTMLElement>()
  const revealVerify = useReveal<HTMLElement>()
  const revealCouncil = useReveal<HTMLElement>()
  const revealGlassbox = useReveal<HTMLElement>()
  const revealWorkspace = useReveal<HTMLElement>()
  const revealFinal = useReveal<HTMLElement>()

  return (
    <div ref={rootRef} className="home theme-dark">
      <header className="home-bar">
        <div className="home-brand">
          <div className="brand-mark" aria-hidden="true" />
          <span className="brand-text">PRISM</span>
        </div>
        <nav aria-label="Product">
          <a href="#story">Story</a>
          <a href="#pipeline">Pipeline</a>
          <a href="#council">Council</a>
          <a href="#glassbox">Glass Box</a>
          <Link to="/login" className="btn sm primary" data-magnetic>
            Enter Prism
          </Link>
        </nav>
      </header>
      <span data-scroll-progress aria-hidden="true" />

      <main>
        {/* (1) Hero — full viewport. Copy owned by the GSAP camera above;
            parallax blobs are pointer-layer owned (no shared transforms). */}
        <section
          data-hero
          className="home-hero aurora"
          style={{
            minHeight: '100vh',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            gap: 'var(--space-5)',
          }}
        >
          <div className="aurora-blob cobalt" aria-hidden="true" data-parallax="0.04" />
          <div className="aurora-blob violet" aria-hidden="true" data-parallax="0.07" />
          <div data-hero-copy style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
            <p className="eyebrow" data-hero-fade>
              Auditable intelligence analysis
            </p>
            <h1 className="display-mega">
              <span className="mask-line" data-mask-line>
                <span style={{ display: 'block' }}>PRISM</span>
              </span>
              <span className="mask-line" data-mask-line>
                <span style={{ display: 'block' }}>Intelligence beyond information.</span>
              </span>
            </h1>
            <p className="lede" data-hero-fade>
              Every answer traceable to the evidence behind it.{' '}
              <strong>Deterministic code owns the record.</strong> Models only propose. A
              human verifier decides.
            </p>
            <div className="btn-row home-ctas" data-hero-fade style={{ margin: 0 }}>
              <Link to="/login" className="btn btn-signal" data-magnetic>
                Enter Prism
              </Link>
              <a href="#story" className="btn" data-magnetic>
                See how it works
              </a>
            </div>
          </div>
          <div className="zoom-layer" data-hero-zoom data-spotlight>
            <Ribbon id="ribbon-hero" />
            <p className="tiny muted" style={{ marginTop: 'var(--space-3)' }}>
              The record only moves forward on human approval.
            </p>
          </div>
        </section>

        {/* (2) Pinned document-to-verdict story — one continuous filmstrip.
            GSAP-owned: no .reveal here, never double-animated. Fallback:
            the same beats stack as a normal static flow. */}
        <section data-story id="story" aria-label="Document to verdict story">
          <div className="section-shell" style={{ paddingBottom: 'var(--space-4)' }}>
            <p className="eyebrow">Document to verdict</p>
            <h2 className="display-section" style={{ marginTop: 'var(--space-2)' }}>
              One record, from source to verdict.
            </h2>
            <p className="lede" style={{ marginTop: 'var(--space-3)' }}>
              Follow a single document through the pipeline. Each step is observable,
              each decision attributable.
            </p>
          </div>
          <div data-story-viewport>
            <div data-story-track className="zoom-layer">
              {STORY_BEATS.map((beat) => (
                <div
                  key={beat.index}
                  style={{
                    minHeight: '100vh',
                    display: 'flex',
                    flexDirection: 'column',
                    justifyContent: 'center',
                  }}
                >
                  <div className="section-shell glass-strong" data-spotlight style={{ padding: 'var(--space-7)' }}>
                    <span className="term-chip">
                      {beat.index} · {beat.stage}
                    </span>
                    <h3 className="display-section" style={{ marginTop: 'var(--space-3)', fontSize: 'clamp(1.6rem, 3.5vw, 2.5rem)' }}>
                      {beat.title}
                    </h3>
                    <p className="lede" style={{ marginTop: 'var(--space-3)' }}>
                      {beat.body}
                    </p>
                    <p className="tiny muted" style={{ marginTop: 'var(--space-4)', marginBottom: 0 }}>
                      Beat {beat.index} of 07 — keep scrolling.
                    </p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* (3) Graph — ribbon motif, structural stat cards (counts of things
            already on this page, never measurements), honest lattice example. */}
        <section id="pipeline" ref={revealGraph} className="home-section reveal">
          <p className="eyebrow">Knowledge graph</p>
          <h2 className="display-section" style={{ marginTop: 'var(--space-2)' }}>
            Approved knowledge, connected.
          </h2>
          <p className="lede" style={{ marginTop: 'var(--space-3)' }}>
            Raw documents become entities and relationships — approved one by one.
          </p>
          <div style={{ marginTop: 'var(--space-5)' }} aria-hidden="true">
            <Ribbon id="ribbon-graph" />
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
              gap: 'var(--space-3)',
              marginTop: 'var(--space-5)',
            }}
          >
            <div className="stat-card" data-spotlight>
              <p className="stat-card-number">{PIPELINE.length}</p>
              <p className="stat-card-label">Pipeline stages</p>
              <p className="stat-card-delta">Each one observable</p>
            </div>
            <div className="stat-card" data-spotlight>
              <p className="stat-card-number">{RIBBON_STAGES.length}</p>
              <p className="stat-card-label">Ribbon checkpoints</p>
              <p className="stat-card-delta">Document to verdict</p>
            </div>
            <div className="stat-card" data-spotlight>
              <p className="stat-card-number">{COUNCIL.length}</p>
              <p className="stat-card-label">Council personas</p>
              <p className="stat-card-delta">One human chair decides</p>
            </div>
          </div>
          <div className="btn-row" style={{ marginTop: 'var(--space-4)' }}>
            <Link to="/graph" className="btn" data-magnetic>
              Open the graph
            </Link>
          </div>
          <div className="home-demo home-lattice" aria-hidden="true">
            <span className="home-node">Meridian Group</span>
            <span className="home-edge">controls</span>
            <span className="home-node">Kestrel Components</span>
            <span className="home-edge">supplies</span>
            <span className="home-node">Verity Foods</span>
          </div>
          <p className="tiny muted">Fictional example. Only approved triples become edges.</p>
          <h3 style={{ marginTop: 'var(--space-6)' }}>The pipeline</h3>
          <p>Eight stages. Each one observable, each decision attributable.</p>
          <ol className="home-pipeline">
            {PIPELINE.map(([name, blurb], i) => (
              <li key={name} className={`stagger-${Math.min(i + 1, 8)}`} data-spotlight>
                <span className="home-pipeline-index">{String(i + 1).padStart(2, '0')}</span>
                <strong>{name}</strong>
                <span className="tiny muted">{blurb}</span>
              </li>
            ))}
          </ol>
        </section>

        {/* (4) Verification — evidence cards with verdict tones (trust tokens). */}
        <section ref={revealVerify} className="home-section reveal" id="verification">
          <p className="eyebrow">Verification</p>
          <h2 className="display-section" style={{ marginTop: 'var(--space-2)' }}>
            Signals kept separate, never blended.
          </h2>
          <p className="lede" style={{ marginTop: 'var(--space-3)' }}>
            Claim verification with the evidence on show — four fields, always shown apart.
          </p>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
              gap: 'var(--space-3)',
              marginTop: 'var(--space-5)',
            }}
          >
            <div
              className="stat-card"
              data-spotlight
              style={{ borderLeft: '3px solid var(--trust-verified)' }}
            >
              <span className="term-chip" style={{ background: 'var(--trust-verified-bg)', alignSelf: 'flex-start' }}>
                Grounded
              </span>
              <p className="stat-card-label" style={{ fontWeight: 600, color: 'var(--ink-0)' }}>
                Retrieved passages
              </p>
              <p className="tiny muted">What the corpus actually says, quoted with sources.</p>
            </div>
            <div
              className="stat-card"
              data-spotlight
              style={{ borderLeft: '3px solid var(--trust-approved)' }}
            >
              <span className="term-chip" style={{ background: 'var(--trust-approved-bg)', alignSelf: 'flex-start' }}>
                Enforced
              </span>
              <p className="stat-card-label" style={{ fontWeight: 600, color: 'var(--ink-0)' }}>
                Deterministic rules
              </p>
              <p className="tiny muted">Hard penalties no judgement call can waive.</p>
            </div>
            <div
              className="stat-card"
              data-spotlight
              style={{ borderLeft: '3px solid var(--trust-pending)' }}
            >
              <span className="term-chip" style={{ background: 'var(--trust-pending-bg)', alignSelf: 'flex-start' }}>
                Advisory
              </span>
              <p className="stat-card-label" style={{ fontWeight: 600, color: 'var(--ink-0)' }}>
                Model judgement
              </p>
              <p className="tiny muted">Labelled as such — never the verdict on its own.</p>
            </div>
          </div>
          <p className="tiny muted" style={{ marginTop: 'var(--space-4)' }}>
            Fused in the open: verified, contradicted, or refused when nothing grounds the
            claim.
          </p>
          <div className="btn-row" style={{ marginTop: 'var(--space-3)' }}>
            <Link to="/verification" className="btn" data-magnetic>
              Open verification
            </Link>
          </div>
        </section>

        {/* (5) Council — three personas argue; the human chair decides. */}
        <section id="council" ref={revealCouncil} className="home-section reveal">
          <p className="eyebrow">Council</p>
          <h2 className="display-section" style={{ marginTop: 'var(--space-2)' }}>
            Three argue. A human decides.
          </h2>
          <p className="lede" style={{ marginTop: 'var(--space-3)' }}>
            Three personas argue the contradiction. The human chair weights each argument
            and decides — no auto-resolution.
          </p>
          <div className="home-demo home-council" aria-hidden="true" style={{ marginTop: 'var(--space-5)' }}>
            {COUNCIL.map((p) => (
              <div key={p.name} className="home-persona" data-spotlight>
                <strong>{p.name}</strong>
                <span className="tiny muted">{p.line}</span>
                <span className="home-weights" aria-hidden="true">
                  {[1, 2, 3, 4, 5].map((w) => (
                    <i key={w} className={w <= p.weight ? 'on' : ''} />
                  ))}
                </span>
              </div>
            ))}
          </div>
          <p className="tiny muted">
            The chair weights each argument 1–5, then advances or synthesizes.
          </p>
          <div className="btn-row" style={{ marginTop: 'var(--space-3)' }}>
            <Link to="/contradictions" className="btn" data-magnetic>
              Open contradictions
            </Link>
          </div>
        </section>

        {/* (6) Glass box — trace motif with actor tones. */}
        <section id="glassbox" ref={revealGlassbox} className="home-section reveal">
          <p className="eyebrow">Glass box</p>
          <h2 className="display-section" style={{ marginTop: 'var(--space-2)' }}>
            Replay anything.
          </h2>
          <p className="lede" style={{ marginTop: 'var(--space-3)' }}>
            Observable execution. System events — never hidden reasoning.
          </p>
          <div className="home-demo home-trace" aria-hidden="true" style={{ marginTop: 'var(--space-5)' }}>
            {TRACE_STEPS.map((s, i) => (
              <span key={s.step}>
                <span
                  className="home-step"
                  style={{ borderLeft: `3px solid ${s.tone}` }}
                >
                  {s.step} · {s.actor}
                </span>
                {i < TRACE_STEPS.length - 1 && <span className="home-arrow">↓</span>}
              </span>
            ))}
          </div>
          <p className="tiny muted">
            ENGINE, LLM and HUMAN steps carry inputs, outputs, timings, versions.
          </p>
          <div className="btn-row" style={{ marginTop: 'var(--space-3)' }}>
            <Link to="/glassbox" className="btn" data-magnetic>
              Open the glass box
            </Link>
          </div>
        </section>

        {/* (7) Workspace — honest framed entry cards to real routes.
            Entry points, never fake screenshots. */}
        <section ref={revealWorkspace} className="home-section reveal" id="workspace">
          <p className="eyebrow">Workspace</p>
          <h2 className="display-section" style={{ marginTop: 'var(--space-2)' }}>
            Open the working surface.
          </h2>
          <p className="lede" style={{ marginTop: 'var(--space-3)' }}>
            Three entry points into the same record. These cards open the real app —
            framed previews, not screenshots.
          </p>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
              gap: 'var(--space-3)',
              marginTop: 'var(--space-5)',
            }}
          >
            {WORKSPACES.map((w) => (
              <div key={w.route} className="glass-strong" data-spotlight style={{ padding: 'var(--space-5)' }}>
                <span className="term-chip">{w.chip}</span>
                <h3 style={{ marginTop: 'var(--space-3)', marginBottom: 'var(--space-2)' }}>
                  {w.title}
                </h3>
                <p className="tiny muted" style={{ marginTop: 0 }}>
                  {w.body}
                </p>
                <div className="btn-row" style={{ marginTop: 'var(--space-4)' }}>
                  <Link to={w.route} className="btn sm" data-magnetic>
                    {w.cta}
                  </Link>
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* (8) Final CTA. */}
        <section ref={revealFinal} className="home-section home-final reveal" id="enter">
          <p className="eyebrow">Enter</p>
          <h2 className="display-section" style={{ marginTop: 'var(--space-2)' }}>
            Decide like the record matters.
          </h2>
          <p className="lede" style={{ marginTop: 'var(--space-3)' }}>
            Proposals are cheap. Approval is the product.
          </p>
          <div className="btn-row home-ctas">
            <Link to="/login" className="btn btn-signal" data-magnetic>
              Enter Prism
            </Link>
            <Link to="/signup" className="btn" data-magnetic>
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
