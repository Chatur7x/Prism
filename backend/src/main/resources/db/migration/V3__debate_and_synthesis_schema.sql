-- =====================================================================
-- PRISM :: V3 :: contradictions, the Council, synthesis, chat, Glass Box
-- =====================================================================

-- ---------------------------------------------------------------------
-- contradictions
--
-- A contradiction is a deterministic finding, not a judgement. It records
-- which records conflict and under which rule, so a verifier can audit
-- why the system believed there was a conflict at all.
-- ---------------------------------------------------------------------
CREATE TABLE contradictions (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    corpus_id           BIGINT        NOT NULL,
    contradiction_type  VARCHAR(40)   NOT NULL,
    subject_text        VARCHAR(300)  NOT NULL,
    predicate           VARCHAR(60)   NULL,
    left_description    VARCHAR(1000) NOT NULL,
    right_description   VARCHAR(1000) NOT NULL,
    -- Deterministic rule that fired, with its version, e.g. RELATION_SINGLE_VALUE.
    rule_code           VARCHAR(64)   NOT NULL,
    rule_version        VARCHAR(64)   NOT NULL,
    explanation         TEXT          NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    -- Provenance: the triples/claims whose conflict produced this record.
    left_triple_id      BIGINT        NULL,
    right_triple_id     BIGINT        NULL,
    left_claim_id       BIGINT        NULL,
    right_claim_id      BIGINT        NULL,
    -- Fingerprint preventing the same conflict being recorded twice by a re-scan.
    conflict_hash       CHAR(64)      NOT NULL,
    trace_run_id        BIGINT        NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contradiction_hash (corpus_id, conflict_hash),
    KEY ix_contradiction_status (status),
    KEY ix_contradiction_corpus (corpus_id),
    KEY ix_contradiction_corpus_status (corpus_id, status),
    KEY ix_contradiction_type (contradiction_type),
    KEY ix_contradiction_left_triple (left_triple_id),
    KEY ix_contradiction_right_triple (right_triple_id),
    CONSTRAINT fk_contradiction_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_contradiction_left_triple FOREIGN KEY (left_triple_id) REFERENCES triples (id) ON DELETE SET NULL,
    CONSTRAINT fk_contradiction_right_triple FOREIGN KEY (right_triple_id) REFERENCES triples (id) ON DELETE SET NULL,
    CONSTRAINT fk_contradiction_left_claim FOREIGN KEY (left_claim_id) REFERENCES claims (id) ON DELETE SET NULL,
    CONSTRAINT fk_contradiction_right_claim FOREIGN KEY (right_claim_id) REFERENCES claims (id) ON DELETE SET NULL,
    CONSTRAINT ck_contradiction_type CHECK (contradiction_type IN
        ('RELATION_CONFLICT', 'POLARITY_CONFLICT', 'VERDICT_CONFLICT')),
    CONSTRAINT ck_contradiction_status CHECK (status IN
        ('OPEN', 'IN_DEBATE', 'RESOLVED', 'DISMISSED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- debates  (the Council)
--
-- state is advanced only by DebateEngine through a validated transition.
-- A @Version column plus a conditional UPDATE on the expected state means
-- two concurrent ADVANCE requests cannot both succeed.
-- ---------------------------------------------------------------------
CREATE TABLE debates (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    contradiction_id    BIGINT       NOT NULL,
    corpus_id           BIGINT       NOT NULL,
    state               VARCHAR(24)  NOT NULL,
    current_round       INT          NOT NULL DEFAULT 0,
    max_rounds          INT          NOT NULL DEFAULT 3,
    chaired_by_user_id  BIGINT       NOT NULL,
    topic               VARCHAR(500) NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    started_at          DATETIME(6)  NULL,
    finished_at         DATETIME(6)  NULL,
    last_error          VARCHAR(2000) NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_debate_contradiction (contradiction_id),
    KEY ix_debate_state (state),
    KEY ix_debate_corpus (corpus_id),
    KEY ix_debate_corpus_state (corpus_id, state),
    CONSTRAINT fk_debate_contradiction FOREIGN KEY (contradiction_id) REFERENCES contradictions (id),
    CONSTRAINT fk_debate_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_debate_chair FOREIGN KEY (chaired_by_user_id) REFERENCES users (id),
    CONSTRAINT ck_debate_state CHECK (state IN
        ('CREATED', 'ROUND_ACTIVE', 'AWAITING_CHAIR', 'SYNTHESIZING', 'COMPLETED', 'ABORTED')),
    CONSTRAINT ck_debate_rounds CHECK (current_round >= 0 AND current_round <= max_rounds)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- debate_rounds
-- ---------------------------------------------------------------------
CREATE TABLE debate_rounds (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    debate_id       BIGINT      NOT NULL,
    round_number    INT         NOT NULL,
    started_at      DATETIME(6) NOT NULL,
    completed_at    DATETIME(6) NULL,
    arguments_completed INT     NOT NULL DEFAULT 0,
    arguments_failed    INT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_round_debate_number (debate_id, round_number),
    KEY ix_round_debate (debate_id),
    CONSTRAINT fk_round_debate FOREIGN KEY (debate_id) REFERENCES debates (id) ON DELETE CASCADE,
    CONSTRAINT ck_round_number CHECK (round_number >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- arguments
--
-- An argument is attributed to a persona AND to the model that produced it.
-- failed = true marks a persona that did not produce usable output; such a row
-- is never presented as though the model had argued.
-- ---------------------------------------------------------------------
CREATE TABLE arguments (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    debate_round_id    BIGINT        NOT NULL,
    debate_id          BIGINT        NOT NULL,
    corpus_id          BIGINT        NOT NULL,
    persona            VARCHAR(16)   NOT NULL,
    argument_text      TEXT          NOT NULL,
    stance             VARCHAR(500)  NULL,
    model              VARCHAR(200)  NULL,
    prompt_version     VARCHAR(64)   NOT NULL,
    status             VARCHAR(16)   NOT NULL DEFAULT 'COMPLETE',
    failed             TINYINT(1)    NOT NULL DEFAULT 0,
    failure_reason     VARCHAR(2000) NULL,
    duration_ms        BIGINT        NULL,
    created_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_argument_round_persona (debate_round_id, persona),
    KEY ix_argument_round (debate_round_id),
    KEY ix_argument_debate (debate_id),
    KEY ix_argument_corpus (corpus_id),
    CONSTRAINT fk_argument_round FOREIGN KEY (debate_round_id) REFERENCES debate_rounds (id) ON DELETE CASCADE,
    CONSTRAINT fk_argument_debate FOREIGN KEY (debate_id) REFERENCES debates (id) ON DELETE CASCADE,
    CONSTRAINT ck_argument_persona CHECK (persona IN ('HAWK', 'DOVE', 'SKEPTIC')),
    CONSTRAINT ck_argument_status CHECK (status IN ('PENDING', 'COMPLETE', 'FAILED')),
    -- A failed persona must record why, so a missing argument is never
    -- silently presented as an absence of dissent.
    CONSTRAINT ck_argument_failure_recorded CHECK (failed = 0 OR failure_reason IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- argument_citations
--
-- NOTE ON THE PRIMARY KEY: this table has a surrogate `id` PRIMARY KEY.
-- A nullable composite PK (triple_id NULL, chunk_id NULL) is not a legal or
-- meaningful primary key in MySQL and would break the uniqueness strategy.
-- Instead:
--   * one row per (argument, citation target) pair, enforced by the unique
--     indexes below;
--   * exactly one of citation_kind / chunk_id / triple_id / claim_id is set,
--     enforced by the check constraints.
-- ---------------------------------------------------------------------
CREATE TABLE argument_citations (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    argument_id     BIGINT      NOT NULL,
    debate_id       BIGINT      NOT NULL,
    corpus_id       BIGINT      NOT NULL,
    citation_kind   VARCHAR(16) NOT NULL,
    chunk_id        BIGINT      NULL,
    triple_id       BIGINT      NULL,
    claim_id        BIGINT      NULL,
    machine_fact_id VARCHAR(64) NULL,
    excerpt         TEXT        NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_citation_argument_chunk (argument_id, chunk_id),
    UNIQUE KEY uk_citation_argument_triple (argument_id, triple_id),
    UNIQUE KEY uk_citation_argument_claim (argument_id, claim_id),
    UNIQUE KEY uk_citation_argument_fact (argument_id, machine_fact_id),
    KEY ix_citation_argument (argument_id),
    KEY ix_citation_chunk (chunk_id),
    KEY ix_citation_triple (triple_id),
    KEY ix_citation_claim (claim_id),
    KEY ix_citation_debate (debate_id),
    CONSTRAINT fk_citation_argument FOREIGN KEY (argument_id) REFERENCES arguments (id) ON DELETE CASCADE,
    CONSTRAINT fk_citation_debate FOREIGN KEY (debate_id) REFERENCES debates (id) ON DELETE CASCADE,
    CONSTRAINT fk_citation_chunk FOREIGN KEY (chunk_id) REFERENCES document_chunks (id) ON DELETE CASCADE,
    CONSTRAINT fk_citation_triple FOREIGN KEY (triple_id) REFERENCES triples (id) ON DELETE CASCADE,
    CONSTRAINT fk_citation_claim FOREIGN KEY (claim_id) REFERENCES claims (id) ON DELETE CASCADE,
    CONSTRAINT ck_citation_kind CHECK (citation_kind IN ('CHUNK', 'TRIPLE', 'CLAIM', 'MACHINE_FACT')),
    CONSTRAINT ck_citation_target_present CHECK (
        (citation_kind = 'CHUNK'      AND chunk_id IS NOT NULL)
     OR (citation_kind = 'TRIPLE'    AND triple_id IS NOT NULL)
     OR (citation_kind = 'CLAIM'     AND claim_id IS NOT NULL)
     OR (citation_kind = 'MACHINE_FACT' AND machine_fact_id IS NOT NULL)),
    -- Guard against a citation that smuggles in a second target.
    CONSTRAINT ck_citation_single_target CHECK (
        (chunk_id IS NULL OR (triple_id IS NULL AND claim_id IS NULL AND machine_fact_id IS NULL))
    AND (triple_id IS NULL OR (chunk_id IS NULL AND claim_id IS NULL AND machine_fact_id IS NULL))
    AND (claim_id IS NULL OR (chunk_id IS NULL AND triple_id IS NULL AND machine_fact_id IS NULL)))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- argument_weights
--
-- Human assessment is an immutable audit fact. Weights are appended, never
-- updated in place, and the LLM has no write path to this table at all.
-- ---------------------------------------------------------------------
CREATE TABLE argument_weights (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    argument_id     BIGINT      NOT NULL,
    debate_id       BIGINT      NOT NULL,
    corpus_id       BIGINT      NOT NULL,
    weight          INT         NOT NULL,
    verifier_user_id BIGINT     NOT NULL,
    note            VARCHAR(1000) NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_weight_argument (argument_id),
    KEY ix_weight_debate (debate_id),
    KEY ix_weight_corpus (corpus_id),
    CONSTRAINT fk_weight_argument FOREIGN KEY (argument_id) REFERENCES arguments (id) ON DELETE CASCADE,
    CONSTRAINT fk_weight_debate FOREIGN KEY (debate_id) REFERENCES debates (id) ON DELETE CASCADE,
    CONSTRAINT fk_weight_verifier FOREIGN KEY (verifier_user_id) REFERENCES users (id),
    CONSTRAINT ck_weight_range CHECK (weight >= 1 AND weight <= 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- synthesis_reports
-- ---------------------------------------------------------------------
CREATE TABLE synthesis_reports (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    debate_id         BIGINT        NOT NULL,
    contradiction_id  BIGINT        NOT NULL,
    corpus_id         BIGINT        NOT NULL,
    conclusion        TEXT          NOT NULL,
    model             VARCHAR(200)  NULL,
    prompt_version    VARCHAR(64)   NOT NULL,
    duration_ms       BIGINT        NULL,
    trace_run_id      BIGINT        NULL,
    created_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- One report per debate. Synthesis is idempotent; a retry reuses the row.
    UNIQUE KEY uk_report_debate (debate_id),
    KEY ix_report_corpus (corpus_id),
    KEY ix_report_contradiction (contradiction_id),
    CONSTRAINT fk_report_debate FOREIGN KEY (debate_id) REFERENCES debates (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_contradiction FOREIGN KEY (contradiction_id) REFERENCES contradictions (id),
    CONSTRAINT fk_report_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- report_blocks
--
-- The report is structured, not a markdown blob. Every block carries its own
-- citations so a reader can click any statement back to its evidence.
-- ---------------------------------------------------------------------
CREATE TABLE report_blocks (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    report_id      BIGINT       NOT NULL,
    debate_id      BIGINT       NOT NULL,
    corpus_id      BIGINT       NOT NULL,
    sequence_no    INT          NOT NULL,
    block_type     VARCHAR(32)  NOT NULL,
    text           TEXT         NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_block_report_seq (report_id, sequence_no),
    KEY ix_block_report (report_id),
    KEY ix_block_debate (debate_id),
    KEY ix_block_corpus (corpus_id),
    CONSTRAINT fk_block_report FOREIGN KEY (report_id) REFERENCES synthesis_reports (id) ON DELETE CASCADE,
    CONSTRAINT fk_block_debate FOREIGN KEY (debate_id) REFERENCES debates (id) ON DELETE CASCADE,
    CONSTRAINT ck_block_type CHECK (block_type IN
        ('FINDING', 'DISAGREEMENT', 'MACHINE_RECORD', 'UNRESOLVED', 'RECOMMENDATION')),
    CONSTRAINT ck_block_sequence CHECK (sequence_no >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- report_block_citations
-- ---------------------------------------------------------------------
CREATE TABLE report_block_citations (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    report_block_id   BIGINT      NOT NULL,
    report_id         BIGINT      NOT NULL,
    corpus_id         BIGINT      NOT NULL,
    citation_kind     VARCHAR(16) NOT NULL,
    chunk_id          BIGINT      NULL,
    triple_id         BIGINT      NULL,
    claim_id          BIGINT      NULL,
    verdict_id        BIGINT      NULL,
    argument_id       BIGINT      NULL,
    machine_fact_id   VARCHAR(64) NULL,
    created_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_blockcite_block_kind_target (report_block_id, citation_kind, chunk_id, triple_id,
                                               claim_id, verdict_id, argument_id, machine_fact_id),
    KEY ix_blockcite_block (report_block_id),
    KEY ix_blockcite_report (report_id),
    KEY ix_blockcite_chunk (chunk_id),
    KEY ix_blockcite_argument (argument_id),
    CONSTRAINT fk_blockcite_block FOREIGN KEY (report_block_id) REFERENCES report_blocks (id) ON DELETE CASCADE,
    CONSTRAINT fk_blockcite_report FOREIGN KEY (report_id) REFERENCES synthesis_reports (id) ON DELETE CASCADE,
    CONSTRAINT fk_blockcite_chunk FOREIGN KEY (chunk_id) REFERENCES document_chunks (id) ON DELETE CASCADE,
    CONSTRAINT fk_blockcite_triple FOREIGN KEY (triple_id) REFERENCES triples (id) ON DELETE CASCADE,
    CONSTRAINT fk_blockcite_claim FOREIGN KEY (claim_id) REFERENCES claims (id) ON DELETE CASCADE,
    CONSTRAINT fk_blockcite_verdict FOREIGN KEY (verdict_id) REFERENCES verdicts (id) ON DELETE CASCADE,
    CONSTRAINT fk_blockcite_argument FOREIGN KEY (argument_id) REFERENCES arguments (id) ON DELETE CASCADE,
    CONSTRAINT ck_blockcite_kind CHECK (citation_kind IN
        ('CHUNK', 'TRIPLE', 'CLAIM', 'VERDICT', 'ARGUMENT', 'MACHINE_FACT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
