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

**Status: `PENDING REAL MODEL`.** Harness built, verified end to end, and proven
able to refuse to fake a result. This remains the single blocking limitation for a
release decision.

The retrieval benchmark measures PRISM's own retriever over a fixed corpus. That
is a genuine measurement and it caught a real defect (section 3), but it says
nothing about extraction quality, because extraction in every run so far has been
done by `FakeLlmClient`.

### Two datasets, because one could not answer the question

| | canonical | prose |
|---|---|---|
| File | `eval/gold-extraction.tsv` | `eval/prose-gold-v1.json` |
| Version | frozen | `prose-gold-v1` |
| Labels | 99 triples, 23 documents, 15/28 predicates | 40 triples, 46 claims, 81 labelled sentences, 10 documents, 19/28 predicates |
| Negatives | none | **48 of 81 (59%)** |
| Measures | transcription | **extraction from prose** |
| Harness | `LlmExtractionEvaluationService` | `ProseExtractionEvaluationService` |

The prose set exists because the canonical one flatters any extractor: it states
every fact as `Subject predicate Object`, so a model scores near 100% by pattern
matching. The prose corpus is real sentences — pronouns, multi-clause, passive
voice, negation, hedging, temporal statements, several relations per sentence,
irrelevant information, and text shaped like instructions — with 59% of its
labelled sentences stating that **nothing** should be extracted. Those negatives
are what make precision observable.

Every label records the exact source sentence, and `ProseGoldSetTest` asserts that
sentence appears in the document it is attributed to. That check immediately
caught two fabricated details in my own labels, which is exactly the failure it
exists to prevent: an invented word in a label silently depresses a model's
measured recall and nothing in the resulting number looks wrong.

### Measured, against the offline fixture

| | canonical | prose |
|---|---|---|
| triples | P 0.9706 / R 1.0000 / F1 0.9851 | **P 0.0000 / R 0.0000 / F1 0.0000** |
| claims | P 0.3641 / R 0.6768 / F1 0.4735 | **P 0.0000 / R 0.0000 / F1 0.0000** |
| malformed | 0 of 23 | 0 of 41 |
| quarantine | 0 of 23 | 0 of 41 |
| ungrounded | not measured | 0 of 41 |

**The prose zeroes are the correct result and the most useful number here.** The
offline provider recognises only `Subject predicate Object` sentences, so pointed
at prose it finds 14 incidental occurrences of a predicate word and gets every one
wrong. That is the concrete demonstration that the canonical corpus was flattering
the fixture, and it is why the prose corpus was written.

**These describe a deterministic fixture and the pipeline around it. No model has
been evaluated.**

### The harness refuses to fake a result

`requireRealModel=true` while the offline fixture is active returns HTTP 412 with
the body token `REAL_MODEL_EXECUTION_REQUIRED`, and `scripts/llm-eval.ps1` exits
**4**. Not a warning in the body — a distinct status and a distinct exit code, so a
CI job cannot mistake "the harness refused" for "the harness passed". The token is
asserted stable by a test, because a CI job greps for it.

```bash
powershell -File scripts/llm-eval.ps1 -Username <v> -Password '<p>' -CorpusId <prose-corpus> -Dataset prose -RequireRealModel
```

The prose corpus is loaded by `scripts/seed-prose-corpus.ps1`, which uploads
through the real multipart endpoint so the real chunker and the real extraction
path both run.

### Still unknown, and must be treated as unknown

- triple and claim precision/recall/F1 for any real model, on prose;
- malformed-response and quarantine rates for a real model;
- how often a real model invents a source sentence;
- entity-resolution quality on real text — **not measured at all**, because
  resolving entities means writing them, which would corrupt the corpus being
  measured;
- agreement between machine verdicts and human verdicts. The offline provider
  produces no `SUPPORTED` verdict, so there is no positive class to score, and the
  separate verification gold set was not built in this pass.

The prose dataset is fictional prose written for the purpose. It contains no OCR
noise, tables, homonyms, aliases or transliteration variants, all of which make
real extraction harder. **Its figures are an upper bound, not a forecast**, and it
is not a statistically representative sample of real documents.

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

Fixed:

- PageRank and communities return typed records carrying the fields the UI was
  already rendering. They previously sent none of them, so the PageRank table
  showed three permanently blank columns and the communities table threw on
  `members.join`.
- **Every collection endpoint now returns one envelope.** There were three shapes
  for one concept: `/api/documents` returned a bare array with the total
  discarded, `/api/admin/users` hand-built a map with a different key set, and
  quarantine used `{total, content}`. They all use `com.prism.common.PageResponse`
  now — `content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`.
  `DocumentService.list` returned `getContent()` and threw the total away, which
  is precisely why `/api/documents` had none.
