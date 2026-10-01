# Evaluating PRISM

This document is about two different things that are easy to confuse, and the
distinction is the reason retrieval has its own evaluation at all.

1. **Does the system find the right passage?** — `retrieval_evaluation`.
2. **Is the claim about that passage true?** — the verdict path and the human
   verifier.

They are separate on purpose. A retrieval metric that appeared to measure truth
would invite optimising for the wrong thing, and the wrong thing is easy to
optimise for: raise Recall@1 by retrieving longer chunks, and measured
"groundedness" goes up while actual grounding gets worse.

---

## Retrieval

### Why it exists

The `retrieval_evaluation` table was created by migration V4 with no entity and
no service behind it. Every other stage of PRISM carries its own gate —
contradictions are checked against a predicate registry, verdicts are scored by a
pure function with a version — but retrieval had none. A change that quietly
ranked the correct passage fifth would have been invisible: the system would
still answer, still cite something, and still look healthy.

So this is a gate, not a diagnostic. `VERIFIER`-gated, because deciding whether
a retriever change is safe to keep is a judgement about the system rather than a
read of it.

### What it measures

For each gold query: does the passage a human says is the answer come back, and
at what rank?

| Metric | Definition |
|---|---|
| `recallAt1` | Gold passage ranked first |
| `recallAt3` | Gold passage in the top 3 |
| `recallAt5` | Gold passage in the top 5 |
| `meanReciprocalRank` | Mean of `1/rank` |
| `notRetrieved` | Gold passages not returned at all |

`topK` is 5 by default and `minScore` is 0.0, so Recall@5 is currently bounded
by the retriever itself rather than by a cut-off — a query that misses entirely
returned nothing relevant in its 5 candidates, it was not truncated.

### Conventions, stated rather than assumed

**MRR averages over queries whose gold passage was actually retrieved.** That is
the standard definition. It is called out explicitly in the API response
(`mrrConvention`) because the alternative — counting a miss as `1/0` — is also in
use, and a reader handed a bare `0.82` cannot tell which produced it.

**Queries with no gold answer are excluded from every denominator.** A query with
no known answer cannot be got right or wrong. Including it would drag every
metric toward zero for reasons that have nothing to do with retrieval quality.

**A gold passage that was never retrieved is excluded from MRR only**, and
counted separately in `notRetrieved`. `goldQueries` and `notRetrieved` together
account for every gold query, so nothing is quietly dropped.

### The gold set

`query = goldChunkId` lines, `#` comments and blank lines ignored. A line format
rather than JSON because a person maintains it by hand beside a corpus and
`query = 412` is something they can write and read.

**Split on the last `=`, not the first.** A natural-language query can contain
one — "revenue = 4.2m or higher" is a real question — and the chunk id is always
a bare integer in the final field. Splitting on the first separator truncated
such a query and then measured the wrong text while reporting a plausible-looking
score. A test caught this.

**Every line is validated before any retrieval runs**, and each failure names its
line number. A partially-applied gold set produces metrics that look real and are
silently wrong, which is worse than producing nothing.

```bash
curl -X POST 'http://localhost:8080/api/retrieval-evaluation?corpusId=1' \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"goldSet":"Where is the Calder site located = 14"}'
```

`GET /api/retrieval-evaluation/rows` returns per-query rows, not just the
aggregate, so a regression can be traced to *the query that caused it* instead of
only being observed as a number moving.

### The gold set used below

Eleven queries against the demo corpus, each hand-checked so that the correct
answer is **stated in exactly one chunk**. That constraint matters: for a fact
mentioned in two memos, "which passage is correct" becomes a judgement call, and
a metric built on judgement calls is not a measurement.

