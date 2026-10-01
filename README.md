# OPERATION PRISM

An auditable analysis platform. Documents go in; structured knowledge, findings,
and a traceable chain of reasoning come out. Every step of that chain is
recorded, and nothing the language model produces is treated as true until a
human has approved it.

> **Deterministic Java owns all state. The LLM only proposes. The human
> verifier is the final authority.**

That sentence is the specification. Everything below exists to make it true in
code rather than in a policy document — and several things in this codebase
exist only because, without them, it quietly stopped being true.

---

## What it actually does

A document enters. It is chunked, sent to a model, and the model's reply is
parsed, schema-validated, semantically validated, and stored **as a proposal**.
Nothing it says is in the knowledge graph yet.

A human opens the approval queue and approves or rejects each proposal. That
decision — not the model — is what promotes it into trusted state. Approval is
recorded as an audit row naming the person and the moment.

Once enough is trusted, deterministic Java reasons over it:

- **Claims** are checked against rule penalties, retrieval evidence, and a
  verification call, then scored. The LLM score, the rule penalty, the fused
  score, and the evidence status are stored as **four separate fields** and are
  never collapsed into one number.
- **Contradictions** are found by a deterministic rule engine against a registry
  of predicate semantics. A relation declared single-valued that the approved
  knowledge gives two different values is a conflict. A relation declared
  multi-valued is not, no matter how odd it looks.
- **A Council** can be convened over a contradiction: several personas argue the
  two positions from the same evidence, a chair weights each argument, and
  synthesis produces a structured report in which every block carries its own
  citations.
- **Chat** answers questions grounded in the corpus, and says so when it cannot
  answer rather than inventing one.

Throughout, the **Glass Box** records who did what — `ENGINE` for deterministic
code, `LLM` for the model, `HUMAN` for a person — in execution order. It shows
the observable execution, not the model's private reasoning.

---

## Running it

### Prerequisites

| Tool | Version used |
|---|---|
| Java | 21 (target bytecode 17) |
| Maven | 3.9+ |
| Node | 24 |
| Docker | 29 (optional; needed only for MySQL or the full stack) |

### The full stack, in Docker

```bash
cp .env.example .env
# Edit .env and set JWT_SECRET to at least 32 characters. There is no default
# on purpose: an image that starts with a known signing key is not safer.
docker compose up --build
```

That brings up MySQL, the backend, and nginx in front of the frontend:

- Frontend — <http://localhost:5173>
- Backend — <http://localhost:8080>
- OpenAPI UI — <http://localhost:8080/api-docs>

Compose reads `MYSQL_PORT`, `BACKEND_PORT`, and `FRONTEND_PORT` from `.env` if
you want different host ports.

### Backend against your own MySQL

```bash
cd backend

export DB_URL='jdbc:mysql://localhost:3306/prism?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8'
export DB_USERNAME=prism
export DB_PASSWORD=prism
export JWT_SECRET='replace-me-with-something-at-least-32-characters'
export LLM_PROVIDER=fake          # or a real provider; see below
export CORS_ALLOWED_ORIGINS='http://localhost:5173'

mvn spring-boot:run
```

