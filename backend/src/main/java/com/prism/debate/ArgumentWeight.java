package com.prism.debate;

import com.prism.corpus.Corpus;
import com.prism.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A human chair's weight on an argument.
 *
 * <p>Human assessment is an immutable audit fact. Rows are appended, never
 * updated: if a chair revises a weight, the second assessment is a new row and
 * the first remains visible. The synthesis layer then uses the most recent
 * weight per argument, so the record shows both the change and the fact that it
 * changed.
 *
 * <p>There is no write path to this table from any model-facing code.
 */
@Entity
@Table(name = "argument_weights",
        indexes = {
                @Index(name = "ix_weight_argument", columnList = "argument_id"),
                @Index(name = "ix_weight_debate", columnList = "debate_id"),
                @Index(name = "ix_weight_corpus", columnList = "corpus_id")
        })
public class ArgumentWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "argument_id", nullable = false)
    private Argument argument;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debate_id", nullable = false)
    private Debate debate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @Column(nullable = false)
    private int weight;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verifier_user_id", nullable = false)
    private User verifier;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ArgumentWeight() {
        // for JPA
    }

    public ArgumentWeight(Argument argument, Debate debate, int weight, User verifier, String note) {
        this.argument = argument;
        this.debate = debate;
        this.corpus = debate.getCorpus();
        this.weight = weight;
        this.verifier = verifier;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Argument getArgument() {
        return argument;
    }

    public Debate getDebate() {
        return debate;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public int getWeight() {
        return weight;
    }

    public User getVerifier() {
        return verifier;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
