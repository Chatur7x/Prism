# Known limitations

This file exists so that nothing about PRISM has to be taken on trust. It records
what the system does not do, what has not been measured, and which parts of the
stated design are still unrealised. It is written to be read by someone deciding
whether to rely on this system, not to reassure them.

Two rules govern its contents:

- **An offline-provider result is never presented as a model result.** The
  deterministic provider proves the pipeline is correct end to end. It says
  nothing whatsoever about how well a real model extracts. Those are different
  claims and they are never merged.
- **A benchmark result is always stated with its size.** Eleven queries is a
  regression guard, not an estimate of retrieval quality. Numbers appear with the
  number of queries that produced them, every time.

---

## 1. No real model has ever been evaluated

**Status: harness built and verified; real-model execution not performed.** This is
the single blocking limitation for a release decision.

The retrieval benchmark measures PRISM's own retriever over a fixed corpus. That
is a genuine measurement and it caught a real defect (§3), but it says nothing
about extraction quality, because extraction in every run so far has been done by
`FakeLlmClient`.

**The measurement harness now exists and is verified end to end:**

- `eval/gold-extraction.tsv` — 99 hand-checked labels over 23 documents and 15 of
  the 28 registered predicates. Each row records the sentence it was read from and
  `GoldExtractionSetTest` asserts that sentence appears verbatim in that document,
  so a fabricated label fails the build. The set is frozen.
- `LlmExtractionEvaluationService` + `POST /api/llm-evaluation/extraction` — runs
  the production extraction path (same prompt, same retry policy, same parser,
  same validator) over every labelled chunk. Read-only: persists nothing, creates
  no trace run.
- `scripts/llm-eval.ps1` — writes a dated JSON report with provider, model, prompt
  version, temperature, max tokens, gold-set size, timestamp, matching rule and
  per-chunk outcomes.

Verified against the offline provider: triples P 0.9706 / R 1.0000 / F1 0.9851,
claims P 0.3641 / R 0.6768 / F1 0.4735, malformed 0/23, quarantine 0/23. **These
numbers describe a deterministic fixture and the pipeline around it, and say
nothing about any model.**

**What remains unknown, and must be treated as unknown:**

- whether a real model produces well-formed JSON at the required schema rate;
- how often it invents a source sentence (the grounding check rejects it, so the
  rate is measurable but unmeasured);
- extraction precision and recall against hand-labelled truth, on prose;
- entity-resolution quality on real text — not measured at all, because resolving
  entities means writing them, which would corrupt the corpus being measured;
- agreement between machine verdicts and human verdicts. The seeded corpus
  produces no `SUPPORTED` verdict from the offline provider, so there is no
  positive class to score.

There is also a **corpus** limitation that no amount of harness work fixes. The
demo corpus states every extractable fact in canonical `Subject predicate Object`
form and its prose is deliberately uninformative, so even a completed real-model
run against it would measure transcription, not extraction from natural prose. A
prose corpus has to exist before this limitation can be closed.

PRISM's response to malformed output is designed and tested — parse, schema,
semantics, then quarantine with a reason — but the *rate* at which a real model
triggers it is unmeasured. A model that quarantines 40% of chunks would be a
perfectly correct system and a useless one.

Until a real-provider run happens, no statement about model quality should be made
from this repository's numbers.

### The offline provider

`LLM_PROVIDER=fake` selects a deterministic client used for development, CI and
the demo. It is a **fixture, not a stub**: it performs real parsing, real schema
validation, real semantic validation, real grounding checks, and real quarantine,
so the pipeline is genuinely exercised. What it does not do is model language.

Its specific limitations, each of which shapes what the demo can demonstrate:

| Behaviour | Consequence |
|---|---|
| Recognises only `Subject predicate Object` sentences | Prose documents yield nothing. The demo corpus is written in that form for this reason. |
| Vocabulary is exactly the 28 registered predicates | A predicate outside the registry is quarantined, correctly. `PredicateVocabularyConsistencyTest` enforces the match in both directions. |
| **Never returns a `SUPPORTED` verdict** | Every verdict is `INSUFFICIENT_EVIDENCE`, so **the VERIFIED_ONLY graph scope is always empty**. This is a real gap in the demo: a headline feature cannot be demonstrated offline. See §8. |
| Deterministic, no sampling | Useful for tests, useless for measuring variance. |

It also now takes the whole capitalised run before the predicate as the subject.
It previously took only the first word, so "Meridian Group" was extracted as
"Meridian" and every multi-word organisation in the demo corpus carried a
truncated name into entity resolution and the graph. Found because the extraction
benchmark scored zero true positives against its own gold set.

### Making the fixture impossible to mistake for a model

