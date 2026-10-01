package com.prism.knowledge;

import com.prism.corpus.Corpus;
// jakarta.persistence.Entity is referenced fully-qualified below: this
// compilation unit defines a domain class also named Entity.
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A resolved entity within one corpus.
 *
 * <p>Identity is {@code (corpus_id, normalized_name)}. The normalized name is a
 * conservative key, so two distinct real-world entities that share a key would
 * be surfaced for human review rather than silently merged.
 */
@jakarta.persistence.Entity
@Table(name = "entities",
        uniqueConstraints = @UniqueConstraint(name = "uk_entity_corpus_key", columnNames = {"corpus_id", "normalized_name"}),
        indexes = {
                @Index(name = "ix_entity_corpus", columnList = "corpus_id"),
                @Index(name = "ix_entity_normalized_type", columnList = "normalized_name,type"),
                @Index(name = "ix_entity_type", columnList = "type")
        })
public class Entity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    /** Display name as first seen. */
    @Column(name = "display_name", nullable = false, length = 300)
    private String displayName;

    /** Conservative identity key. See {@link EntityNormalizer}. */
    @Column(name = "normalized_name", nullable = false, length = 300)
    private String normalizedName;

    @Column(name = "type", nullable = false, length = 60)
    private String type = "UNSPECIFIED";

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResolutionState resolutionState = ResolutionState.KEEP_SEPARATE;

    @Column(name = "support_count", nullable = false)
    private int supportCount = 1;

    @Column(name = "first_seen_chunk_id")
    private Long firstSeenChunkId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Entity() {
        // for JPA
    }

    public Entity(Corpus corpus, String displayName, String normalizedName, String type, Long firstSeenChunkId) {
        this.corpus = corpus;
        this.displayName = displayName;
        this.normalizedName = normalizedName;
        this.type = type == null || type.isBlank() ? "UNSPECIFIED" : type.toUpperCase(java.util.Locale.ROOT);
        this.firstSeenChunkId = firstSeenChunkId;
        this.resolutionState = ResolutionState.KEEP_SEPARATE;
        this.supportCount = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void recordSupport(Long chunkId, Instant now) {
        this.supportCount++;
        if (chunkId != null && this.firstSeenChunkId == null) {
            this.firstSeenChunkId = chunkId;
        }
        this.updatedAt = now;
    }

    public void markMergedInto(Instant now) {
        this.resolutionState = ResolutionState.MERGE;
        this.updatedAt = now;
    }

    public void markSeparate(Instant now) {
        this.resolutionState = ResolutionState.KEEP_SEPARATE;
        this.updatedAt = now;
    }

    public void flagForReview(Instant now) {
        this.resolutionState = ResolutionState.REVIEW;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ResolutionState getResolutionState() {
        return resolutionState;
    }

    public int getSupportCount() {
        return supportCount;
    }

    public Long getFirstSeenChunkId() {
        return firstSeenChunkId;
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
