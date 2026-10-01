package com.prism.corpus;

import com.prism.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * The isolation boundary for the entire system.
 *
 * <p>Documents, chunks, entities, triples, claims, verdicts, contradictions,
 * debates, and chat sessions all resolve to exactly one corpus. No retrieval,
 * graph query, or citation may cross this boundary.
 */
@Entity
@Table(name = "corpora")
public class Corpus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CorpusStatus status = CorpusStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Corpus() {
        // for JPA
    }

    public Corpus(String name, String description, User owner) {
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.status = CorpusStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void rename(String name, String description, Instant now) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        this.status = CorpusStatus.ARCHIVED;
        this.updatedAt = now;
    }

    public void reactivate(Instant now) {
        this.status = CorpusStatus.ACTIVE;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == CorpusStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public User getOwner() {
        return owner;
    }

    public CorpusStatus getStatus() {
        return status;
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
