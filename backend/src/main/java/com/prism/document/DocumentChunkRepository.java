package com.prism.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByDocumentOrderByChunkIndex(Document document);

    long countByDocumentId(Long documentId);

    Optional<DocumentChunk> findByIdAndDocumentId(Long chunkId, Long documentId);

    void deleteByDocumentId(Long documentId);

    /**
     * MySQL FULLTEXT search, hard-scoped to a single corpus.
     *
     * <p>The {@code corpus_id} join is part of the query, not a post-filter, so a
     * cross-corpus hit is impossible even if the index returns extra rows.
     */
    @Query(value = """
            SELECT c.* FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE d.corpus_id = :corpusId
              AND MATCH(c.content) AGAINST (:query IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(c.content) AGAINST (:query IN NATURAL LANGUAGE MODE) DESC, c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentChunk> fulltextSearch(@Param("corpusId") Long corpusId,
                                       @Param("query") String query,
                                       @Param("limit") int limit);

    /**
     * Corpus-scoped FULLTEXT search returning chunk ids with their relevance.
     *
     * <p>Returns <b>ids, not entities</b>, on purpose. A native query selecting
     * {@code c.*, <score>} arrives from Hibernate as an {@code Object[]} where
     * each element is one column, so casting element zero to
     * {@code DocumentChunk} throws {@code ClassCastException} at runtime.
     * Selecting the id keeps the positional contract obvious and type-safe; the
     * caller then loads the chunks with their documents in a single JPQL query.
     */
    @Query(value = """
            SELECT c.id, MATCH(c.content) AGAINST (:query IN NATURAL LANGUAGE MODE) AS relevance
            FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE d.corpus_id = :corpusId
              AND MATCH(c.content) AGAINST (:query IN NATURAL LANGUAGE MODE)
            ORDER BY relevance DESC, c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> fulltextIdsWithScore(@Param("corpusId") Long corpusId,
                                        @Param("query") String query,
                                        @Param("limit") int limit);

    /**
     * Loads the given chunks with their documents in one round trip.
     *
     * <p>Paired with {@link #fulltextIdsWithScore} so a retrieval costs two
     * queries in total rather than one query per chunk.
     */
    @Query("select c from DocumentChunk c join fetch c.document d where c.id in :ids")
    List<DocumentChunk> findAllWithDocumentByIdIn(@Param("ids") List<Long> ids);

    @Query("select c from DocumentChunk c join fetch c.document d where c.id = :id and d.corpus.id = :corpusId")
    Optional<DocumentChunk> findByIdAndCorpusId(@Param("id") Long id, @Param("corpusId") Long corpusId);

    @Query("select c from DocumentChunk c join fetch c.document d where d.corpus.id = :corpusId order by c.id")
    List<DocumentChunk> findAllByCorpusId(@Param("corpusId") Long corpusId);

    long countByDocumentCorpusId(Long corpusId);
}
