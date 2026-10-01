package com.prism.debate;

import com.prism.contradiction.Contradiction;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A Council convened over one contradiction.
 *
 * <p>{@code state} advances only through {@link DebateEngine}. The {@code @Version}
 * column plus a conditional state check at the persistence boundary means two
 * concurrent ADVANCE requests produce one success and one 409, never two
 * successes.
 */
@Entity
@Table(name = "debates",
        indexes = {
                @Index(name = "ix_debate_state", columnList = "state"),
                @Index(name = "ix_debate_corpus", columnList = "corpus_id"),
                @Index(name = "ix_debate_corpus_state", columnList = "corpus_id,state")
        })
public class Debate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contradiction_id", nullable = false)
    private Contradiction contradiction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DebateState state = DebateState.CREATED;

    @Column(name = "current_round", nullable = false)
    private int currentRound;

    @Column(name = "max_rounds", nullable = false)
    private int maxRounds = DebateEngine.DEFAULT_MAX_ROUNDS;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chaired_by_user_id", nullable = false)
    private User chairedBy;

    @Column(nullable = false, length = 500)
    private String topic;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Debate() {
        // for JPA
    }

    public Debate(Contradiction contradiction, User chair, String topic, int maxRounds) {
        this.contradiction = contradiction;
        this.corpus = contradiction.getCorpus();
        this.chairedBy = chair;
        this.topic = topic;
        this.maxRounds = maxRounds;
        this.state = DebateState.CREATED;
        this.currentRound = 0;
        this.createdAt = Instant.now();
    }

    /**
     * Applies a state change, but only when the debate is still in the state the
     * caller expected.
     *
     * @return false when the debate has already moved on, so the caller reports
     *         a conflict instead of overwriting a newer state
     */
    public boolean applyState(DebateState expected, DebateState target, int newRound, Instant now) {
        if (this.state != expected) {
            return false;
        }
        this.state = target;
        this.currentRound = newRound;
        if (target == DebateState.ROUND_ACTIVE && this.startedAt == null) {
            this.startedAt = now;
        }
        if (target.isTerminal()) {
            this.finishedAt = now;
        }
        return true;
    }

    public void recordError(String error) {
        this.lastError = error == null ? null
                : (error.length() > 1900 ? error.substring(0, 1900) : error);
    }

    public Long getId() {
        return id;
    }

    public Contradiction getContradiction() {
        return contradiction;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public DebateState getState() {
        return state;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public User getChairedBy() {
        return chairedBy;
    }

    public String getTopic() {
        return topic;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public long getVersion() {
        return version;
    }
}
