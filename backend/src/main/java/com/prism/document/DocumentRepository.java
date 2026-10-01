package com.prism.document;

import com.prism.corpus.Corpus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Page<Document> findByCorpusOrderByCreatedAtDesc(Corpus corpus, Pageable pageable);

    List<Document> findByCorpus(Corpus corpus);

    Optional<Document> findByIdAndCorpusId(Long id, Long corpusId);

    long countByCorpusId(Long corpusId);

    long countByCorpusIdAndStatus(Long corpusId, DocumentStatus status);

    List<Document> findByStatusIn(List<DocumentStatus> statuses);

    /** Idempotency guard: a retry must not create a second document for the same content. */
    @Query("select d from Document d where d.corpus.id = :corpusId and d.contentHash = :hash order by d.id")
    List<Document> findByCorpusIdAndContentHash(@Param("corpusId") Long corpusId, @Param("hash") String hash);
}
