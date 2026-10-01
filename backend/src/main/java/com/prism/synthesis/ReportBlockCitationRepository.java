package com.prism.synthesis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReportBlockCitationRepository extends JpaRepository<ReportBlockCitation, Long> {

    @Query("select c from ReportBlockCitation c join fetch c.block b where b.report.id = :reportId "
            + "order by b.sequenceNo")
    List<ReportBlockCitation> findByReportId(@Param("reportId") Long reportId);

    @Query("select c from ReportBlockCitation c where c.block.id = :blockId")
    List<ReportBlockCitation> findByBlockId(@Param("blockId") Long blockId);
}
