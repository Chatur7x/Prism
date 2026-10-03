package com.prism.trace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TraceStepRepository extends JpaRepository<TraceStep, Long> {

    List<TraceStep> findByRunIdOrderBySeqAsc(Long runId);

    /**
     * Distinct actor types per run, for the Glass Box list's Actors column.
     *
     * <p>One query for the whole page rather than one per run. The column used
     * to be fed by a field the server never sent, so it rendered "-" for every
     * run while looking like it worked.
     */
    @Query("SELECT s.run.id, s.actorType FROM TraceStep s WHERE s.run.id IN :runIds "
            + "GROUP BY s.run.id, s.actorType")
    List<Object[]> findActorTypesByRunIds(@Param("runIds") List<Long> runIds);

    Optional<TraceStep> findByIdAndRunId(Long stepId, Long runId);

    /**
     * There is deliberately no {@code maxSeq} here.
     *
     * <p>Step sequences are claimed through
     * {@link TraceRunRepository#incrementStepSeq(Long)} and read back with
     * {@link TraceRunRepository#readStepSeq(Long)}. A {@code max(seq) + 1} query
     * is a read-then-increment with a window between the two statements, and a
     * debate round writes steps from three threads at once — so it handed all
     * three the same number and the {@code (run_id, seq)} unique index rejected
     * the losers, losing audit steps. Not providing the method at all is the
     * cheapest way to stop it being reintroduced.
     */
    long countByRunId(Long runId);
}
