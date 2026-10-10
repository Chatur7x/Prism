# PRISM — College Major-Project Viva Q&A

> Every answer below is grounded in the repository as of the finalization pass
> (branch `master`, commit `c17178e` per `docs/COLLEGE_PROJECT_FINALIZATION.md`).
> Anything the author could not verify in code is marked
> **"(unverified — confirm)"**. No benchmark scores are invented: all numbers
> are quoted from `docs/evaluation.md`, `docs/performance.md`,
> `docs/SHOWCASE_GUIDE.md`, or `eval/runs/*.json`.

---

## 1. Problem statement and motivation

### Q1. What problem does PRISM solve, in one sentence?
Answer: Unstructured documents hide facts, conflicts, and provenance, so PRISM turns documents into checkable knowledge where deterministic Java owns all state, the language model only proposes, and a human verifier has the final word (`README.md` "What it actually does"; `docs/COLLEGE_DEMO_SCRIPT.md` §0–1).

### Q2. Why is "the LLM only proposes" the central design rule rather than just a policy?
Answer: Because every state-changing path enforces it in code: model output is parsed, schema-validated, semantically validated, and persisted as a `PENDING` proposal, and the architecture record states there is no code path that sets a proposal to `APPROVED` — approval is the exclusive privilege of the human workflow (`README.md`; `docs/architecture.md` §1).

### Q3. What goes wrong if model output is silently "cleaned up" before storage?
Answer: Malformed output is quarantined and retained verbatim, never repaired into plausibility, because a system that quietly fixes model mistakes cannot later be audited for them (`docs/architecture.md` §1).

### Q4. What is the demo corpus, and why was it built that way?
Answer: 24 fictional memos with deliberately planted findings: 5 conflicts on single-valued predicates, 1 `located_in` bound violation, 26 multi-valued relations that must not be flagged, and 1 deliberately uninformative memo — so the pipeline must prove it can tell silence from substance (`README.md` "Trying the whole thing").

### Q5. Who is the intended user of this system?
Answer: An internal analyst/verifier team doing auditable document analysis (single-tenant internal use is the stated deployment target; refresh tokens were deliberately not built because re-authentication is acceptable there — `docs/limitations.md` §5).

## 2. Existing systems and related work

### Q6. How is PRISM different from a standard RAG chatbot?
Answer: A chatbot retrieves passages and answers; PRISM additionally extracts structured triples/claims, gates them behind human approval, scores claim verdicts with four separately-stored fields, detects contradictions deterministically, debates them in a Council, and records everything in a Glass Box trace (`README.md`; `docs/evaluation.md` "Verdicts").

### Q7. How is PRISM different from a plain knowledge-graph tool?
Answer: The graph is built only from human-approved triples, contradiction detection is driven by a registered predicate-semantics registry rather than graph shape alone, and every Council report block carries its own citations (`docs/evaluation.md` "Contradiction detection"; `Prompts.java` `synthesisSystem`).

### Q8. Why not just trust the LLM's verdict directly?
Answer: The verdict path stores the LLM score, the deterministic rule penalty, the fused ranking score, and the evidence status as four separate fields that are never collapsed into one number, precisely so no single model judgement is treated as truth (`docs/architecture.md` §5; `docs/evaluation.md` "Verdicts").

### Q9. What existing benchmarks or datasets did you compare against?
Answer: None from outside — the project built two internal gold sets instead (canonical `eval/gold-extraction.tsv`, 99 labels; prose `eval/prose-gold-v1.json`, 81 labelled sentences) and explicitly records that external comparison was out of scope and real-model quality is unmeasured (`docs/limitations.md` §1).

## 3. Proposed solution and scope

### Q10. What does a document's journey through PRISM look like end to end?
Answer: Upload → chunking (`SentenceChunker`, ~300-token targets per `application.yml`) → per-chunk extraction call → parse/validate/quarantine → `PENDING` proposals → human approval queue → trusted triples/claims → graph, verification, contradiction scan, Council debate, synthesis report, grounded chat — with Glass Box tracing throughout (`README.md`; `docs/architecture.md` §9).

### Q11. What is deliberately out of scope for this project?
Answer: Real-model quality measurement (no real model evaluated — `REAL_MODEL_EVALUATION_PENDING`), a large retrieval gold set (kept at 11 hand-checked queries deliberately), refresh tokens, multi-instance SSE, horizontal scalability, and any public deployment (`docs/limitations.md` §§1–9; `README.md` "Status").

### Q12. What is the single sentence that acts as the project's specification?
Answer: "Deterministic Java owns all state. The LLM only proposes. The human verifier is the final authority." (`README.md` header; `docs/architecture.md` §1 "The invariant").