```
Where is the Calder site located              = 14
Who operates the Corvale site                 = 14
Which organisation does Aster Labs report to  = 12
Who is the parent organisation of Aster Labs  = 12
Which organisation owns Vantage Materials     = 18
Vantage Materials parent organisation         = 18
Who does Orion Systems supply                 = 12
Who does Orion Systems compete with           = 9
Who does Calder collaborate with              = 20
Who funds joint research in two areas         = 20
Who operates the shared services migration programme = 74
```

The last four are deliberately mixed phrasing — some use the corpus's own
predicate word, some use ordinary English. That is the point of the set: it
measures how a human's question behaves against a corpus that is written in
`Subject predicate Object` form.

### Result

```json
{
  "totalEvaluations": 11,
  "goldQueries": 11,
  "notRetrieved": 1,
  "recallAt1": 0.6364,
  "recallAt3": 0.8182,
  "recallAt5": 0.9091,
  "meanReciprocalRank": 0.82
}
```

| Query | Gold | Rank |
|---|---|---|
| Who operates the shared services migration programme | 74 | **1** |
| Who funds joint research in two areas | 20 | **1** |
| Vantage Materials parent organisation | 18 | **1** |
| Which organisation owns Vantage Materials | 18 | **1** |
| Where is the Calder site located | 14 | **1** |
| Who operates the Corvale site | 14 | **1** |
| Who does Orion Systems compete with | 9 | **1** |
| Which organisation does Aster Labs report to | 12 | 2 |
| Who is the parent organisation of Aster Labs | 12 | 2 |
| Who does Orion Systems supply | 12 | 5 |
| Who does Calder collaborate with | 20 | **not retrieved** |

Eleven queries is a small set. These numbers indicate behaviour; they do not
establish it. A real evaluation needs hundreds of queries, and that is listed
under unfinished work rather than glossed over here.

### The one miss, diagnosed

`Who does Calder collaborate with` did not retrieve chunk 20 — which contains
the sentence `Calder Dynamics collaborates_with Helix Consortium`.

The query says **collaborate**. The corpus says **`collaborates_with`**. Those are
different tokens, and nothing bridges them: query normalisation strips
punctuation and content words but does not stem, and there is no synonym
expansion. The query's other content words — *Calder*, *Helix* — do appear in
chunk 20, but chunk 20 is a long multi-section memo, so term frequency ranks
several other Calder or Helix chunks above it.

What retrieval did return was chunk 14, the Calder *site register* — which has
nothing to do with collaboration.

**The interesting part is what the system did next.** The chat response came back
`grounded=true` **and** `insufficientEvidence=true`, with a single citation to
the irrelevant chunk 14. It did not manufacture an answer, and it flagged its own
evidence as insufficient. A system that answered this confidently with a citation
to the site register would be far more dangerous than one that admits it does not
know.

Two real findings here, both worth stating:

1. **A vocabulary gap exists between how a corpus is written and how a person
   asks.** This is the dominant error mode, and no amount of ranking tuning fixes
   it — the fix is query expansion or predicate-aware normalisation, which is
   listed as unfinished work. The gold set caught this on its first honest run,
   which is the argument for having one.
2. **`insufficientEvidence=true` alongside a citation is the correct outcome,
   not a contradiction of itself.** "I retrieved something, and it does not
   answer your question" is exactly the state a grounded system should be in.

### What would improve it

In rough order of expected value per unit of work:

- **Predicate-aware query expansion.** Map question words onto the predicate
  vocabulary — `collaborate` → `collaborates_with`. The predicate registry
  already exists; the mapping does not. This is the single highest-value change.
- **Chunk-level answering, not chunk-level retrieval.** Chunk 20 is a whole
  memo. Splitting it would put the one relevant sentence in a much smaller
  candidate, which helps precision and Recall@1 together.
- **Entity-aware boosting.** `Calder` resolves to `Calder Dynamics` through entity
  resolution; retrieval does not currently use that resolution.
- **A larger gold set**, ideally several hundred queries including paraphrases
  and unanswerable ones, so the numbers mean something.

---

## Verdicts

