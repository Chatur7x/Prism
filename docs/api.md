# PRISM API

74 operations across 66 paths. Every request and response below was transcribed
from the live OpenAPI document at `/api-docs`, not written from memory — the
first pass of this file was drafted from memory and had roughly twenty wrong
field names in it, which is how several of them got caught.

## Conventions

**Base URL** `http://localhost:8080` in development; `/api` under nginx in the
Docker stack, same origin as the frontend.

**Authentication** `Authorization: Bearer <access-token>`, obtained from
`POST /api/auth/login`. There is no API key and no cookie session.

**Content type** `application/json` on every request with a body, except the
file-upload variant of `POST /api/documents`, which is `multipart/form-data`.
The same path also accepts a JSON create-from-text body; the two are distinct
operations distinguished by content type.

**Authorisation** is two layers. `WebSecurityConfig` is the coarse gate
(public login/register, `ADMIN`-only `/api/admin/**` and `/api-docs`,
authenticated everything else). Method-level `@PreAuthorize` then sets the
role per operation, and corpus isolation is re-checked in the service layer
per object. The role notes below come from those annotations, not from the
OpenAPI document, which does not carry them.

**Errors** always this shape, never a bare string:

```json
{
  "timestamp": "2026-10-01T15:09:12.539Z",
  "status": 409,
  "error": "STATE_CONFLICT",
  "message": "debate 1 is AWAITING_CHAIR; synthesis runs only from SYNTHESIZING",
  "path": "/api/debates/1/synthesize",
  "traceId": "cfffc5dc22c74fef9480ad129d126b9e",
  "violations": []
}
```

`traceId` is the correlation id. It appears in the server log for the same
request, which is what makes a user-reported failure diagnosable. `violations`
carries field-level detail for validation failures and is empty otherwise.

The `error` field is a stable machine-readable code; `message` is prose and may
change.

### Status codes that carry meaning here

| Code | When |
|---|---|
| `401 UNAUTHENTICATED` | No token, or a token that is not valid |
| `403 ACCESS_DENIED` | Valid token, but not for this object — including another user's corpus |
| `404 NOT_FOUND` | The id does not exist **as far as this caller can see**; a row in someone else's corpus reads as 404, not 403, so existence is not leaked |
| `409 STATE_CONFLICT` | A legal request against an illegal state — synthesising a debate that has not finished, or advancing one someone else just advanced |
| `412` with `status: REAL_MODEL_EXECUTION_REQUIRED` | The prose evaluation harness was asked to require a real model while the offline fixture is active. It refused rather than report fixture output as a model result |
| `422 VALIDATION_FAILED` | Field-level validation failed; see `violations` |
| `429 RATE_LIMITED` | Too many requests; the client is expected to back off |
| `500` | A bug. Check the log by `traceId` |

A `500` is always a defect. `429` during a bulk operation is not — the
verification endpoints are rate limited and a corpus-wide run is expected to hit
it. `scripts/seed-demo.ps1` handles the back-off.

### Roles

| Role | Can |
|---|---|
| `ANALYST` | Upload documents, read their own corpora, submit proposals. **This is what self-registration grants, always.** |
| `VERIFIER` | Everything an analyst can, plus approve/reject proposals, verify claims, adjudicate verdicts, dismiss contradictions, convene and chair a Council, run retrieval and extraction evaluation |
| `ADMIN` | Everything, plus `/api/admin/**` and `/api-docs` |

### Typed vs untyped responses

A response that is a named record in the OpenAPI document (a `$ref` such as
`DebateResponse`) is transcribed into `frontend/src/api/types.ts` and checked
by `scripts/contract-check.ps1`. A response the document describes as a bare
`object` is a hand-built `Map<String, Object>` on the server: the frontend
type for it was written by hand and **can drift silently**, because there is
no schema for the contract check to compare against. A drifted field reads as
`undefined` in the UI, which in an audit tool looks like "the system has no
data" rather than "the client asked for the wrong field".

23 operations return an untyped map (or an array of them) on success:

