-- =====================================================================
-- PRISM :: V1 :: core identity, corpus isolation, and document ingestion
--
-- Design rules enforced here:
--   * Every table has a non-null, non-auto-generated alternative PK path.
--   * No nullable primary key column anywhere.
--   * All corpus-scoped tables carry corpus_id and an index on it, because
--     every read path must be able to enforce the isolation boundary in SQL.
--   * Enum columns are VARCHAR with a documented closed value set, so adding
--     a value is a migration rather than a silent type change.
--   * Everything uses DATETIME(6) for ordering precision.
-- =====================================================================

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'ANALYST', 'VERIFIER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- corpora  (the isolation boundary)
-- ---------------------------------------------------------------------
CREATE TABLE corpora (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NULL,
    owner_id    BIGINT       NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_corpora_owner (owner_id),
    KEY ix_corpora_status (status),
    CONSTRAINT fk_corpora_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ck_corpora_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- documents
-- ---------------------------------------------------------------------
CREATE TABLE documents (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    corpus_id         BIGINT        NOT NULL,
    uploader_id       BIGINT        NOT NULL,
    title             VARCHAR(500)  NOT NULL,
    content_text      LONGTEXT      NOT NULL,
    original_filename VARCHAR(512)  NULL,
    mime_type         VARCHAR(200)  NULL,
    content_hash      VARCHAR(64)   NULL,
    status            VARCHAR(32)   NOT NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_documents_corpus (corpus_id),
    KEY ix_documents_status (status),
    KEY ix_documents_corpus_status (corpus_id, status),
    KEY ix_documents_hash (corpus_id, content_hash),
    CONSTRAINT fk_documents_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_documents_uploader FOREIGN KEY (uploader_id) REFERENCES users (id),
    CONSTRAINT ck_documents_status CHECK (status IN
        ('UPLOADED', 'CHUNKING', 'CHUNKED', 'EXTRACTING', 'AWAITING_APPROVAL', 'READY', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- document_chunks
--
-- The FULLTEXT index here is the backbone of v1 retrieval. It is on
-- content only; every query must also join documents to filter corpus_id,
-- so a match can never leak across the isolation boundary.
-- ---------------------------------------------------------------------
CREATE TABLE document_chunks (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    document_id    BIGINT      NOT NULL,
    chunk_index    INT         NOT NULL,
    content        LONGTEXT    NOT NULL,
    start_offset   INT         NOT NULL,
    end_offset     INT         NOT NULL,
    token_estimate INT         NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_document_index (document_id, chunk_index),
    KEY ix_chunk_document_index (document_id, chunk_index),
    CONSTRAINT fk_chunks_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT ck_chunk_offsets CHECK (end_offset > start_offset)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Natural-language-mode FULLTEXT. Sized for the document lengths PRISM handles.
ALTER TABLE document_chunks ADD FULLTEXT KEY ft_chunk_content (content);

-- ---------------------------------------------------------------------
-- extraction_runs
--
-- Carries recovery fields (status, attempt_count, heartbeat, last_error)
-- so a restart cannot leave a job stuck in RUNNING forever.
-- ---------------------------------------------------------------------
CREATE TABLE extraction_runs (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    document_id    BIGINT       NOT NULL,
    corpus_id      BIGINT       NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    total_chunks   INT          NOT NULL DEFAULT 0,
    processed_chunks INT        NOT NULL DEFAULT 0,
    triples_found  INT          NOT NULL DEFAULT 0,
    claims_found   INT          NOT NULL DEFAULT 0,
    quarantined_count INT       NOT NULL DEFAULT 0,
    prompt_version VARCHAR(64)  NOT NULL,
    model          VARCHAR(200) NULL,
    attempt_count  INT          NOT NULL DEFAULT 0,
    started_at     DATETIME(6)  NOT NULL,
    heartbeat_at   DATETIME(6)  NULL,
    finished_at    DATETIME(6)  NULL,
    last_error     VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    KEY ix_extraction_document (document_id),
    KEY ix_extraction_status (status),
    KEY ix_extraction_corpus (corpus_id),
    KEY ix_extraction_status_heartbeat (status, heartbeat_at),
    CONSTRAINT fk_extraction_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_extraction_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT ck_extraction_status CHECK (status IN
        ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Idempotency: at most one live run per document. Enforced with ALTER because
-- MySQL has no CREATE UNIQUE KEY statement.
ALTER TABLE extraction_runs
    ADD UNIQUE KEY uk_extraction_document_live (document_id, status);

-- ---------------------------------------------------------------------
-- extraction_quarantine
--
-- Enough detail to audit why a model response was rejected, without
-- needing to reproduce the call.
-- ---------------------------------------------------------------------
CREATE TABLE extraction_quarantine (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    extraction_run_id BIGINT       NOT NULL,
    document_id      BIGINT        NOT NULL,
    corpus_id        BIGINT        NOT NULL,
    chunk_id         BIGINT        NULL,
    chunk_index      INT           NULL,
    prompt_version   VARCHAR(64)   NOT NULL,
    model            VARCHAR(200)  NULL,
    raw_response     LONGTEXT      NULL,
    error_type       VARCHAR(64)   NOT NULL,
    validation_message VARCHAR(2000) NULL,
    attempt          INT           NOT NULL DEFAULT 1,
    created_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY ix_quarantine_run (extraction_run_id),
    KEY ix_quarantine_document (document_id),
    KEY ix_quarantine_corpus (corpus_id),
    KEY ix_quarantine_chunk (chunk_id),
    KEY ix_quarantine_error_type (error_type),
    CONSTRAINT fk_quarantine_run FOREIGN KEY (extraction_run_id) REFERENCES extraction_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_quarantine_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_quarantine_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_quarantine_chunk FOREIGN KEY (chunk_id) REFERENCES document_chunks (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
