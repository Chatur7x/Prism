-- =====================================================================
-- PRISM :: V2 :: knowledge proposals, claims, verdicts
--
-- The central rule of this migration: nothing in `triples` or `claims`
-- is trusted until a human sets status = 'APPROVED'. Every graph query
-- in the application filters on that status; no code path reads a
-- PENDING or REJECTED row as knowledge.
-- =====================================================================

-- ---------------------------------------------------------------------
-- entities
-- ---------------------------------------------------------------------
CREATE TABLE entities (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    corpus_id          BIGINT        NOT NULL,
    display_name       VARCHAR(300)  NOT NULL,
    normalized_name    VARCHAR(300)  NOT NULL,
    type               VARCHAR(60)   NOT NULL DEFAULT 'UNSPECIFIED',
    description        VARCHAR(1000) NULL,
    resolution_state   VARCHAR(16)   NOT NULL DEFAULT 'KEEP_SEPARATE',
    support_count      INT           NOT NULL DEFAULT 1,
    first_seen_chunk_id BIGINT       NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- Identity is scoped to the corpus. Two corpora may legitimately contain
    -- an entity with the same name without them being the same node.
    UNIQUE KEY uk_entity_corpus_key (corpus_id, normalized_name),
    KEY ix_entity_corpus (corpus_id),
    KEY ix_entity_normalized_type (normalized_name, type),
    KEY ix_entity_type (type),
    CONSTRAINT fk_entity_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_entity_first_chunk FOREIGN KEY (first_seen_chunk_id)
        REFERENCES document_chunks (id) ON DELETE SET NULL,
    CONSTRAINT ck_entity_resolution CHECK (resolution_state IN ('MERGE', 'KEEP_SEPARATE', 'REVIEW'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- entity_aliases
-- ---------------------------------------------------------------------
CREATE TABLE entity_aliases (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    entity_id         BIGINT       NOT NULL,
    -- Denormalised from the owning entity so alias uniqueness is enforceable
    -- in one unique key. Always written from entity.corpus, never from input.
    corpus_id         BIGINT       NOT NULL,
    alias_text        VARCHAR(300) NOT NULL,
    normalized_alias  VARCHAR(300) NOT NULL,
    support_count     INT          NOT NULL DEFAULT 1,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- An alias may point at only one entity within a corpus, otherwise
    -- resolution would be ambiguous and non-deterministic.
    UNIQUE KEY uk_alias_corpus_key (corpus_id, normalized_alias),
    KEY ix_alias_entity (entity_id),
    KEY ix_alias_corpus (corpus_id),
    CONSTRAINT fk_alias_entity FOREIGN KEY (entity_id) REFERENCES entities (id) ON DELETE CASCADE,
    CONSTRAINT fk_alias_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- triples  (proposed facts)
--
-- fact_key collapses duplicate extraction of the same relation from
-- different chunks. A UNIQUE constraint on it makes re-running extraction
-- idempotent: the second attempt increments evidence_chunk_count instead
-- of creating a parallel node that would inflate graph out-degree.
-- ---------------------------------------------------------------------
CREATE TABLE triples (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    corpus_id          BIGINT       NOT NULL,
    subject_entity_id  BIGINT       NOT NULL,
    subject_text       VARCHAR(300) NOT NULL,
    predicate          VARCHAR(60)  NOT NULL,
    object_entity_id   BIGINT       NOT NULL,
    object_text        VARCHAR(300) NOT NULL,
    source_sentence    TEXT         NOT NULL,
    source_chunk_id    BIGINT       NOT NULL,
    proposed_by_user_id BIGINT      NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    decided_by_user_id BIGINT       NULL,
    decided_at         DATETIME(6)  NULL,
    decision_note      VARCHAR(1000) NULL,
    prompt_version     VARCHAR(64)  NULL,
    extraction_run_id  BIGINT       NULL,
    -- Human-readable fingerprint, kept for debugging and for the Glass Box.
    fact_key           VARCHAR(700) NOT NULL,
    -- SHA-256 of fact_key, hex encoded (64 chars). The uniqueness constraint is
    -- on the hash rather than the key itself: a utf8mb4 VARCHAR(700) exceeds
    -- InnoDB's 3072-byte index limit, and hashing also gives a fixed-width,
    -- cache-friendly index. A collision here would merge two facts, so the key
    -- is also compared on lookup.
    fact_hash          CHAR(64)     NOT NULL,
    evidence_chunk_count INT        NOT NULL DEFAULT 1,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_triple_fact_hash (corpus_id, fact_hash),
    KEY ix_triple_corpus_status (corpus_id, status),
    KEY ix_triple_subject (subject_entity_id),
    KEY ix_triple_object (object_entity_id),
    KEY ix_triple_predicate (predicate),
    KEY ix_triple_chunk (source_chunk_id),
    -- Serves the "which claims cite this triple" lookup in the Skeptic brief.
    KEY ix_triple_subject_predicate (subject_entity_id, predicate),
    CONSTRAINT fk_triple_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_triple_subject FOREIGN KEY (subject_entity_id) REFERENCES entities (id),
    CONSTRAINT fk_triple_object FOREIGN KEY (object_entity_id) REFERENCES entities (id),
    CONSTRAINT fk_triple_chunk FOREIGN KEY (source_chunk_id) REFERENCES document_chunks (id),
    CONSTRAINT fk_triple_proposer FOREIGN KEY (proposed_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_triple_decider FOREIGN KEY (decided_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_triple_run FOREIGN KEY (extraction_run_id) REFERENCES extraction_runs (id) ON DELETE SET NULL,
    CONSTRAINT ck_triple_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    -- An approved triple must record who approved it and when. This is the
    -- machine-level guarantee behind "every approved triple has an audit trail".
    CONSTRAINT ck_triple_approved_has_decider CHECK (
        status <> 'APPROVED' OR (decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- claims
-- ---------------------------------------------------------------------
CREATE TABLE claims (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    corpus_id           BIGINT        NOT NULL,
    subject             VARCHAR(300)  NOT NULL,
    claim_text          TEXT          NOT NULL,
    polarity            VARCHAR(16)   NOT NULL,
    predicate           VARCHAR(60)   NULL,
    object_text         VARCHAR(300)  NULL,
    qualifier           VARCHAR(500)  NULL,
    effective_time      VARCHAR(200)  NULL,
    location            VARCHAR(200)  NULL,
    quantity            VARCHAR(200)  NULL,
    source_sentence     TEXT          NOT NULL,
    source_chunk_id     BIGINT        NOT NULL,
    proposed_by_user_id BIGINT        NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'PROPOSED',
    decided_by_user_id  BIGINT        NULL,
    decided_at          DATETIME(6)   NULL,
    prompt_version      VARCHAR(64)   NULL,
    extraction_run_id   BIGINT        NULL,
    -- Human-readable fingerprint; claim_hash carries the uniqueness constraint
    -- for the same index-length reason as triples.fact_hash.
    claim_key           VARCHAR(1000) NOT NULL,
    claim_hash          CHAR(64)      NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_claim_hash (corpus_id, claim_hash),
    KEY ix_claim_status (status),
    KEY ix_claim_corpus (corpus_id),
    KEY ix_claim_corpus_status (corpus_id, status),
    KEY ix_claim_subject (subject),
    KEY ix_claim_chunk (source_chunk_id),
    KEY ix_claim_predicate (predicate),
    CONSTRAINT fk_claim_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_claim_chunk FOREIGN KEY (source_chunk_id) REFERENCES document_chunks (id),
    CONSTRAINT fk_claim_proposer FOREIGN KEY (proposed_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_claim_decider FOREIGN KEY (decided_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_claim_run FOREIGN KEY (extraction_run_id) REFERENCES extraction_runs (id) ON DELETE SET NULL,
    CONSTRAINT ck_claim_status CHECK (status IN
        ('PROPOSED', 'APPROVED', 'REJECTED', 'VERIFIED', 'ADJUDICATED')),
    CONSTRAINT ck_claim_polarity CHECK (polarity IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- verdicts
--
-- Confidence semantics are deliberately split into separate columns:
--   llm_score      what the model said
--   rule_penalty   what the deterministic rule engine deducted
--   fused_score    the arithmetic combination of the two
--   evidence_status whether evidence was found at all
--
-- fused_score is a SCORE, not a calibrated probability. No calibration
-- experiment has been run, so it must never be presented as one.
-- ---------------------------------------------------------------------
CREATE TABLE verdicts (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    claim_id               BIGINT       NOT NULL,
    corpus_id              BIGINT       NOT NULL,
    verdict_type           VARCHAR(32)  NOT NULL,
    machine_verdict_type   VARCHAR(32)  NOT NULL,
    human_verdict_type     VARCHAR(32)  NULL,
    adjudication_state     VARCHAR(16)  NOT NULL DEFAULT 'MACHINE_ONLY',
    adjudicator_user_id    BIGINT       NULL,
    adjudicated_at         DATETIME(6)  NULL,
    adjudication_note      VARCHAR(2000) NULL,
    llm_score              DECIMAL(4,3) NULL,
    rule_penalty           DECIMAL(4,3) NOT NULL DEFAULT 0.000,
    fused_score            DECIMAL(4,3) NULL,
    evidence_status        VARCHAR(24)  NOT NULL,
    rule_version           VARCHAR(64)  NULL,
    verdict_reason         TEXT         NULL,
    llm_reasoning          TEXT         NULL,
    model                  VARCHAR(200) NULL,
    prompt_version         VARCHAR(64)  NULL,
    retrieval_query        VARCHAR(500) NULL,
    trace_run_id           BIGINT       NULL,
    attempt                INT          NOT NULL DEFAULT 1,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- One live verdict per claim. History of superseded verdicts is kept in
    -- verdict_history rather than by re-inserting, so a claim's current
    -- verdict is unambiguous.
    UNIQUE KEY uk_verdict_current_claim (claim_id),
    KEY ix_verdict_claim (claim_id),
    KEY ix_verdict_status (adjudication_state),
    KEY ix_verdict_corpus (corpus_id),
    KEY ix_verdict_corpus_type (corpus_id, verdict_type),
    CONSTRAINT fk_verdict_claim FOREIGN KEY (claim_id) REFERENCES claims (id) ON DELETE CASCADE,
    CONSTRAINT fk_verdict_corpus FOREIGN KEY (corpus_id) REFERENCES corpora (id),
    CONSTRAINT fk_verdict_adjudicator FOREIGN KEY (adjudicator_user_id) REFERENCES users (id),
    CONSTRAINT ck_verdict_type CHECK (verdict_type IN
        ('SUPPORTED', 'CONTRADICTED', 'INSUFFICIENT_EVIDENCE', 'EXAGGERATED', 'SOURCE_MISSING')),
    CONSTRAINT ck_verdict_machine_type CHECK (machine_verdict_type IN
        ('SUPPORTED', 'CONTRADICTED', 'INSUFFICIENT_EVIDENCE', 'EXAGGERATED', 'SOURCE_MISSING')),
    CONSTRAINT ck_verdict_human_type CHECK (human_verdict_type IS NULL OR human_verdict_type IN
        ('SUPPORTED', 'CONTRADICTED', 'INSUFFICIENT_EVIDENCE', 'EXAGGERATED', 'SOURCE_MISSING')),
    CONSTRAINT ck_verdict_evidence_status CHECK (evidence_status IN
        ('EVIDENCE_FOUND', 'NO_EVIDENCE', 'CROSS_CORPUS_ATTEMPT_BLOCKED')),
    CONSTRAINT ck_verdict_adjudication_state CHECK (adjudication_state IN
        ('MACHINE_ONLY', 'CONTESTED', 'HUMAN_DECISION')),
    -- A human decision must record who made it and when, and must preserve
    -- the machine verdict. This constraint makes that structural.
    CONSTRAINT ck_verdict_human_decision_complete CHECK (
        adjudication_state <> 'HUMAN_DECISION'
        OR (human_verdict_type IS NOT NULL
            AND adjudicator_user_id IS NOT NULL
            AND adjudicated_at IS NOT NULL
            AND machine_verdict_type IS NOT NULL)),
    -- SOURCE_MISSING asserts "no relevant evidence was found", so it must not
    -- be paired with EVIDENCE_FOUND. CONTRADICTED, by contrast, requires
    -- evidence to exist. This makes "no evidence" and "evidence against"
    -- structurally distinct states rather than a naming convention.
    CONSTRAINT ck_verdict_missing_requires_no_evidence CHECK (
        verdict_type <> 'SOURCE_MISSING' OR evidence_status = 'NO_EVIDENCE'),
    CONSTRAINT ck_verdict_contradicted_requires_evidence CHECK (
        verdict_type <> 'CONTRADICTED' OR evidence_status = 'EVIDENCE_FOUND')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- verdict_passages  (evidence linking)
--
-- A verdict either has evidence rows, or its evidence_status says
-- NO_EVIDENCE. The application enforces this; storing rank and score keeps
-- the retrieval result reproducible from the verdict alone.
-- ---------------------------------------------------------------------
CREATE TABLE verdict_passages (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    verdict_id     BIGINT       NOT NULL,
    corpus_id      BIGINT       NOT NULL,
    chunk_id       BIGINT       NOT NULL,
    document_id    BIGINT       NOT NULL,
    chunk_text     TEXT         NOT NULL,
    retrieval_rank INT          NOT NULL,
    retrieval_score DECIMAL(10,4) NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_verdict_passage (verdict_id, chunk_id),
    KEY ix_verdict_passage_verdict (verdict_id),
    KEY ix_verdict_passage_chunk (chunk_id),
    KEY ix_verdict_passage_corpus (corpus_id),
    CONSTRAINT fk_vp_verdict FOREIGN KEY (verdict_id) REFERENCES verdicts (id) ON DELETE CASCADE,
    -- chunk_text is denormalised deliberately: the Glass Box must show the
    -- exact evidence text at judgment time even if the document later changes.
    CONSTRAINT fk_vp_chunk FOREIGN KEY (chunk_id) REFERENCES document_chunks (id) ON DELETE CASCADE,
    CONSTRAINT fk_vp_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- verdict_history
--
-- Superseded machine verdicts. A human override never overwrites the
-- machine result; it is preserved here so the audit shows both facts.
-- ---------------------------------------------------------------------
CREATE TABLE verdict_history (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    claim_id           BIGINT      NOT NULL,
    corpus_id          BIGINT      NOT NULL,
    verdict_type       VARCHAR(32) NOT NULL,
    llm_score          DECIMAL(4,3) NULL,
    rule_penalty       DECIMAL(4,3) NOT NULL DEFAULT 0.000,
    fused_score        DECIMAL(4,3) NULL,
    evidence_status    VARCHAR(24) NOT NULL,
    model              VARCHAR(200) NULL,
    superseded_at      DATETIME(6) NOT NULL,
    superseded_reason  VARCHAR(200) NULL,
    PRIMARY KEY (id),
    KEY ix_vhistory_claim (claim_id, superseded_at),
    KEY ix_vhistory_corpus (corpus_id),
    CONSTRAINT fk_vhistory_claim FOREIGN KEY (claim_id) REFERENCES claims (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