| Operation | Shape on the wire |
|---|---|
| `GET /api/admin/system/status` | `{llmProvider, llm, offlineTestMode, userCount, pendingJobs, runningJobs, failedJobs, abandonedJobs, checkedAt, warning?}` |
| `GET /api/admin/jobs` | Array of `{id, jobKey, type, status, attempts, corpusId, documentId, heartbeatAt, lastError}` |
| `GET /api/approval-queue?corpusId=&page=&size=` | `{triples, claims, pendingTripleCount, pendingClaimCount, page, size}` |
| `GET /api/chat/sessions/{id}` | Session with full message history |
| `POST /api/chat/sessions/{id}/messages` | The grounded answer with its flags and citations |
| `GET /api/claims?corpusId=&status=&page=&size=` | `{content, total, page, size}` — legacy shape, not the unified envelope |
| `POST /api/claims/verify-all?corpusId=&limit=` | `{attempted, succeeded, failed, verdictIds}` |
| `GET /api/contradictions?corpusId=&status=&page=&size=` | `{content, total, page, size}` — legacy shape, not the unified envelope |
| `GET /api/debates/fsm` | `states` as a name→description **map**, `events` as an array, `weightRange: {min, max}` |
| `POST /api/debates/{id}/arguments/{argumentId}/weight` | `{weightId, argumentId, weight, verifier, note, createdAt}` |
| `POST /api/debates/{id}/synthesize` | Receipt `{reportId, debateId, createdAt, model}` |
| `GET /api/documents/{id}/content` | `{id, title, contentText}` |
| `GET /api/documents/{id}/progress` | `{documentId, status, chunkCount, extractionRunId, runStatus, processedChunks, totalChunks, triplesFound, claimsFound, quarantinedCount, model, lastError}` |
| `GET /api/entities/{id}` | Entity with aliases and approved relations |
| `POST /api/llm-evaluation/gold-set` | Description of the shipped canonical gold set, no provider call |
| `POST /api/llm-evaluation/prose/gold-set` | Description of the prose gold set (version, counts, negatives, rows by predicate) |
| `GET /api/retrieval-evaluation?corpusId=` | Aggregate Recall@k and MRR |
| `POST /api/retrieval-evaluation?corpusId=` | The resulting metrics for the run |
| `GET /api/traces?corpusId=&page=&size=` | Unified `PageResponse<RunSummary>` envelope; `actorSummary` computed from stored steps |
| `GET /api/traces/{id}` | Complete step DAG, ordered for replay |
| `GET /api/traces/{id}/xray` | Steps grouped by `ENGINE` / `LLM` / `HUMAN` |
| `GET /api/triples?corpusId=&status=&page=&size=` | `{content, total, page, size}` — legacy shape, not the unified envelope |
| `GET /api/verdicts?corpusId=&page=&size=` | `{content, total, page, size}` — legacy shape, not the unified envelope |
| `GET /api/verdicts/{id}/history` | Array of superseded machine verdicts |

Only three collection endpoints return the unified `PageResponse` envelope
(see Documents and Admin). The `{content, total, page, size}` shape above is
an older convention that was never migrated; unifying it is outstanding, and
until then a client must read `.total`, not headers or array length.

---

## Authentication

### `POST /api/auth/register`
Create an account. **Always `ANALYST`.** There is no request field that can
change that, and `VERIFIER` cannot be self-granted — it comes from an existing
admin or the bootstrap path. Public; no token needed.

```json
{ "username": "analyst", "email": "a@example.com", "password": "at-least-12-chars" }
```

Passwords are BCrypt-hashed and never logged, returned, or persisted in the
clear. Minimum 12 characters.

### `POST /api/auth/login`
Public. Body `{username, password}`. Returns `AuthResponse`:
`{accessToken, tokenType, expiresInSeconds, expiresAt, user}` where `user` is
`{id, username, email, role}`.

### `GET /api/auth/me`
The authenticated profile, a `UserSummary`:
`{id, username, email, role, enabled, createdAt}`.

