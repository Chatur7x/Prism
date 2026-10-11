-- V8: let extraction history accumulate; guard only active runs.
--
-- uk_extraction_document_live constrained (document_id, status) for EVERY
-- status, so the second finished run for a document could never complete:
-- marking it SUCCEEDED violated the key (production: document 63, V2 run,
-- "Duplicate entry '63-SUCCEEDED'"). The name promised a liveness guard but
-- the key also forbade terminal-state history.
--
-- Single-flight for ACTIVE runs is enforced elsewhere and stays intact:
-- background_jobs carries a true unique job_key plus an atomic PENDING→
-- RUNNING claim, and ExtractionPersistenceService.startRun now refuses when
-- a PENDING/RUNNING run exists (honest 409 instead of a constraint blowup
-- at completion time). Crash recovery marks stale RUNNING rows FAILED before
-- any new run starts, so the guard cannot wedge restart.
--
-- This migration only relaxes an over-broad key: no rows change, no data
-- moves, and rollback is re-adding a UNIQUE key (which will fail if two
-- same-status rows for one document exist by then — that is the point).
ALTER TABLE extraction_runs DROP KEY uk_extraction_document_live;
ALTER TABLE extraction_runs ADD KEY ix_extraction_document_status (document_id, status);
