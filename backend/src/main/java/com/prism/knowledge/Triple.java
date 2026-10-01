package com.prism.knowledge;

import com.prism.corpus.Corpus;
import com.prism.document.DocumentChunk;
import com.prism.user.User;
// NOTE: jakarta.persistence.Entity is NOT imported here. This file references
// the domain class com.prism.knowledge.Entity for the subject and object
// endpoints, and importing the JPA annotation under the same simple name would
// silently shadow it.
import jakarta.persistence.Column;
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
 * A proposed (subject, predicate, object) fact, with full provenance.
 *
 * <p>A triple is a <em>proposal</em> the moment it is created. It carries no
 * authority until a Verifier approves it. The source chunk and quoted sentence
 * are mandatory: an approved triple without provenance is a defect, not a
 * valid state.
 */
@jakarta.persistence.Entity
@Table(name = "triples",
        indexes = {
                @Index(name = "ix_triple_corpus_status", columnList = "corpus_id,status"),
                @Index(name = "ix_triple_subject", columnList = "subject_entity_id"),
                @Index(name = "ix_triple_object", columnList = "object_entity_id"),
                @Index(name = "ix_triple_predicate", columnList = "predicate"),
                @Index(name = "ix_triple_chunk", columnList = "source_chunk_id")
        })
public class Triple {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_entity_id", nullable = false)
    private Entity subjectEntity;

    @Column(name = "subject_text", nullable = false, length = 300)
    private String subject;

    @Column(nullable = false, length = 60)
    private String predicate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "object_entity_id", nullable = false)
    private Entity objectEntity;

    @Column(name = "object_text", nullable = false, length = 300)
    private String object;

    /** The verbatim source sentence the model quoted. Mandatory. */
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
    private ProposalStatus status = ProposalStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_user_id")
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "extraction_run_id")
    private Long extractionRunId;

    /**
     * Fingerprint of the fact itself, used to collapse duplicate extraction of
     * the same relation from different chunks without losing the chunk that
     * first established it.
     */
    @Column(name = "fact_key", nullable = false, length = 700)
    private String factKey;

    /**
     * SHA-256 of {@link #factKey}. Carries the uniqueness constraint.
     *
     * <p>Declared as {@code char(64)} to match the migration. CHAR is the right
     * type here: a hex digest is always exactly 64 characters, and CHAR stores
     * it without a length prefix while giving the optimiser a fixed width to
     * work with on the unique index.
     */
    @Column(name = "fact_hash", nullable = false, columnDefinition = "char(64)")
    private String factHash;

    @Column(name = "evidence_chunk_count", nullable = false)
    private int evidenceChunkCount = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Triple() {
        // for JPA
    }

    public Triple(Corpus corpus, Entity subjectEntity, String subject, String predicate,
                  Entity objectEntity, String object, String sourceSentence,
                  DocumentChunk sourceChunk, User proposedBy, String promptVersion,
                  Long extractionRunId, String factKey, String factHash) {
        this.corpus = corpus;
        this.subjectEntity = subjectEntity;
        this.subject = subject;
        this.predicate = predicate;
        this.objectEntity = objectEntity;
        this.object = object;
        this.sourceSentence = sourceSentence;
        this.sourceChunk = sourceChunk;
        this.proposedBy = proposedBy;
        this.promptVersion = promptVersion;
        this.extractionRunId = extractionRunId;
        this.factKey = factKey;
        this.factHash = factHash;
        this.status = ProposalStatus.PENDING;
        this.evidenceChunkCount = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Approves the triple. The status guard makes a double approval a no-op that
     * the service turns into a conflict, not a silent second write.
     */
    public boolean approve(User verifier, String note, Instant now) {
        if (status != ProposalStatus.PENDING) {
            return false;
        }
        this.status = ProposalStatus.APPROVED;
        this.decidedBy = verifier;
        this.decidedAt = now;
        this.decisionNote = note;
        this.updatedAt = now;
        return true;
    }

    public boolean reject(User verifier, String note, Instant now) {
        if (status != ProposalStatus.PENDING) {
            return false;
        }
        this.status = ProposalStatus.REJECTED;
        this.decidedBy = verifier;
        this.decidedAt = now;
        this.decisionNote = note;
        this.updatedAt = now;
        return true;
    }

    public void recordAdditionalEvidence(Instant now) {
        this.evidenceChunkCount++;
        this.updatedAt = now;
    }

    public boolean isApproved() {
        return status == ProposalStatus.APPROVED;
    }

    public Long getId() {
        return id;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public Entity getSubjectEntity() {
        return subjectEntity;
    }

    public String getSubject() {
        return subject;
    }

    public String getPredicate() {
        return predicate;
    }

    public Entity getObjectEntity() {
        return objectEntity;
    }

    public String getObject() {
        return object;
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

    public ProposalStatus getStatus() {
        return status;
    }

    public User getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Long getExtractionRunId() {
        return extractionRunId;
    }

    public String getFactKey() {
        return factKey;
    }

    public String getFactHash() {
        return factHash;
    }

    public int getEvidenceChunkCount() {
        return evidenceChunkCount;
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
