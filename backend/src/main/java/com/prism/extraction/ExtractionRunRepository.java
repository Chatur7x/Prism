package com.prism.extraction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, Long> {

    /**
     * The most recent extraction run for a document, or empty if it never ran.
     *
     * <p><b>{@code findFirst} is load-bearing, not decoration.</b> Declared as
     * plain {@code findByDocumentIdOrderByIdDesc} with an {@code Optional} return
     * type, Spring Data sorts by id descending and then demands
     * {@code getSingleResult()} — which throws
     * {@code NonUniqueResultException} the moment a second run exists. A
     * document gets a second run every time {@code /reprocess} is called, so
     * that combination made the progress endpoint return 500 for every document
     * that had ever been reprocessed. The method name promised "the latest" and
     * the query did not deliver it.
     */
    Optional<ExtractionRun> findFirstByDocumentIdOrderByIdDesc(Long documentId);

    Optional<ExtractionRun> findFirstByDocumentIdAndStatusInOrderByIdDesc(Long documentId,
                                                                         List<ExtractionStatus> statuses);

    List<ExtractionRun> findByStatus(ExtractionStatus status);

    /**
     * Runs that claim to be RUNNING but whose heartbeat is older than the
     * cutoff. This is the query the startup recovery job uses.
     */
    @Query("select r from ExtractionRun r where r.status = com.prism.extraction.ExtractionStatus.RUNNING "
            + "and (r.heartbeatAt is null or r.heartbeatAt < :cutoff) order by r.id")
    List<ExtractionRun> findStaleRunning(@Param("cutoff") java.time.Instant cutoff);

    Page<ExtractionRun> findByCorpusIdOrderByStartedAtDesc(Long corpusId, Pageable pageable);

    long countByCorpusIdAndStatus(Long corpusId, ExtractionStatus status);
}