### Q13. What would make this system a release candidate, at minimum?
Answer: Evaluating a real language model through the already-built measurement harness — recorded as the single blocking limitation `REAL_MODEL_EVALUATION_PENDING` (`README.md` "Status"; `docs/limitations.md` §1).

### Q14. Why does the project refuse to fake a measurement?
Answer: The prose-eval harness returns HTTP 412 with token `REAL_MODEL_EXECUTION_REQUIRED` (CLI exit 4) when asked to require a real model while the offline fixture is active, so CI cannot mistake a refusal for a pass (`docs/limitations.md` §1; `docs/evaluation.md` "Extraction from prose").

## 4. Backend architecture (Spring Boot)

### Q15. What is the backend stack?
Answer: Spring Boot (Java 21, bytecode 17), MySQL 8 with Flyway-only migrations (`ddl-auto: validate`), served behind nginx via Docker Compose; frontend on port 5173, backend on 8080 (`README.md` "Running it"; `docker-compose.yml`).

### Q16. What layering rules does the backend follow?
Answer: Controller → Service → Engine → Repository: controllers authenticate, authorise, delegate, and map (no business logic); services orchestrate and own transactions; engines (`ClaimRuleEngine`, `DebateEngine`, `EntityResolver`, `PredicateSemanticRegistry`, `ConfidenceFusion`, `PageRank`, `CommunityDetection`, `SentenceChunker`, `ExtractionValidator`) are deterministic and unit-testable without Spring or a database (`docs/architecture.md` §2).

### Q17. What is the Spring `@Transactional` self-invocation trap, and how does the code handle it?
Answer: A `@Transactional` method called from inside its own class never passes through Spring's proxy, so the annotation is silently ignored — this caused real failures (e.g. every approval deadlocking 50 s via `TraceRecorder`). The rule is that any transaction needing an internal call moves into its own bean (`ProposalWriter`, `DebateStateService`, `TraceStepWriter`, and others — `docs/architecture.md` §§2, 4.6).

### Q18. Why is no transaction held open across an LLM call?
Answer: Debate start/advance runs three persona model calls; holding a pooled DB connection across network latency exhausts the pool under concurrency, so the single must-succeed statement (conditional `UPDATE … WHERE state = :expected`) gets its own short transaction in `DebateStateService` (`docs/architecture.md` §2).

### Q19. How does ingestion survive restarts and concurrency?
Answer: Each document gets a durable `background_jobs` row claimed by a worker pool; jobs are idempotent (facts keyed by SHA-256 identity hash) and recoverable (`RecoveryService` resets stale `RUNNING` jobs preserving attempt counts); entity inserts use insert-if-absent on the `(corpus_id, normalized_name)` unique index and support counts use atomic `UPDATE … + 1` (`docs/architecture.md` §§4, 9).

### Q20. What fixed the 14-of-24-documents lock-wait failure during ingestion?
Answer: Entity resolution was moved out of the proposal-writing transaction into short independent transactions, with inserts delegated to `ProposalWriter` whose transaction never updates an entity row — demo-corpus ingestion went from 14 failures in 315 s to zero failures in 6 s (`docs/architecture.md` §4.3).

## 5. Frontend (React + Cytoscape)

### Q21. What is the frontend stack?
Answer: React 19 + Vite + TypeScript in `strict` mode with `noUncheckedIndexedAccess`; hand-written CSS against design tokens (no Tailwind/PostCSS transform); routes lazy-loaded with an entry bundle of ~62 kB gzipped (`docs/architecture.md` §8; `frontend/package.json`).

### Q22. How is the knowledge graph rendered?
Answer: With Cytoscape.js (`cytoscape ^3.34.3` in `frontend/package.json`), via the `GraphCanvas` component (`frontend/src/components/GraphCanvas.tsx`) using a cose layout, with pan/zoom and node selection; the demo shows 27 nodes / 46 edges for corpus 1 (`docs/SHOWCASE_GUIDE.md` step 6).

### Q23. How does the frontend avoid displaying wrong or mock data?
Answer: There are no mock screens — every page consumes a real backend endpoint — and API types are transcribed from the backend's own OpenAPI document, which caught roughly twenty wrong field names during development (`docs/architecture.md` §8).

### Q24. What happened with the admin page, and what did it teach?
Answer: `AdminPage` typed `adminApi.users()` as a bare array while the server returned a page object, so it rendered "No users" for every account; the contract check never covered that endpoint. The fix unified collection endpoints on one `PageResponse` envelope and extended the contract check (`docs/limitations.md` §7).

## 6. Database (MySQL, Flyway, corpus isolation)

