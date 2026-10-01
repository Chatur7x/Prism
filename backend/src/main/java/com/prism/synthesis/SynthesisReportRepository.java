package com.prism.synthesis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SynthesisReportRepository extends JpaRepository<SynthesisReport, Long> {

    Optional<SynthesisReport> findByDebateId(Long debateId);

    Optional<SynthesisReport> findByIdAndCorpusId(Long id, Long corpusId);

    List<SynthesisReport> findByCorpusIdOrderByCreatedAtDesc(Long corpusId);

    @Query("select r from SynthesisReport r where r.debate.id = :debateId")
    Optional<SynthesisReport> findForDebate(@Param("debateId") Long debateId);
}
