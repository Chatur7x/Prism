package com.prism.knowledge;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Triple queries.
 *
 * <p><b>Provenance is fetched with explicit {@code join fetch}, not
 * {@code @EntityGraph}.</b> Two reasons:
 *
 * <ol>
 *   <li>{@code spring.jpa.open-in-view} is disabled, so a transaction closes as
 *       soon as a service method returns. Any association a controller's DTO
 *       mapping touches must already be loaded, or the mapping throws
 *       {@code LazyInitializationException}.</li>
 *   <li>In this Spring Data version {@code @EntityGraph.attributePaths} is a
 *       single comma-joined {@code String}, and dotted nested paths through it
 *       fail with "Unable to locate Attribute". A {@code join fetch} states the
 *       same intent in JPQL, is validated at startup, and cannot be misparsed.
 *       </li>
 * </ol>
 *
 * <p>Every accessor feeding a controller response therefore fetches
 * {@code sourceChunk} and {@code sourceChunk.document} explicitly.
 */
public interface TripleRepository extends JpaRepository<Triple, Long> {

    /**
     * Joins the provenance every triple response displays.
     *
     * <p>{@code decidedBy} is joined with {@code left join} because it is
     * nullable: a triple awaiting a decision has no decider, and an inner join
     * would silently drop those rows from the approval queue — which is
     * precisely the queue that needs them.
     */
    String PROVENANCE_JOINS =
            " join fetch t.sourceChunk sc join fetch sc.document left join fetch t.decidedBy";

    @Query("select t from Triple t" + PROVENANCE_JOINS + " where t.id = :id")
    Optional<Triple> findById(@Param("id") Long id);

    @Query("select t from Triple t" + PROVENANCE_JOINS + " where t.id = :id and t.corpus.id = :corpusId")
    Optional<Triple> findByIdAndCorpusId(@Param("id") Long id, @Param("corpusId") Long corpusId);

    Optional<Triple> findByCorpusIdAndFactHash(Long corpusId, String factHash);

    @Query("select t from Triple t" + PROVENANCE_JOINS
            + " where t.corpus.id = :corpusId order by t.createdAt desc")
    Page<Triple> findByCorpusIdOrderByCreatedAtDesc(@Param("corpusId") Long corpusId, Pageable pageable);

    @Query("select t from Triple t" + PROVENANCE_JOINS
            + " where t.corpus.id = :corpusId and t.status = :status order by t.createdAt desc")
    Page<Triple> findByCorpusIdAndStatusOrderByCreatedAtDesc(@Param("corpusId") Long corpusId,
                                                              @Param("status") ProposalStatus status,
                                                              Pageable pageable);

    long countByCorpusIdAndStatus(Long corpusId, ProposalStatus status);

    long countByCorpusId(Long corpusId);

    @Query("select t from Triple t" + PROVENANCE_JOINS + " where t.sourceChunk.id = :chunkId")
    List<Triple> findBySourceChunkId(@Param("chunkId") Long chunkId);

    /**
     * Approved triples for graph construction, with both endpoints loaded.
     *
     * <p>Every graph edge in PRISM comes from this query, which is what
     * guarantees no pending or rejected extraction can become a graph edge.
     */
    @Query("select t from Triple t join fetch t.subjectEntity join fetch t.objectEntity "
            + "where t.corpus.id = :corpusId and t.status = 'APPROVED'")
    List<Triple> findApprovedInCorpus(@Param("corpusId") Long corpusId);

    @Query("select t from Triple t join fetch t.subjectEntity join fetch t.objectEntity "
            + "where t.corpus.id = :corpusId and t.status = 'APPROVED' "
            + "and t.subjectEntity.id = :entityId")
    List<Triple> findApprovedBySubject(@Param("corpusId") Long corpusId, @Param("entityId") Long entityId);

    @Query("select t from Triple t join fetch t.subjectEntity join fetch t.objectEntity "
            + "where t.corpus.id = :corpusId and t.status = 'APPROVED' "
            + "and (t.subjectEntity.id = :entityId or t.objectEntity.id = :entityId)")
    List<Triple> findApprovedTouchingEntity(@Param("corpusId") Long corpusId, @Param("entityId") Long entityId);

    @Query("select t from Triple t join fetch t.subjectEntity join fetch t.objectEntity "
            + "where t.corpus.id = :corpusId and t.status = 'APPROVED' "
            + "and t.subjectEntity.id = :entityId and t.predicate = :predicate")
    List<Triple> findApprovedBySubjectAndPredicate(@Param("corpusId") Long corpusId,
                                                   @Param("entityId") Long entityId,
                                                   @Param("predicate") String predicate);

    @Query("select t from Triple t join fetch t.subjectEntity join fetch t.objectEntity "
            + "where t.corpus.id = :corpusId and t.status = 'APPROVED' "
            + "and (lower(t.subject) like :needle or lower(t.object) like :needle) "
            + "order by t.subject, t.predicate, t.object")
    List<Triple> searchApprovedText(@Param("corpusId") Long corpusId, @Param("needle") String needle,
                                    Pageable pageable);

    /**
     * Approved triples whose text contains every one of the given terms.
     *
     * <p>Built in Java from {@link #searchApprovedText} rather than as a single
     * monster LIKE clause, so the number of LIKE conditions stays bounded and the
     * query plan remains predictable as the term list grows.
     */
    default List<Triple> searchApprovedTextForAll(Long corpusId, List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        StringBuilder needle = new StringBuilder("%");
        int remaining = terms.size();
        for (String term : terms) {
            needle.append(term.toLowerCase(java.util.Locale.ROOT));
            if (--remaining > 0) {
                needle.append('%');
            }
        }
        needle.append('%');
        return searchApprovedText(corpusId, needle.toString(),
                org.springframework.data.domain.PageRequest.of(0, 20));
    }
}
