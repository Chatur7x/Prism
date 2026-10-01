package com.prism.claims;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A superseded machine verdict.
 *
 * <p>Written when a claim is re-verified. Keeping history rather than updating
 * in place means an operator can see how a claim's assessment moved over time,
 * and a human override can always be compared against what the machine
 * originally said.
 */
@Entity
@Table(name = "verdict_history",
        indexes = {
                @Index(name = "ix_vhistory_claim", columnList = "claim_id,superseded_at"),
                @Index(name = "ix_vhistory_corpus", columnList = "corpus_id")
        })
public class VerdictHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "corpus_id", nullable = false)
    private Long corpusId;

    @Column(name = "verdict_type", nullable = false, length = 32)
    private String verdictType;

    @Column(name = "llm_score", precision = 4, scale = 3)
    private BigDecimal llmScore;

    @Column(name = "rule_penalty", precision = 4, scale = 3, nullable = false)
    private BigDecimal rulePenalty = BigDecimal.ZERO;

    @Column(name = "fused_score", precision = 4, scale = 3)
    private BigDecimal fusedScore;

    @Column(name = "evidence_status", nullable = false, length = 24)
    private String evidenceStatus;

    @Column(name = "model", length = 200)
    private String model;

    @Column(name = "superseded_at", nullable = false)
    private Instant supersededAt = Instant.now();

    @Column(name = "superseded_reason", length = 200)
    private String supersededReason;

    protected VerdictHistory() {
        // for JPA
    }

    public VerdictHistory(Long claimId, Long corpusId, Verdict verdict, String reason) {
        this.claimId = claimId;
        this.corpusId = corpusId;
        this.verdictType = verdict.getVerdictType().name();
        this.llmScore = verdict.getLlmScore();
        this.rulePenalty = verdict.getRulePenalty();
        this.fusedScore = verdict.getFusedScore();
        this.evidenceStatus = verdict.getEvidenceStatus().name();
        this.model = verdict.getModel();
        this.supersededReason = reason;
        this.supersededAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getVerdictType() {
        return verdictType;
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

    public String getEvidenceStatus() {
        return evidenceStatus;
    }

    public String getModel() {
        return model;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public String getSupersededReason() {
        return supersededReason;
    }
}
