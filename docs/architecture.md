# PRISM architecture

This document records the decisions that shape the system and, where a decision
looks obvious but is not, why it is what it is. It is written for someone about
to change the code, not for someone evaluating whether to build it.

---

## 1. The invariant

> **DETERMINISTIC JAVA OWNS ALL STATE. THE LLM ONLY PROPOSES. THE HUMAN VERIFIER IS THE FINAL AUTHORITY.**

Every design decision below is a consequence of this sentence. When a design
choice and the invariant disagreed during development, the invariant won.

Concretely, it means:

- A model response is **untrusted input** until it has been parsed, schema
  validated, semantically validated, persisted as a `PENDING` proposal, and
  approved by a person.
- Malformed output is **quarantined and retained verbatim**, never silently
  dropped and never repaired into plausibility. It is shown on the document
  page. A system that quietly cleans up model mistakes cannot be audited for them.
- There is no code path that sets a proposal's status to `APPROVED`. Approval is
  the exclusive privilege of the human workflow.
- A machine verdict is never overwritten by a human one. `Verdict` carries
  `machineVerdictType`, `humanVerdictType`, `adjudicationState`, and `overridden`
  separately, and the superseded verdict is retained in `verdict_history`.
- The Glass Box shows **observable execution only**: the request, the response,
  the validation applied, the state changes, and every human action. It does not
  and cannot show a model's hidden reasoning, which is not available to show.

---

## 2. Layering

```
Controller  →  Service  →  Engine  →  Repository
```

- **Controllers** hold no business logic. They authenticate, authorise, delegate,
  and map to a response record. A controller that decides something is a bug.
  Collection endpoints that page return the one `PageResponse<T>` envelope
  (`content, page, size, totalElements, totalPages, hasNext`) — currently
  `GET /api/documents`, `GET /api/admin/users`, and
  `GET /api/documents/{id}/quarantine` — rather than Spring Data's `Page`,
  whose serialisation couples clients to Spring internals. The older
  `{content, total, page, size}` map shape on the remaining list endpoints
  predates it and is unmigrated, not a second standard.
- **Services** orchestrate and own transactions.
- **Engines** are deterministic, pure or near-pure, and unit-testable without a
  Spring context or a database: `ClaimRuleEngine`, `DebateEngine`,
  `EntityResolver`, `PredicateSemanticRegistry`, `ConfidenceFusion`,
  `PageRank`, `CommunityDetection`, `SentenceChunker`, `ExtractionValidator`.
- **Repositories** hold queries only.

### Why transaction boundaries are extracted into separate beans

Spring's `@Transactional` is applied by a **proxy**. A method called from inside
its own class never passes through that proxy, so the annotation is silently
ignored — no exception, no warning, just no transaction.

This is not hypothetical. It has bitten this codebase in four distinct places,
each of which produced a real, reproducible failure:

| Location | Symptom |
|---|---|
| `EntitySupportCounter` (was inside `EntityStoreService`) | `No EntityManager with actual transaction available for current thread` |
| `DebateStateService` (was `applyTransition` inside `DebateService`) | same error; every debate 500'd |
| `ProposalWriter` (was inside `ExtractionPersistenceService`) | proposal writes ran with no transaction |
| `TraceRecorder` | see §4 |

The rule this produces: **any method that needs a transaction and is called from
within its own class goes into its own bean.** The existing instances are
`ProposalWriter`, `EntitySupportCounter`, `EntityStoreService`, `DebateStateService`,
`VerdictPersistenceService`, `ChatPersistenceService`, `BackgroundJobService`,
`DocumentStatusService`.

### Why no transaction spans an external call

`DebateService.start` and `advance` run three personas, which means model calls.
They are deliberately **not** transactional. Holding a database transaction open
across someone else's network latency pins a pooled connection for seconds per
persona, and under concurrency that is how a connection pool exhausts.

The consequence is that the one statement such a method *must* perform — the
conditional `UPDATE ... WHERE state = :expected` that makes a transition legal
exactly once — needs a transaction of its own. That is `DebateStateService`.

The same reasoning governs `DocumentStatusService`, `BackgroundJobService`, and
`ChatPersistenceService`.

