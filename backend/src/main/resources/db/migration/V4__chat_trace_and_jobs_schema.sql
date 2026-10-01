-- =====================================================================
-- PRISM :: V4 :: grounded chat and the Glass Box trace store
-- =====================================================================

-- ---------------------------------------------------------------------
-- chat_sessions
--
-- Every session is bound to exactly one corpus. There is no cross-corpus
-- chat: a session cannot cite outside the corpus it was created in.
-- ---------------------------------------------------------------------
CREATE TABLE chat_sessions (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    corpus_id       BIGINT        NOT NULL,
    user_id         BIGINT        NOT NULL,
    title           VARCHAR(300)  NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    last_message_at DATETIME(6)   NULL,
    message_count   INT           NOT NULL DEFAULT 0,
    version         BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_chatsession_user (user_id),
    KEY ix_chatsession_corpus (corpus_id),
    KEY ix_chatsession_user_updated (user_id, updated_at),
    CONSTRAINT fk_chatsession_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_chatsession_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- chat_messages
--
-- citations_json holds only ids the backend validated. The backend rejects a
-- response whose citations reference anything absent from the retrieved set,
-- so a stored message can always be re-verified.
-- ---------------------------------------------------------------------
CREATE TABLE chat_messages (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    session_id        BIGINT        NOT NULL,
    corpus_id         BIGINT        NOT NULL,
    user_id           BIGINT        NOT NULL,
    role              VARCHAR(16)   NOT NULL,
    content           TEXT          NOT NULL,
    citations_json    TEXT          NULL,
    model             VARCHAR(200)  NULL,
    prompt_version    VARCHAR(64)   NULL,
    latency_ms        BIGINT        NULL,
    retrieval_count   INT           NOT NULL DEFAULT 0,
    -- False when the system could not answer from available evidence. The UI
    -- must not imply certainty when this is 0.
    grounded          TINYINT(1)    NOT NULL DEFAULT 1,
    insufficient_evidence TINYINT(1) NOT NULL DEFAULT 0,
    trace_run_id      BIGINT        NULL,
    created_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY ix_chatmessage_session (session_id, created_at),
    KEY ix_chatmessage_corpus (corpus_id),
    KEY ix_chatmessage_user (user_id),
    CONSTRAINT fk_chatmessage_session FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_chatmessage_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_chatmessage_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_chatmessage_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- trace_runs
--
-- The Glass Box replay unit. Note: no foreign key to corpora is declared as
-- NOT NULL because a trace may exist for a corpus that was later archived;
-- authorization is re-checked at read time, not assumed from this row.
-- ---------------------------------------------------------------------
CREATE TABLE trace_runs (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    operation_type  VARCHAR(40)   NOT NULL,
    corpus_id       BIGINT        NULL,
    document_id     BIGINT        NULL,
    user_id         BIGINT        NULL,
    operation_key   VARCHAR(200)  NULL,
    status          VARCHAR(16)   NOT NULL,
    metadata_json   LONGTEXT      NULL,
    started_at      DATETIME(6)   NOT NULL,
    finished_at     DATETIME(6)   NULL,
    duration_ms     BIGINT        NULL,
    PRIMARY KEY (id),
    KEY idx_trace_corpus_started (corpus_id, started_at),
    KEY idx_trace_status (status),
    KEY idx_trace_operation (operation_type),
    KEY idx_trace_key (operation_key),
    CONSTRAINT fk_trace_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id) ON DELETE SET NULL,
    CONSTRAINT fk_trace_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE SET NULL,
    CONSTRAINT fk_trace_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_trace_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'ABORTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- trace_steps
--
-- (run_id, seq) is unique and indexed for ordered replay. parent_step_id
-- forms the tree the X-Ray renders. Columns deliberately hold observable
-- execution facts only: no hidden chain-of-thought, no credentials.
-- ---------------------------------------------------------------------
CREATE TABLE trace_steps (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    run_id              BIGINT        NOT NULL,
    parent_step_id      BIGINT        NULL,
    seq                 BIGINT        NOT NULL,
    actor_type          VARCHAR(16)   NOT NULL,
    event_type          VARCHAR(40)   NOT NULL,
    name                VARCHAR(200)  NOT NULL,
    status              VARCHAR(24)   NOT NULL DEFAULT 'OK',
    input_summary       VARCHAR(1000) NULL,
    input_reference_ids TEXT          NULL,
    output_summary      VARCHAR(2000) NULL,
    output_reference_ids TEXT         NULL,
    rule_version        VARCHAR(64)   NULL,
    prompt_version      VARCHAR(64)   NULL,
    model               VARCHAR(200)  NULL,
    duration_ms         BIGINT        NULL,
    error_message       VARCHAR(2000) NULL,
    attempt             INT           NOT NULL DEFAULT 1,
    created_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_step_run_seq (run_id, seq),
    KEY ix_step_run_seq (run_id, seq),
    KEY ix_step_parent (parent_step_id),
    KEY ix_step_actor (actor_type),
    KEY ix_step_event (event_type),
    CONSTRAINT fk_step_run FOREIGN KEY (run_id) REFERENCES trace_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_step_parent FOREIGN KEY (parent_step_id) REFERENCES trace_steps (id) ON DELETE CASCADE,
    CONSTRAINT ck_step_actor CHECK (actor_type IN ('ENGINE', 'LLM', 'HUMAN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- retrieval_evaluation
--
-- Gold-evidence measurements (Recall@k, MRR) for the retrieval evaluation
-- capability. Stored so a regression can be compared over time.
-- ---------------------------------------------------------------------
CREATE TABLE retrieval_evaluation (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    corpus_id      BIGINT       NOT NULL,
    query_text     VARCHAR(500) NOT NULL,
    gold_chunk_id  BIGINT       NULL,
    retrieved_rank INT          NULL,
    score          DECIMAL(10,4) NULL,
    recall_at_1    TINYINT(1)   NOT NULL,
    recall_at_3    TINYINT(1)   NOT NULL,
    recall_at_5    TINYINT(1)   NOT NULL,
    reciprocal_rank DECIMAL(8,5) NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_reval_corpus (corpus_id),
    CONSTRAINT fk_reval_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- background_jobs
--
-- Durable job bookkeeping for async recovery. A restart can find every job
-- the in-memory queue was holding and requeue the ones that are still
-- expected to run, without duplicating completed work.
-- ---------------------------------------------------------------------
CREATE TABLE background_jobs (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    job_key         VARCHAR(200)  NOT NULL,
    job_type        VARCHAR(40)   NOT NULL,
    corpus_id       BIGINT        NULL,
    document_id     BIGINT        NULL,
    status          VARCHAR(16)   NOT NULL,
    attempt_count   INT           NOT NULL DEFAULT 0,
    max_attempts    INT           NOT NULL DEFAULT 5,
    payload_json    LONGTEXT      NULL,
    started_at      DATETIME(6)   NULL,
    heartbeat_at    DATETIME(6)   NULL,
    finished_at     DATETIME(6)   NULL,
    last_error      VARCHAR(2000) NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- job_key is the idempotency key: re-enqueueing the same logical work
    -- updates the existing row rather than creating a duplicate.
    UNIQUE KEY uk_job_key (job_key),
    KEY idx_job_status_heartbeat (status, heartbeat_at),
    KEY idx_job_type (job_type),
    KEY idx_job_corpus (corpus_id),
    CONSTRAINT fk_job_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT ck_job_status CHECK (status IN
        ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'ABANDONED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
