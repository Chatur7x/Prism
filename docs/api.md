# PRISM API

69 operations across 61 paths. Every request and response below was transcribed
from the live OpenAPI document at `/api-docs`, not written from memory — the
first pass of this file was drafted from memory and had roughly twenty wrong
field names in it, which is how several of them got caught.

## Conventions

**Base URL** `http://localhost:8080` in development; `/api` under nginx in the
Docker stack, same origin as the frontend.

**Authentication** `Authorization: Bearer <access-token>`, obtained from
`POST /api/auth/login`. There is no API key and no cookie session.

**Content type** `application/json` on every request with a body. `POST
/api/documents` is the exception: `multipart/form-data`.

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
| `VERIFIER` | Everything an analyst can, plus approve/reject proposals, verify claims, adjudicate verdicts, dismiss contradictions, convene and chair a Council, run retrieval evaluation |
| `ADMIN` | Everything, plus `/api/admin/**` and `/api-docs` |

---

## Authentication

### `POST /api/auth/register`
Create an account. **Always `ANALYST`.** There is no request field that can
change that, and `VERIFIER` cannot be self-granted — it comes from an existing
admin or the bootstrap path.

```json
{ "username": "analyst", "email": "a@example.com", "password": "at-least-12-chars" }
```

Passwords are BCrypt-hashed and never logged, returned, or persisted in the
clear. Minimum 12 characters.

### `POST /api/auth/login`
Returns `{ accessToken, tokenType, expiresIn, user }`.

### `GET /api/auth/me`
The authenticated profile.

### `POST /api/auth/refresh`
Re-issues a token **for a still-valid token**. See the README's limitations
section — this is not a refresh-token flow.

---

## Corpora

A corpus is the isolation boundary. Every other operation in this API is
scoped to one, and none can be made to cross that boundary.

| Operation | Notes |
|---|---|
| `POST /api/corpora` | Create; caller is owner |
| `GET /api/corpora` | Only corpora the caller owns, unless `ADMIN` |
| `GET /api/corpora/{id}` | Owner or `ADMIN` |
| `PATCH /api/corpora/{id}` | Owner only |
| `DELETE /api/corpora/{id}` | Owner only. **Archives, never hard-deletes** — provenance of approved knowledge depends on the documents |
| `GET /api/corpora/{id}/statistics` | Counts of documents, triples, claims, verdicts, contradictions, debates, quarantined responses, each broken down by status |

## Documents

| Operation | Notes |
|---|---|
| `POST /api/documents` | `multipart/form-data`: file plus `corpusId`. Accepts PDF, DOCX, TXT, MD, CSV. Begins extraction asynchronously |
| `GET /api/documents?corpusId=` | |
| `GET /api/documents/{id}/chunks` | Chunks **with their exact character offsets** — the offsets are what make a citation checkable |
| `GET /api/documents/{id}/content` | Full text, for provenance inspection |
| `GET /api/documents/{id}/progress` | Extraction progress, quarantine counts, job state |
| `GET /api/documents/{id}/quarantine` | Rejected model responses. Malformed output is quarantined and visible, never silently dropped or repaired |
| `POST /api/documents/{id}/reprocess` | Idempotent: existing chunks reused, duplicate facts collapsed rather than duplicated |
| `DELETE /api/documents/{id}` | **Always refuses.** Provenance depends on documents |

A rejected extraction response is not an error condition of the upload — it is a
recorded, retrievable fact about what the model did. Check `quarantine` rather
than assuming an empty document means nothing was produced.

## Approval

Nothing enters trusted state without passing through here.

| Operation | Notes |
|---|---|
| `GET /api/approval-queue?corpusId=&size=` | Pending triples and claims awaiting a decision |
| `POST /api/triples/{id}/approve` | `VERIFIER`. Records who decided, and when |
| `POST /api/triples/{id}/reject` | The record is retained permanently |
| `POST /api/claims/{id}/approve` | For verification |
| `POST /api/claims/{id}/reject` | |

Both approve and reject take an optional `note`. Rejection is not deletion: a
rejected proposal stays, because "a human looked at this and said no" is itself
audit information.

## Claims and verdicts

| Operation | Notes |
|---|---|
| `GET /api/claims?corpusId=&status=` | |
| `POST /api/claims/verify` | Verify one claim against retrieved evidence |
| `POST /api/claims/verify-all` | Every approved-but-unverified claim. **Serial by design** — each verification does its own corpus-scoped retrieval and model call, and running them concurrently was how support counts were previously lost |
| `GET /api/verdicts?corpusId=` | |
| `POST /api/verdicts/{id}/adjudicate` | Records a human decision. **The machine verdict is preserved, never overwritten** |
| `GET /api/verdicts/{id}/history` | Superseded machine verdicts for the same claim |

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