---

## 3. Corpus isolation

`User → Corpus → Document → DocumentChunk` is enforced in **every** retrieval,
graph, verification, contradiction, debate, and chat operation.

`CorpusAccessService` is the single authority. No controller or service
reimplements the check.

### A bug worth documenting

`ContradictionRepository.findByIdAndCorpusId(id, corpusId)` was being called with
a **user id** as its second argument, in three places. The method name makes it
look like the isolation check; its second parameter is a *corpus* id.

It failed closed for nearly every caller — a 404 that reads like a missing row —
which is exactly why it survived. Had a user's id ever coincided with a corpus id
they did not own, it would have authorised a cross-boundary read.

The fix is the pattern to copy:

```java
Contradiction c = contradictions.findById(id)
        .orElseThrow(() -> ApiException.notFound("Contradiction", id));
access.requireAccessible(c.getCorpus().getId(), userId);
```

Two steps, both explicit. A lookup that takes a corpus id must never be handed a
user id, and the compiler cannot help you notice, so the call should not be able
to be written that way.

---

## 4. Concurrency

The ingestion pipeline processes a corpus's documents in parallel, and every
document mentions the same handful of entities. Those entity rows are therefore
the hottest contended rows in the system. Four distinct defects came out of that.

### 4.1 Duplicate entity insert

Two workers both miss the lookup and both insert. The unique index on
`(corpus_id, normalized_name)` rejects the second, and without handling, the whole
document fails.

`EntityStoreService.insertIfAbsent` treats the constraint violation as a *signal
that someone else won the race*, re-reads the winner's row, and carries on. It
runs in its own transaction: a constraint violation marks a transaction
rollback-only, so catching it in the caller's transaction would poison every
later statement on that connection.

### 4.2 Lost support counts

Two workers both load the entity, both increment `supportCount`, and the second
write fails on a stale `@Version` — or worse, succeeds against a stale read and
discards the first increment.

Fixed structurally, not with a retry loop: a single atomic
`UPDATE support_count = support_count + 1`. The database, not the application,
does the arithmetic, so there is no read-modify-write window at all.

`supportCount` is **advisory** — it orders the entity list and feeds the Skeptic's
brief, and gates nothing. The retry on transient lock failure can therefore
over-count by one, which is accepted deliberately: a marginal over-count on an
advisory counter is harmless where a lost document would not be.

### 4.3 Lock-wait timeouts

Entity resolution originally happened *inside* the transaction that then wrote
proposals, so the entity row lock was held for the entire write. A 24-document
corpus serialised onto a handful of rows and 14 of 24 documents died of
`Lock wait timeout exceeded`.

Resolution and writing are now separate phases. `ExtractionPersistenceService`
resolves every endpoint through short independent transactions, then delegates
the inserts to `ProposalWriter`, whose transaction never touches an entity row
for update. Ingestion of the 24-memo demo corpus went from 14 failures in
315 seconds to zero failures in 6 seconds.

### 4.4 Trace step sequence — the subtlest one

`trace_steps.seq` is unique per run and was assigned in Java as
`max(seq) + 1`. A debate round writes steps from three threads, so all three read
the same maximum and computed the same next value; the unique index rejected the
losers.

The visible symptom was much worse than a lost audit row.
`TraceRecorder.record` caught the violation to satisfy *"auditing must never break
the operation"* — but the catch sat **inside** the `@Transactional(REQUIRES_NEW)`
method:

1. The exception marks the transaction rollback-only.
2. The catch hides it and returns `null`.
3. The proxy throws `UnexpectedRollbackException` at commit — **outside the
   method body, outside the catch**.

The safety wrapper was itself the thing that failed the operation, and all three
personas of round one were recorded as failed arguments.

Both halves are fixed, and **either half alone is insufficient**:

- `trace_runs.step_seq` (migration V6) so the database hands out the number:
  `UPDATE step_seq = step_seq + 1`, then read back inside the same transaction.
- The try/catch moved **outside** the transactional boundary, so a genuinely
  failed audit write rolls back alone and cannot poison its caller.

