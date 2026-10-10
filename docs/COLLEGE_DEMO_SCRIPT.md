# PRISM College Demo Script (10–12 minutes)

Presenter: open `docs/SHOWCASE_GUIDE.md` alongside this. All IDs are real.
If anything fails, `docs/DEMO_RECOVERY_GUIDE.md` has the recovery per step.

## 0–1 min — Introduction and problem statement

Say: unstructured documents hide facts, conflicts, and provenance. PRISM
turns documents into checkable knowledge where **deterministic Java owns all
state, the model only proposes, and a human verifier has the final word**.
Nothing the model says becomes trusted until a person approves it.

Show: title page at http://localhost:5173. No action needed.

## 1–2 min — Architecture and stack

Say: Spring Boot + MySQL 8 (Flyway migrations) behind nginx; React +
TypeScript + Vite + Cytoscape.js frontend; qwen2.5:7b through Ollama via an
OpenAI-compatible client; every model call versioned (EXTRACT_V1, JUDGE_V1,
HAWK/DOVE/SKEPTIC_V1, SYNTHESIS_V1, CHAT_V1) and traced in the Glass Box.

Show: `/glassbox` — point at ENGINE / LLM / HUMAN actor badges. (30 s)

## 2–3 min — Login and documents

Action: log in as the VERIFIER account (operator holds credentials).
Open corpus **PRISM-QA-SYNTHETIC-2026-10 (id=20)** → Documents.
Expected: 5 docs (ids 51–55), all AWAITING_APPROVAL.
Examiner note: statuses are real pipeline states (UPLOADED → … →
AWAITING_APPROVAL), not mock labels. (~1 min)

## 3–4 min — Extraction and human verification

Action: open the approval queue for corpus 20.
Expected: 7 approved claims (ids 306–312), 0 triples — say why honestly:
the CPU model emitted predicates outside the 28-term registry, so those
chunks were quarantined rather than silently accepted. Quarantine is the
feature working, not failing.
Then approve or reject one live PROPOSED item in corpus 1 (36 PROPOSED)
to show the human gate. (~1.5 min)

## 4–5 min — Knowledge graph

Action: open Graph, corpus **Delacroix Group PDF dossier (id=1)**,
scope ALL_APPROVED.
Expected: **27 nodes / 46 edges**, painted canvas (verified 2000+ ink
samples in rehearsal). Pan/zoom a node; show its provenance.
Say: corpus 20 has no triples (see above), so the graph step uses corpus 1
deliberately — the guide documents this fallback. (~1 min)

## 5–6 min — Verification and contradictions

Action: open Claims, corpus 1 → claim 60
(`Saltmarsh Freight operates_in Halden.`), status VERIFIED, verdict
SUPPORTED by qwen2.5:7b. Then Contradictions: **8 OPEN**, e.g. `reports_to`
Delacroix→Aurelia vs →Brightwater.
Examiner note: quote the measured verification result — 2/4 fresh items
plus 4/4 persisted (8 items, 0.75), with V7/V10 misses analyzed as judge
reasoning gaps, never re-labelled. (~1.5 min)

## 6–8 min — Council

Action: open **debate 4** (COMPLETED, topic: Verity Foods subsidiary_of).
Walk rounds 1–3: round 1 shows a HAWK timeout recorded as a *named failure*
(the product refusing to crash or fake a voice); rounds 2–3 show 6 genuine
cited arguments. Show chair weights with the chair's name attached.
Say: 3 personas × ~2–7 min each on CPU is why this is a persisted artifact,
not a live re-run. (~2 min)

## 8–9 min — Synthesis

Action: debate 4 → Report (**report 1**).
Expected: conclusion "subsidiary relationship … remains unresolved",
2 blocks, 5/5 citations resolving to arguments/evidence, model qwen2.5:7b,
SYNTHESIS_V1. State the generation cost (~7.6 min) and timestamp: this is a
historical artifact being displayed, not fresh inference. (~1 min)

## 9–10 min — Grounded chat

Action: Chat, corpus 20. Ask: "Who directs Meridian Research Institute in
September 2026?" Expected: "Dr. Asha Rao" with chunk 207 + 210 citations
(saved session 8). Optionally ask "What is the Route 7 ticket price?"
Expected: abstention ("no evidence about ticket prices") — the model saying
it does not know is the feature. (~1 min)

## 10–11 min — Glass Box

Action: Traces → debate-4 run → replay (Play/Pause/Next).
Expected: ENGINE transitions, LLM persona calls with model + prompt
version, HUMAN chair weights — in execution order, from stored data, no
re-inference. (~1 min)

## 11–12 min — Results, limitations, future work

Results: 259/259 backend tests, 28/28 security, 19 endpoints
contract-clean, 17/17 rehearsal checks, real qwen2.5:7b throughout.
Limitations (say all four): CPU-bound latencies; corpus-20 triple gap;
verification 8 items not a benchmark; no public deploy (no TLS, ports
published, dev fallbacks).
Future: GPU inference, resumable 41-chunk eval, temporal reasoning in the
judge, production hardening per the audit.

## Recovery (one line per step)

Login 429 → wait 70 s. Empty graph → switch corpus to 1. Debate stream
401 → fixed in c17178e; refresh. Slow inference → present the persisted
artifact and say so. Full table: `docs/DEMO_RECOVERY_GUIDE.md`.
