# Evaluating PRISM

This document is about three different things that are easy to confuse, and the
distinctions are the reason each has its own evaluation.

1. **Does the system find the right passage?** — `retrieval_evaluation`.
2. **Is the claim about that passage true?** — the verdict path and the human
   verifier.

They are separate on purpose. A retrieval metric that appeared to measure truth
would invite optimising for the wrong thing, and the wrong thing is easy to
optimise for: raise Recall@1 by retrieving longer chunks, and measured
"groundedness" goes up while actual grounding gets worse.

## Extraction

### What it measures, and what it cannot

`POST /api/llm-evaluation/extraction?corpusId=N` runs the production extraction
path — the same `EXTRACT_V1` prompt, the same retry policy, the same parser, the
same validator — over every chunk carrying a hand-checked label, and reports
triple precision/recall/F1, claim precision/recall/F1, malformed-output rate and
quarantine rate. It is read-only: it persists nothing and creates no trace run,
because it must not be able to change the state it measures.

The gold set is `eval/gold-extraction.tsv`, 99 rows over 23 of the 24 demo
documents and 15 of the 28 registered predicates. Every row records the document
and the sentence it was read from, and `GoldExtractionSetTest` asserts each
sentence appears verbatim in that document, so a fabricated or drifted label
fails the build rather than quietly depressing a measured score. The set is
frozen and is **not** regenerated from the corpus: a set derived from whatever
the corpus currently says cannot detect that the corpus changed underneath a
measurement. The 13 uncovered predicates are absent because the corpus never
states one; padding them would improve coverage on paper and be a lie about the
corpus.

**Read this before quoting any figure from it.** The demo corpus states every
extractable fact in canonical `Subject predicate Object` form, and its prose is
deliberately uninformative — document 24 states no extractable fact at all. So
these numbers measure transcription and entity resolution. They do **not**
measure extraction from natural prose, and a model will score near 100% here by
pattern-matching. That caveat is a constant the report embeds as
`corpusLimitation` rather than prose in a document someone may not read.

### Measured, against the offline provider

Run on 2026-10-02, 23 chunks carrying a label, `LLM_PROVIDER=fake`,
`EXTRACT_V1`, temperature 0.0, max tokens 2048, max retries 3:

| | P | R | F1 | tp | fp | fn |
|---|---|---|---|---|---|---|
| triples | 0.9706 | 1.0000 | 0.9851 | 99 | 3 | 0 |
| claims | 0.3641 | 0.6768 | 0.4735 | 67 | 117 | 32 |

| | |
|---|---|
| malformed rate | 0.0000 (0 of 23) |
| quarantine rate | 0.0000 (0 of 23) |
| provider errors | 0 |
| retries exhausted | 0 |

**No real model has been evaluated.** These figures describe a deterministic
fixture and the pipeline around it. `scripts/llm-eval.ps1` prints
`PROVIDER: FAKE / TEST MODE` in that case and exits 3 if every chunk was refused,
so a fixture run cannot be presented as a model run.

### Running it against a real model

Set before starting the backend — never hardcode a credential:

```powershell
$env:LLM_PROVIDER       = 'openai'      # any OpenAI-compatible endpoint
$env:LLM_BASE_URL       = 'https://your-endpoint/v1'
$env:LLM_API_KEY        = '<key>'
$env:LLM_EXTRACT_MODEL  = 'your-model'
```

then:

```powershell
powershell -File scripts/llm-eval.ps1 -Username <verifier> -Password '...' -CorpusId 1
```

The script writes a dated JSON report to `eval/report-<provider>-<timestamp>.json`
containing provider, model, prompt version, temperature, max tokens, max retries,
gold-set size, timestamp, the matching rule and per-chunk outcomes. Reports are
gitignored; the gold set is tracked.

Entity-resolution precision/recall/F1 are **not** measured. Resolving entities
requires writing them, which would corrupt the corpus being measured, and the
harness is deliberately read-only. That is recorded as absent rather than
approximated. Verdict accuracy against a hand-labelled claim set is likewise not
yet measured; the verdicts produced for the seeded corpus come from the offline
provider, which returns no `SUPPORTED`, so there is no positive class to score.

---

## Extraction from prose

The extraction figures above come from the **canonical** gold set, which states
every fact as `Subject predicate Object`. That set measures transcription. It
cannot measure extraction from natural prose, and this section is the one that
can.

### The dataset

`eval/prose-gold-v1.json`, version `prose-gold-v1`, over the ten documents in
`eval/prose-corpus/`.