`TraceStepRepository.maxSeq` was **deleted** rather than left unused. A
read-modify-write primitive sitting in a repository is an invitation to
reintroduce the bug.

### 4.6 The same trap, in the method written to avoid it

The fix above moved the transactional work out of `TraceRecorder.record` and into
`TraceStepWriter` — and then `record` called it, on `this`, in the same class.

Spring's transaction advice is proxy-based. `this.stepWriter` would have gone
through the proxy, but the original `this.recordStep(...)` did not, so
`REQUIRES_NEW` was silently ignored and the step write joined the caller's
transaction. For an approval, the caller is the long-lived approval transaction,
so the write held X locks on both `trace_runs` and `trace_steps` until that
transaction committed. The very next call, `finishRun`, *did* go through the
proxy, opened a second connection, and waited for a row the first transaction was
still holding.

Every approval failed after 50 seconds with a lock-wait timeout. Moving the
method into a separate bean took it to **474 ms**.

The general rule, now stated in each of these classes:

> **A transactional method must be called from outside its own class.** If it
> must live near its collaborators, the collaborators move into a new bean.

Eight beans exist for this reason: `ProposalWriter`, `EntityStoreService`,
`EntitySupportCounter`, `DebateStateService`, `DocumentStatusService`,
`BackgroundJobService`, `ChatPersistenceService`, `TraceStepWriter`,
`ArgumentPersistenceService`, `SynthesisPersistenceService`. A grep for
self-invoked `@Transactional` is worth running after any transaction change; it
finds real ones among a lot of false positives (calls to same-named methods on
other objects).

### 4.7 Writes that had no transaction at all

`SynthesisService.synthesize` is deliberately non-transactional — it makes a model
call and must not hold a connection across one. But its writes were on the same
non-transactional instance, so:

- A `@Modifying` state transition threw `No EntityManager with actual transaction
  available for current thread`. Synthesis had therefore **never worked**; the
  first time it was exercised end to end is when this surfaced.
- Even without that, the report, its blocks, its citations, the debate's
  completion, and the contradiction's resolution would each have committed
  separately. A report whose citations were lost, on a debate still marked
  SYNTHESIZING, on a contradiction still OPEN, is precisely the state an audit
  system must never reach.

`SynthesisPersistenceService` now does all of it in one transaction. It calls
`DebateRepository.transitionState` directly rather than going through
`DebateStateService`, because that bean's `REQUIRES_NEW` would suspend and commit
independently — the same guard, but here it cannot commit a COMPLETED debate for a
report that then fails to insert.

`ArgumentPersistenceService` had the same shape for the same reason: an argument
and its citations, in two implicit transactions, where the citations *are* the
argument's standing. An argument whose citations were lost looks exactly like one
that genuinely cited nothing.

### 4.5 Optimistic locking and detached entities

A detached entity carrying a stale `@Version` must never be written. `findById`
returns detached; `save` merges into a *new* instance whose version is the one it
was loaded with; a second save then throws `StaleObjectStateException`.

The pattern everywhere: **every state transition re-reads and writes inside one
short `REQUIRES_NEW` transaction.** `BackgroundJobService.requeueAfterRestart`
and `DocumentStatusService` are the canonical examples.

---

## 5. Determinism and its limits

### What is deterministic

- Contradiction detection: pure functions over approved facts with a `ruleCode`
  and `ruleVersion` recorded on every finding.
- Verdict scoring: `ConfidenceFusion` is a pure function. The LLM score, the rule
  penalty, the fused score, and the evidence status are four separate values and
  are never collapsed into one "confidence".
- PageRank: fixed damping factor, fixed iteration count. Reproducible across runs.
- The debate state machine: `DebateEngine.decide(state, event, round, maxRounds)`
  is a pure function. Transitions are conditional `UPDATE`s guarded on the
  expected state, so two chairs pressing "advance" produce one transition and one
  409.
- Claim review status. Approval is idempotent and double-approval is a conflict.

### What is not

The model. Everything model-shaped is a **proposal** until a human disposes of it,
and that is the point rather than a limitation to apologise for.

### `fusedScore` is a ranking score, not a probability

