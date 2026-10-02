package com.prism.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run identity for the retrieval benchmark.
 *
 * <p>The defect these tests describe was not hypothetical. The summary aggregated
 * every row a corpus had ever accumulated, so running the benchmark twice
 * doubled the rows and moved the metrics without retrieval changing at all, and
 * a single ad-hoc probe query stayed in the corpus's headline figures
 * permanently. Worse, adding a row is exactly what improving the retriever does,
 * so the aggregate could report a real improvement as a regression.
 */
class RetrievalRunKeyTest {

    private static final List<RetrievalEvaluationService.GoldQuery> GOLD = List.of(
            new RetrievalEvaluationService.GoldQuery("Who does Calder collaborate with", 20L),
            new RetrievalEvaluationService.GoldQuery("Where is the Calder site located", 14L));

    @Test
    @DisplayName("the same gold set always produces the same run key")
    void deterministic() {
        // Re-running the benchmark must replace the previous run rather than add
        // to it, which is only possible if the key is a function of the gold set.
        assertEquals(RetrievalEvaluationService.runKeyFor(GOLD),
                RetrievalEvaluationService.runKeyFor(GOLD));
        assertEquals(RetrievalEvaluationService.runKeyFor(GOLD),
                RetrievalEvaluationService.runKeyFor(List.of(GOLD.get(0), GOLD.get(1))));
    }

    @Test
    @DisplayName("reformatting the gold file does not create a second run")
    void insensitiveToFormatting() {
        // Reformatting or retyping a query measures the same thing. If whitespace
        // or case changed the key, every reformat would silently become a new run
        // and the "newest run" summary would describe a measurement nobody made.
        List<RetrievalEvaluationService.GoldQuery> reformatted = List.of(
                new RetrievalEvaluationService.GoldQuery("  who does   calder COLLABORATE with  ", 20L),
                new RetrievalEvaluationService.GoldQuery("Where is the Calder site located", 14L));

        assertEquals(RetrievalEvaluationService.runKeyFor(GOLD),
                RetrievalEvaluationService.runKeyFor(reformatted));
    }

    @Test
    @DisplayName("a different question or a different gold chunk is a different run")
    void sensitiveToContent() {
        List<RetrievalEvaluationService.GoldQuery> otherQuestion = List.of(
                new RetrievalEvaluationService.GoldQuery("Who funds Calder", 20L),
                GOLD.get(1));
        List<RetrievalEvaluationService.GoldQuery> otherChunk = List.of(
                new RetrievalEvaluationService.GoldQuery("Who does Calder collaborate with", 74L),
                GOLD.get(1));

        assertNotEquals(RetrievalEvaluationService.runKeyFor(GOLD),
                RetrievalEvaluationService.runKeyFor(otherQuestion));
        assertNotEquals(RetrievalEvaluationService.runKeyFor(GOLD),
                RetrievalEvaluationService.runKeyFor(otherChunk));
    }

    @Test
    @DisplayName("reordering the gold set is the same measurement")
    void insensitiveToOrder() {
        // Retrieval is per query and independent, so a gold set in a different
        // order produces the same figures. Treating order as significant would
        // mean an accidental reshuffle reads as a new measurement.
        assertEquals(RetrievalEvaluationService.runKeyFor(GOLD),
                RetrievalEvaluationService.runKeyFor(List.of(GOLD.get(1), GOLD.get(0))));
    }

    @Test
    @DisplayName("the key is 64 hex characters, so it fits the column exactly")
    void shape() {
        // The column is CHAR(64). A 63- or 65-character key would be truncated or
        // rejected, and truncation would merge distinct runs.
        String key = RetrievalEvaluationService.runKeyFor(GOLD);
        assertEquals(64, key.length(), key);
        assertTrue(key.matches("[0-9a-f]{64}"), key);
    }

    @Test
    @DisplayName("a query with no gold answer gets its own run, not a shared one")
    void unanswerableIsIsolated() {
        // recordUnanswerable derives its key from the query alone. Sharing a key
        // with a gold-set run would let a no-gold row dilute its recall averages.
        String fromGoldSet = RetrievalEvaluationService.runKeyFor(GOLD);
        String fromSingle = RetrievalEvaluationService.runKeyFor(
                List.of(new RetrievalEvaluationService.GoldQuery("an unanswerable question", null)));

        assertNotEquals(fromGoldSet, fromSingle);
    }
}