The risk with an offline provider is not a crash, it is a number. A report
generated against the fixture is arithmetically valid and completely silent that
no model was involved, so the mode is stated three ways, because each fails
differently:

- **a startup banner** from `LlmModeReporter`, at WARN, so it is in the first
  thirty log lines rather than somewhere a scrollback search might miss;
- **`testMode`** on the provider description, which reaches the admin API and
  therefore anything that archives it. The real provider reports `testMode=false`
  explicitly, so a consumer can assert on it without knowing which provider it is
  talking to;
- **`corpusLimitation` and the provider banner in `scripts/llm-eval.ps1`**, which
  prints `PROVIDER: FAKE / TEST MODE` and exits 3 if every chunk was refused,
  since the score above that would be vacuous.

**There is no fallback from a real provider to the fixture.** The fixture bean
requires `prism.llm.provider=fake` explicitly; the real bean is `matchIfMissing`.
An unset or misspelt provider value therefore selects the real client and fails
loudly, rather than degrading to canned responses. `LlmProviderModeTest` asserts
both directions against the annotations themselves, since the annotation is what
the container consults — a future edit that added a `try`/`catch` around provider
construction would otherwise quietly reintroduce the silent downgrade.

The `VERIFIED_ONLY` consequence deserves emphasis because it is easy to mistake
for a bug. The scope returns no nodes, and that is *correct*: no verdict is
`SUPPORTED`, so no triple qualifies. A reader checking the endpoint will see an
empty graph and should read the verdicts table before concluding anything is
broken.

---

## 2. The retrieval benchmark is eleven queries

**Status: expanded set not produced.** The gold set was not enlarged, because it
could not be enlarged honestly.

`scripts/gold-set.txt` holds 11 queries, each with a gold chunk chosen by reading
the chunk and confirming it is the passage someone answering the question would
need. None was chosen by looking at what the retriever returned.

Producing 50–100 queries requires labelling 50–100 passages by hand. Generating
them with a language model and then measuring retrieval against that generated
set would produce a confident number with no relationship to whether the system
works — the labels would be as wrong as the retrieval, and the metric would
measure agreement with the generator. **That was not done, and the limitation is
recorded here rather than papered over with a larger, weaker number.**

The measured figures, at the size they were actually measured:

| Metric | Value |
|---|---|
| Queries | 11 |
| Recall@1 | 0.6364 |
| Recall@3 | 0.9091 |
| Recall@5 | 0.9091 |
| MRR (over queries whose passage was retrieved) | 0.8333 |
| Not retrieved at all | 1 |

Eleven queries means a single query moves any metric by roughly nine percentage
points. These numbers are a **regression guard**: they detect a change, and they
are not an estimate of retrieval quality for any corpus.

The benchmark is now idempotent — re-running the same gold set replaces its run
rather than adding to it — so the figures above are reproducible. See §3.

---

## 3. Query expansion: what it fixed and what it cost

`QueryExpander` maps question wording to the token a corpus uses. It is
deterministic, versioned (`EXPAND_V1`), and recorded on every retrieval result
and every benchmark row. It is not a model: retrieval feeds the verification
judge, so a prompt injected into a document must not be able to steer which
passages a verdict may cite.

Measured on the same corpus, same gold set, same moment:

| | Recall@1 | Recall@3 | Recall@5 | MRR |
|---|---|---|---|---|
| before (no expansion) | 0.6364 | 0.8182 | 0.9091 | 0.8033 |
| after (`EXPAND_V1`) | 0.6364 | 0.9091 | 0.9091 | 0.8333 |

Query-level, which matters more than the totals:

| Query | Before | After |
|---|---|---|
| Who does Calder collaborate with | not retrieved | **rank 2** |
| Who does Orion Systems supply | rank 5 | **rank 6 — out of the top-5 window** |
| the other nine | unchanged | unchanged |

**One fixed, one traded.** Recall@3 and MRR improved; Recall@1 and Recall@5 did
not move. Adding `supplies` as a relevance term promoted three supply-related
chunks above the gold one. That is the ordinary precision/recall trade of adding a
term to a bag-of-words query, and it is recorded here rather than reported as an
unqualified win.

The remaining miss, `Who does Orion Systems supply`, is not fixed. Diagnosing it
properly needs either a reranker or a larger gold set; neither is in scope here.

---

## 4. Testcontainers pinned to Docker API 1.44

**Status: resolved.** `MigrationIntegrationTest` runs 6 real integration tests,
0 skipped, against a real MySQL container.

```
NpipeSocketClientProviderStrategy: failed with exception BadRequestException (Status 400)
```