### Q25. Why Flyway only, and what does `ddl-auto: validate` actually prove?
Answer: Every schema change is a numbered migration and Hibernate never creates or updates the schema; `validate` proves entity mappings match the schema but cannot prove the schema matches the mappings — a `NOT NULL` column no entity mentions is invisible to it (`README.md`; `docs/architecture.md` §6).

### Q26. What real defect did that one-directional check miss?
Answer: `report_block_citations.corpus_id` sat unmapped for the project's whole life and every synthesis failed at runtime; the permanent fix is `SchemaContractChecker`, which cross-checks every `NOT NULL` column against every mapping, with a test recreating the historical defect (`docs/architecture.md` §6; `docs/limitations.md` §9 phase 6).

### Q27. How is corpus isolation enforced?
Answer: `User → Corpus → Document → DocumentChunk` is enforced in every retrieval, graph, verification, contradiction, debate, and chat operation through the single authority `CorpusAccessService`, by resolving an authorised `Corpus` object and passing that (not its id) downstream (`docs/architecture.md` §3).

### Q28. Was there ever an isolation bug? What was it?
Answer: Yes — `ContradictionRepository.findByIdAndCorpusId` was called with a user id as the corpus id in three places, failing closed as a 404 that looked like a missing row; the fix pattern is an explicit lookup-then-`requireAccessible` in two steps (`docs/architecture.md` §3).

## 7. Authentication, RBAC, and security

### Q29. What are the roles, and what can each do?
Answer: `Role` enum in `backend/src/main/java/com/prism/user/Role.java` defines `ADMIN`, `ANALYST`, `VERIFIER`: self-registration always yields `ANALYST`; `VERIFIER`/`ADMIN` come only from an existing admin or the bootstrap path; corpus edits, approvals, verification, debates, and retrieval evaluation are verifier/admin-gated via `CorpusAccessService` (`docs/architecture.md` §7; `AuthController`, `CorpusAccessService`).

### Q30. How are passwords and tokens handled?
Answer: Passwords are BCrypt-hashed (cost 12), minimum 12 characters, never logged/returned/persisted; sessions are HS256 JWTs signed with an environment secret the app refuses to boot without (minimum 32 bytes via `SecurityConfigValidator`); access tokens are short-lived (~15 min) with no refresh-token flow (`docs/architecture.md` §7; `docs/limitations.md` §5).

### Q31. What are the rate limits?
Answer: `FixedWindowRateLimiter` with per-minute buckets configured in `backend/src/main/resources/application.yml` as `auth-per-minute: 10` and `llm-per-minute: 30` (test profile raises both to 10000); the demo seeder honours 429s with exponential backoff rather than being special-cased (`application.yml` lines 76–78, 184–186; `docs/architecture.md` §7).

### Q32. Where is the frontend token stored, and why is that acceptable here?
Answer: In `localStorage` — readable by any script on the origin, so the deployment assumes a single trusted origin; an `httpOnly`/`Secure`/`SameSite=Strict` cookie with CSRF protection is documented as the better answer for single-origin production (`README.md` "Security"; `docs/architecture.md` §7).

### Q33. What other hardening is in place?
Answer: CORS explicit-origin list (wildcard refused; empty means same-origin-only), actuator limited to health/info/metrics, OpenAPI docs ADMIN-only, secrets never logged with truncated error bodies, per-endpoint trace ids joinable to Glass Box steps, and prompt-injection preamble plus schema/reference validation on every model output (`docs/architecture.md` §7; `Prompts.java` `UNTRUSTED_DATA_PREAMBLE`).

### Q34. What did the security testing actually cover?
Answer: A 28-check probe (`scripts/security-probe.ps1`, re-run green on
2026-10-10: auth, RBAC, corpus isolation, token handling, input validation)
plus `scripts/auth-sweep.ps1`, which exercises the protected resources
against direct IDs, pagination/nested references, invalid IDs, and both
access directions. No leaks found; the probe's corpus-isolation section
passes. Quote "28/28" for the probe specifically.

## 8. Knowledge graphs (Cytoscape, PageRank, communities)

### Q35. What graph algorithms does the system implement, and where?
Answer: Deterministic `PageRank` and `CommunityDetection` engines in `backend/src/main/java/com/prism/graph/` (pure, unit-testable), exposed via typed graph/PageRank/communities endpoints and painted by the Cytoscape canvas (`docs/architecture.md` §2; `docs/limitations.md` §7).

### Q36. What are the PageRank parameters?
Answer: Damping 0.85, 50 iterations, 1e-6 tolerance, 60 s cache TTL — all in `application.yml` under `prism.graph`; fixed damping and iteration count make it reproducible across runs (`application.yml` lines 112–116; `docs/architecture.md` §5).