Not evaluated by the retrieval harness, and deliberately kept out of it. A
verdict is a judgement about a claim's truth, and the four things it carries are
stored as four independent fields:

| Field | Question it answers |
|---|---|
| `llmScore` | What did the model say? |
| `rulePenalty` | What did deterministic Java deduct? |
| `fusedScore` | How should this be ordered? |
| `evidenceStatus` | Was there evidence to check against? |

`fusedScore` is **a ranking score, not a calibrated probability.** It is useful
for ordering findings. It is not a claim that something is 87% likely, and nothing
in the system treats it as one — no threshold in the verdict logic is expressed
in terms of it.

`rulePenalty` is the half that does not need a model. Weasel language and
absolute claims each cost a fixed, versioned amount, capped. Given the same claim
text it produces the same number every time, which is what makes it the
reproducible part of the score.

The verdict *type* — `SUPPORTED`, `CONTRADICTED`, `INSUFFICIENT_EVIDENCE`,
`SOURCE_MISSING` — is separate from all four fields, and `SOURCE_MISSING` is
neither of the other two. A finding whose source could not be resolved, a finding
its source refutes, and a finding with nothing to check against are three
different situations. Collapsing them would turn *we don't know* into *we know it
is false*, which in an audit system is the worst available error.

When a human adjudicates, the machine verdict is **preserved, not overwritten**,
and the superseded values are retrievable through
`GET /api/verdicts/{id}/history`. The machine's decision stays on the record
alongside the human's.

---

## Contradiction detection

Deterministic, and its accuracy is therefore a property of the registry rather
than of a model.

The demo corpus is built to test the boundary, not just the happy path:

| Planted | Predicates | Expected |
|---|---|---|
| 5 conflicts | `reports_to`, `headquartered_in`, `parent_organization`, `chief_executive`, `subsidiary_of` | Detected |
| 1 violation | `located_in` | Detected |
| 26 relations | `supplies`, `controls`, `funds`, `member_of`, `collaborates_with`, `invests_in` | **Not** detected |
| 1 memo | — | Yields nothing |

All 5 planted conflicts are detected and none of the 26 multi-valued relations
is flagged. The second row of that table is the one worth checking. A demo where
everything conflicts proves nothing about whether the system can tell a real
conflict from a merely numerous one.

**An unknown predicate resolves to `MULTI` cardinality**, so it can never produce
a relation conflict. That is a deliberate choice of failure direction: the cost
is a missed finding, not a false accusation. It also means detection does not
extend automatically — a predicate nobody registered is invisible to it, and
there are currently 28 registered predicates.

One real bug this evaluation found, which is worth recording because the class is
unusual: `chief_executive` was in the predicate registry and planted in the demo
corpus, but was **absent from the offline provider's vocabulary**. The fact was in
the corpus, the system knew the predicate, and the contradiction it would have
revealed was silently unfindable. No single component was wrong — the registry
was right and the provider was right — so only comparing them catches it.
`PredicateVocabularyConsistencyTest` now asserts the provider's vocabulary and
the registry's are equal in both directions.

---

## Unfinished

- **A real gold set.** Eleven hand-checked queries indicate behaviour; they do
  not establish it. Hundreds are needed, including paraphrases, multi-hop
  queries, and queries with no answer.
- **Predicate-aware query expansion**, which is the fix for the one miss and the
  error class behind it.
- **Extraction accuracy is unmeasured.** The offline provider is deterministic
  precisely because it is not a real model. The pipeline, validation, quarantine,
  and approval semantics are exercised honestly, but how well extraction handles
  messy prose is a question this repository cannot answer.
- **No inter-annotator agreement.** The gold set was checked by one person. Where
  two readers disagree about the correct passage, the set should record both.
- **No regression baseline in CI.** The gold set can be re-run and compared by
  hand, but nothing fails a build when Recall@1 drops. That needs a threshold
  someone has agreed to hold, which is a decision rather than a measurement.