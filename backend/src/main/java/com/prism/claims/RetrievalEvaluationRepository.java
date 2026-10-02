package com.prism.claims;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface RetrievalEvaluationRepository extends JpaRepository<RetrievalEvaluation, Long> {

    /**
     * Aggregate recall over one run's rows, restricted to queries that have a
     * gold passage.
     *
     * <p>Rows without a gold answer are excluded from the denominator, because a
     * query with no known answer cannot be got right or wrong. Including them
     * would drag every metric toward zero for reasons that have nothing to do
     * with retrieval quality.
     *
     * <p>Scoped by {@code runKey}: an aggregate over every row a corpus ever
     * accumulated is the union of every measurement ever taken of it, which
     * moves when rows are added rather than when retrieval changes.
     */
    @Query("select avg(case when e.recallAt1 then 1.0 else 0.0 end), "
            + "       avg(case when e.recallAt3 then 1.0 else 0.0 end), "
            + "       avg(case when e.recallAt5 then 1.0 else 0.0 end) "
            + "from RetrievalEvaluation e "
            + "where e.corpus.id = :corpusId and e.runKey = :runKey and e.goldChunkId is not null")
    List<Object> recallSummary(@Param("corpusId") Long corpusId, @Param("runKey") String runKey);

    /**
     * Mean reciprocal rank over one run's rows where the gold passage was found.
     *
     * <p>{@code avg} ignores nulls, so unretrieved gold passages drop out rather
     * than counting as a reciprocal rank of zero. That matches the definition of
     * MRR as "over queries where a relevant document was retrieved" and is
     * stated explicitly in {@link RetrievalEvaluationService#summarise} so a
     * reader is never left guessing which convention produced the number.
     */
    @Query("select avg(e.reciprocalRank) from RetrievalEvaluation e "
            + "where e.corpus.id = :corpusId and e.runKey = :runKey and e.goldChunkId is not null")
    BigDecimal meanReciprocalRank(@Param("corpusId") Long corpusId, @Param("runKey") String runKey);

    /**
     * The run key of the most recent run for a corpus.
     *
     * <p>Ordered by id descending, not by {@code max(runKey)}. A run key is a
     * SHA-256 hex digest, so {@code max()} over one compares strings and has
     * nothing to do with time: with a case-insensitive collation {@code LEGACY}
     * sorts above {@code ec09cd...}, and the summary silently described rows that
     * were months old. Ordering by the auto-increment id is the only ordering
     * that is actually chronological, because ids are assigned in write order.
     *
     * <p>One row, so the caller takes the first element of a
     * {@code Pageable}-limited list rather than a scalar: a scalar-returning
     * single-result query throws if a second row ever matches, which is a
     * surprising way to learn the query is ambiguous.
     */
    @Query("select e.runKey from RetrievalEvaluation e "
            + "where e.corpus.id = :corpusId order by e.id desc")
    List<String> findLatestRunKeys(@Param("corpusId") Long corpusId,
                                   org.springframework.data.domain.Pageable pageable);

    /** The expansion version that produced the newest run, for the summary. */
    @Query("select e.expanderVersion from RetrievalEvaluation e "
            + "where e.corpus.id = :corpusId and e.runKey = :runKey order by e.id desc")
    List<String> findExpanderVersions(@Param("corpusId") Long corpusId,
                                      @Param("runKey") String runKey,
                                      org.springframework.data.domain.Pageable pageable);

    long countByCorpusId(Long corpusId);

    long countByCorpusIdAndRunKey(Long corpusId, String runKey);

    long countByCorpusIdAndRunKeyAndGoldChunkIdIsNotNull(Long corpusId, String runKey);

    /** How many gold passages were retrieved at all, i.e. were never lost. */
    long countByCorpusIdAndRunKeyAndRetrievedRankIsNotNull(Long corpusId, String runKey);

    /**
     * Discards a previous run of the same gold set.
     *
     * <p>Bulk delete rather than loading and removing each row: a 100-query gold
     * set is 100 entities to read for no purpose.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RetrievalEvaluation e "
            + "where e.corpus.id = :corpusId and e.runKey = :runKey")
    int deleteRun(@Param("corpusId") Long corpusId, @Param("runKey") String runKey);

    @Query("select e from RetrievalEvaluation e where e.corpus.id = :corpusId order by e.id desc")
    List<RetrievalEvaluation> findRecent(@Param("corpusId") Long corpusId,
                                         org.springframework.data.domain.Pageable pageable);

    /** Rows of one run, oldest first, so a run reads in gold-set order. */
    @Query("select e from RetrievalEvaluation e "
            + "where e.corpus.id = :corpusId and e.runKey = :runKey order by e.id asc")
    List<RetrievalEvaluation> findRun(@Param("corpusId") Long corpusId,
                                      @Param("runKey") String runKey);
}