The diagnosis: Testcontainers 1.21.3 *shades* docker-java, and the shaded
`RemoteApiVersion` enum stops at `VERSION_1_44`. Docker 29.6.2 speaks API 1.55 and
answers anything below its floor with a 400 and an all-empty body, which
docker-java surfaces as `BadRequestException` during the daemon probe. The
container never started, so the container-missing skip took over and the 6 tests
reported green without executing anything.

1.21.3 is the current release, so there is no version to bump to. The fix is the
library's own supported knob: pin the negotiated API version to **1.44**, which is
the highest the library can express and the lowest Docker 29 accepts, so it works
on both old and new daemons. It is a `pom` property passed to surefire, so
`mvn test` needs no manual flag and CI inherits it. Overridable with
`-Ddocker.api.version=…`.

Making the tests run immediately exposed two defects **in the tests themselves**,
which had never executed before:

- `requiredIndexesExist` selected two columns and asked `JdbcTemplate` for a
  single `String`, which throws `IncorrectResultSetColumnCount`, while the
  assertion beneath it matched on a `table|index` label the query never produced.
- `hibernateMappingMatchesSchema` asserted `COUNT(*) FROM users` is zero. That is
  simply wrong: `AdminBootstrap` creates the first admin on startup, so the count
  is 1 before anything else runs.

Neither was weakened to make it pass. Both now assert something true.

**Residual limitation:** a daemon older than Docker 25 does not know API 1.44 and
would fail the probe. CI pins the pairing; a developer on a pre-25 daemon needs
`-Ddocker.api.version=` set to a version that daemon accepts.

---

## 5. Refresh tokens are not implemented

**Status: documented limitation, deliberately not fixed.**

`POST /api/auth/refresh` requires a still-valid access token, so a client whose
access token has expired cannot refresh it and must log in again.

Implementing refresh properly means a separate token type, hashed storage,
rotation with reuse detection, expiry, revocation, and logout invalidation. That
is a substantial addition to the authentication surface, and doing it *superficially*
— an unrotated long-lived token, for instance — would be worse than not having it,
because it would look like the capability exists.

**Decision: not justified for this release.** For the deployment this was built
for — single-tenant internal use, demo and evaluation — re-authenticating is an
acceptable cost. Anyone exposing this to untrusted users must treat it as a
blocking gap, not a footnote. The 15-minute access-token lifetime bounds the
window.

Token storage in the browser is `localStorage`, which is readable by any script on
the origin. That is a deliberate trade for a system that serves a single origin
from the same host as its API; it is **not** acceptable if the frontend is ever
served from a different origin than the API, or alongside untrusted third-party
scripts.

---

## 6. SSE is single-instance

**Status: documented limitation. A review was not completed.**

Debate events stream over Server-Sent Events from an in-process emitter registry.
This works correctly for one instance. It does not work across instances: a client
connected to instance A receives nothing when the debate advances on instance B.

No Redis or Kafka was introduced to remove this, and none should be, purely to
delete a line from a limitations document. If horizontal scaling is ever required,
the emitter registry is the single component that must become shared, and it is
deliberately isolated so that it can be.

**Not verified:** the review asked for — cleanup on completion, on timeout, and on
disconnect; concurrent subscribers; emitter leaks; event ordering; a subscriber
arriving after synthesis completed — was **not carried out**. Those are open
questions, not passed checks.

---

## 7. The API has untyped responses

**Status: partially fixed.** 21 endpoints returned `Map<String, Object>`, which
renders in OpenAPI as a bare `object`. That is why the frontend types were
hand-written and why `scripts/contract-check.ps1` exists.

Fixed so far: pagerank and communities now return typed records carrying the
fields the UI was already rendering (they previously sent none of them, so the
PageRank table showed three permanently blank columns and the communities table
threw on `members.join`).

Still untyped, and therefore still documented only as `object`: chat session
detail, chat answer, verify-all, the approval queue, triples/claims/verdicts/
contradictions/traces list responses, the trace detail and X-Ray views, argument
weighting, synthesis receipts, the debate FSM, and admin list/status. The
frontend types for these are verified against live JSON by the contract check, so
they are known correct today — but they are not enforced by a schema, and a
backend change would not be caught by the compiler.

Phase 14 of the hardening plan covers the rest. It is not done.

---

## 8. Features that cannot be demonstrated offline

`VERIFIED_ONLY` graph scope returns no nodes with the offline provider, because
no verdict is ever `SUPPORTED` (§1). A person evaluating this system offline
cannot see the verified-only view populated.

This is a gap in the demonstration, not in the logic: the scope correctly filters
to triples backed by a settled `SUPPORTED` verdict, and there are none. The
honest fix is a provider that can produce a `SUPPORTED` verdict when retrieved
evidence genuinely supports a claim — which is real-LLM territory, and therefore
blocked on §1.