| | |
|---|---|
| Labelled sentences | 81 |
| Negatives (`expectNothing`) | **48 — 59%** |
| Gold triples | 40, across 19 of the 28 registered predicates |
| Gold claims | 46, across POSITIVE, NEGATIVE and NEUTRAL |
| Documents | 10 |

The negatives are the point. A prose corpus with no denials cannot measure
precision, and denials are where extraction systems fail most expensively. The
set includes sentences that explicitly deny a relationship while naming both
parties, sentences that name a plausible false fact and reject it, and two
sentences shaped like instructions — one a literal injection string quoted as an
artefact, one genuine instructions to a human reader — both labelled to extract
nothing.

Some sentences state a real relation the vocabulary cannot express (secondment,
litigation, administration, co-location) and are labelled nothing, with the reason
recorded per sentence. Inventing a composite entity name to force them into a
predicate would have made the numbers better and the dataset a lie.

### Measured, against the offline fixture

| | canonical | prose |
|---|---|---|
| triples P / R / F1 | 0.9706 / 1.0000 / 0.9851 | **0.0000 / 0.0000 / 0.0000** |
| claims P / R / F1 | 0.3641 / 0.6768 / 0.4735 | **0.0000 / 0.0000 / 0.0000** |
| malformed | 0 of 23 | 0 of 41 |
| quarantine | 0 of 23 | 0 of 41 |
| ungrounded | not measured | 0 of 41 |

Raw totals, triples: expected 40, predicted 14, correct 0, missed 40, incorrect 14.
Claims: expected 46, predicted 169, correct 0, missed 46, incorrect 169.

**The zeroes are correct and are the most useful number in this document.** The
offline provider recognises only canonical sentences, so pointed at prose it finds
14 incidental occurrences of a predicate word — `controls`, `supplies`, `owns` — and
gets every one wrong. This is the concrete demonstration that the canonical corpus
was flattering the fixture, and it is why the prose corpus was written.

These numbers describe the fixture and the pipeline. **No model has been
evaluated.**

### The harness will not report fixture output as a model result

With `-RequireRealModel` and the fixture active, the endpoint returns HTTP 412
with the body token `REAL_MODEL_EXECUTION_REQUIRED` and the CLI exits **4**.

```bash
powershell -File scripts/llm-eval.ps1 -Username <v> -Password '<p>' \
  -CorpusId <prose-corpus> -Dataset prose -RequireRealModel
```

The status is distinct from a success and the exit code is distinct from a
failure, so a CI job cannot mistake a refusal for a pass. The token is asserted
stable by a test, because a CI job greps for that string.

### Caveats that must travel with these figures

- The dataset is **fictional prose written for the purpose**. It is not a
  statistically representative sample of real documents.
- It contains no OCR noise, tables, homonyms, aliases or transliteration
  variants, all of which make real extraction harder. **Its figures are an upper
  bound, not a forecast.**
- Expectations are claimed, not broadcast: an expectation belongs to a sentence,
  a sentence lives in one chunk, and each is handed to exactly one chunk. The
  first implementation scattered a document's whole gold set across all of its
  chunks and reported 156 expected from a 40-triple gold set — a harness
  manufacturing its own recall.
- Matching is case-folded, whitespace-collapsed equality on subject, predicate and
  object; claims additionally require matching polarity. **Entity aliases are not
  resolved before comparison**, so a near-miss spelling counts as a miss.
- A chunk the harness refuses contributes no predictions, so everything expected
  from it is a miss. Hiding that would let a provider score well by refusing to
  answer.

### Not measured

- Any real model's precision, recall or F1.
- Any real model's malformed-response or quarantine rate.
- How often a real model invents a source sentence.
- **Entity-resolution quality: not measured at all.** Resolving entities means
  writing them, which would corrupt the corpus being measured. Recorded as absent
  rather than approximated.
- Verification performance against an independent hand-labelled set. The offline
  provider produces no `SUPPORTED` verdict, so there is no positive class to
  score, and that gold set was not built in this pass.

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

Measured after query expansion was added. `expanderVersion` is recorded on the
run, so the figures are attributable to a specific retrieval configuration and not
only to a corpus.

```json
{
  "runKey": "ec09cd6463d0973357cab3e28b23f0f6900ec36024e993368365528ffd98dd29",
  "expanderVersion": "EXPAND_V1",
  "measured": true,
  "totalEvaluations": 11,
  "goldQueries": 11,
  "notRetrieved": 1,
  "recallAt1": 0.6364,
  "recallAt3": 0.9091,
  "recallAt5": 0.9091,
  "meanReciprocalRank": 0.8333
}
```