- The quarantine listing is a typed `QuarantineRow` record rather than a map built
  from string literals.
- The traces list joined the envelope and gained a real Actors column. The Glass
  Box list rendered a permanent "-" from a field the server never sent; the
  server now computes `actorSummary` (e.g. `ENGINE+LLM`) from the stored steps
  in one grouped query.
- A new `GET /api/debates` list both the Contradictions and Reports pages
  called but which did not exist. The former hid its Councils section, the
  latter showed an error banner, both on a 404. It returns the shared envelope
  of debate responses without rounds or arguments; detail still carries those.
- `GET /api/verdicts/{id}` 500'd on every verdict (`LazyInitializationException`
  on the passage's document proxy). Detail and adjudication now run inside a
  transaction, and the contract check gained a verdict-detail case — the case
  the list-passing/detail-500 gap required.

**The admin page was broken by this and nobody noticed.** `AdminPage` called
`adminApi.users()`, typed it `UserSummary[]`, and rendered `users.data.length`
against a response with no `length`. It therefore showed "No users" and an empty
table for every account, for everyone, always. `scripts/contract-check.ps1`
reported 18/18 clean throughout, because `/api/admin/users` was not one of the
endpoints it checked. The contract check now asserts the envelope for all three,
and is status-aware so an ADMIN-gated endpoint returns a refusal to a VERIFIER
run reports `skip` rather than six missing fields.

Still untyped, and therefore still documented only as `object`: chat session
detail, chat answer, verify-all, the approval queue, triples/claims/verdicts/
contradictions list responses, the trace detail and X-Ray views, argument
weighting, synthesis receipts, the debate FSM, and admin status. The frontend
types for these are verified against live JSON by the contract check, so they are
known correct today - but they are not enforced by a schema, and a backend change
would not be caught by the compiler. `docs/api.md` is regenerated from the live
spec (74 operations, 66 paths), with each remaining untyped endpoint marked.

Spring Data's `Page` is deliberately never exposed. Its serialisation is unstable
across versions and carries an internal `pageable` object, so a client binding to
it couples itself to Spring.

---

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

## 8. Features that cannot be demonstrated offline

`VERIFIED_ONLY` graph scope returns no nodes with the offline provider, because
no verdict is ever `SUPPORTED` (┬º1). A person evaluating this system offline
cannot see the verified-only view populated.

This is a gap in the demonstration, not in the logic: the scope correctly filters
to triples backed by a settled `SUPPORTED` verdict, and there are none. The
honest fix is a provider that can produce a `SUPPORTED` verdict when retrieved
evidence genuinely supports a claim ÔÇö which is real-LLM territory, and therefore
blocked on ┬º1.

The same applies to any UI that reads as though the system found nothing, where
the truth is that the offline provider found nothing. That distinction is worth
checking before concluding anything is broken.

---

## 9. Items in the hardening plan not reached

For completeness, so the gap between this document and the plan is explicit.
Everything below is classified; nothing here is assumed.

