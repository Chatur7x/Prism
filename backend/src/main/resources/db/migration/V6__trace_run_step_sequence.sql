-- =============================================================================
-- V6: atomic step-sequence counter for trace_runs
-- =============================================================================
--
-- WHY THIS EXISTS
--   trace_steps.seq is unique per run (uk_step_run_seq on (run_id, seq)), and
--   it was assigned in Java as `max(seq) + 1`. That is a read-then-increment
--   with a window between the two statements, and a debate round runs its three
--   personas on separate threads. All three read the same maximum, all three
--   computed the same next value, and the unique index rejected the losers.
--
--   The visible symptom was much worse than a missing audit row. TraceRecorder
--   caught the constraint violation to satisfy "auditing must never break the
--   operation", but the catch sat *inside* the @Transactional(REQUIRES_NEW)
--   method: the violation had already marked the transaction rollback-only, the
--   catch hid it, and the transaction proxy then threw UnexpectedRollbackException
--   at commit — outside the method body, outside the catch. The safety wrapper
--   was the thing that failed the operation, and all three personas of round one
--   were recorded as failed arguments.
--
--   The fix has two halves and this migration is the first:
--     1. this column, so the database can hand out the number instead of Java
--        reading a maximum and adding one;
--     2. moving the try/catch in TraceRecorder outside the transactional
--        boundary, so a genuinely failed audit write rolls back alone.
--
--   Either half alone is insufficient. A counter without the catch outside the
--   transaction would still fail the operation on a genuine error; a catch
--   outside the transaction without a counter would still lose steps to the race.
--
-- NOT NULL DEFAULT 0
--   Matches every other counter in the schema. The DEFAULT only applies to the
--   new column itself; the backfill below then lifts every pre-existing run to
--   at least its own highest recorded step.
--
-- BACKFILL IS REQUIRED, NOT COSMETIC
--   A run that already has steps at seq 1..9 would keep step_seq = 0, and the
--   first step recorded after the migration would claim seq 1 again and be
--   rejected by uk_step_run_seq. The audit write would be lost, and -- worse --
--   the swallow-inside-a-transaction behaviour described above would turn a lost
--   audit step into a failed operation. Starting each run above its own maximum
--   is the only value that makes the counter correct.
--
--   The backfill is a single set-based UPDATE rather than a per-run loop: it
--   runs once, inside the migration, and touches only rows that are behind.
-- =============================================================================

ALTER TABLE trace_runs
    ADD COLUMN step_seq BIGINT NOT NULL DEFAULT 0 AFTER duration_ms;

UPDATE trace_runs r
    SET r.step_seq = COALESCE((
            SELECT MAX(s.seq) FROM trace_steps s WHERE s.run_id = r.id
        ), 0)
    WHERE r.step_seq < COALESCE((
            SELECT MAX(s.seq) FROM trace_steps s WHERE s.run_id = r.id
        ), 0);

-- =============================================================================
-- error_summary: the reason a run failed, at run level
-- =============================================================================
-- WHY THIS EXISTS
--   TraceRun.complete() has always accepted an errorSummary and thrown it away.
--   The parameter was threaded all the way from every failure path --
--   traces.finishRun(id, FAILED, "judge call failed: ...") -- and then dropped on
--   the floor, because no column existed to hold it.
--
--   The effect was that a failed run in the Glass Box could say "FAILED" and
--   nothing else. The individual failing step is recorded, so the information is
--   not wholly absent, but the run row -- the thing the list view renders, and
--   the thing a reader lands on -- carried no reason at all. An audit tool that
--   cannot explain why an operation failed is not an audit tool.
--
--   2000 chars matches the per-step error_message budget in trace_steps, so a
--   summary and its cause are truncated on the same scale.
-- =============================================================================

ALTER TABLE trace_runs
    ADD COLUMN error_summary VARCHAR(2000) NULL AFTER step_seq;