### `POST /api/auth/refresh`
Re-issues a token **for a still-valid token**. See the README's limitations
section — this is not a refresh-token flow. Returns `AuthResponse`.

---

## Corpora

A corpus is the isolation boundary. Every other operation in this API is
scoped to one, and none can be made to cross that boundary.

| Operation | Notes |
|---|---|
| `POST /api/corpora` | `ANALYST`. Create; caller is owner. Body `{name, description}`; 201 |
| `GET /api/corpora` | Any authenticated caller. Only corpora the caller owns, unless `ADMIN` |
| `GET /api/corpora/{id}` | Any authenticated caller. Owner or `ADMIN` |
| `PATCH /api/corpora/{id}` | Any authenticated caller; owner-only enforced in the service. Body `{name, description}` |
| `DELETE /api/corpora/{id}` | Any authenticated caller; owner-only enforced in the service. **Archives, never hard-deletes** — provenance of approved knowledge depends on the documents |
| `GET /api/corpora/{id}/statistics` | `VERIFIER`. Counts of documents, triples, claims, verdicts, contradictions, debates, quarantined responses, each broken down by status. Verifier-gated: the pending counts are the operational picture of the human gate |

## Documents

| Operation | Notes |
|---|---|
| `POST /api/documents` (JSON) | `ANALYST`. Create from text: `{corpusId, title, contentText}`. Begins extraction asynchronously; 201 |
| `POST /api/documents` (multipart) | `ANALYST`. `multipart/form-data`: `corpusId`, optional `title`, plus `file`. Accepts PDF, DOCX, TXT, MD, CSV. Begins extraction asynchronously; 201 |
| `GET /api/documents?corpusId=&page=&size=` | Any authenticated caller. **Unified `PageResponse` envelope** (see below) |
| `GET /api/documents/{id}` | Any authenticated caller; denied unless the caller can access its corpus |
| `GET /api/documents/{id}/chunks` | Typed `ChunkResponse` array: `{id, chunkIndex, content, startOffset, endOffset, tokenEstimate}`. Chunks **with their exact character offsets** — the offsets are what make a citation checkable |
| `GET /api/documents/{id}/content` | **Untyped.** Full text, for provenance inspection: `{id, title, contentText}` |
| `GET /api/documents/{id}/progress` | **Untyped.** Extraction progress, quarantine counts, job state |
| `GET /api/documents/{id}/quarantine?page=&size=` | Any authenticated caller. **Unified `PageResponse<QuarantineRow>`** (see below). Rejected model responses. Malformed output is quarantined and visible, never silently dropped or repaired |
| `POST /api/documents/{id}/reprocess` | `ANALYST`. Idempotent: existing chunks reused, duplicate facts collapsed rather than duplicated |
| `DELETE /api/documents/{id}` | Any authenticated caller; the service refuses. **Always refuses.** Provenance depends on documents |

A rejected extraction response is not an error condition of the upload — it is a
recorded, retrievable fact about what the model did. Check `quarantine` rather
than assuming an empty document means nothing was produced.

### The unified page envelope

Three endpoints return `PageResponse<T>` — `GET /api/documents`,
`GET /api/admin/users`, and `GET /api/documents/{id}/quarantine`:

```json
{
  "content": [],
  "page": 0,
  "size": 50,
  "totalElements": 24,
  "totalPages": 1,
  "hasNext": false
}
```

`totalPages` and `hasNext` are derived server-side from the total, because
those are exactly the two fields a hand-rolled envelope gets wrong: with a
truncated final page there is no way to tell "this is the last page" from
"there are more" without the total. Spring Data's own `Page` is deliberately
not exposed — its serialisation carries an internal `pageable` object, so a
client binding to it is coupled to Spring.

### `QuarantineRow`

