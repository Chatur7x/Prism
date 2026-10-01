package com.prism.extraction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, Long> {

    Optional<ExtractionRun> findByDocumentIdOrderByIdDesc(Long documentId);

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
