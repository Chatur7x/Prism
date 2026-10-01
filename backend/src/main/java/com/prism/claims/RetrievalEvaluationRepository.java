package com.prism.claims;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface RetrievalEvaluationRepository extends JpaRepository<RetrievalEvaluation, Long> {

    /**
     * Aggregate recall over a corpus's evaluations, restricted to rows that have
     * a gold passage.
     *
     * <p>Rows without a gold answer are excluded from the denominator, because a
     * query with no known answer cannot be got right or wrong. Including them
     * would drag every metric toward zero for reasons that have nothing to do
     * with retrieval quality.
     */
    @Query("select avg(case when e.recallAt1 then 1.0 else 0.0 end), "
            + "       avg(case when e.recallAt3 then 1.0 else 0.0 end), "
            + "       avg(case when e.recallAt5 then 1.0 else 0.0 end) "
            + "from RetrievalEvaluation e "
            + "where e.corpus.id = :corpusId and e.goldChunkId is not null")
    List<Object> recallSummary(@Param("corpusId") Long corpusId);

    /**
     * Mean reciprocal rank over rows where the gold passage was actually found.
     *
     * <p>{@code avg} ignores nulls, so unretrieved gold passages drop out rather
     * than counting as a reciprocal rank of zero. That matches the definition of
     * MRR as "over queries where a relevant document was retrieved" and is
     * stated explicitly in {@link RetrievalEvaluationService#summarise} so a
     * reader is never left guessing which convention produced the number.
     */
    @Query("select avg(e.reciprocalRank) from RetrievalEvaluation e "
            + "where e.corpus.id = :corpusId and e.goldChunkId is not null")
    BigDecimal meanReciprocalRank(@Param("corpusId") Long corpusId);

    long countByCorpusId(Long corpusId);

    long countByCorpusIdAndGoldChunkIdIsNotNull(Long corpusId);

    /** How many gold passages were retrieved at all, i.e. were never lost. */
    long countByCorpusIdAndRetrievedRankIsNotNull(Long corpusId);

    @Query("select e from RetrievalEvaluation e where e.corpus.id = :corpusId order by e.id desc")
    List<RetrievalEvaluation> findRecent(@Param("corpusId") Long corpusId,
                                         org.springframework.data.domain.Pageable pageable);

    /** Rows recorded before a given instant, so a re-run can be compared. */
    @Query("select e from RetrievalEvaluation e where e.corpus.id = :corpusId and e.createdAt >= :since")
    List<RetrievalEvaluation> findSince(@Param("corpusId") Long corpusId, @Param("since") Instant since);
}