```json
{
  "id": 7,
  "chunkId": 412,
  "chunkIndex": 3,
  "errorType": "SCHEMA_VALIDATION",
  "validationMessage": "triples[2].predicate is blank",
  "model": "qwen2.5:7b",
  "promptVersion": "EXTRACT_V1",
  "attempt": 2,
  "createdAt": "2026-10-01T15:09:12.539Z",
  "rawResponse": "{...verbatim model output...}"
}
```

`rawResponse` is retained verbatim on purpose: it is the evidence for whatever
the model actually returned, and reformatting it would defeat the point of
quarantining rather than discarding. The record is typed (rather than the
hand-built map it used to be) so a renamed field fails the contract check
instead of rendering as a blank cell.

## Approval

Nothing enters trusted state without passing through here. `VERIFIER` on all
five operations.

| Operation | Notes |
|---|---|
| `GET /api/approval-queue?corpusId=&page=&size=` | **Untyped.** Pending triples and claims awaiting a decision: `{triples, claims, pendingTripleCount, pendingClaimCount, page, size}` |
| `POST /api/triples/{id}/approve` | Records who decided, and when. Optional body `{note}` |
| `POST /api/triples/{id}/reject` | The record is retained permanently. Body `{note}` |
| `POST /api/claims/{id}/approve` | For verification. No body |
| `POST /api/claims/{id}/reject` | Optional body `{note}` |

Both approve and reject take an optional `note`. Rejection is not deletion: a
rejected proposal stays, because "a human looked at this and said no" is itself
audit information.

## Claims, triples, and verdicts

Reads are any authenticated caller with corpus access; state changes are
`VERIFIER`.

| Operation | Notes |
|---|---|
| `GET /api/triples?corpusId=&status=&page=&size=` | **Untyped**, legacy `{content, total, page, size}` |
| `GET /api/triples/{id}` | Typed `TripleResponse`, with provenance |
| `GET /api/claims?corpusId=&status=&page=&size=` | **Untyped**, legacy `{content, total, page, size}` |
| `GET /api/claims/{id}` | Typed `ClaimResponse`, with source provenance |
| `POST /api/claims/verify` | Verify one claim against retrieved evidence. Body `{claimId}`. Returns a typed `VerificationOutcome`. Any authenticated caller; corpus access is re-checked in the service |
| `POST /api/claims/verify-all?corpusId=&limit=` | **Untyped.** Every approved-but-unverified claim, up to `limit` (default 10, max 50). **Serial by design** — each verification does its own corpus-scoped retrieval and model call, and running them concurrently was how support counts were previously lost. Returns `{attempted, succeeded, failed, verdictIds}` |
| `GET /api/verdicts?corpusId=&page=&size=` | **Untyped**, legacy `{content, total, page, size}` |
| `GET /api/verdicts/{id}` | Typed `VerdictResponse`, with evidence passages |
| `POST /api/verdicts/{id}/adjudicate` | `VERIFIER`. Records a human decision. **The machine verdict is preserved, never overwritten**. Body `{verdict, note}`; a second decision on the same verdict is a 409 |
| `GET /api/verdicts/{id}/history` | **Untyped array.** Superseded machine verdicts for the same claim |

### What a verdict holds

Four independent things, stored as four independent fields:

| Field | What it is |
|---|---|
| `llmScore` | What the model said |
| `rulePenalty` | What the deterministic rule engine deducted |
| `fusedScore` | The combination — **a ranking score, not a calibrated probability** |
| `evidenceStatus` | Whether evidence was actually found: `EVIDENCE_FOUND`, `SOURCE_MISSING`, or equivalent |

and a verdict *type* which is one of `SUPPORTED`, `CONTRADICTED`,
`INSUFFICIENT_EVIDENCE`, `SOURCE_MISSING`.

`SOURCE_MISSING` is **not** `CONTRADICTED` and **not** `INSUFFICIENT_EVIDENCE`.
A finding whose source could not be resolved, a finding the source refutes, and
a finding with nothing to check against are three different situations, and
collapsing them turns "we don't know" into "we know it is false".

## Contradictions

Deterministic rule engine against a registry of predicate semantics. The scan
is any authenticated caller with corpus access; convening and dismissing are
`VERIFIER`. The rescan is idempotent — a rescan creates no duplicates.

