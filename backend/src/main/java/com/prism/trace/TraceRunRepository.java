package com.prism.trace;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TraceRunRepository extends JpaRepository<TraceRun, Long> {

    Page<TraceRun> findByCorpusIdOrderByStartedAtDesc(Long corpusId, Pageable pageable);

    Page<TraceRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    Optional<TraceRun> findByIdAndCorpusId(Long id, Long corpusId);

    List<TraceRun> findByOperationKeyOrderByStartedAtDesc(String operationKey);

    List<TraceRun> findByStatus(TraceRunStatus status);

    long countByCorpusId(Long corpusId);

    @Query("select r from TraceRun r where r.corpus.id = :corpusId and r.operationType = :type "
            + "order by r.startedAt desc")
    List<TraceRun> findRecentByCorpusAndType(@Param("corpusId") Long corpusId,
                                             @Param("type") TraceOperationType type,
                                             Pageable pageable);

    /**
     * Claims the next step sequence for a run, atomically.
     *
     * <p>A single {@code UPDATE ... SET step_seq = step_seq + 1} rather than
     * {@code max(seq) + 1} in Java. A debate round writes steps from three
     * threads concurrently, and read-then-increment hands all of them the same
     * number, which {@code uk_step_run_seq} then rejects — losing an audit step
     * from a system whose entire purpose is the audit trail.
     *
     * <p>Concurrent callers serialise on the row lock for the duration of their
     * own short transaction, so each receives a distinct, gap-free value. That
     * lock is held only for the increment, the step insert, and the commit.
     *
     * <p>No version bump: {@code trace_runs} carries no {@code @Version} column
     * (nothing in this schema updates a run's mutable state concurrently — steps
     * are append-only and a run is completed exactly once), so there is no
     * in-memory copy that could later write back a stale value.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TraceRun r set r.stepSeq = r.stepSeq + 1 where r.id = :id")
    int incrementStepSeq(@Param("id") Long id);

    /**
     * Reads back the sequence just claimed.
     *
     * <p>Must be called inside the same transaction as
     * {@link #incrementStepSeq}, so the caller sees its own increment rather
     * than a value another thread has since advanced.
     */
    @Query("select r.stepSeq from TraceRun r where r.id = :id")
    Long readStepSeq(@Param("id") Long id);

    /**
     * Closes a run, writing only the columns completion changes.
     *
     * <p>Deliberately a targeted {@code UPDATE} rather than {@code save(entity)}.
     * A full save rewrites every column, {@code step_seq} included, so a
     * completion racing a concurrent step increment would write back a stale
     * counter and collide with {@code uk_step_run_seq} — losing a step from the
     * audit trail. It would also take the run's row lock for the whole write.
     *
     * <p>The status is bound as the {@link TraceRunStatus} enum, not as its
     * {@code name()} String. In a bulk {@code @Modifying} update Hibernate has no
     * entity metadata to coerce a String against an enum-typed attribute, so the
     * comparison fails at execution with "did not match parameter type". Passing
     * the enum lets the configured JDBC type handle it, and keeps
     * {@code @Enumerated(EnumType.STRING)} as the single mapping.
     *
     * @return rows updated; 0 when the run no longer exists
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TraceRun r set r.status = :status, r.errorSummary = :errorSummary, "
            + "r.finishedAt = :finishedAt, r.durationMs = :durationMs "
            + "where r.id = :id")
    int completeRun(@Param("id") Long id,
                    @Param("status") TraceRunStatus status,
                    @Param("errorSummary") String errorSummary,
                    @Param("finishedAt") java.time.Instant finishedAt,
                    @Param("durationMs") Long durationMs);
}
