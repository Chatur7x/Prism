package com.prism.debate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Debate queries.
 *
 * <p>A debate response includes its contradiction and corpus, so those are
 * fetched in the query. Same reasoning as {@link com.prism.knowledge.TripleRepository}:
 * open-in-view is disabled, and explicit {@code join fetch} is validated JPQL
 * rather than a string that can be misparsed.
 */
public interface DebateRepository extends JpaRepository<Debate, Long> {

    /**
     * Preloads for a full debate response: the contradiction, its corpus, and
     * the chair.
     *
     * <p>{@code chairedBy} is fetched because the response carries the chair's
     * username. A debate is created and returned inside one transaction, so by
     * the time the controller maps it the session has closed; without this the
     * lazy proxy yields a null username rather than the chair's name, and the UI
     * shows a Council with no chair — which is precisely the accountability gap
     * this system exists to close.
     *
     * <p>All three are {@code join fetch} rather than inner {@code where} joins,
     * so a row that fails to match cannot silently vanish from a result the way
     * it did in the approval queue.
     */
    String RESPONSE_JOINS = " join fetch d.contradiction con join fetch con.corpus"
            + " join fetch d.chairedBy";

    @Query("select d from Debate d" + RESPONSE_JOINS + " where d.id = :id")
    Optional<Debate> findById(@Param("id") Long id);

    @Query("select d from Debate d" + RESPONSE_JOINS + " where d.id = :id and d.corpus.id = :corpusId")
    Optional<Debate> findByIdAndCorpusId(@Param("id") Long id, @Param("corpusId") Long corpusId);

    @Query("select d from Debate d" + RESPONSE_JOINS + " where d.contradiction.id = :contradictionId")
    Optional<Debate> findByContradictionId(@Param("contradictionId") Long contradictionId);

    @Query("select d from Debate d" + RESPONSE_JOINS
            + " where d.corpus.id = :corpusId order by d.createdAt desc")
    Page<Debate> findByCorpusIdOrderByCreatedAtDesc(@Param("corpusId") Long corpusId, Pageable pageable);

    List<Debate> findByCorpusIdAndStateIn(Long corpusId, List<DebateState> states);

    long countByCorpusId(Long corpusId);

    long countByCorpusIdAndState(Long corpusId, DebateState state);

    /**
     * Conditional state transition. This is the concurrency guard: the update
     * only matches when the row is still in {@code expectedState}, so two
     * simultaneous ADVANCE calls cannot both succeed.
     *
     * <p>{@code finishedAt} is written by the caller rather than derived in the
     * query, because only the caller knows whether the target state is terminal.
     * Deriving it here would mean either listing the terminal states in JPQL —
     * where an enum literal in an {@code IN} clause is awkward — or stamping a
     * finish time on every intermediate round, which is worse than leaving it
     * null: a debate that reached {@code AWAITING_CHAIR} after round two would
     * appear to have finished.
     *
     * <p>Unconditionally assigning the parameter is safe precisely because no
     * transition leaves a terminal state, so {@code finishedAt} is only ever
     * written on the transition into one. A caller moving to a non-terminal state
     * passes null, which clears the column; that is a no-op on a debate that
     * cannot yet have a finish time.
     *
     * @return rows updated; 1 means this caller won, 0 means someone else did
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Debate d set d.state = :newState, d.currentRound = :newRound, d.version = d.version + 1, "
            + "d.finishedAt = :finishedAt "
            + "where d.id = :id and d.state = :expectedState")
    int transitionState(@Param("id") Long id,
                        @Param("expectedState") DebateState expectedState,
                        @Param("newState") DebateState newState,
                        @Param("newRound") int newRound,
                        @Param("finishedAt") java.time.Instant finishedAt);
}