| Operation | Notes |
|---|---|
| `POST /api/contradictions/scan?corpusId=` | Re-run detection. Returns a typed `ScanResult`: `{findings, created, unchanged}` |
| `GET /api/contradictions?corpusId=&status=&page=&size=` | **Untyped**, legacy `{content, total, page, size}` |
| `GET /api/contradictions/{id}` | Typed `ContradictionResponse` |
| `POST /api/contradictions/{id}/dismiss` | As not a genuine conflict; recorded. Refused with 409 while a debate is in progress — abort the debate first |

Response fields are `subjectText`, `predicate`, `leftDescription`,
`rightDescription`, `leftTripleId`, `rightClaimId`, `ruleCode`, `ruleVersion`,
`explanation`.

`ruleVersion` is on the finding on purpose. When a rule changes, old findings
can be told apart from new ones — without it, "we changed the rule and the count
went down" is indistinguishable from "we changed the rule and it works better".

## Debates (the Council)

Reads are any authenticated caller with corpus access; driving the debate is
`VERIFIER`.

| Operation | Notes |
|---|---|
| `POST /api/contradictions/{id}/debate` | Convene. Optional body `{topic}`. Returns the same `DebateResponse` as every other debate endpoint |
| `POST /api/debates/{id}/start` | Runs round 1's personas |
| `POST /api/debates/{id}/advance` | Submit this round's weights and open the next round, **or move to synthesis at the ceiling**. Concurrent calls yield one success and one 409 |
| `POST /api/debates/{id}/arguments/{argumentId}/weight` | **Untyped.** Chair weight 1–5 plus optional note. **Append-only**: revising creates a new audit row and does not overwrite. Returns `{weightId, argumentId, weight, verifier, note, createdAt}` |
| `POST /api/debates/{id}/synthesize` | **Untyped.** Returns a receipt `{reportId, debateId, createdAt, model}`. Idempotent: an existing report is returned |
| `GET /api/debates/{id}/report` | Typed `ReportResponse`: the report itself |
| `GET /api/debates?corpusId=&page=&size=` | Unified `PageResponse<DebateResponse>` envelope, newest first. Added because two pages called it and it did not exist (404): the Contradictions page hid its Councils section and Reports showed an error banner |
| `GET /api/debates/{id}` | Typed `DebateResponse`: full state - rounds, arguments, weights, citations |
| `GET /api/debates/{id}/stream` | SSE. Single-instance in-memory broker |
| `GET /api/debates/fsm` | **Untyped.** The state machine, for rendering |
| `POST /api/debates/{id}/abort` | The contradiction returns to `OPEN` |

### The state machine

```
CREATED ──start──> ROUND_ACTIVE ──args done──> AWAITING_CHAIR
                                                       │
                     ┌─────────────────────────────────┤
           another round required                   ceiling reached
                     │                                 │
               ROUND_ACTIVE                          SYNTHESIZING
                                                         │
                                              SYNTHESIS_DONE
                                                         │
                                                     COMPLETED
```

`GET /api/debates/fsm` returns `states` as a name→description **map**, `events`
as an array, and `weightRange: {min, max}`.

Note the two-step ending. After the last permitted round the debate sits in
`AWAITING_CHAIR`; **one more `advance` is required** to cross into
`SYNTHESIZING`. Requesting synthesis before that is a `409`, correctly — a
report on a conversation the engine has not finished is not a verdict.

The machine decides the ending, not the caller. A chair who keeps pressing
"one more round" is stopped by the ceiling; that is the point of a ceiling.

### Synthesis report

Blocks, each with its own citations. A citation names **exactly one** target,
and `kind` says which: `ARGUMENT`, `MACHINE_FACT`, `CHUNK`, `TRIPLE`, `CLAIM`,
or `VERDICT`.

