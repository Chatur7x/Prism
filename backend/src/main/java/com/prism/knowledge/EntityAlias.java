package com.prism.knowledge;

// NOTE: jakarta.persistence.Entity is NOT imported here. This file references
// the domain class com.prism.knowledge.Entity, and importing the JPA annotation
// under the same simple name would silently shadow it.
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * An alternative surface form that refers to an existing entity.
 *
 * <p>Aliases are what make recall work: a document that says "Northstar" and one
 * that says "Northstar Holdings" resolve to the same node without the resolver
 * having to guess.
 */
@jakarta.persistence.Entity
@Table(name = "entity_aliases",
        uniqueConstraints = @UniqueConstraint(name = "uk_alias_corpus_key", columnNames = {"corpus_id", "normalized_alias"}),
        indexes = {
                @Index(name = "ix_alias_entity", columnList = "entity_id"),
                @Index(name = "ix_alias_corpus_key", columnList = "corpus_id,normalized_alias")
        })
public class EntityAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private Entity entity;

    /** Denormalised from {@link #entity} to enforce alias uniqueness in one key. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private com.prism.corpus.Corpus corpus;

    @Column(name = "alias_text", nullable = false, length = 300)
    private String aliasText;

    @Column(name = "normalized_alias", nullable = false, length = 300)
    private String normalizedAlias;

    @Column(name = "support_count", nullable = false)
    private int supportCount = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EntityAlias() {
        // for JPA
    }

    public EntityAlias(Entity entity, String aliasText, String normalizedAlias) {
        this.entity = entity;
        // corpus_id is denormalised from the owning entity so alias uniqueness
        // is enforceable in a single unique key. Never taken from client input.
        this.corpus = entity.getCorpus();
        this.aliasText = aliasText;
        this.normalizedAlias = normalizedAlias;
        this.supportCount = 1;
        this.createdAt = Instant.now();
    }

    public com.prism.corpus.Corpus getCorpus() {
        return corpus;
    }

    public void recordSupport() {
        this.supportCount++;
    }

    public Long getId() {
        return id;
    }

    public Entity getEntity() {
        return entity;
    }

    public String getAliasText() {
        return aliasText;
    }

    public String getNormalizedAlias() {
        return normalizedAlias;
    }

    public int getSupportCount() {
        return supportCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
