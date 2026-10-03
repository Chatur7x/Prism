# Performance baseline

**This is a baseline on one developer machine. It is not a scalability claim and
no number here supports one.** A single sample from a 24-document corpus on a
laptop says nothing about behaviour under load, and the corpus is three orders of
magnitude smaller than anything a real deployment would hold.

It is recorded because "we have never measured this" is a worse position than
"we measured it once, here, under these conditions".

## Environment

| | |
|---|---|
| CPU | Intel Core Ultra 5 125H, 18 logical cores |
| RAM | 15.4 GB |
| OS | Windows 11 Home, Single Language |
| JVM | OpenJDK 21.0.12.1 LTS |
| Database | MySQL 8.0.36 in Docker, FULLTEXT enabled |
| Provider | `LLM_PROVIDER=fake` — the offline fixture |
| Corpus | 24 documents, 87 chunks, 58 approved triples |

The provider matters more than any other line above. With the offline fixture,
every "extraction" timing below measures chunking plus deterministic parsing. **No
figure in this document includes model latency, because no model has been
evaluated.** See [`evaluation.md`](evaluation.md) and
[`limitations.md`](limitations.md).

## Read path, single sample

| Step | ms |
|---|---|
| documents page (50) | 426 |
| document detail | 206 |
| document chunks | 145 |
| corpora list | 627 |
| graph nodes | 671 |
| graph `VERIFIED_ONLY` | 527 |
| PageRank (limit 100) | 258 |
| PageRank (limit 100, warm) | 111 |
| communities | 169 |
| claims (50) | 234 |
| contradictions (50) | 462 |
| trace list (20) | 1044 |
| retrieval evaluation (11 queries) | 141 |

`trace list` is the slowest read at 1044 ms. It is the first endpoint that joins
across the trace tables to assemble a run summary, and it is the one to watch if
these numbers ever matter. It is also the least cached, being observability data
rather than knowledge.

PageRank drops from 258 ms to 111 ms on a second call. That gap is the JVM warming
up plus MySQL's buffer pool filling, not a cache in the application — PageRank is
computed per request and cached results are not part of the design.

## Write path

| Step | ms |
|---|---|
| upload (create + enqueue) | 471 |
| chunk + extract to completion (6 chunks, 6 triples, 6 claims, 0 quarantined) | 6193 |

The 6193 ms is **wall clock until the document reached `AWAITING_APPROVAL`**,
measured by polling every 300 ms. It therefore includes up to 300 ms of polling
slack and the full background round trip. It is not per-chunk extraction time and
should not be read as one.

With the offline fixture, that figure is chunking plus deterministic parsing. Under
a real provider it would additionally contain one model call per chunk, sequential
per chunk, with `maxRetries` at 3 — so it would be dominated by provider latency
and cost, not by anything measurable in this repository today.

## What was not measured, and why it matters

- **Any model latency, retry count or cost.** No credentials were available, so no
  real provider was called. This is the single largest gap in this document and it
  is the same gap as the blocking release limitation.
- **Debate, synthesis and chat latency.** The Council script exercises them and
  passes, but it reports pass/fail rather than timings. Adding timings to a
  78-check lifecycle script was judged lower value than the schema contract check
  and the authorisation sweep.
- **Concurrent load.** No load generator was run. Nothing here should be read as
  throughput.
- **Approval, verification and adjudication latency.** A short-lived JWT expired
  mid-measurement. Re-authenticating was not repeated because the write path above
  already covers the dominant cost, and the read path covers the endpoint shapes.

## Why access tokens expired mid-measurement

Worth recording rather than hiding, because it bit the measurement twice. Access
tokens are short-lived and there is **no refresh token** (see
[`limitations.md`](limitations.md) §5). A client whose token expires cannot renew
it and must sign in again. Any measurement or load script must therefore
re-authenticate on a schedule, or budget for the failure.

## Reproducing

```bash
# local backend
export DB_URL='jdbc:mysql://localhost:3307/prism?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8'
export DB_USERNAME=prism DB_PASSWORD=prism
export JWT_SECRET='prism-dev-secret-key-that-is-definitely-long-enough-32b'
export LLM_PROVIDER=fake
cd backend && mvn spring-boot:run

# then, as a verifier
curl -o /dev/null -s -w 'documents page  %{time_total}s\n' \
  'http://localhost:8080/api/documents?corpusId=1&size=50' -H "Authorization: Bearer $TOKEN"
```

Single-sample timings from `curl -w '%{time_total}'` are reproducible; the table
above was collected the same way. Treat any single figure as having at least
several hundred milliseconds of noise on this machine.