### Q37. What graph scopes exist, and why is `VERIFIED_ONLY` empty in the demo?
Answer: Scopes include `ALL_APPROVED` (27 nodes / 46 edges on corpus 1) and `VERIFIED_ONLY`, which filters to triples backed by a settled `SUPPORTED` verdict; the offline fixture never returns `SUPPORTED`, so the scope is correctly empty offline — a demonstration gap, not a bug (`docs/limitations.md` §§1, 8).

### Q38. What does the Skeptic persona get from the graph layer?
Answer: A machine-evidence brief assembled from live verdicts, PageRank, and communities — every figure corresponds to a database row at brief-build time, and the Skeptic must treat it as authoritative and never re-derive figures (`Prompts.java` `skepticSystem` javadoc and prompt).

## 9. LLMs (qwen2.5:7b via Ollama, prompts, RAG)

### Q39. Which model runs the pipeline, and how is it reached?
Answer: `qwen2.5:7b` served by Ollama (default `LLM_BASE_URL http://localhost:11434/v1`, pinned `OLLAMA_MODELS` dir, port 11435 in the showcase) through `OpenAiCompatibleLlmClient`, an OpenAI-compatible HTTP client that is the default provider bean; defaults for all five model slots (extract/judge/debate/synthesis/chat) are `qwen2.5:7b` in `application.yml` lines 79–93 (`docs/COLLEGE_PROJECT_FINALIZATION.md`; `OpenAiCompatibleLlmClient.java`; `docs/SHOWCASE_GUIDE.md` "Startup").

### Q40. What are the seven versioned prompts, and where do they live?
Answer: `EXTRACT_V1`, `JUDGE_V1`, `HAWK_V1`, `DOVE_V1`, `SKEPTIC_V1`, `SYNTHESIS_V1`, `CHAT_V1` — named constants plus builder methods in `backend/src/main/java/com/prism/llm/Prompts.java`; every LLM call records name and version so a verdict can be re-examined against its exact instructions (`Prompts.java` lines 19–33; `docs/COLLEGE_DEMO_SCRIPT.md` §1–2).

### Q41. What does the EXTRACT prompt demand of the model?
Answer: Only explicitly stated facts, each with an exact verbatim source sentence; triples as `(subject, predicate, object)` with snake_case predicates; claims with `POSITIVE`/`NEGATIVE`/`NEUTRAL` polarity preserving hedging/denial; empty arrays when nothing is extractable; single JSON object, schema keys only (`Prompts.java` `extractSystem`).

### Q42. How is prompt injection defended?
Answer: Defence in depth: the `UNTRUSTED_DATA_PREAMBLE` contract, source text fenced in a delimited `DATA` block in the user turn (never the system turn), JSON-only output, and schema plus reference-id validation before anything influences state; 21 deterministic-layer injection tests plus injection bait in the prose gold set (`Prompts.java`; `docs/limitations.md` §9 phase 10).

### Q43. What is the offline fixture, and what is it not?
Answer: `LLM_PROVIDER=fake` selects `FakeLlmClient` — a real client doing real parsing/validation/quarantine/persistence, but recognising only `Subject predicate Object` sentences over the 28-registry vocabulary; it deterministically proves pipeline mechanics and says nothing about model quality, and the startup banner plus eval CLI label it `FAKE / TEST MODE` (`README.md` "LLM providers"; `docs/limitations.md` §1).

### Q44. Why does retrieval deliberately avoid using an LLM?
Answer: Retrieval feeds the verification judge, so a prompt injected into a document must not steer which passages a verdict may cite — `QueryExpander` (`EXPAND_V1`) is deterministic, versioned, and recorded on every result (`docs/limitations.md` §3; `docs/evaluation.md` "Query expansion").

## 10. RAG and retrieval evaluation

### Q45. How does retrieval work technically?
Answer: MySQL FULLTEXT over chunks (native queries return id/relevance pairs, then a `join fetch` for chunks), top-K 5 with min-score 0.0, deterministic `EXPAND_V1` query expansion adding corpus tokens as relevance terms (`docs/evaluation.md` "What the index actually does, measured").

### Q46. What did you learn about MySQL FULLTEXT that contradicted the obvious assumption?
Answer: Three things: underscore is a word character (`collaborates_with` is one token, so expanding to `collaborates` scores 0); `NATURAL LANGUAGE MODE` ignores boolean syntax (the supposed boolean AND never existed); and adding a term admits new documents (the `supplies` expansion fixed one query and demoted another) — each changed the implementation (`docs/evaluation.md` "What the index actually does, measured").

