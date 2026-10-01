package com.prism.claims;

import com.prism.corpus.Corpus;
import com.prism.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The result of verifying one claim, plus the human decision when there is one.
 *
 * <p><b>The machine verdict is never overwritten.</b> {@code machineVerdictType}
 * and {@code humanVerdictType} are separate columns, and a human decision is
 * only accepted when both exist. {@code verdictType} is the currently
 * effective verdict. That structure is what makes "preserve the original machine
 * result" a database-level guarantee rather than a service-layer intention.
 *
 * <p>Confidence fields are kept separate on purpose: {@code llmScore},
 * {@code rulePenalty}, {@code fusedScore}, and {@code evidenceStatus} answer
 * different questions and are never collapsed into one "confidence" number.
 */
@Entity
@Table(name = "verdicts",
        indexes = {
                @Index(name = "ix_verdict_claim", columnList = "claim_id"),
                @Index(name = "ix_verdict_status", columnList = "adjudication_state"),
                @Index(name = "ix_verdict_corpus", columnList = "corpus_id"),
                @Index(name = "ix_verdict_corpus_type", columnList = "corpus_id,verdict_type")
        })
public class Verdict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    /** The currently effective verdict. */
    @Enumerated(EnumType.STRING)
    @Column(name = "verdict_type", nullable = false, length = 32)
    private VerdictType verdictType;

    /** What the model said. Preserved even when a human overrides it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "machine_verdict_type", nullable = false, length = 32)
    private VerdictType machineVerdictType;

    /** What the human decided. Null until a human decides. */
    @Enumerated(EnumType.STRING)
    @Column(name = "human_verdict_type", length = 32)
    private VerdictType humanVerdictType;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjudication_state", nullable = false, length = 16)
    private AdjudicationState adjudicationState = AdjudicationState.MACHINE_ONLY;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjudicator_user_id")
    private User adjudicator;

    @Column(name = "adjudicated_at")
    private Instant adjudicatedAt;

    @Column(name = "adjudication_note", length = 2000)
    private String adjudicationNote;

    /** The judge's own score, in [0, 1]. Null when the judge produced none. */
    @Column(name = "llm_score", precision = 4, scale = 3)
    private BigDecimal llmScore;

    /** Deduction from the deterministic rule engine, in [0, 1]. */
    @Column(name = "rule_penalty", precision = 4, scale = 3, nullable = false)
    private BigDecimal rulePenalty = BigDecimal.ZERO;

    /**
     * Arithmetic combination of the two above.
     *
     * <p>A ranking score, NOT a calibrated probability. See
     * {@link ConfidenceFusion} and {@code docs/evaluation.md}.
     */
    @Column(name = "fused_score", precision = 4, scale = 3)
    private BigDecimal fusedScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_status", nullable = false, length = 24)
    private EvidenceStatus evidenceStatus;

    @Column(name = "rule_version", length = 64)
    private String ruleVersion;

    @Lob
    @Column(name = "verdict_reason", columnDefinition = "TEXT")
    private String verdictReason;

    /** The judge's concise explanation. Never hidden chain-of-thought. */
    @Lob
    @Column(name = "llm_reasoning", columnDefinition = "TEXT")
    private String llmReasoning;

    @Column(name = "model", length = 200)
    private String model;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "retrieval_query", length = 500)
    private String retrievalQuery;

    @Column(name = "trace_run_id")
    private Long traceRunId;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Verdict() {
        // for JPA
    }

    public Verdict(Claim claim, VerdictType machineVerdictType, EvidenceStatus evidenceStatus,
                   Double llmScore, double rulePenalty, Double fusedScore, String verdictReason,
                   String llmReasoning, String model, String promptVersion, String ruleVersion,
                   String retrievalQuery, Long traceRunId) {
        this.claim = claim;
        this.corpus = claim.getCorpus();
        this.machineVerdictType = machineVerdictType;
        this.verdictType = machineVerdictType;
        this.evidenceStatus = evidenceStatus;
        this.llmScore = toDecimal(llmScore);
        this.rulePenalty = toDecimal(rulePenalty);
        this.fusedScore = toDecimal(fusedScore);
        this.verdictReason = verdictReason;
        this.llmReasoning = llmReasoning;
        this.model = model;
        this.promptVersion = promptVersion;
        this.ruleVersion = ruleVersion;
        this.retrievalQuery = retrievalQuery;
        this.traceRunId = traceRunId;
        this.adjudicationState = machineVerdictType != null && machineVerdictType.requiresAdjudication()
                ? AdjudicationState.CONTESTED
                : AdjudicationState.MACHINE_ONLY;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Records a human decision. The machine verdict is left untouched.
     *
     * @throws IllegalStateException if no machine verdict is recorded, which
     *                               would make the override unauditable
     */
    public void adjudicate(User human, VerdictType humanVerdict, String note, Instant now) {
        if (this.machineVerdictType == null) {
            throw new IllegalStateException(
                    "cannot adjudicate a verdict with no recorded machine decision");
        }
        this.humanVerdictType = humanVerdict;
        this.adjudicationState = AdjudicationState.HUMAN_DECISION;
        this.adjudicator = human;
        this.adjudicatedAt = now;
        this.adjudicationNote = note;
        this.verdictType = humanVerdict;
        this.updatedAt = now;
    }

    /** Marks the verdict as awaiting human review without changing the outcome. */
    public void markContested(Instant now) {
        if (this.adjudicationState == AdjudicationState.MACHINE_ONLY) {
            this.adjudicationState = AdjudicationState.CONTESTED;
            this.updatedAt = now;
        }
    }

    public boolean wasOverridden() {
        return humanVerdictType != null && !humanVerdictType.equals(machineVerdictType);
    }

    private static BigDecimal toDecimal(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(Math.round(value * 1000.0) / 1000.0);
    }

    public Long getId() {
        return id;
    }

    public Claim getClaim() {
        return claim;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public VerdictType getVerdictType() {
        return verdictType;
    }

    public VerdictType getMachineVerdictType() {
        return machineVerdictType;
    }

    public VerdictType getHumanVerdictType() {
        return humanVerdictType;
    }

    public AdjudicationState getAdjudicationState() {
        return adjudicationState;
    }

    public User getAdjudicator() {
        return adjudicator;
    }

    public Instant getAdjudicatedAt() {
        return adjudicatedAt;
    }

    public String getAdjudicationNote() {
        return adjudicationNote;
    }

    public BigDecimal getLlmScore() {
        return llmScore;
    }

    public BigDecimal getRulePenalty() {
        return rulePenalty;
    }

    public BigDecimal getFusedScore() {
        return fusedScore;
    }

    public EvidenceStatus getEvidenceStatus() {
        return evidenceStatus;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public String getVerdictReason() {
        return verdictReason;
    }

    public String getLlmReasoning() {
        return llmReasoning;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getRetrievalQuery() {
        return retrievalQuery;
    }

    public Long getTraceRunId() {
        return traceRunId;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