| Phase | State |
|---|---|
| 1 - repository audit | **Done.** 13 defects found and fixed; see the release report. |
| 2 - predicate-aware query expansion | **Done**, with the `supply` regression recorded (§3). |
| 3 - real LLM validation workflow | **Harness done, real-model execution not.** Two datasets (`eval/gold-extraction.tsv` canonical, `eval/prose-gold-v1.json` prose), two harnesses (`LlmExtractionEvaluationService`, `ProseExtractionEvaluationService`), `scripts/llm-eval.ps1` and `scripts/seed-prose-corpus.ps1`. Both verified end to end against the offline provider. **No real model has been evaluated** (§1). |
| 4 - expand retrieval gold set | **Not done**, deliberately (§2). The 11 hand-checked queries remain the frozen baseline. |
| 5 - Testcontainers / CI | **Resolved** (§4). 6 migration integration tests run, 0 skipped. |
| 6 - database contract audit | **Done this pass.** `SchemaContractChecker` compares the live schema against the JPA metamodel and flags an unmapped NOT NULL column with no default, a missing mapped column or table, a missing critical index and a missing critical foreign key. `contractCheckDetectsTheHistoricalDefect` recreates the real defect — a `NOT NULL` column with no default on `report_block_citations` — and proves the check fails. 29 tables, 374 columns, 364 mapped, 0 failures on the real schema. Flyway remains authoritative; Hibernate schema generation is still not used. |
| 7 - authentication hardening | **Decided and documented** (§5). |
| 8 - SSE review | **Not done** (§6). |
| 9 - security penetration pass | **Done.** 31 checks in `scripts/security-probe.ps1` (cross-corpus leak found and fixed) plus a full per-resource sweep in `scripts/auth-sweep.ps1`: **127 checks across documents, chunks, entities, triples, claims, verdicts, contradictions, debates, reports, chat sessions, trace runs and trace steps** — direct IDs, search/retrieval/pagination/nested references, invalid IDs, reverse direction, and error-body disclosure scans. **No leaks found.** Residual fixture rows per run (attacker user/corpus/document/session) are documented in the sweep report. |
| 10 - prompt injection tests | **Done at the deterministic layer.** `PromptInjectionTest` (21 tests): extraction guards plus verification (rule penalty/fusion/judge output), debate weights, synthesis citations, chat grounding, and taxonomy non-conflation. The prose gold set carries labelled injection bait. One genuine low-severity finding fixed: unescaped LIKE wildcards in chat triple lookup (bounded, same-corpus). Model-level susceptibility is unmeasured (§1). |
| 11 - failure recovery | **Done.** Provider timeout/429/5xx/permanent-failure, retry bounds, backoff cap, quarantine gate (existing) plus restart during extraction, duplicate extraction/approval/convene/start, concurrent debate advance, stale-run surfacing, and machine-verdict preservation (`RestartRecoveryIntegrationTest`, 10 integration tests; `RecoverySemanticsTest`, 12 unit tests). Found and fixed a real bug: orphaned jobs without a document looped PENDING forever instead of failing terminally. DB-interruption mid-write is covered by the transactional boundaries the tests pin (approval scan rolls back with the approval; synthesis is a single transaction), not by killing the database mid-test. |
| 12 - provenance audit | **Done.** Full ID-chain walk against live stored records in `docs/provenance-walk.md`: approved triple 57 from document 18 through chunk 61 to graph edge (7→1), claim 114 through verdict 6 and trace 212 to contradiction 9, debate 2, report 2, and synthesis trace 352. Found and fixed a 500 on `GET /api/verdicts/{id}` (lazy proxy outside a session; endpoints now transactional) and added the missing `GET /api/debates` list both pages called. No quarantined item and no human adjudication existed live, so those two hops are recorded as not-observed rather than assumed. |
| 13 - Glass Box audit | **Done.** `GlassBoxReplayIntegrationTest` (6 tests): replay shows stored snapshots after domain mutation, parent/child links with database-allocated seq ordering, actor/model/prompt/rule metadata round-trip, error recording with a secrets scan, structural no-chain-of-thought assertion over entity fields and `StepView` components, and run lifecycle. |
| 14 - API contract audit | **Done.** 22 endpoints agree in both directions plus four envelope assertions (documents, admin users, traces, debates) and a verdict-detail case added after the 500. The pagination inconsistencies are fixed; the traces list gained a real Actors column (was a permanent "-"). `docs/api.md` regenerated from the live spec (74 operations, 66 paths). 23 endpoints remain untyped `Map` responses, each marked **Untyped** in `api.md`. |
| 15 - frontend integration audit | **Partly.** Contract + typecheck + production build clean; admin-page and Councils-section shape bugs found and fixed. A real browser is not connected in this environment, so the 30-step journey was run as an HTTP walk plus source verification (`tsc` clean, all routes serve, every list has loading/empty/error states) — no crash found by any available method, but console-error and screenshot evidence remain **not-tested**. Two findings fixed from it: the missing debates endpoint and the verifier blank admin page (now an `AccessDenied` message). |
| 16 - performance baseline | **Done this pass**, as a baseline only. See [`performance.md`](performance.md). No figure in it includes model latency, because no model has been evaluated. |
| 17 - documentation | This file, plus `README.md`, `evaluation.md`, `performance.md`, `provenance-walk.md`. `api.md` regenerated from the live spec (74 operations, 66 paths; 23 endpoints marked **Untyped**); `architecture.md` updated for the envelope, the schema checker, and the prose harness. |
| 18 - final demo validation | **Done.** Corpus rebuilt from empty: 24 documents, 87 chunks, 58 triples approved, 6 verdicts, 10 contradictions, all 5 planted contradictions detected. |
| 19 - clean-checkout release check | **Done.** Fresh clone of this commit: tree clean, backend 248/248 `BUILD SUCCESS`, frontend `npm ci` + production build clean, clone tree still clean afterwards. |
| 20 - release decision | Reached. **NOT RELEASE CANDIDATE**, on §1 alone: `REAL_MODEL_EVALUATION_PENDING`. |

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
