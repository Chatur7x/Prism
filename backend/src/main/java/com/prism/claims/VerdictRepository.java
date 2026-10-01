package com.prism.claims;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Verdict queries.
 *
 * <p>A verdict response projects the claim's text and subject, plus the
 * adjudicator's username. With open-in-view disabled those must be fetched in
 * the query, which is what the {@code join fetch} clauses below do — the same
 * reasoning as {@link TripleRepository}, and for the same reason: explicit JPQL
 * is validated at startup and cannot be misparsed the way a comma-joined
 * {@code @EntityGraph} string can.
 */
public interface VerdictRepository extends JpaRepository<Verdict, Long> {

    String RESPONSE_JOINS = " join fetch v.claim c join fetch c.sourceChunk sc join fetch sc.document left join fetch v.adjudicator";

    @Query("select v from Verdict v" + RESPONSE_JOINS + " where v.id = :id")
    Optional<Verdict> findById(@Param("id") Long id);

    @Query("select v from Verdict v" + RESPONSE_JOINS + " where v.id = :id and v.corpus.id = :corpusId")
    Optional<Verdict> findByIdAndCorpusId(@Param("id") Long id, @Param("corpusId") Long corpusId);

    /**
     * At most one current verdict per claim, enforced by a unique key.
     *
     * <p>Fetches the claim's provenance because the caller replaces this verdict
     * and the superseding record renders the same fields.
     */
    @Query("select v from Verdict v" + RESPONSE_JOINS + " where v.claim.id = :claimId")
    Optional<Verdict> findByClaimId(@Param("claimId") Long claimId);

    @Query("select v from Verdict v" + RESPONSE_JOINS
            + " where v.corpus.id = :corpusId order by v.createdAt desc")
    Page<Verdict> findByCorpusIdOrderByCreatedAtDesc(@Param("corpusId") Long corpusId, Pageable pageable);

    long countByCorpusId(Long corpusId);

    long countByCorpusIdAndVerdictType(Long corpusId, VerdictType verdictType);

    long countByCorpusIdAndAdjudicationState(Long corpusId, AdjudicationState state);

    @Query("select v from Verdict v" + RESPONSE_JOINS
            + " where v.corpus.id = :corpusId and v.adjudicationState = :state")
    List<Verdict> findByCorpusIdAndAdjudicationState(@Param("corpusId") Long corpusId,
                                                     @Param("state") AdjudicationState state);

    @Query("select v from Verdict v" + RESPONSE_JOINS
            + " where v.corpus.id = :corpusId order by v.createdAt desc")
    List<Verdict> findRecent(@Param("corpusId") Long corpusId, Pageable pageable);

    /**
     * Verdicts for the given claims, used to build the Skeptic brief without
     * triggering fresh verification.
     */
    @Query("select v from Verdict v join fetch v.claim where v.claim.id in :claimIds")
    List<Verdict> findByClaimIds(@Param("claimIds") List<Long> claimIds);

    /** For the verified-graph scope, which needs the claim's subject and predicate. */
    @Query("select v from Verdict v join fetch v.claim where v.corpus.id = :corpusId "
            + "and v.verdictType = :verdictType")
    List<Verdict> findByCorpusIdAndVerdictType(@Param("corpusId") Long corpusId,
                                              @Param("verdictType") VerdictType verdictType);

    @Query("select v.verdictType as type, count(v) as total from Verdict v "
            + "where v.corpus.id = :corpusId group by v.verdictType")
    List<Object[]> countByTypeInCorpus(@Param("corpusId") Long corpusId);
}