Deterministic rule engine against a registry of predicate semantics. Idempotent
— a rescan creates no duplicates.

| Operation | Notes |
|---|---|
| `POST /api/contradictions/scan?corpusId=` | Re-run detection |
| `GET /api/contradictions?corpusId=&status=` | |
| `GET /api/contradictions/{id}` | |
| `POST /api/contradictions/{id}/dismiss` | As not a genuine conflict; recorded |

Response fields are `subjectText`, `predicate`, `leftDescription`,
`rightDescription`, `leftTripleId`, `rightClaimId`, `ruleCode`, `ruleVersion`,
`explanation`.

`ruleVersion` is on the finding on purpose. When a rule changes, old findings
can be told apart from new ones — without it, "we changed the rule and the count
went down" is indistinguishable from "we changed the rule and it works better".

## Debates (the Council)

| Operation | Notes |
|---|---|
| `POST /api/contradictions/{id}/debate` | Convene. Returns the same `DebateResponse` as every other debate endpoint |
| `POST /api/debates/{id}/start` | Runs round 1's personas |
| `POST /api/debates/{id}/advance` | Submit this round's weights and open the next round, **or move to synthesis at the ceiling**. Concurrent calls yield one success and one 409 |
| `POST /api/debates/{id}/arguments/{argumentId}/weight` | Chair weight 1–5. **Append-only**: revising creates a new audit row and does not overwrite |
| `POST /api/debates/{id}/synthesize` | Returns a receipt with the report id |
| `GET /api/debates/{id}/report` | The report itself |
| `GET /api/debates/{id}` | Full state: rounds, arguments, weights, citations |
| `GET /api/debates/{id}/stream` | SSE. Single-instance in-memory broker |
| `GET /api/debates/fsm` | The state machine, for rendering |
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

| Operation | Notes |
|---|---|
| `GET /api/graph/{corpusId}` | Nodes and edges of the **trusted** graph — approved only |
| `GET /api/graph/{corpusId}/pagerank` | |
| `GET /api/graph/{corpusId}/communities` | Deterministic label propagation |
| `GET /api/entities?corpusId=` | |
| `GET /api/entities/{id}` | With aliases and approved relations |

Edge shape is `{ from, to, predicate, label }`. Every edge in the trusted graph
has approval provenance behind it, because it can only have got there by being
approved.

## Retrieval evaluation

| Operation | Notes |
|---|---|
| `POST /api/retrieval-evaluation?corpusId=` | Run a gold set; returns the resulting metrics |
| `GET /api/retrieval-evaluation?corpusId=` | Aggregate Recall@k and MRR |
| `GET /api/retrieval-evaluation/rows?corpusId=&limit=` | Per-query rows, newest first |

`VERIFIER`-gated. This is not a diagnostic: the numbers decide whether a
retriever change is safe to keep, which is a judgement about the system rather
than a read of it.

The gold set is `query = goldChunkId` lines — `#` comments and blank lines
ignored, **split on the last `=`** because a query may legitimately contain one
("revenue = 4.2m or higher"). Every line is validated, with its line number, before
any retrieval runs.

See `docs/evaluation.md` for what the metrics do and do not mean.

## Trace (the Glass Box)

| Operation | Notes |
|---|---|
| `GET /api/traces?corpusId=&size=` | Runs visible to the caller |
| `GET /api/traces/{id}` | Complete step DAG, ordered for replay |
| `GET /api/traces/{id}/steps` | Flat ordered list, for a timeline |
| `GET /api/traces/{id}/xray` | Grouped by `engine` / `llm` / `human` |

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

| Operation | Notes |
|---|---|
| `POST /api/chat/sessions` | **Permanently bound to one corpus** |
| `GET /api/chat/sessions` | |
| `GET /api/chat/sessions/{id}` | With full message history |
| `POST /api/chat/sessions/{id}/messages` | A grounded question |

An answer carries a grounded flag and an insufficient-evidence flag. When the
corpus cannot answer, the response says so — with zero citations — rather than
producing a plausible answer. A grounded answer with no citations is a bug; a
refusal is the correct behaviour and is exercised by the smoke test.

## Admin

`ADMIN` only.

| Operation | Notes |
|---|---|
| `GET /api/admin/system/status` | LLM provider, job counts, configuration sanity |
| `GET /api/admin/jobs` | Background job queue |
| `GET /api/admin/users` | |
| `POST /api/admin/users` | Create with an **explicit** role — the path by which `VERIFIER` is actually granted |
| `PATCH /api/admin/users/{id}` | Change role, enable/disable |
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