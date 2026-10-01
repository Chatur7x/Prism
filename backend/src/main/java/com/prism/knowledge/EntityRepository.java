package com.prism.knowledge;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EntityRepository extends JpaRepository<Entity, Long> {

    /**
     * Identity lookup. The unique index on {@code (corpus_id, normalized_name)}
     * is the authority for "is this the same entity?", not application logic.
     */
    Optional<Entity> findByCorpusIdAndNormalizedName(Long corpusId, String normalizedName);

    boolean existsByCorpusIdAndNormalizedName(Long corpusId, String normalizedName);

    Optional<Entity> findByIdAndCorpusId(Long id, Long corpusId);

    /**
     * Entities that still represent a live identity in this corpus.
     *
     * <p>Excludes entities marked {@code MERGE}: once an identity has been
     * explicitly merged into another, its row is kept for provenance but must
     * not participate in resolution, the graph, or PageRank — otherwise the
     * merged-away node keeps accruing support and reappears downstream.
     */
    @Query("select e from Entity e where e.corpus.id = :corpusId and e.resolutionState <> com.prism.knowledge.ResolutionState.MERGE "
            + "order by e.supportCount desc, e.displayName")
    List<Entity> findLiveInCorpus(@Param("corpusId") Long corpusId);

    long countByCorpusId(Long corpusId);

    /**
     * Substring search over display and normalized names within one corpus.
     *
     * <p>Corpus-scoped on purpose: a global name search would let the existence
     * of an entity in another corpus leak through a result count.
     */
    @Query("select e from Entity e where e.corpus.id = :corpusId "
            + "and (lower(e.displayName) like :needle or lower(e.normalizedName) like :needle) "
            + "order by e.supportCount desc, e.displayName")
    List<Entity> searchInCorpus(@Param("corpusId") Long corpusId,
                                @Param("needle") String needle,
                                Pageable pageable);

    /**
     * Atomically increments the support count and backfills the first-seen chunk.
     *
     * <p>Written as a single {@code UPDATE} rather than a load/mutate/save cycle
     * on purpose. Extraction resolves the same entity from several worker threads
     * at once, and a read-modify-write would either lose an increment or fail on
     * a stale {@code @Version}. Letting the database do the arithmetic removes
     * the race entirely.
     *
     * <p>{@code firstSeenChunkId} is only backfilled while it is null, so the
     * original provenance is never overwritten.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Entity e
               set e.supportCount = e.supportCount + 1,
                   e.firstSeenChunkId = case when e.firstSeenChunkId is null then :chunkId
                                             else e.firstSeenChunkId end,
                   e.updatedAt = :now,
                   e.version = e.version + 1
             where e.id = :id
            """)
    int incrementSupport(@Param("id") Long id,
                         @Param("chunkId") Long chunkId,
                         @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Entity e set e.resolutionState = :state, e.updatedAt = :now, e.version = e.version + 1 "
            + "where e.id = :id")
    int markResolution(@Param("id") Long id,
                       @Param("state") ResolutionState state,
                       @Param("now") Instant now);
}
