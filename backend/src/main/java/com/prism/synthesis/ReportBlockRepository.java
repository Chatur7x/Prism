package com.prism.synthesis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportBlockRepository extends JpaRepository<ReportBlock, Long> {

    List<ReportBlock> findByReportIdOrderBySequenceNoAsc(Long reportId);

    long countByReportId(Long reportId);
}