### Q47. What are the retrieval scores, with their mandatory caveat?
Answer: On the frozen 11-query gold set with `EXPAND_V1`: Recall@1 0.6364, Recall@3 0.9091, Recall@5 0.9091–1.0 depending on corpus build, MRR ~0.83 over retrieved queries, 0–1 not retrieved — a regression guard where one query moves any metric ~9 points, never quoted as retrieval quality (`docs/limitations.md` §2; `docs/evaluation.md` "Result").

### Q48. What happens when the system cannot answer a question?
Answer: The chat response returns `grounded=true` with `insufficientEvidence=true` and cites what it retrieved instead of manufacturing an answer — recorded as the correct outcome ("I retrieved something, and it does not answer your question") during the `Orion Systems supply` miss diagnosis (`docs/evaluation.md` "The remaining miss").

## 11. Claim verification and verdicts

### Q49. What are the five verdicts, and why five instead of two?
Answer: `SUPPORTED`, `CONTRADICTED`, `INSUFFICIENT_EVIDENCE`, `EXAGGERATED`, `SOURCE_MISSING` (`Prompts.java` `judgeSystem`); collapsing "we don't know" into "we know it is false" is stated as the single most damaging error an audit system can make (`docs/architecture.md` §5).

### Q50. How is a verdict computed from its inputs?
Answer: The judge model returns verdict + confidence + reasoning + passage ids against supplied evidence only; deterministic Java adds rule penalties (weasel language 0.04 each, absolute claims 0.06 each, capped at 0.4, versioned `RULE_V1`) and fuses them via pure `ConfidenceFusion` — the four stored values are never collapsed (`docs/evaluation.md` "Verdicts"; `application.yml` lines 107–111).

### Q51. What is the measured verification result on the real model?
Answer: `eval/runs/verification-verification-gold-v1-20261010-103803.json`: accuracy 0.5 — 2/4 scored items matched (V6 `SUPPORTED`, V8 `CONTRADICTED`), V7 and V10 missed as judge-reasoning gaps and were never re-labelled; plus 4/4 persisted items, i.e. 8 items total, explicitly not a benchmark (`docs/SHOWCASE_GUIDE.md` "What is deliberately NOT claimed").

### Q52. What role does the human play in verification?
Answer: A machine verdict is never overwritten: `Verdict` carries `machineVerdictType`, `humanVerdictType`, `adjudicationState`, and `overridden` separately, the superseded verdict is retained in `verdict_history`, and overriding is an event with its own actor and reason (`docs/architecture.md` §1; `docs/evaluation.md` "Verdicts").

## 12. Contradiction detection

### Q53. How does contradiction detection work without an LLM?
Answer: Pure functions over approved facts in `ContradictionDetector`: triples are grouped by (subject, predicate) and checked against `PredicateSemanticRegistry` cardinality — a second distinct object on a `SINGLE` predicate emits a `RELATION_SINGLE_VALUE` finding with rule code and version; `MULTI` never conflicts (`docs/architecture.md` §5; `ContradictionDetector.java`).

### Q54. How many predicates are registered, and how do they split?
Answer: 28: 10 `SINGLE` (`reports_to`, `headquartered_in`, `parent_organization`, `subsidiary_of`, `founded_by`, `chief_executive`, `located_in`, `part_of`, `succeeded_by`, `preceded_by`), 15 `MULTI`, 3 `BOUNDED` (`sources_from` ≤3, `operated_by` ≤2, `regulated_by` ≤4); unknown predicates resolve to permissive `MULTI`, failing toward "no contradiction" (`PredicateSemanticRegistry.java`).

### Q55. What is the demo-corpus contradiction result?
Answer: All 5 planted conflicts detected (including `reports_to` Delacroix→Aurelia vs →Brightwater) and none of the 26 multi-valued relations flagged; the final validation also reports 10 contradictions on the rebuilt corpus with all 5 planted ones detected (`docs/evaluation.md` "Contradiction detection"; `docs/limitations.md` §9 phase 18).

### Q56. What real bug did the evaluation catch between the registry and the provider?
Answer: `chief_executive` was registered and planted in the corpus but absent from the offline provider's vocabulary, making its contradiction silently unfindable — fixed by `PredicateVocabularyConsistencyTest` asserting both directions match (`docs/evaluation.md` "Contradiction detection").

## 13. Council FSM and the human chair

### Q57. What are the three personas, and what does each argue?
Answer: HAWK (strongest supported reading), DOVE (most cautious reading with material uncertainty), SKEPTIC (tests positions against the machine-evidence brief) — each citing only supplied passage/machine-fact ids, all as JSON-only arguments to the chair (`Prompts.java` `hawkSystem`/`doveSystem`/`skepticSystem`).

