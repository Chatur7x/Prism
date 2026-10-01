package com.prism.contradiction;

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

import java.time.Instant;

/**
 * A deterministic finding that two approved records cannot both be true.
 *
 * <p>{@code ruleCode} and {@code ruleVersion} are stored so a human can audit
 * why the system asserted a conflict, and so a later change to the rules can be
 * evaluated against these historical findings.
 */
@Entity
@Table(name = "contradictions",
        indexes = {
                @Index(name = "ix_contradiction_status", columnList = "status"),
                @Index(name = "ix_contradiction_corpus", columnList = "corpus_id"),
                @Index(name = "ix_contradiction_corpus_status", columnList = "corpus_id,status"),
                @Index(name = "ix_contradiction_type", columnList = "contradiction_type")
        })
public class Contradiction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @Column(name = "contradiction_type", nullable = false, length = 40)
    private String contradictionType;

    @Column(name = "subject_text", nullable = false, length = 300)
    private String subjectText;

    @Column(name = "predicate", length = 60)
    private String predicate;

    @Column(name = "left_description", nullable = false, length = 1000)
    private String leftDescription;

    @Column(name = "right_description", nullable = false, length = 1000)
    private String rightDescription;

    @Column(name = "rule_code", nullable = false, length = 64)
    private String ruleCode;

    @Column(name = "rule_version", nullable = false, length = 64)
    private String ruleVersion;

    @Lob
    @Column(name = "explanation", nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContradictionStatus status = ContradictionStatus.OPEN;

    @Column(name = "left_triple_id")
    private Long leftTripleId;

    @Column(name = "right_triple_id")
    private Long rightTripleId;

    @Column(name = "left_claim_id")
    private Long leftClaimId;

    @Column(name = "right_claim_id")
    private Long rightClaimId;

    /**
     * Stable identity so a re-scan updates rather than duplicates.
     *
     * <p>Declared as {@code char(64)} to match the migration: a hex digest is
     * always exactly 64 characters.
     */
    @Column(name = "conflict_hash", nullable = false, columnDefinition = "char(64)")
    private String conflictHash;

    @Column(name = "trace_run_id")
    private Long traceRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Contradiction() {
        // for JPA
    }

    public Contradiction(Corpus corpus, ContradictionFinding finding, Long traceRunId) {
        this.corpus = corpus;
        this.contradictionType = finding.type();
        this.subjectText = finding.subjectText();
        this.predicate = finding.predicate();
        this.leftDescription = finding.leftDescription();
        this.rightDescription = finding.rightDescription();
        this.ruleCode = finding.ruleCode();
        this.ruleVersion = finding.ruleVersion();
        this.explanation = finding.explanation();
        this.status = ContradictionStatus.OPEN;
        this.leftTripleId = finding.leftTripleId();
        this.rightTripleId = finding.rightTripleId();
        this.leftClaimId = finding.leftClaimId();
        this.rightClaimId = finding.rightClaimId();
        this.conflictHash = finding.conflictHash();
        this.traceRunId = traceRunId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Guarded so a debate cannot begin twice from a stale UI. */
    public boolean markInDebate(Instant now) {
        if (status != ContradictionStatus.OPEN) {
            return false;
        }
        this.status = ContradictionStatus.IN_DEBATE;
        this.updatedAt = now;
        return true;
    }

    public void markResolved(Instant now) {
        this.status = ContradictionStatus.RESOLVED;
        this.updatedAt = now;
    }

    public void markDismissed(Instant now) {
        this.status = ContradictionStatus.DISMISSED;
        this.updatedAt = now;
    }

    public void reopen(Instant now) {
        this.status = ContradictionStatus.OPEN;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public String getContradictionType() {
        return contradictionType;
    }

    public String getSubjectText() {
        return subjectText;
    }

    public String getPredicate() {
        return predicate;
    }

    public String getLeftDescription() {
        return leftDescription;
    }

    public String getRightDescription() {
        return rightDescription;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public String getExplanation() {
        return explanation;
    }

    public ContradictionStatus getStatus() {
        return status;
    }

    public Long getLeftTripleId() {
        return leftTripleId;
    }

    public Long getRightTripleId() {
        return rightTripleId;
    }

    public Long getLeftClaimId() {
        return leftClaimId;
    }

    public Long getRightClaimId() {
        return rightClaimId;
    }

    public String getConflictHash() {
        return conflictHash;
    }

    public Long getTraceRunId() {
        return traceRunId;
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
