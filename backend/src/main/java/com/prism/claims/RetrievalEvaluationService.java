package com.prism.claims;

import com.prism.common.error.ApiException;
import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures whether retrieval finds the passage a human says is the answer.
 *
 * <p><b>Why this exists.</b> The {@code retrieval_evaluation} table was created
 * by migration V4 with no entity and no service, which means retrieval quality
 * was unmeasured rather than measured-and-good. Every other stage of PRISM
 * carries its own gate: contradictions are checked against a predicate registry,
 * verdicts are scored by a pure function with a version. Retrieval had no such
 * gate, so a regression that quietly ranked the right passage fourth would have
 * been invisible.
 *
 * <p><b>Scope.</b> This measures whether the right passage is <em>retrieved and
 * ranked</em>. It says nothing about whether the claim about that passage is
 * true — that is what the verdict path and the human verifier are for. Keeping
 * the two apart matters: a retrieval metric that appeared to measure truth would
 * be a worse metric, because it would invite optimising for the wrong thing.
 *
 * <p><b>Measurement convention.</b> MRR here averages over queries where the gold
 * passage <em>was</em> retrieved, which is the standard definition. Rows with no
 * gold answer are excluded from every metric's denominator, and rows whose gold
 * passage was never retrieved are excluded from MRR only — which is why the
 * summary reports {@code notRetrieved} separately. A reader can then see both
 * numbers rather than having to guess which convention produced a figure.
 */
@Service
public class RetrievalEvaluationService {

    private final RetrievalService retrieval;
    private final RetrievalEvaluationRepository evaluations;
    private final RetrievalRunStore runStore;
    private final CorpusAccessService access;

    public RetrievalEvaluationService(RetrievalService retrieval,
                                      RetrievalEvaluationRepository evaluations,
                                      RetrievalRunStore runStore,
                                      CorpusAccessService access) {
        this.retrieval = retrieval;
        this.evaluations = evaluations;
        this.runStore = runStore;
        this.access = access;
    }

    /** One query with the passage a human judged to be correct, if known. */
    public record GoldQuery(String query, Long goldChunkId) {
    }

    /**
     * Aggregate retrieval metrics for the newest benchmark run of a corpus.
     *
     * <p>{@code recallAtN} are fractions in [0,1] over queries that have a gold
     * answer. {@code meanReciprocalRank} averages only over queries whose gold
     * passage was retrieved. {@code notRetrieved} counts the ones that were
     * missed entirely, so the two together account for every gold query.
     *
     * @param runKey           which run these figures describe, so a number is
     *                         never anonymous
     * @param expanderVersion  the query-expansion table that produced the run
     */
    public record Summary(String runKey, String expanderVersion,
                          int totalEvaluations, int goldQueries, int notRetrieved,
                          double recallAt1, double recallAt3, double recallAt5,
                          BigDecimal meanReciprocalRank) {

        /** True when the corpus has never been measured. */
        public boolean unmeasured() {
            return runKey == null;
        }
    }

