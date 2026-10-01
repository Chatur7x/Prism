package com.prism.claims;

import com.prism.corpus.Corpus;
import com.prism.document.DocumentChunk;
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
 * A structured assertion awaiting and receiving verification.
 *
 * <p>Carries more than free text so contradiction detection and the Skeptic can
 * reason about structure: an optional predicate and object, plus qualifier,
 * time, location, and quantity slots when the source made them explicit.
 */
@Entity
@Table(name = "claims",
        indexes = {
                @Index(name = "ix_claim_status", columnList = "status"),
                @Index(name = "ix_claim_corpus", columnList = "corpus_id"),
                @Index(name = "ix_claim_corpus_status", columnList = "corpus_id,status"),
                @Index(name = "ix_claim_subject", columnList = "subject"),
                @Index(name = "ix_claim_chunk", columnList = "source_chunk_id")
        })
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @Column(nullable = false, length = 300)
    private String subject;

    @Lob
    @Column(name = "claim_text", nullable = false, columnDefinition = "TEXT")
    private String claimText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClaimPolarity polarity = ClaimPolarity.NEUTRAL;

    /** Optional structured relation, when the claim maps onto a predicate. */
    @Column(name = "predicate", length = 60)
    private String predicate;

    @Column(name = "object_text", length = 300)
    private String objectText;

    /** Free-text qualifier, e.g. "in the 2026 restructuring". */
    @Column(name = "qualifier", length = 500)
    private String qualifier;

    /** Effective time of the assertion, when the source states one. */
    @Column(name = "effective_time", length = 200)
    private String effectiveTime;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "quantity", length = 200)
    private String quantity;

    @Lob
    @Column(name = "source_sentence", nullable = false, columnDefinition = "TEXT")
    private String sourceSentence;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_chunk_id", nullable = false)
    private DocumentChunk sourceChunk;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposed_by_user_id", nullable = false)
    private User proposedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClaimStatus status = ClaimStatus.PROPOSED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_user_id")
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "extraction_run_id")
    private Long extractionRunId;

    /** Fingerprint used to collapse duplicate extraction of the same assertion. */
    @Column(name = "claim_key", nullable = false, length = 1000)
    private String claimKey;

    /**
     * SHA-256 of {@link #claimKey}. Carries the uniqueness constraint.
     *
     * <p>Declared as {@code char(64)} to match the migration: a hex digest is
     * always exactly 64 characters.
     */
    @Column(name = "claim_hash", nullable = false, columnDefinition = "char(64)")
    private String claimHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Claim() {
        // for JPA
    }

    public Claim(Corpus corpus, String subject, String claimText, ClaimPolarity polarity,
                 String predicate, String objectText, String sourceSentence,
                 DocumentChunk sourceChunk, User proposedBy, String promptVersion,
                 Long extractionRunId, String claimKey, String claimHash) {
        this.corpus = corpus;
        this.subject = subject;
        this.claimText = claimText;
        this.polarity = polarity == null ? ClaimPolarity.NEUTRAL : polarity;
        this.predicate = predicate;
        this.objectText = objectText;
        this.sourceSentence = sourceSentence;
        this.sourceChunk = sourceChunk;
        this.proposedBy = proposedBy;
        this.promptVersion = promptVersion;
        this.extractionRunId = extractionRunId;
        this.claimKey = claimKey;
        this.claimHash = claimHash;
        this.status = ClaimStatus.PROPOSED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void addQualifier(String qualifier, String effectiveTime, String location, String quantity) {
        this.qualifier = qualifier;
        this.effectiveTime = effectiveTime;
        this.location = location;
        this.quantity = quantity;
    }

    public boolean approve(User verifier, Instant now) {
        if (status != ClaimStatus.PROPOSED) {
            return false;
        }
        this.status = ClaimStatus.APPROVED;
        this.decidedBy = verifier;
        this.decidedAt = now;
        this.updatedAt = now;
        return true;
    }

    public boolean reject(User verifier, Instant now) {
        if (status != ClaimStatus.PROPOSED) {
            return false;
        }
        this.status = ClaimStatus.REJECTED;
        this.decidedBy = verifier;
        this.decidedAt = now;
        this.updatedAt = now;
        return true;
    }

    public void markVerified(Instant now) {
        this.status = ClaimStatus.VERIFIED;
        this.updatedAt = now;
    }

    public void markAdjudicated(Instant now) {
        this.status = ClaimStatus.ADJUDICATED;
        this.updatedAt = now;
    }

    public boolean isEligibleForVerification() {
        return status == ClaimStatus.APPROVED;
    }

    public Long getId() {
        return id;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public String getSubject() {
        return subject;
    }

    public String getClaimText() {
        return claimText;
    }

    public ClaimPolarity getPolarity() {
        return polarity;
    }

    public String getPredicate() {
        return predicate;
    }

    public String getObjectText() {
        return objectText;
    }

    public String getQualifier() {
        return qualifier;
    }

    public String getEffectiveTime() {
        return effectiveTime;
    }

    public String getLocation() {
        return location;
    }

    public String getQuantity() {
        return quantity;
    }

    public String getSourceSentence() {
        return sourceSentence;
    }

    public DocumentChunk getSourceChunk() {
        return sourceChunk;
    }

    public User getProposedBy() {
        return proposedBy;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public User getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Long getExtractionRunId() {
        return extractionRunId;
    }

    public String getClaimKey() {
        return claimKey;
    }

    public String getClaimHash() {
        return claimHash;
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