It is not calibrated against any ground truth, and it must not be read as "95%
chance this claim is true". It ranks candidates for a human to look at. The UI
presents it as a ranking score for exactly this reason.

### `SOURCE_MISSING` is never conflated

`SOURCE_MISSING` means the cited source does not exist in the current corpus.
`CONTRADICTED` means evidence was found and it disagrees. `INSUFFICIENT_EVIDENCE`
means evidence was found and neither supports nor refutes. Collapsing these would
turn "we could not check" into "we checked and it is false", which is the single
most damaging error this kind of system can make.

---

## 6. Persistence

### Flyway only

`ddl-auto: validate`, never `create` or `update`. Every schema change is a
numbered migration.

This is not dogmatism — `validate` caught 27 enum fields missing
`@Enumerated(EnumType.STRING)`, three `CHAR(64)` vs `VARCHAR(64)` mismatches, and
a missing `extraction_runs.version`, all of which would otherwise have surfaced
as a runtime failure or, worse, a silent coercion.

### What `validate` cannot see

`validate` compares the **entity mappings to the schema**. It cannot compare the
**schema to the mappings**, because a column no entity mentions has nothing to
compare against.

`report_block_citations.corpus_id` is `NOT NULL` with no default and had no
`corpus` field in its entity for the entire life of the project. It was found
only when synthesis was first executed, and every synthesis failed with
`Field 'corpus_id' doesn't have a default value`. Nothing else in the codebase
writes that table, and nothing validates it.

The manual check that found it is worth repeating when a table is added:

```sql
-- every NOT NULL column with no default
SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'prism' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL
  AND EXTRA NOT LIKE '%auto_increment%'
  AND COLUMN_NAME NOT IN ('created_at', 'updated_at');
```

Cross-reference that list against the entity's `@Column`/`@JoinColumn` names.
Scalar fields mapped by naming convention will show as "unmapped" to a naive
comparison, which is the noise; what actually matters is association columns, since
those *require* an explicit `@JoinColumn` and have no convention to fall back on.
Run against the current schema, `report_block_citations.corpus_id` is the only
real gap, and it is fixed.

Making this a permanent check is now done: `SchemaContractChecker` runs the
cross-check the manual query above describes — every `NOT NULL` column with no
default against every entity mapping — plus mapped-but-missing tables and
columns, missing critical indexes, and missing critical foreign keys. It reads
the schema and never repairs it, so the same class backs the integration test
(which recreates the historical `report_block_citations.corpus_id` defect and
proves the check fails), a CI job, or an admin endpoint. Two subtleties it
had to learn: join columns are not `BASIC` attributes, so a naive mapping scan
reports every foreign key as unmapped (70 false positives on the real schema),
and unannotated fields take their column names from the camelCase-to-snake_case
naming strategy, without which correct mappings read as missing columns.

### Conventions

- **No nullable primary key**, anywhere.
- Uniqueness uses `CHAR(64)` SHA-256 hash columns; entity mappings carry
  `columnDefinition = "char(64)"` to match the migrations exactly.
- `open-in-view: false`. Associations a response mapping touches are fetched with
  explicit `join fetch` in JPQL.
- Nullable associations (`decidedBy`, `adjudicator`) use `left join fetch`.
  An inner join silently removes pending rows from the approval queue — the
  rows are there, they are just not returned.
- Native FULLTEXT queries return `(id, relevance)` pairs, **not** entities.
  `SELECT c.*, <score>` arrives as `Object[]` per column and casting element zero
  to `DocumentChunk` throws `ClassCastException`. Retrieval issues two queries:
  one for ids and scores, one `join fetch` for the chunks.

### Why `join fetch`, never `@EntityGraph`

In this Spring Data version `attributePaths` is a single comma-joined `String`.
`attributePaths = "debateRound,debate"` is not two paths — it is one attribute
literally named `debateRound,debate`, and the query fails at startup with
`Unable to locate Attribute with the given name`.

JPQL `join fetch` is unambiguous, greppable, and validated. Every multi-association
preload in this codebase uses it. The one place the `@EntityGraph` form is safe
is a single top-level path such as `attributePaths = "corpus"`.

### Deliberately not fetching