    /**
     * Deterministic identity of a gold set.
     *
     * <p>The SHA-256 of the canonicalised queries, so two runs of the same gold
     * set share a key and the second run replaces the first rather than adding to
     * it. The alternative -- a random id per run -- makes every run additive, and
     * an aggregate over additive runs reports the union of everything ever
     * measured, which moves when rows are inserted rather than when retrieval
     * changes.
     *
     * <p>Canonicalisation folds case, collapses whitespace and sorts the queries,
     * so reformatting the file, retyping a query or reshuffling the lines does not
     * create a second run that measures effectively the same thing.
     */
    public static String runKeyFor(List<GoldQuery> queries) {
        List<String> lines = new ArrayList<>(queries.size());
        for (GoldQuery q : queries) {
            lines.add(q.query().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT)
                    + "\t" + q.goldChunkId());
        }
        // Sorted, because a gold set is a set. Retrieval runs each query
        // independently, so reshuffling the file measures exactly the same thing;
        // treating order as significant would let an accidental reshuffle read as
        // a new measurement and orphan the previous run's rows.
        java.util.Collections.sort(lines);
        String canonical = String.join("\n", lines);

        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            // SHA-256 is required of every JRE. Failing here would mean the
            // benchmark could not run at all, which is the correct outcome for an
            // environment that cannot provide it.
            throw new IllegalStateException("SHA-256 is required to identify a benchmark run", ex);
        }
    }

    /**
     * Runs each query and records where the gold passage landed.
     *
     * <p>Retrieval is the real service, not a stub: this measures the same code
     * path the verification and chat stages use, so a number produced here is a
     * statement about production behaviour.
     *
     * <p><b>Not transactional, and deliberately.</b> Retrieval issues its own
     * queries and there is no coherent reason to hold a transaction across a loop
     * of them. The writes are short and independent, so each persists on its own,
     * and a run interrupted part-way keeps the measurements it already took
     * rather than discarding a minute of work.
     *
     * <p>That is also why discarding the previous run is a separate step rather
     * than part of the loop: the delete happens before any retrieval, so the
     * previous run's figures stop being current the moment this one starts.
     *
     * @param corpus an already-authorised corpus, so the scan can never be run
     *               against a corpus the caller cannot reach
     * @return the evaluations written, in query order
     */
    public List<RetrievalEvaluation> evaluate(Corpus corpus, List<GoldQuery> queries) {
        String runKey = runKeyFor(queries);
        // Discarded through a separate bean: this method is not transactional, and
        // a self-invoked @Transactional would be silently ignored.
        runStore.discard(corpus.getId(), runKey);

        List<RetrievalEvaluation> written = new ArrayList<>(queries.size());
        for (GoldQuery q : queries) {
            written.add(measureOne(corpus, runKey, q));
        }
        return written;
    }

    private RetrievalEvaluation measureOne(Corpus corpus, String runKey, GoldQuery q) {
        RetrievalService.RetrievalResult result = retrieval.retrieve(corpus.getId(), q.query());

        // Rank is 1-based and refers to the retriever's own ordering, which is
        // what a reader of the measurement needs to see: not "was it in the top
        // k" but "where did it actually come".
        Integer rank = null;
        BigDecimal score = null;
        List<RetrievalService.RetrievedPassage> passages = result.passages();
        for (int i = 0; i < passages.size(); i++) {
            RetrievalService.RetrievedPassage p = passages.get(i);
            if (p.chunkId().equals(q.goldChunkId())) {
                rank = i + 1;
                // RetrievalService scores as a double; the column is DECIMAL(10,4)
                // so the value is converted once, here, rather than letting a
                // float reach a decimal column and be silently rounded by the
                // driver.
                score = BigDecimal.valueOf(p.score()).setScale(4, RoundingMode.HALF_UP);
                break;
            }
        }

        RetrievalEvaluation evaluation = new RetrievalEvaluation(corpus, runKey,
                result.expansionVersion(), q.query(), q.goldChunkId(), rank, score, true);
        return evaluations.save(evaluation);
    }

    /**
     * Records a query that has no known answer.
     *
     * <p>Exposed separately so an unanswerable query is a deliberate, recorded
     * decision rather than a silent omission. Such a row contributes to no recall
     * metric, and its {@code retrievedRank} stays null, which distinguishes "no
     * gold passage exists" from "the gold passage was not found".
     */
    @Transactional
    public RetrievalEvaluation recordUnanswerable(Corpus corpus, String query) {
        // Recorded under its own single-query run key so it does not join, and
        // cannot dilute, a gold-set measurement.
        String runKey = runKeyFor(List.of(new GoldQuery(query, null)));
        return evaluations.save(new RetrievalEvaluation(corpus, runKey,
                QueryExpander.VERSION, query, null, null, null, false));
    }

    /**
     * Computes the summary for a corpus's newest benchmark run.
     *
     * <p><b>Scoped to one run.</b> The previous version aggregated every row the
     * corpus had ever accumulated, which made the benchmark impossible to run
     * twice: a second run of the same gold set doubled the rows, so the metrics
     * moved without retrieval changing at all, and one ad-hoc probe query stayed
     * in the corpus's headline figures permanently. It also made before/after
     * comparison unsound in the direction that matters, because adding a row is
     * exactly what improving the retriever does.
     *
     * <p>Pure arithmetic over the repository's aggregates, so the convention for
     * what enters each denominator is stated in one place rather than spread
     * across the queries.
     *
     * @return a summary whose {@code runKey} is null when the corpus has never
     *         been measured; the figures are zero rather than absent so a client
     *         does not have to handle a missing field
     */
    @Transactional(readOnly = true)
    public Summary summarise(Long corpusId, Long userId) {
        access.requireAccessible(corpusId, userId);

        String runKey = latestRunKey(corpusId);
        if (runKey == null) {
            return new Summary(null, null, 0, 0, 0, 0d, 0d, 0d, null);
        }

        long total = evaluations.countByCorpusIdAndRunKey(corpusId, runKey);
        long gold = evaluations.countByCorpusIdAndRunKeyAndGoldChunkIdIsNotNull(corpusId, runKey);
        long found = evaluations.countByCorpusIdAndRunKeyAndRetrievedRankIsNotNull(corpusId, runKey);

        double r1 = 0d;
        double r3 = 0d;
        double r5 = 0d;
        List<Object> recalls = evaluations.recallSummary(corpusId, runKey);
        if (recalls != null && !recalls.isEmpty() && recalls.get(0) != null) {
            Object[] row = recalls.get(0) instanceof Object[] o ? o : new Object[]{recalls.get(0)};
            r1 = toDouble(row[0]);
            r3 = toDouble(row.length > 1 ? row[1] : null);
            r5 = toDouble(row.length > 2 ? row[2] : null);
        }
        BigDecimal mrr = evaluations.meanReciprocalRank(corpusId, runKey);
        String version = evaluations.findExpanderVersions(corpusId, runKey,
                org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst().orElse(null);

        return new Summary(runKey, version, (int) total, (int) gold, (int) (gold - found),
                round(r1), round(r3), round(r5), mrr);
    }

    @Transactional(readOnly = true)
    public List<RetrievalEvaluation> recent(Long corpusId, Long userId, int limit) {
        access.requireAccessible(corpusId, userId);
        return evaluations.findRecent(corpusId,
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    /**
     * The rows of one run, in gold-set order.
     *
     * @param runKey null means the newest run, which is what a caller asking for
     *               "the rows" almost always wants
     */
    @Transactional(readOnly = true)
    public List<RetrievalEvaluation> runRows(Long corpusId, Long userId, String runKey) {
        access.requireAccessible(corpusId, userId);
        String key = runKey != null ? runKey : latestRunKey(corpusId);
        return key == null ? List.of() : evaluations.findRun(corpusId, key);
    }

    /**
     * Runs a gold set and returns the summary in one call.
     *
     * <p>Convenience for the controller, and the reason a run is one HTTP request:
     * measuring and reporting separately would let a caller read a summary that
     * described somebody else's run.
     */
    public Summary evaluateAndSummarise(Corpus corpus, Long userId, List<GoldQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            throw ApiException.validation("a gold set must contain at least one query");
        }
        evaluate(corpus, queries);
        return summarise(corpus.getId(), userId);
    }

    /**
     * Parses a gold set supplied as {@code query = chunkId} lines.
     *
     * <p>A line format rather than JSON because a gold set is data a person
     * maintains by hand alongside a corpus, and {@code query = 412} is something
     * they can write and read. Blank lines and {@code #} comments are ignored so
     * the file can be annotated.
     *
     * <p><b>Splits on the last {@code =}, not the first.</b> A natural-language
     * gold query can legitimately contain an equals sign — "revenue = 4.2m or
     * higher" is a real question — and the chunk id is always a bare integer in
     * the final field. Splitting on the first separator would truncate such a
     * query and then measure the wrong text while reporting a plausible-looking
     * score, which is worse than rejecting it.
     *
     * <p>Every line is validated and every failure names its line number. A
     * partially-applied gold set would produce metrics that look real and are
     * silently wrong.
     */
    public static List<GoldQuery> parseGoldSet(String text) {
        List<GoldQuery> parsed = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.lastIndexOf('=');
            if (eq <= 0) {
                throw ApiException.validation("line " + (i + 1) + " is not 'query = chunkId': " + line);
            }
            String query = line.substring(0, eq).trim();
            String idText = line.substring(eq + 1).trim();
            long chunkId;
            try {
                chunkId = Long.parseLong(idText);
            } catch (NumberFormatException ex) {
                throw ApiException.validation("line " + (i + 1) + " has a non-numeric chunkId: " + idText);
            }
            if (query.isEmpty()) {
                throw ApiException.validation("line " + (i + 1) + " has an empty query");
            }
            if (query.length() > 500) {
                throw ApiException.validation("line " + (i + 1) + " query exceeds 500 characters");
            }
            // Rejected rather than tolerated. A duplicated line is measured twice
            // and so counts double in every recall average, which looks like a
            // retrieval result rather than a typo in the file. It is also the one
            // mistake that silently biases the benchmark instead of failing it.
            String canonical = query.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
            for (GoldQuery existing : parsed) {
                if (existing.query().replaceAll("\\s+", " ")
                        .toLowerCase(java.util.Locale.ROOT).equals(canonical)) {
                    throw ApiException.validation("line " + (i + 1)
                            + " repeats an earlier query: " + query
                            + " -- a duplicated query is counted twice in every average");
                }
            }
            parsed.add(new GoldQuery(query, chunkId));
        }
        if (parsed.isEmpty()) {
            throw ApiException.validation("the gold set is empty; at least one 'query = chunkId' line is required");
        }
        return parsed;
    }

    private static double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0d;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * The newest run for a corpus, or null when it has never been measured.
     *
     * <p>Rows of one run are written in order, so the highest id is the most
     * recent row and its run key names the newest run.
     */
    private String latestRunKey(Long corpusId) {
        return evaluations.findLatestRunKeys(corpusId,
                org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst().orElse(null);
    }
    /** Exposed for the controller's response record. */
    public Map<String, Object> toResponse(Summary s) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runKey", s.runKey());
        body.put("expanderVersion", s.expanderVersion());
        body.put("measured", !s.unmeasured());
        body.put("totalEvaluations", s.totalEvaluations());
        body.put("goldQueries", s.goldQueries());
        body.put("notRetrieved", s.notRetrieved());
        body.put("recallAt1", s.recallAt1());
        body.put("recallAt3", s.recallAt3());
        body.put("recallAt5", s.recallAt5());
        body.put("meanReciprocalRank", s.meanReciprocalRank());
        body.put("mrrConvention", "averaged over gold queries whose passage was retrieved");
        body.put("scope", "the newest benchmark run for this corpus; re-running a gold set replaces its run");
        return body;
    }
}