### Q58. What is the debate state machine?
Answer: Pure `DebateEngine.decide(state, event, round, maxRounds)`: `CREATED → ROUND_ACTIVE → AWAITING_CHAIR → (next round | SYNTHESIZING) → COMPLETED`, plus `ABORTED`; default ceiling 3 rounds; illegal events throw 409; concurrent advances resolve to exactly one winner via conditional `UPDATE … WHERE state = :expected` (`DebateEngine.java`; `docs/architecture.md` §5).

### Q59. What does the human chair do, concretely?
Answer: Weights each argument 1–5 (`validateWeight` rejects 0 and 10 as silencing/domination), with the chair's name attached; weights steer the synthesis report, and debate 4's weights are shown live in the demo (`DebateEngine.java` lines 92–111; `docs/COLLEGE_DEMO_SCRIPT.md` §6–8).

### Q60. Why is the Council shown as a persisted artifact rather than run live?
Answer: Each persona takes ~2–7 min on CPU (180 s budget + 30 s grace; debate 4's round-1 HAWK timeout is recorded as a named failure, not a crash), a full 3-round debate took ~14 min wall, and synthesis ~7.6 min — so debate 4 / report 1 are replayed from stored data with timestamps stated (`docs/SHOWCASE_GUIDE.md` "Timings"; `docs/COLLEGE_DEMO_SCRIPT.md` §6–8).

### Q61. What does the synthesis report guarantee?
Answer: Every block (`FINDING`/`DISAGREEMENT`/`MACHINE_RECORD`/`UNRESOLVED`/`RECOMMENDATION`) carries argument, machine-fact, and passage ids; it reports what remains unresolved without settling it; debate 4's report concludes the subsidiary relationship "remains unresolved" with 5/5 citations valid (`Prompts.java` `synthesisSystem`; `docs/SHOWCASE_GUIDE.md` step 10).

## 14. Human-in-the-loop approval

### Q62. How does the approval queue work?
Answer: Proposals sit `PENDING`/`PROPOSED` until a verifier approves or rejects each; approval promotes into trusted state and is recorded as an audit row naming person and moment; rejection is permanent and recorded; double-approval is a conflict (`README.md`; `docs/SHOWCASE_GUIDE.md` steps 4–5).

### Q63. Can the demo approve items live on stage?
Answer: Yes — corpus 1 carries 36 `PROPOSED` items for a live approve/reject, while corpus 20's 7 approved claims (ids 306–312, 0 triples) are shown to explain the quarantine honestly (`docs/COLLEGE_DEMO_SCRIPT.md` §3–4).

## 15. Glass Box tracing

### Q64. What does the Glass Box record, and what does it refuse to show?
Answer: Observable execution only — request, response, validation, state changes, human actions — tagged `ENGINE`/`LLM`/`HUMAN` in execution order with model, prompt version, timings, evidence, rule output, fusion arithmetic, and errors; it never shows hidden chain-of-thought, by design (`docs/architecture.md` §§1, 10; `docs/COLLEGE_DEMO_SCRIPT.md` §1–2).

### Q65. How is trace ordering made safe under concurrency?
Answer: Step sequence numbers are allocated by the database (`trace_runs.step_seq` via `UPDATE … + 1` in the same transaction, migration V6) after the Java `max+1` scheme collided across three persona threads; the audit-write try/catch sits outside the transactional boundary so a failed audit cannot poison its caller (`docs/architecture.md` §4.4).

### Q66. Is the Glass Box replay real or reconstructed?
Answer: Real: `GlassBoxReplayIntegrationTest` proves replay renders stored snapshots even after domain mutation, and the demo replays debate 4's run (ENGINE transitions, LLM persona calls with model + prompt version, HUMAN chair weights) from stored data with Play/Pause/Next and no re-inference (`docs/limitations.md` §9 phase 13; `docs/COLLEGE_DEMO_SCRIPT.md` §10–11).

## 16. Performance

### Q67. What does the committed performance baseline say?
Answer: Single-laptop, fake-fixture baseline (`docs/performance.md`): slowest read is trace list at 1044 ms; PageRank 258 ms cold / 111 ms warm (JVM + buffer pool, no app cache); document write path 471 ms upload + 6193 ms wall to `AWAITING_APPROVAL` including polling slack — explicitly not a scalability claim and containing no model latency.

### Q68. What are the real-model CPU latencies quoted on stage?
Answer: Single-sample CPU timings from `docs/SHOWCASE_GUIDE.md` "Timings to quote on stage": one extraction call 24–46 s; one verification verdict ~115–180 s (≈2–3 min, corroborated by per-item `latencyMs` 114930–181983 in the verification run JSON); one Council persona ~2–7 min; full 3-round debate ~14 min wall; one synthesis ~7.6 min; one grounded chat ~3 min (the brief's "~1–3 min" phrasing is looser **— quote ~3 min**).

### Q69. Why is real inference so slow here, and is that a defect?
Answer: `qwen2.5:7b` runs on CPU via Ollama with sequential per-chunk calls (one model call per chunk, up to 3 retries); the loadtest probe notes 276 s wall for 2 documents / 8 chunks versus 6193 ms for a 6-chunk fixture document — expected, not a regression (`eval/runs/loadtest-bulk-users-1000.json`).

### Q70. What was not measured performance-wise?
Answer: Model latency/retry/cost distributions, debate/synthesis/chat latency distributions, any concurrent load, and approval/verification/adjudication latency (a short-lived JWT expired mid-measurement) — all listed as gaps in `docs/performance.md`.

## 17. Testing

### Q71. How many backend tests exist, and what do they include?
Answer: 259 tests, 0 skipped (`README.md` "End-to-end tests" command block); they include 27 integration tests against real MySQL containers (11 schema/migration, 10 restart-recovery, 6 Glass Box replay) plus unit suites such as `PredicateVocabularyConsistencyTest`, `PromptInjectionTest` (21 tests), `RestartRecoveryIntegrationTest`, and `SchemaContractChecker` tests. (The limitations doc's older "248/248" line is stale **— quote 259**.)

### Q72. What end-to-end scripts exist beyond unit tests?
Answer: 23-step API smoke walk (`smoke-test.ps1`), 78-check Council lifecycle (`council-test.ps1`: convene → 3 rounds → chair weights → ceiling → synthesis → report → Glass Box), security probe, 127-check auth sweep, contract check, prose seeding, and both extraction-eval harnesses (`README.md`).

### Q73. What does the contract check verify?
Answer: Live JSON against `frontend/src/api/types.ts` in both directions plus the page envelope; regenerated `docs/api.md` records 74 operations / 66 paths with remaining untyped endpoints marked. Measured runs printed "19 endpoints agreed, 4 skipped" and "18 agreed, 5 skipped" (skips are empty collections, not failures); quote the count from the run you actually performed.

### Q74. Was the UI ever driven in a real browser?
Answer: Yes for the showcase: `scripts/showcase-rehearsal.js` drives the 12 demo steps through the real UI with `playwright-core` Chromium (console-error and failed-request capture, screenshots to disk), read-only against application state. Verified: **5 consecutive 17/17 passes**, including one after a full Docker restart, with zero console errors and zero 401s on the final runs.

### Q75. What does grounded chat guarantee, and what is the live example?
Answer: `CHAT_V1` answers only from supplied evidence passages + graph facts with mandatory citations and a `sufficient_evidence` flag, abstaining plainly otherwise; the staged question "Who directs Meridian Research Institute in September 2026?" answers "Dr. Asha Rao" with chunk 207 + 210 citations (saved session 8, ~175 s on CPU), and "What is the Route 7 ticket price?" abstains (`Prompts.java` `chatSystem`; `docs/COLLEGE_PROJECT_FINALIZATION.md` P1; `docs/COLLEGE_DEMO_SCRIPT.md` §9–10).

## 18. Limitations (say all of them honestly)

### Q76. Why did corpus 20 produce zero triples?
Answer: Docs 51–55 completed with 0 triples, 1–3 claims, 1–2 quarantined chunks each: the CPU model emitted verbs outside the 28-term registry (e.g. quarantine row 64: unknown predicate `states`), so whole chunk responses — including valid claims — were quarantined. Verdict: pipeline working as designed; no registry change, no prompt tuning, no manufactured triples; the graph step uses corpus 1 instead (`docs/COLLEGE_PROJECT_FINALIZATION.md` P0; `docs/COLLEGE_DEMO_SCRIPT.md` §3–4).

### Q77. Why is verification "8 items, not a benchmark"?
Answer: 2/4 fresh scored items (0.5 accuracy) plus 4/4 persisted = 8 items total with 6 principled skips (already-`VERIFIED` claims, texts not found); V7/V10 misses were analysed as judge-reasoning gaps and never re-labelled; the dataset limitation string travels in the JSON itself (`SHOWCASE_GUIDE.md`; verification run JSON).

### Q78. Why is there no real-model extraction P/R/F1?
Answer: The 41-chunk prose eval timed out twice on CPU, so no extraction precision/recall/F1 from the real model exists; the fixture scores 0.0000 across the board on prose (correctly — it only pattern-matches canonical sentences), and entity-resolution quality plus machine-vs-human verdict agreement are unmeasured (`docs/SHOWCASE_GUIDE.md`; `docs/limitations.md` §1).

### Q79. Why is there no public deployment?
Answer: The GitHub Pages site ships the interface only (`VITE_STATIC_ONLY=true` banner; sign-in fails at the network layer, which is the truthful outcome); all state lives in Spring + MySQL, which a static host cannot run. Local/demo limitations also noted: no TLS, published dev ports, and dev fallbacks **(unverified — confirm exact demo-network details before quoting)** (`README.md`; `docs/limitations.md` §11).

### Q80. What breaks at scale, by the project's own admission?
Answer: SSE debate events are single-instance in-memory (a second backend would strand clients); `supportCount` can over-count by one under contention (advisory only, gates nothing); 15-minute tokens with no refresh force re-login; documents can never be deleted (409 for everyone, protecting provenance); retrieval topping out at Recall@5 ≈ 0.9–1.0 on 11 queries says nothing about a large corpus (`docs/limitations.md` §§5–6, 10; `docs/architecture.md` §4.2).

## 19. Future scope

### Q81. What is the highest-value next step?
Answer: GPU inference followed by the resumable 41-chunk real-model extraction eval — the two unlocks that convert "pipeline proven, quality unknown" into measured quality (`docs/COLLEGE_DEMO_SCRIPT.md` §11–12 "Future"; `docs/SHOWCASE_GUIDE.md` "deliberately NOT claimed").

### Q82. What retrieval improvements are queued, in order?
Answer: A reranker scoring passages against the question (expansion alone provably trades precision for recall); chunk-level answering via smaller candidates; entity-aware boosting reusing entity resolution; and a several-hundred-query hand-checked gold set including paraphrases and unanswerables (`docs/evaluation.md` "What would improve it").

### Q83. What reasoning improvements are planned?
Answer: Temporal reasoning in the judge (the V7/V10-class misses), plus production hardening per the audit: refresh-token flow, shared SSE broker, typed remaining endpoints, and CI regression thresholds for the retrieval benchmark (`docs/COLLEGE_DEMO_SCRIPT.md` §11–12; `docs/evaluation.md` "Unfinished").

### Q84. What would you do differently if you started over?
Answer: Per the architecture record: never expose untyped `Map` responses (they hid the admin-page and PageRank-blank-column bugs), never hold transactions across model calls, never trust `validate` for unmapped columns, and never let a demo corpus flatter the extractor — the prose gold set with 59% negatives exists because the canonical one did (`docs/limitations.md` §§1, 7; `docs/architecture.md` §§2, 6).

---

## Appendix — where each number comes from

| Claim | Source file |
|---|---|
| 259 backend tests | `README.md` |
| 31 security-probe checks / 127 auth-sweep checks | `README.md`, `docs/limitations.md` §9 |
| 28/28, 19 endpoints, 17/17 rehearsal | `docs/COLLEGE_DEMO_SCRIPT.md` §11–12 (scope differs — confirm) |
| 74 operations / 66 paths OpenAPI | `docs/api.md`, `docs/limitations.md` §9 |
| Retrieval 0.6364 / 0.9091 / 0.9091 / MRR 0.8333, 11 queries | `docs/evaluation.md`, `docs/limitations.md` §2 |
| Canonical fixture P/R/F1, prose 0.0000s | `docs/evaluation.md`, `docs/limitations.md` §1 |
| Verification 2/4 + 4/4 = 8 items, 0.5 accuracy | `eval/runs/verification-verification-gold-v1-20261010-103803.json`, `docs/SHOWCASE_GUIDE.md` |
| CPU latencies (24–46 s, ~2–3 min, 2–7 min, ~7.6 min, ~3 min) | `docs/SHOWCASE_GUIDE.md` "Timings to quote on stage" |
| 276 s / 8-chunk real vs 6193 ms fixture | `eval/runs/loadtest-bulk-users-1000.json`, `docs/performance.md` |
| 28 predicates; 27 nodes / 46 edges; 7 claims 306–312; debate 4 / report 1 / session 8 | `PredicateSemanticRegistry.java`; `docs/SHOWCASE_GUIDE.md`; `docs/COLLEGE_DEMO_SCRIPT.md` |
| Rate limits 10/min auth, 30/min LLM | `backend/src/main/resources/application.yml` |
| PageRank 0.85 / 50 iters / 1e-6 | `backend/src/main/resources/application.yml` |
