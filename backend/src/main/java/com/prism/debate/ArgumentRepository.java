package com.prism.debate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Argument queries.
 *
 * <p>An argument response shows the persona, the round, the chair weight, and
 * the validated citations. With open-in-view disabled, the round and the owning
 * debate must be preloaded or the response mapping throws once the transaction
 * closes.
 *
 * <p>Every preload here is an explicit {@code join fetch} rather than an
 * {@code @EntityGraph}. In this Spring Data version {@code attributePaths} is a
 * single comma-joined {@code String}, so
 * {@code attributePaths = "debateRound,debate"} is not two paths — it is one
 * attribute literally named {@code debateRound,debate}, and the query fails at
 * startup with "Unable to locate Attribute with the given name". A JPQL
 * {@code join fetch} is unambiguous, greppable, and does not depend on how the
 * framework happens to parse a string.
 */
public interface ArgumentRepository extends JpaRepository<Argument, Long> {

    String FETCH = "select a from Argument a join fetch a.debateRound r join fetch a.debate";

    @Query(FETCH + " where r.id = :roundId order by a.id")
    List<Argument> findByDebateRoundIdOrderByIdAsc(@Param("roundId") Long roundId);

    @Query(FETCH + " where a.debate.id = :debateId order by r.id, a.id")
    List<Argument> findByDebateIdOrderByDebateRoundIdAscIdAsc(@Param("debateId") Long debateId);

    @Query(FETCH + " where r.id in :roundIds order by r.id, a.id")
    List<Argument> findByDebateRoundIdInOrderByIdAsc(@Param("roundIds") List<Long> roundIds);

    @Query(FETCH + " where a.id = :id and a.debate.id = :debateId")
    Optional<Argument> findByIdAndDebateId(@Param("id") Long id, @Param("debateId") Long debateId);

    /**
     * Paginated form.
     *
     * <p>No {@code join fetch}: a fetch join combined with pagination fetches
     * the whole joined result and pages it in memory, which Hibernate warns
     * about and which would silently return wrong page sizes as soon as a round
     * had more arguments than the page size. The trade is deliberate — the
     * caller that pages gets counts and ids reliably, and the two queries that
     * need fully-loaded arguments use the fetching variants above.
     */
    Page<Argument> findByDebateId(Long debateId, Pageable pageable);

    long countByDebateIdAndFailed(Long debateId, boolean failed);

    @Query(FETCH + " where r.debate.id = :debateId and r.roundNumber = :roundNumber order by a.id")
    List<Argument> findByDebateAndRound(@Param("debateId") Long debateId,
                                         @Param("roundNumber") int roundNumber);
}