`fetch = LAZY` where a field is never read on a hot path, and the field is not
dereferenced outside a transaction. With `open-in-view: false`, a lazy proxy
touched after the session closes throws — which is a loud, correct failure rather
than a silent N+1.

`TraceStep` has four lazy associations that the detail and x-ray queries do not
need, so they stay lazy.

---

## 7. Security

| Concern | Decision |
|---|---|
| Passwords | BCrypt |
| Sessions | JWT, HS256, secret from the environment |
| Weak secret | `SecurityConfigValidator` refuses to boot below 32 bytes |
| Password length | Minimum 12 characters, enforced on registration and admin creation |
| Self-registration | Always `ANALYST`. `VERIFIER` and `ADMIN` only via admin or bootstrap |
| Object-level authorisation | Every read and write re-checks corpus access server-side |
| Rate limiting | `FixedWindowRateLimiter`, applied to write endpoints. The demo seeder honours it with exponential backoff rather than being special-cased |
| CORS | Explicit origin list, never wildcard. Empty means same-origin only |
| Actuator | `health, info, metrics` only; `health` shows no details |
| OpenAPI | ADMIN-only. 403 for non-admins is correct, not a defect |
| Secrets in logs | Never logged. `GlobalExceptionHandler` truncates and does not echo request bodies |
| Error responses | Trace id, so a browser error is joinable to a Glass Box step |

### Token storage on the frontend: an accepted tradeoff

The token is in `localStorage`. That is readable by any script on the origin, so
this deployment assumes the frontend is served from its own origin and no
third-party script is trusted there.

The alternative — an httpOnly cookie — needs CSRF defence and a same-site
deployment story. That is a larger change than this codebase currently justifies,
and it is recorded here so the tradeoff is a decision rather than an oversight.

---

## 8. Frontend

- React 19 + Vite + TypeScript, `strict` with `noUncheckedIndexedAccess`.
- **No mock screens.** Every page consumes a real backend endpoint. A page that
  renders fixture data would prove nothing about the system.
- API types are transcribed from the backend's own OpenAPI document rather than
  written from memory. Guessed field names render `undefined`, which in an audit
  tool looks like "the system has no data" rather than "the client asked for the
  wrong field". This caught roughly twenty wrong names during development.
- One HTTP chokepoint (`api/client.ts`) that attaches the Authorization header,
  clears the session exactly once on 401, and preserves the backend's trace id
  into the UI.
- Routes are lazy-loaded; the entry bundle is ~62 kB gzipped.
- CSS is hand-written against design tokens. There is no Tailwind and no PostCSS
  transform, so the visual language is reviewable in one place rather than
  assembled at build time. `vite.config.ts` pins an empty PostCSS config so a
  neighbouring project's `postcss.config.mjs` cannot be discovered by accident.
- The three assistant chat states — grounded, refused, citations failed — are
  rendered distinctly. A refusal and a grounded answer must never be scannable as
  the same kind of message.

### Docker, which is where two real bugs appeared

Local development and Docker differ in ways that are easy to dismiss and expensive
to miss. Both bugs below passed every local test and failed only on first
`docker compose up`.

**CORS validation rejected the correct configuration.** `corsConfigurationSource`
threw if no origins were configured. Under nginx the frontend and API are served
from **one origin** and the browser makes same-origin requests, so no CORS origin
is needed — and the backend refused to boot. The configuration that is right for
the deployment everyone actually runs was rejected by the check meant to protect
them. An empty list is now valid and means "no cross-origin access", which is
*stricter* than allowing some origins; `*` is still refused, because that is the
one genuinely dangerous value.

**Both healthchecks probed `localhost`, which resolves to `::1` first.** nginx's
default `listen 80` binds IPv4 only, so `wget http://localhost/healthz` always got
connection refused and the frontend reported itself permanently unhealthy while
serving perfectly well. Both now probe `127.0.0.1`. The compose file overrides the
Dockerfile healthcheck, so both had to be fixed — and only the one that is
actually used would have been noticed.

The lesson generalises: a configuration that is rejected loudly in dev can be
correct in prod, and a healthcheck that fails for a name-resolution reason looks
exactly like a service that is down.

---