All six target fields are published — `chunkId`, `tripleId`, `claimId`,
`verdictId`, `argumentId`, `machineFactId`. An earlier version of this endpoint
published only three of them, so a citation into a triple or claim came back
looking empty; because the endpoint also returned an untyped map, the OpenAPI
schema could not be used to notice. It is a typed record now.

## Graph

Any authenticated caller with corpus access. The graph endpoints take an
optional `?scope=` (default `ALL_APPROVED`); only approved knowledge is ever
exposed here.

| Operation | Notes |
|---|---|
| `GET /api/graph/{corpusId}?scope=` | Typed `GraphView`: nodes and edges of the **trusted** graph — approved only |
| `GET /api/graph/{corpusId}/pagerank?scope=&limit=` | Typed `PageRankRow` array (`limit` default 50) |
| `GET /api/graph/{corpusId}/communities?scope=` | Typed `CommunityRow` array. Deterministic label propagation |
| `GET /api/entities?corpusId=&search=` | Typed `EntityResponse` array |
| `GET /api/entities/{id}` | **Untyped.** With aliases and approved relations |

Edge shape is `{ from, to, predicate, label }`. Every edge in the trusted graph
has approval provenance behind it, because it can only have got there by being
approved.

## Extraction evaluation (canonical)

`VERIFIER`-gated. Measures transcription against the shipped canonical gold
set — the figures say whether the pipeline copies `Subject predicate Object`
sentences faithfully, not whether it extracts from prose. For prose, see the
next section; the two harnesses are separate on purpose, so the wrong number
cannot be quoted.

| Operation | Notes |
|---|---|
| `POST /api/llm-evaluation/extraction?corpusId=` | Body `{goldSet}`. Runs the production extraction path over the labelled chunks. Returns a typed `ExtractionReport`: triple/claim precision, recall and F1 with raw totals, malformed and quarantine rates, and a `corpusLimitation` caveat that travels with the numbers |
| `POST /api/llm-evaluation/gold-set` | **Untyped.** Body `{goldSet}`. Reports what the shipped gold set contains without calling a provider. No side effects and no cost |

## Prose extraction evaluation

`VERIFIER`-gated. Measures extraction from natural prose against
`eval/prose-gold-v1.json` (prose-gold-v1: 10 documents, 81 labelled sentences,
of which 48 expect nothing to be extracted, 40 expected triples, 46 expected
claims). The negatives are the point: precision is only observable where
something must *not* be extracted. Matching is case-folded,
whitespace-collapsed equality on subject, predicate and object; claims
additionally require matching polarity, and entity aliases are not resolved, so
a near-miss spelling counts as a miss.

| Operation | Notes |
|---|---|
| `POST /api/llm-evaluation/prose/extraction?corpusId=&requireRealModel=` | Body `{goldSet}` (max 2 MB). Runs the production extraction path over every chunk of the labelled documents. Read-only: nothing is persisted and no trace run is created. Returns a typed `ProseReport`: triple/claim precision, recall and F1 with raw totals, malformed, quarantine and ungrounded-citation rates, plus `testMode` and the `datasetLimitation` caveat, which must be quoted alongside the numbers |
| `POST /api/llm-evaluation/prose/gold-set` | **Untyped.** Body `{goldSet}`. Describes the dataset without calling a provider: version, document/sentence/negative counts, expected triples and claims, rows by predicate. No side effects and no cost |

With `requireRealModel=true` while the offline fixture is active, the
extraction endpoint refuses with **412** carrying
`REAL_MODEL_EXECUTION_REQUIRED` instead of a report, so a CI job demanding
real-model numbers fails clearly rather than quietly succeeding on fixture
data. The gold-set endpoint documents the same 412 because the guard is
controller-wide, but describing a dataset never executes a model.

## Retrieval evaluation

| Operation | Notes |
|---|---|
| `POST /api/retrieval-evaluation?corpusId=` | **Untyped.** Run a gold set; returns the resulting metrics |
| `GET /api/retrieval-evaluation?corpusId=` | **Untyped.** Aggregate Recall@k and MRR |
| `GET /api/retrieval-evaluation/rows?corpusId=&runKey=` | Typed `RowResponse` array: per-query rows. `runKey` selects one benchmark run |