Schema is created by **Flyway only**. `spring.jpa.hibernate.ddl-auto` is
`validate`, and that is deliberate: it can prove the entity mappings match the
schema, but it cannot prove the schema matches the mappings. See
[Known limitations](#known-limitations).

### Frontend

```bash
cd frontend
npm install
npm run dev
```

### LLM providers

`LLM_PROVIDER=fake` runs the whole pipeline offline against a deterministic
fixture. It is a real client that performs real parsing, validation, quarantine,
and persistence — it is not a stub — but it only recognises sentences of the
form `Subject predicate Object`, which is what makes the end-to-end tests
reproducible without a network or an API key.

Set `LLM_PROVIDER` to a hosted provider and supply its API key through the
environment to use a real model. No key is ever read from a file in the
repository or written to a log.

---

## Trying the whole thing

The demo corpus is 24 fictional memos with deliberately planted findings. It is
deterministic: same corpus, same results, every run.

```bash
# 1. Create a user and load the corpus. Stops at the human approval gate.
powershell -File scripts/seed-demo.ps1

# 2. Sign in as the operator it printed, promote to verifier, then drive the
#    approval gate, verification, and contradiction detection to completion.
powershell -File scripts/seed-demo.ps1 -Username <operator> -Password <password> \
  -ApproveAll -Verifications 10
```

What the corpus contains, and why:

| Planted | Predicate | Why |
|---|---|---|
| 5 conflicts | `reports_to`, `headquartered_in`, `parent_organization`, `chief_executive`, `subsidiary_of` | Single-valued relations the approved knowledge gives two different values |
| 1 violation | `located_in` | Out-of-range value against a bound |
| 26 relations | `supplies`, `controls`, `funds`, `member_of`, `collaborates_with`, `invests_in` | Multi-valued — **must not** be flagged, and aren't |
| 1 memo | — | Deliberately uninformative, so the pipeline has to cope with a document that yields nothing |

That last row of the table is the one worth checking. A demo where every
document produces findings proves nothing about whether the system can tell
silence from substance.

### End-to-end tests

```bash
# 23-step API walk over the whole pipeline
powershell -File scripts/smoke-test.ps1 -Username <u> -Password <p>

# 79-check Council lifecycle: convene -> 3 rounds -> chair weights ->
# round ceiling -> synthesis -> report -> Glass Box
powershell -File scripts/council-test.ps1 -CorpusId 1 -Username <u> -Password <p> -Rounds 3
```

```bash
cd backend && mvn test      # 107 tests
cd frontend && npm run build
```

Six tests skip when no Docker daemon is reachable — see
[Known limitations](#known-limitations).

---

## Retrieval evaluation

Retrieval is measured, not assumed. The `retrieval_evaluation` table existed
from migration V4 with no entity and no service behind it, which meant retrieval
quality was *unmeasured* rather than measured-and-good.

```bash
curl -X POST 'http://localhost:8080/api/retrieval-evaluation?corpusId=1' \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"goldSet":"# query = goldChunkId, one per line\nMeridian headquarters = 412\nwho owns Aster = 87"}'
```

Returns Recall@1/3/5 and MRR. The gold set is a line format because a person
maintains it by hand beside a corpus and `query = 412` is something they can
write and read. Every line is validated — including its line number — before any
retrieval runs, because a partially-applied gold set produces metrics that look
real and are silently wrong.

One convention is worth stating rather than leaving a reader to guess: **MRR
averages over queries whose gold passage was actually retrieved.** Queries with
no gold answer are excluded from every denominator, and gold passages that were
never retrieved are excluded from MRR only. `notRetrieved` is reported alongside
so the two numbers account for every gold query.

This measures whether the right passage is *retrieved and ranked*. It says
nothing about whether a claim about that passage is true — that is the verdict
path and the human verifier. Keeping them apart matters: a retrieval metric that
appeared to measure truth would invite optimising for the wrong thing.

---

## Design notes

`docs/architecture.md` is the long version — the layering rules, the
transaction-proxy trap table, the concurrency defects that were found and fixed,
and what the design deliberately does not do.

Three things worth knowing before reading any of it:

**A `@Transactional` method called from inside its own class does not get a
transaction.** Spring's advice is proxy-based and never sees an internal call.
This produced a self-deadlock where a trace-step write silently joined a
long-running approval transaction, held row locks on `trace_runs` and
`trace_steps` until that transaction committed, and then had the following call
block on them for 50 seconds. Six small beans exist in this codebase solely to
put transactional work behind a bean boundary. Each one names the trap in its
class comment.

**`fusedScore` is a ranking score, not a calibrated probability.** It is useful
for ordering findings. It is not a claim that something is 87% likely, and
nothing in the system treats it as one.

**`SOURCE_MISSING` is not `CONTRADICTED`.** A finding whose source could not be
resolved is a different fact from a finding contradicted by its source, and
`INSUFFICIENT_EVIDENCE` is different again. They are three enum values for three
different situations, and collapsing them would turn "we don't know" into "we
know it is false."

---

## Security

- Passwords are BCrypt-hashed. Plaintext passwords are never logged, never
  returned, and never persisted.
- JWT signing keys come from the environment. `.env.example` documents the
  variable; `.env` is git-ignored and no real value is in the repository.
- Access is checked **per object**, not per route: every retrieval, graph query,
  verification, contradiction, debate, and chat operation is scoped to a corpus
  the caller can reach. Corpus isolation is enforced by resolving an authorised
  `Corpus` and passing *that*, not its id, so a downstream call cannot be aimed
  at a corpus the caller never touched.
- Self-registration always yields `ANALYST`. `VERIFIER` and `ADMIN` are granted
  by an existing admin or the bootstrap path — never by the person signing up.
- CORS has no wildcard. Actuator is not exposed. Rate limiting is on.

### Token storage

The frontend keeps its access token in **`localStorage`**.

That is a real trade-off, stated plainly. It survives a page reload without a
round trip, but any successful XSS can read it. `localStorage` was chosen over
an `httpOnly` cookie because the API is called cross-origin during development,
where a cookie would need matching `SameSite` and CORS credentials handling on
both sides — and getting that subtly wrong is worse than the storage risk. For a
deployment on a single origin, an `httpOnly`, `Secure`, `SameSite=Strict` cookie
with CSRF protection is the better answer, and the backend's stateless JWT check
does not change.

---

## Known limitations

Stated because a system that claims to have none is not telling you the truth.

**Six migration integration tests skip without a usable Docker daemon.** They use
Testcontainers, which cannot negotiate with Docker 29's API version — the probe
returns `BadRequestException (Status 400)` before any container starts. This is a
version incompatibility, not a broken test. The migrations *have* been verified:
they were applied by hand in a throwaway `mysql:8.0.36` container, all six, from
an empty schema, and the resulting 28 tables, 77 foreign keys, and 37 CHECK
constraints were inspected. What is unverified is that the tests will run
unattended in CI, which needs a Docker/Testcontainers pairing that negotiates.
Until then, they stay skipped — weakening them to make a suite green would have
destroyed the only automated check on the schema.

**`ddl-auto: validate` is one-directional.** It compares the entity mappings to
the schema. It cannot detect a NOT NULL column with no default that *no entity
mentions* — there is nothing to compare. A `corpus_id` column on
`report_block_citations` sat unmapped for the whole life of the project and was
only found when synthesis was first executed, at which point every synthesis
failed with `Field 'corpus_id' doesn't have a default value`. A cross-check of
every NOT NULL column against every mapping is documented in
`docs/architecture.md`; making it a permanent automated test needs the same
working Testcontainers setup.

**SSE event delivery is single-instance.** Debate progress events are held in
memory and pushed over SSE. Two backend instances behind a load balancer would
each hold only their own events, so a client connected to instance B would miss
events raised on instance A. Everything persisted is correct; only the live
notification is instance-local. Moving to a shared broker — Redis pub/sub or a
database-backed queue — would fix it without touching the domain.

**`supportCount` can over-count by one under contention.** It is maintained by
an atomic `UPDATE ... SET support_count = support_count + 1`, which is correct
for every read that matters but is not a lock. It is advisory: no decision,
threshold, or verdict reads it. A counter that gates something would need a
different mechanism.

**Real extraction quality is unmeasured.** `FakeLlmClient` is deterministic
precisely because it is not a real model. The pipeline, validation, quarantine,
and approval semantics are exercised honestly, but the accuracy of extraction
against messy prose is a question this repository cannot answer.

**Conflict detection is registry-driven, and the registry is finite.**
Contradictions fire for predicates that have registered semantics — currently 28.
An unknown predicate resolves to `MULTI` cardinality, which means it can never
produce a relation conflict. That is deliberate: the failure mode is missing a
finding, not inventing one. But it does mean extending detection means extending
the registry, and a predicate nobody registered is invisible to it.

**Token lifetime is a hard expiry, not a refresh flow.** `POST /api/auth/refresh`
exists and re-issues a token, but it requires the presented token to still be
valid — it reads the authenticated principal and mints a new token for it. There
is no separate long-lived refresh token, so nothing survives the access token's
own expiry except a new sign-in, and there is no server-side revocation list to
invalidate a token before it expires. Both would need somewhere to store refresh
token state, which is a schema addition rather than a code change.