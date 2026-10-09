package com.prism.debate;

import com.prism.corpus.Corpus;
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

import java.time.Instant;

/**
 * One persona's argument in one round.
 *
 * <p>{@code failed} is load-bearing. When a persona produces nothing usable —
 * provider down, timeout, malformed output — the row is still written, marked
 * failed, with the reason. Synthesising a plausible-sounding argument for a
 * silent failure would corrupt the council's output with something no model
 * actually said.
 */
@Entity
@Table(name = "arguments",
        indexes = {
                @Index(name = "ix_argument_round", columnList = "debate_round_id"),
                @Index(name = "ix_argument_debate", columnList = "debate_id"),
                @Index(name = "ix_argument_corpus", columnList = "corpus_id")
        })
public class Argument {

    /** Outcome of a persona call. */
    public enum Status {
        PENDING,
        COMPLETE,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debate_round_id", nullable = false)
    private DebateRound debateRound;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debate_id", nullable = false)
    private Debate debate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Persona persona;

    @Lob
    @Column(name = "argument_text", nullable = false, columnDefinition = "TEXT")
    private String argumentText;

    @Column(name = "stance", length = 500)
    private String stance;

    @Column(name = "model", length = 200)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private boolean failed;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Argument() {
        // for JPA
    }

    public Argument(DebateRound round, Debate debate, Persona persona, String argumentText,
                    String stance, String model, String promptVersion, Long durationMs) {
        this.debateRound = round;
        this.debate = debate;
        this.corpus = debate.getCorpus();
        this.persona = persona;
        this.argumentText = argumentText;
        this.stance = stance;
        this.model = model;
        this.promptVersion = promptVersion;
        this.status = Status.COMPLETE;
        this.failed = false;
        this.durationMs = durationMs;
        this.createdAt = Instant.now();
    }

    /**
     * Records a persona that produced nothing usable. The argument text states
     * the failure rather than a fabricated position.
     */
    public static Argument failed(DebateRound round, Debate debate, Persona persona,
                                  String reason, String model, String promptVersion) {
        // The persona must be named for the Glass Box to be honest, but a null
        // here must not turn a slow model into a server fault: the failure is
        // recorded either way, just without the persona name if it is unknown.
        String who = (persona == null) ? "persona" : persona.name();
        Argument argument = new Argument(round, debate, persona,
                "No argument was produced. The " + who
                        + " call did not complete: " + reason
                        + ". This is recorded as a failure, not as a position.",
                null, model, promptVersion, null);
        argument.status = Status.FAILED;
        argument.failed = true;
        argument.failureReason = reason;
        return argument;
    }

    public Long getId() {
        return id;
    }

    public DebateRound getDebateRound() {
        return debateRound;
    }

    public Debate getDebate() {
        return debate;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public Persona getPersona() {
        return persona;
    }

    public String getArgumentText() {
        return argumentText;
    }

    public String getStance() {
        return stance;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isFailed() {
        return failed;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