## 9. Async work

`IngestionPipeline` writes a durable `background_jobs` row per document, then a
worker pool claims it. Nothing lives only in memory.

- **Idempotent**: a retried job must not create a second copy of a fact, which
  would inflate graph out-degree. Facts and claims are keyed by a SHA-256 hash of
  their identity and re-approval records additional evidence instead of
  duplicating the row.
- **Recoverable**: `RecoveryService` resets stale `RUNNING` jobs on startup, in
  place, preserving attempt count so a crash loop is still bounded and eventually
  surfaces rather than retrying forever.
- The document status has the same re-read-then-write discipline
  (`DocumentStatusService`) for exactly the detached-version reason in §4.5.

---

## 10. Known limitations

These are real and are not worked around silently.

1. **The SSE debate broker is in-memory and single-instance.** Two backend
   instances would each stream their own debate's events to their own connected
   clients. Everything else — state, rounds, arguments, weights — is in the
   database and is correct; only the live event feed is not. Horizontal scaling
   needs Redis pub/sub or similar.

2. **`MigrationIntegrationTest` skips in this environment.** Testcontainers 1.21.3
   cannot negotiate with Docker 29.6.2 (API v1.55 is newer than its negotiator)
   and the named-pipe probe fails with `BadRequestException (Status 400)`. The
   annotation is `disabledWithoutDocker = true` and the test is real; it is gated
   on a Docker/Testcontainers pairing that negotiates. The migrations have been
   applied to a real MySQL 8.0.36 by hand and all six apply cleanly from an empty
   schema, but that is not the same as the automated test running in CI.

3. **`fusedScore` is uncalibrated.** See §5.

4. **Retrieval quality is measured, not assumed.** `RetrievalEvaluation` (entity,
   service, repository, controller) records frozen-benchmark runs with Recall@1/3/5
   and MRR. The frozen 11-query gold set is preserved; see `docs/evaluation.md`.
   The remaining caveat is causal, not metrical: the rebuilt-corpus improvement
   cannot be attributed purely to query expansion, because the before leg cannot
   be reproduced without editing source.

5. **The demo uses `LLM_PROVIDER=fake`.** The offline provider performs real
   parsing, validation, quarantine, and persistence — it is not a stub that
   returns nothing, and it is why the end-to-end tests are deterministic. But its
   extraction heuristics only recognise a narrow sentence shape, so the demo
   corpus is written to that shape. A real provider is a configuration change,
   not a code change.

---

## 11. Measuring extraction from prose, without lying about it

The canonical demo corpus states every fact as `Subject predicate Object`, so a
provider scores near 100% by pattern matching and the number says nothing about
extraction from a board minute. `ProseExtractionEvaluationService` is the
separate harness for that question, against the hand-written prose gold set
(`eval/prose-gold-v1.json`: 10 documents, 81 labelled sentences, 48 expecting
nothing to be extracted, 40 expected triples, 46 expected claims).

Three properties are load-bearing:

- **Negatives.** 59% of the labelled sentences state that nothing should be
  extracted, and precision is only observable there. The canonical harness has
  no negatives, which is why this is a separate service rather than a flag on
  it — one implementation with a branch would grow a code path only one
  dataset ever exercises.
- **Expectations are claimed, not broadcast.** An expectation belongs to a
  sentence, and a sentence lives in one chunk, so each is handed to exactly one
  chunk (anchored on the subject). Scattering a document's whole gold set
  across all of its chunks counted one expected triple once per chunk, turning
  a 40-triple gold set into 156 expected misses — a harness reporting a recall
  it had manufactured.
- **The harness refuses to fake a result.** With `requireRealModel=true` while
  the offline fixture is active, evaluation throws
  `RealModelExecutionRequired` and the controller translates it to **412
  Precondition Failed** carrying the `REAL_MODEL_EXECUTION_REQUIRED` token —
  a distinct status and a distinct token, so CI cannot mistake "the harness
  refused" for "the harness passed". Fixture output describes the pipeline and
  the fixture, and nothing about any model, so it is not reported in place of
  a result. The run is read-only: it persists nothing and creates no trace
  run, so it cannot change the state it measures.