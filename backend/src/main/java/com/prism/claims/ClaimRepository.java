package com.prism.claims;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Claim queries.
 *
 * <p>Provenance is fetched with explicit {@code join fetch} rather than
 * {@code @EntityGraph}: open-in-view is disabled, so a claim handed to a
 * controller must already have its source chunk and that chunk's document
 * loaded. {@code join fetch} states the intent in validated JPQL, which is more
 * robust than the comma-joined {@code attributePaths} string in this Spring Data
 * version.
 */
public interface ClaimRepository extends JpaRepository<Claim, Long> {

    String PROVENANCE_JOINS = " join fetch c.sourceChunk sc join fetch sc.document left join fetch c.decidedBy";

    @Query("select c from Claim c" + PROVENANCE_JOINS + " where c.id = :id")
    Optional<Claim> findById(@Param("id") Long id);

    @Query("select c from Claim c" + PROVENANCE_JOINS + " where c.id = :id and c.corpus.id = :corpusId")
    Optional<Claim> findByIdAndCorpusId(@Param("id") Long id, @Param("corpusId") Long corpusId);

    Optional<Claim> findByCorpusIdAndClaimHash(Long corpusId, String claimHash);

    @Query("select c from Claim c" + PROVENANCE_JOINS
            + " where c.corpus.id = :corpusId order by c.createdAt desc")
    Page<Claim> findByCorpusIdOrderByCreatedAtDesc(@Param("corpusId") Long corpusId, Pageable pageable);

    @Query("select c from Claim c" + PROVENANCE_JOINS
            + " where c.corpus.id = :corpusId and c.status = :status order by c.createdAt desc")
    Page<Claim> findByCorpusIdAndStatusOrderByCreatedAtDesc(@Param("corpusId") Long corpusId,
                                                             @Param("status") ClaimStatus status,
                                                             Pageable pageable);

    long countByCorpusIdAndStatus(Long corpusId, ClaimStatus status);

    long countByCorpusId(Long corpusId);

    /**
     * Claims eligible for verification: approved but not yet verified.
     *
     * <p>Loads the source chunk because the verification trace reports the
     * claim's provenance.
     */
    @Query("select c from Claim c" + PROVENANCE_JOINS
            + " where c.corpus.id = :corpusId and c.status = 'APPROVED' order by c.createdAt")
    List<Claim> findVerifiableInCorpus(@Param("corpusId") Long corpusId, Pageable pageable);

    @Query("select c from Claim c" + PROVENANCE_JOINS
            + " where c.corpus.id = :corpusId and c.status = 'APPROVED'")
    List<Claim> findApprovedInCorpus(@Param("corpusId") Long corpusId);

    @Query("select c from Claim c" + PROVENANCE_JOINS + " where c.sourceChunk.id = :chunkId")
    List<Claim> findBySourceChunkId(@Param("chunkId") Long chunkId);

    @Query("select c from Claim c" + PROVENANCE_JOINS
            + " where c.corpus.id = :corpusId and c.predicate is not null and c.predicate = :predicate")
    List<Claim> findByPredicateInCorpus(@Param("corpusId") Long corpusId, @Param("predicate") String predicate);
}