| Query | Gold | Rank | Category |
|---|---|---|---|
| Who operates the shared services migration programme | 74 | **1** | multi-hop |
| Who funds joint research in two areas | 20 | **1** | unsupported |
| Vantage Materials parent organisation | 18 | **1** | synonym |
| Which organisation owns Vantage Materials | 18 | **1** | synonym |
| Where is the Calder site located | 14 | **1** | exact entity |
| Who operates the Corvale site | 14 | **1** | exact entity |
| Who does Orion Systems compete with | 9 | **1** | relation |
| Which organisation does Aster Labs report to | 12 | 2 | relation |
| Who is the parent organisation of Aster Labs | 12 | 3 | relation |
| Who does Calder collaborate with | 20 | 2 | synonym |
| Who does Orion Systems supply | 12 | **not retrieved** | relation |

Eleven queries is a small set. These numbers indicate behaviour; they do not
establish it. A real evaluation needs hundreds of queries, and that is listed
under unfinished work rather than glossed over here.

The benchmark is idempotent. A run is keyed by the SHA-256 of its canonicalised
gold set and replaces itself, so running the same set twice returns identical
metrics — verified. Before that, the summary aggregated every row a corpus had
ever accumulated, so a second run doubled the rows and moved the numbers without
retrieval changing at all.

### Query expansion: before and after

`QueryExpander` maps question wording to the token a corpus uses. It is
deterministic, versioned, and recorded on every result — deliberately not a model,
because a prompt injected into a document must not be able to steer which
passages a verdict may cite.

Both columns measured on the same corpus, the same gold set, at the same moment:

| | Recall@1 | Recall@3 | Recall@5 | MRR | not retrieved |
|---|---|---|---|---|---|
| before | 0.6364 | 0.8182 | 0.9091 | 0.8033 | 1 |
| after (`EXPAND_V1`) | 0.6364 | **0.9091** | 0.9091 | **0.8333** | 1 |

Per query, which is the part that actually tells you anything:

| Query | Before | After |
|---|---|---|
| Who does Calder collaborate with | not retrieved | **rank 2** |
| Who does Orion Systems supply | rank 5 | **rank 6 — out of the top-5 window** |
| the other nine | unchanged | unchanged |

**One query fixed and one traded.** Recall@3 and MRR rose; Recall@1 and Recall@5
did not move. Adding `supplies` as a relevance term also pulled in three
supply-related chunks that outranked the gold one. That is the ordinary
precision/recall cost of an extra term in a bag-of-words query, and it is the
reason the before figure is shown next to the after figure rather than replaced
by it.

### Re-measured after the offline-provider fix

The table above was measured on a demo corpus built while the offline provider
had a defect: it took only the first word of a sentence as the triple subject, so
"Meridian Group" was extracted as "Meridian". That defect has been fixed, and the
corpus has since been rebuilt from an empty database. Re-running the same gold
set against the rebuilt corpus (`runKey ec09cd64…`, `EXPAND_V1`) gives:

| | Recall@1 | Recall@3 | Recall@5 | MRR | not retrieved |
|---|---|---|---|---|---|
| after, rebuilt corpus | **0.7273** | 0.9091 | **1.0** | **0.8364** | 0 |

| Query | Gold | Rank on rebuilt corpus |
|---|---|---|
| Where is the Calder site located | 14 | 1 |
| Who operates the Corvale site | 14 | 1 |
| Which organisation does Aster Labs report to | 12 | 1 |
| Who is the parent organisation of Aster Labs | 12 | 2 |
| Which organisation owns Vantage Materials | 18 | 1 |
| Vantage Materials parent organisation | 18 | 1 |
| Who does Orion Systems supply | 12 | **5** |
| Who does Orion Systems compete with | 9 | 1 |
| Who does Calder collaborate with | 20 | 2 |
| Who funds joint research in two areas | 20 | 1 |
| Who operates the shared services migration programme | 74 | 1 |

`collaborate` is still rank 2. `supply`, which the earlier measurement put at
rank 6 and outside the top-5 window, is now rank 5 and inside it, so Recall@5 is
1.0 and nothing is unretrieved.

**The cause of this improvement is not established.** Two honest candidates
remain and neither has been confirmed:

- The corpus was rebuilt from an empty database, so triple, verdict and
  contradiction state differ from the earlier run even though the chunk text and
  the gold set are byte-identical (`runKey` is unchanged, which is what proves the
  gold set is the same).