`VERIFIER`-gated. This is not a diagnostic: the numbers decide whether a
retriever change is safe to keep, which is a judgement about the system rather
than a read of it.

The gold set is `query = goldChunkId` lines — `#` comments and blank lines
ignored, **split on the last `=`** because a query may legitimately contain one
("revenue = 4.2m or higher"). Every line is validated, with its line number, before
any retrieval runs.

See `docs/evaluation.md` for what the metrics do and do not mean.

## Trace (the Glass Box)

Any authenticated caller; only runs visible to the caller are listed.

| Operation | Notes |
|---|---|
| `GET /api/traces?corpusId=&page=&size=` | Unified `PageResponse<RunSummary>` envelope. `corpusId` is optional |
| `GET /api/traces/{id}` | **Untyped.** Complete step DAG, ordered for replay |
| `GET /api/traces/{id}/steps` | Typed `StepView` array: flat ordered list, for a timeline |
| `GET /api/traces/{id}/xray` | **Untyped.** Grouped by `engine` / `llm` / `human` |

The X-Ray groups by **actor**, and that grouping is the whole point:

- `ENGINE` — deterministic Java. Carries `ruleVersion`, never `model`.
- `LLM` — the model. Carries `model` and `promptVersion`.
- `HUMAN` — a person. Carries who and what they decided.

It shows the observable execution — which was called, on what, with what result
and how long. It does **not** expose the model's private reasoning, because that
is not something the system observes and inventing a plausible-looking
substitute for it would be worse than leaving it out.

A single debate spans **several** trace runs: convening, each start and advance,
every chair weighting, and synthesis each record their own. The debate's story
is the union. Asserting on one run proves nothing — a round run legitimately has
no `HUMAN` step, because the human action was recorded by the separate weighting
call, and reading that as "no human was involved" is exactly the misreading the
X-Ray exists to prevent.

## Chat

Any authenticated caller with access to the bound corpus.

| Operation | Notes |
|---|---|
| `POST /api/chat/sessions` | Typed `SessionResponse`. **Permanently bound to one corpus**. Body `{corpusId, title}` |
| `GET /api/chat/sessions` | Typed `SessionResponse` array |
| `GET /api/chat/sessions/{id}` | **Untyped.** With full message history |
| `POST /api/chat/sessions/{id}/messages` | **Untyped.** A grounded question. Body `{question}` |

An answer carries a grounded flag and an insufficient-evidence flag. When the
corpus cannot answer, the response says so — with zero citations — rather than
producing a plausible answer. A grounded answer with no citations is a bug; a
refusal is the correct behaviour and is exercised by the smoke test.

## Admin

`ADMIN` only.

| Operation | Notes |
|---|---|
| `GET /api/admin/system/status` | **Untyped.** LLM provider, job counts, configuration sanity. Carries `offlineTestMode` and, under the fixture, a `warning` that the responses are deterministic fixtures |
| `GET /api/admin/jobs?status=` | **Untyped array.** Background job queue |
| `GET /api/admin/users?page=&size=` | **Unified `PageResponse<UserSummary>` envelope** |
| `POST /api/admin/users` | Create with an **explicit** role — the path by which `VERIFIER` is actually granted. Body `{username, email, password, role}` |
| `PATCH /api/admin/users/{id}` | Change role, enable/disable. Body `{role, enabled}` |
| `/api-docs` | OpenAPI document |
| `/actuator/metrics` | Metrics |

---

## Regenerating this file

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api-docs > openapi.json
```

`/api-docs` is admin-gated on purpose: the document enumerates every path,
every role boundary, and every field name. That is fine for an operator and a
liability in public.

If you change a response shape, regenerate this from the live document. The
frontend types in `frontend/src/api/types.ts` are transcribed from the same
source for the same reason — a hand-written type drifts from its endpoint
silently, and a `tsc` error is a much better way to find out than a runtime
`undefined`.
