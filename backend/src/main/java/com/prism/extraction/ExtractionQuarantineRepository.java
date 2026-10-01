package com.prism.extraction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExtractionQuarantineRepository extends JpaRepository<ExtractionQuarantine, Long> {

    List<ExtractionQuarantine> findByRunIdOrderByIdAsc(Long runId);

    Page<ExtractionQuarantine> findByCorpusIdOrderByCreatedAtDesc(Long corpusId, Pageable pageable);

    Page<ExtractionQuarantine> findByDocumentIdOrderByCreatedAtDesc(Long documentId, Pageable pageable);

    long countByRunId(Long runId);

    @Query("select q.errorType as reason, count(q) as total from ExtractionQuarantine q "
            + "where q.corpus.id = :corpusId group by q.errorType")
    List<Object[]> countByCorpusGroupedByReason(@Param("corpusId") Long corpusId);

    @Query("select count(q) from ExtractionQuarantine q where q.corpus.id = :corpusId")
    long countByCorpus(@Param("corpusId") Long corpusId);
}
