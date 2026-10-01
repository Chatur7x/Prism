-- =============================================================================
-- V5: optimistic-locking column for extraction_runs
-- =============================================================================
--
-- WHY THIS EXISTS
--   ExtractionRun carries a JPA @Version field so that two workers racing to
--   update the same run's progress cannot interleave writes. Every other
--   optimistically-locked table in this schema (documents, corpora, entities,
--   triples, claims, verdicts, contradictions, debates, chat_sessions,
--   background_jobs) already has the column; extraction_runs was the only gap.
--
--   This was caught by `ddl-auto: validate`, which is precisely the point of
--   running that instead of `update`: a mismatched mapping fails the boot
--   loudly rather than silently writing to a column that does not exist.
--
--   This migration is additive only. It does not alter or drop any existing
--   column, table, index, or constraint, so it is safe to apply to a database
--   that already holds data.
--
-- NOT NULL DEFAULT 0
--   Matches every other version column in the schema. Existing rows, if any,
--   default to 0 and are therefore valid immediately.
-- =============================================================================

ALTER TABLE extraction_runs
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER last_error;