- The provider fix changed entity names from "Meridian" to "Meridian Group".
  `RetrievalService` does not read the entity table, so this should not matter,
  which is a reason to doubt it rather than to accept it.

The **before** leg was not re-run, because `QueryExpander` is a static utility
with no runtime toggle; disabling it to produce a baseline would mean editing
source. So the two rows are not a controlled comparison and are not presented as
one. The controlled comparison remains the before/after pair above, measured on
one corpus; the rebuilt-corpus row is a fresh measurement that supersedes the
earlier *after* figure.

### What the index actually does, measured

Three things about MySQL FULLTEXT contradicted the obvious assumption, and each
one changed the implementation:

1. **An underscore is a word character.** `collaborates_with` is indexed as
   *one* token. `+collaborates` scores **0** against a chunk that contains the word
   at offset 640; `+collaborates_with` scores **6.4**. An expansion to the head
   word reads as correct and expands nothing.

2. **`IN NATURAL LANGUAGE MODE` ignores boolean syntax.** `+calder +collaborate`
   and `calder collaborate` both score 0.8827 on the same chunk, and
   `(+a OR +b)` scores identically to `+a +b` in every case tried. So the base
   query was never the boolean AND the code claimed, and expansion is not
   "widening an AND" — it is adding the corpus token as an extra relevance term.
   The `OR` groups were removed, and both javadocs were corrected.
   `QueryExpanderTest` asserts no boolean syntax is emitted, so the false claim
   cannot return as documentation.

3. **Adding a term admits documents that contain it.** Point 2 in the table above
   is the cost.

### The remaining miss

`Who does Orion Systems supply` still does not retrieve chunk 12 within the
top-five window. Chunk 12 contains `supplies`, not `supply`, and InnoDB does no
stemming or prefix matching — so the expansion target was necessary, and it is
also what pushed three other chunks above the gold one.

Fixing this properly needs a reranker that scores a passage against the question
rather than against its own tokens, or a gold set large enough to tell a
systematic bias from noise. Neither is in scope here, so it stays open.

**What the system did while unable to answer is worth recording.** The chat
response came back `grounded=true` **and** `insufficientEvidence=true`, citing
whatever it had retrieved. It did not manufacture an answer and it flagged its own
evidence as insufficient. A system that answered confidently on this input would
be far more dangerous than one that admits it does not know — and two real
findings came out of the diagnosis:

1. **A vocabulary gap between how a corpus is written and how a person asks is
   the dominant error mode**, and no amount of ranking tuning fixes it. The gold
   set caught it on its first honest run.
2. **`insufficientEvidence=true` alongside a citation is the correct outcome, not
   a contradiction of itself.** "I retrieved something, and it does not answer
   your question" is exactly the state a grounded system should be in.

### What would improve it

In rough order of expected value per unit of work:

- **A reranker.** `Who does Orion Systems supply` now fails because adding a
  relevance term admits other chunks. A reranker that scores a passage against the
  question, rather than against its own tokens, is the fix; expansion alone
  cannot be, and the before/after table shows the cost it pays.
- **Chunk-level answering, not chunk-level retrieval.** Chunk 20 is a whole
  memo. Splitting it would put the one relevant sentence in a much smaller
  candidate, which helps precision and Recall@1 together.
- **Entity-aware boosting.** `Calder` resolves to `Calder Dynamics` through entity
  resolution; retrieval does not currently use that resolution.
- **A larger gold set**, ideally several hundred queries including paraphrases
  and unanswerable ones, so the numbers mean something. Not done: the labels have
  to be hand-checked, and a language-model-generated set would measure agreement
  with the generator rather than whether retrieval works.
- **A real-model extraction evaluation.** See `docs/limitations.md` §1. Every
  number above was produced with the offline provider, so none of them says
  anything about extraction quality.

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
  queries, and queries with no answer. Not attempted: the labels have to be
  hand-checked, and generating them would measure agreement with the generator.
- **Extraction accuracy is unmeasured.** The offline provider is deterministic
  precisely because it is not a real model. The pipeline, validation, quarantine,
  and approval semantics are exercised honestly, but how well extraction handles
  messy prose is a question this repository cannot answer. See
  `docs/limitations.md` §1.
- **No inter-annotator agreement.** The gold set was checked by one person. Where
  two readers disagree about the correct passage, the set should record both.
- **No regression baseline in CI.** The gold set can be re-run and compared by
  hand, but nothing fails a build when Recall@1 drops. That needs a threshold
  someone has agreed to hold, which is a decision rather than a measurement.
