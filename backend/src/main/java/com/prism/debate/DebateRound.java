package com.prism.debate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "debate_rounds",
        indexes = @Index(name = "ix_round_debate", columnList = "debate_id"))
public class DebateRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @JoinColumn(name = "debate_id", nullable = false)
    private Debate debate;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "arguments_completed", nullable = false)
    private int argumentsCompleted;

    @Column(name = "arguments_failed", nullable = false)
    private int argumentsFailed;

    protected DebateRound() {
        // for JPA
    }

    public DebateRound(Debate debate, int roundNumber) {
        this.debate = debate;
        this.roundNumber = roundNumber;
        this.startedAt = Instant.now();
    }

    public void recordArgument(boolean succeeded, Instant now) {
        if (succeeded) {
            argumentsCompleted++;
        } else {
            argumentsFailed++;
        }
    }

    public void complete(Instant now) {
        this.completedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Debate getDebate() {
        return debate;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getArgumentsCompleted() {
        return argumentsCompleted;
    }

    public int getArgumentsFailed() {
        return argumentsFailed;
    }
}
