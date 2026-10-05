# PRISM Frontend Motion System

Motion supports comprehension. Nothing moves for decoration in the app;
the homepage gets restrained technical motion only.

## Durations (tokens — add to motion.css)

- Micro (hover, focus, select): 100–180ms
- UI transition (drawer, modal, tab, accordion): 200–400ms
- Content entrance (card, argument, message, report block): 300–600ms
- Data animation (stepper fill, graph settle, count-up): 400–1000ms
- Replay/process (trace replay phases, debate round transition):
  800–1600ms, always scrubbable/pausable, never autoplay on load

## Easing

`ease-out` entrances; `ease-in-out` for replay/continuous. No bounce, no
elastic, no scroll hijacking. Base transforms: fade, translate (8–16px),
scale 0.98→1. Line-draw for pipeline/stepper SVGs. Glow only for the
currently-replaying trace step.

## Per-surface rules

- Approval: selection fade; decide-and-advance slide (one item at a time).
- Debate: argument enter (translate+fade ≤400ms, announced via live region);
  evidence attach fade; verdict badge pop (single); round/synthesis
  transitions. Stream arrivals must not shift layout (reserve space).
- Graph: layout settle ≤1000ms; neighborhood highlight/dim on select;
  single subtle selection pulse, no loops.
- Chat: message entrance only; evidence-panel sync highlight.
- Reports: block stagger-in, disabled under reduced-motion.
- Glass Box replay: phase stepper with play/pause/scrub + keyboard
  (space/arrows); step highlight + transcript follow; reduced-motion →
  instant jumps between phases.
- Dashboard: CountUp on tiles (skip if reduced-motion); pipeline strip fill.
- Homepage: hero entrance + pipeline line-draw on scroll into view
  (IntersectionObserver, once); everything static under reduced-motion.

## Reduced motion (extends existing infra)

Current CSS already kills page/card/row/alert/spinner/shimmer/orb/sheen/
marquee under `prefers-reduced-motion`. New work must: gate ReplayPlayer
autoplay (require press-play always — good for everyone), replace argument
stagger with instant appearance, replace count-up with final values,
provide the replay transcript as the no-motion experience.

## Loading motion

Skeleton shimmer (exists) for lists; stepper fill for pipelines; determinate
progress bar only where the backend reports progress (verify-all counts,
upload is indeterminate — never fake a percentage).
