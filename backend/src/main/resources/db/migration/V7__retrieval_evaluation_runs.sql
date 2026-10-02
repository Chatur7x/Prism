-- ---------------------------------------------------------------------
-- retrieval_evaluation: make a benchmark run a discrete, re-runnable unit
-- ---------------------------------------------------------------------
-- The table stored one row per query per measurement, and the summary
-- aggregated every row belonging to a corpus. That made the benchmark
-- un-runnable twice: a second run of the same gold set doubled the rows,
-- so the metrics moved for no reason other than that they had been measured
-- twice, and a single ad-hoc probe query stayed in the corpus's headline
-- numbers forever.
--
-- It also made before/after comparison impossible in the way that matters.
-- Improving the retriever inserts rows; the aggregate then reports the
-- union of the old and new runs, so a real improvement can read as a
-- regression and vice versa.
--
-- run_key is the SHA-256 of the canonicalised gold set. Re-running the same
-- gold set replaces that run's rows, so the benchmark is idempotent and its
-- numbers are reproducible. summarise() reads the newest run only.
--
-- expander_version records which retrieval expansion table produced the rows,
-- so a metric is attributable to a retriever configuration and not only to a
-- corpus.
ALTER TABLE retrieval_evaluation
    ADD COLUMN run_key CHAR(64) NOT NULL DEFAULT '' AFTER corpus_id,
    ADD COLUMN expander_version VARCHAR(32) NOT NULL DEFAULT '' AFTER run_key,
    ADD KEY ix_reval_run (corpus_id, run_key);

-- Rows written before runs were distinguished cannot honestly be attributed
-- to one: they came from whatever ad-hoc runs happened to be issued. They are
-- grouped under a single literal so they remain readable. Because the summary
-- reads only the newest run, they cannot contaminate a later measurement.
UPDATE retrieval_evaluation SET run_key = 'LEGACY' WHERE run_key = '';
