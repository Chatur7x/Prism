package com.prism.claims;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VerdictPassageRepository extends JpaRepository<VerdictPassage, Long> {

    List<VerdictPassage> findByVerdictIdOrderByRetrievalRankAsc(Long verdictId);

    void deleteByVerdictId(Long verdictId);

    @Query("select p.chunk.id from VerdictPassage p where p.verdict.id = :verdictId order by p.retrievalRank")
    List<Long> findChunkIdsByVerdictId(@Param("verdictId") Long verdictId);

    @Query("select p from VerdictPassage p where p.corpus.id = :corpusId and p.chunk.id = :chunkId")
    List<VerdictPassage> findByCorpusAndChunk(@Param("corpusId") Long corpusId, @Param("chunkId") Long chunkId);
}
