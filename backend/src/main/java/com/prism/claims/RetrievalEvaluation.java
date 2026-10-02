package com.prism.claims;

import com.prism.corpus.Corpus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One measured retrieval outcome: a query, the passage it should have found, and
 * where that passage actually ranked.
 *
 * <p>The table was created empty in migration V4 with no entity and no service,
 * so retrieval quality was unmeasured rather than measured-and-good. This class
 * and {@link RetrievalEvaluationService} give it somewhere to write.
 *
 * <p><b>What is stored is per-query evidence, not a summary metric.</b> Storing
 * the ranked list lets a later change be compared against the same queries
 * without re-running the corpus, and it lets a regression be traced to the query
 * that caused it rather than to an aggregate that moved for unknown reasons.
 * Aggregate Recall@k and MRR are computed on read.
 *
 * <p>{@code goldChunkId} is nullable: a query with no known answer cannot
 * produce a recall figure, and forcing a placeholder would put a meaningless row
 * into every average. Such a query still records where the passages ranked, which
 * is what makes an unanswerable query reviewable rather than invisible.
 */
@Entity
@Table(name = "retrieval_evaluation",
        indexes = {
                @Index(name = "ix_reval_corpus", columnList = "corpus_id"),
                @Index(name = "ix_reval_run", columnList = "corpus_id, run_key")
        })
public class RetrievalEvaluation {

    /**
     * Run key used for rows written before runs were distinguished.
     *
     * <p>Those rows came from ad-hoc measurements with no common gold set, so
     * they cannot be attributed to a run. They are grouped under one literal
     * rather than a hash, which makes them obviously synthetic, and because
     * the summary reads only the newest run they cannot contaminate anything.
     */
    public static final String LEGACY_RUN_KEY = "LEGACY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    /**
     * Identifies the benchmark run this row belongs to: the SHA-256 of the
     * canonicalised gold set.
     *
     * <p>Deterministic rather than a random id, and that is the point. Re-running
     * the same gold set produces the same key, so the run replaces itself instead
     * of adding to the previous measurement. A benchmark you cannot run twice and
     * get the same number is not a benchmark.
     */
    @Column(name = "run_key", nullable = false, length = 64, columnDefinition = "char(64)")
    private String runKey;

    /**
     * Which {@link QueryExpander} table produced these rows.
     *
     * <p>Recorded so a metric is attributable to a retriever configuration and not
     * only to a corpus. Without it, two runs of the same gold set against the same
     * documents differ for reasons the numbers cannot explain.
     */
    @Column(name = "expander_version", nullable = false, length = 32)
    private String expanderVersion;

    /** The query as issued, so the measurement can be repeated exactly. */
    @Column(name = "query_text", nullable = false, length = 500)
    private String queryText;

    /**
     * The chunk a human judged to be the correct answer, or null when the query
     * has no known answer.
     */
    @Column(name = "gold_chunk_id")
    private Long goldChunkId;

    /** 1-based rank of the gold passage, or null when it was not retrieved. */
    @Column(name = "retrieved_rank")
    private Integer retrievedRank;

    /** Relevance score the retriever assigned, preserved for recalibration. */
    @Column(name = "score", precision = 10, scale = 4)
    private BigDecimal score;

    // Stored rather than derived so a row is a complete, self-contained record
    // of one measurement. Recomputing them from the ranked list would be
    // equivalent but would make the table depend on a convention rather than on
    // its data.

    @Column(name = "recall_at_1", nullable = false)
    @ColumnDefault("0")
    private boolean recallAt1;

    @Column(name = "recall_at_3", nullable = false)
    @ColumnDefault("0")
    private boolean recallAt3;

    @Column(name = "recall_at_5", nullable = false)
    @ColumnDefault("0")
    private boolean recallAt5;

    /**
     * Reciprocal rank, 1/rank, or null when the gold passage was not retrieved.
     *
     * <p>Null rather than zero: "the passage was not retrieved at all" and "it
     * ranked below the cut-off" are different facts, and MRR's definition says
     * the former contributes nothing while saying nothing about the latter.
     */
    @Column(name = "reciprocal_rank", precision = 8, scale = 5)
    private BigDecimal reciprocalRank;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RetrievalEvaluation() {
        // for JPA
    }

    public RetrievalEvaluation(Corpus corpus, String runKey, String expanderVersion,
                               String queryText, Long goldChunkId,
                               Integer retrievedRank, BigDecimal score, boolean hasGold) {
        this.corpus = corpus;
        this.runKey = runKey;
        this.expanderVersion = expanderVersion;
        this.queryText = queryText;
        this.goldChunkId = goldChunkId;
        this.retrievedRank = retrievedRank;
        this.score = score;
        this.createdAt = Instant.now();

        // A query with no gold passage contributes to no recall metric. It is
        // still recorded, with the rank left null, so the case is visible.
        boolean hit = hasGold && retrievedRank != null;
        this.recallAt1 = hit && retrievedRank <= 1;
        this.recallAt3 = hit && retrievedRank <= 3;
        this.recallAt5 = hit && retrievedRank <= 5;
        this.reciprocalRank = hit ? BigDecimal.ONE.divide(BigDecimal.valueOf(retrievedRank), 5, java.math.RoundingMode.HALF_UP) : null;
    }

    public Long getId() {
        return id;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public String getRunKey() {
        return runKey;
    }

    public String getExpanderVersion() {
        return expanderVersion;
    }

    public String getQueryText() {
        return queryText;
    }

    public Long getGoldChunkId() {
        return goldChunkId;
    }

    public Integer getRetrievedRank() {
        return retrievedRank;
    }

    public BigDecimal getScore() {
        return score;
    }

    public boolean isRecallAt1() {
        return recallAt1;
    }

    public boolean isRecallAt3() {
        return recallAt3;
    }

    public boolean isRecallAt5() {
        return recallAt5;
    }

    public BigDecimal getReciprocalRank() {
        return reciprocalRank;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}