The same applies to any UI that reads as though the system found nothing, where
the truth is that the offline provider found nothing. That distinction is worth
checking before concluding anything is broken.

---

## 9. Items in the hardening plan not reached

For completeness, so the gap between this document and the plan is explicit:

| Phase | State |
|---|---|
| 1 — repository audit | **Done.** 13 defects found and fixed; see the release report. |
| 2 — predicate-aware query expansion | **Done**, with the `supply` regression recorded (§3). |
| 3 — real LLM validation workflow | **Harness done, real-model execution not.** `eval/gold-extraction.tsv` (99 verified labels), `LlmExtractionEvaluationService`, `scripts/llm-eval.ps1`. Verified end-to-end against the offline provider. **No real model has been evaluated** (§1). |
| 4 — expand retrieval gold set | **Not done**, deliberately (§2). The 11 hand-checked queries remain the frozen baseline. |
| 5 — Testcontainers / CI | **Resolved** (§4). 6 migration integration tests run, 0 skipped. |
| 6 — database contract audit | **Still open.** `ddl-auto: validate` plus the SQL cross-check in `architecture.md`. `validate` cannot detect an unmapped NOT NULL column — it was found by a runtime error, not by validation — so a dedicated schema contract check is still wanted and was **not** built in this pass. |
| 7 — authentication hardening | **Decided and documented** (§5). |
| 8 — SSE review | **Not done** (§6). |
| 9 — security penetration pass | **Done.** 31 checks in `scripts/security-probe.ps1`; it found a cross-corpus data leak. Re-run green after this pass. |
| 10 — prompt injection tests | **Done** at the deterministic layer (`PromptInjectionTest`, 9 tests). Model-level susceptibility is unmeasured (§1). |
| 11 — failure recovery | **Partly done.** Provider timeout / 429 / 5xx / permanent-failure, retry bounds, backoff cap, and the malformed-response quarantine gate are now tested (`LlmFailureRecoveryTest`, 10 tests; `MalformedResponseQuarantineTest`, 11 tests). **Not tested:** database interruption mid-work, application restart during extraction or synthesis, duplicate extraction/approval/debate start, and concurrent debate advance. |
| 12 — provenance audit | **Not done.** |
| 13 — Glass Box audit | **Partly.** Found and fixed a trace-detail contract bug that made the Glass Box header render `Trace #undefined`. Replay-uses-stored-data not verified. |
| 14 — API contract audit | **Partly** (§7). 18 endpoints agree in both directions; `docs/api.md` not regenerated from the live schema. Two pagination-shape inconsistencies found and not yet fixed. |
| 15 — frontend integration audit | **Partly.** 18 endpoints verified by contract check; not exercised in a browser. |
| 16 — performance baseline | **Not done.** No timings recorded. |
| 17 — documentation | This file, plus updates to `README.md`, `api.md`, `evaluation.md`. |
| 18 — final demo validation | **Done.** Corpus rebuilt from empty: 24 documents, 87 chunks, 58 triples approved, 6 verdicts, 10 contradictions, all 5 planted contradictions detected. |
| 19 — clean-checkout release check | **Not done.** |
| 20 — release decision | Reached. See the release report: **NOT RELEASE CANDIDATE**, on §1 alone. |

---

## 10. Standing constraints

These are properties of the system, not gaps. They are listed so a reader does
not have to infer them from the code.

- **`fusedScore` is a ranking score, not a calibrated probability.** It orders
  candidates. It does not mean "87% likely to be true", and it must not be shown
  to a reader as a percentage.
- **`SOURCE_MISSING` is never conflated with `CONTRADICTED` or
  `INSUFFICIENT_EVIDENCE`.** A source that could not be found is a different fact
  from a source that was found and disagrees.
- **The LLM's score, the rule penalty, the fused score, and the evidence status
  are four separate values** and are stored separately. They are never collapsed
  into one number.
- **A machine decision is preserved when a human overrides it.** Overriding is an
  event with its own actor and reason, not an edit.
- **The Glass Box shows observable execution, never hidden reasoning.** Prompt
  version, model, timings, retrieved evidence, rule output, fusion arithmetic,
  state transitions, human actions, citation validation and errors. There is no
  chain-of-thought to show, by design.
- **Unknown predicates resolve to `MULTI` cardinality**, so they never produce a
  relation conflict. The system fails toward "no contradiction".
- **Documents are never deleted.** Approved knowledge and the verdicts citing it
  depend on that provenance, so the endpoint refuses unconditionally for everyone,
  owner included, with a 409.
