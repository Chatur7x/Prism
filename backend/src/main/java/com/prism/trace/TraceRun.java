package com.prism.trace;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One observable execution of a PRISM operation.
 *
 * <p>A run is the replay unit. Replay reads the steps stored here and must not
 * reconstruct history from current database state, because the state may have
 * changed since the run executed.
 */
@Entity
@Table(name = "trace_runs")
public class TraceRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 40)
    private TraceOperationType operationType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corpus_id")
    private Corpus corpus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private com.prism.document.Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Caller-supplied correlation key, e.g. {@code verify:claim:42}. */
    @Column(name = "operation_key", length = 200)
    private String operationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TraceRunStatus status = TraceRunStatus.RUNNING;

    @Lob
    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /**
     * Highest step sequence issued for this run.
     *
     * <p>Not a derived value: {@code max(seq) + 1} over a unique index is a
     * read-then-increment, and a debate round writes steps from three threads at
     * once. Holding the counter on the run and incrementing it with a single
     * {@code UPDATE step_seq = step_seq + 1} makes the database, not the
     * application, the allocator, which removes the race entirely.
     */
    @Column(name = "step_seq", nullable = false)
    private long stepSeq;

    /**
     * Why the run ended, when it ended badly.
     *
     * <p>Previously accepted by {@link #complete} and silently discarded,
     * because no column existed. Every failure path in the system passes a
     * reason here, so a run could report FAILED with nothing to explain it.
     */
    @Column(name = "error_summary", length = 2000)
    private String errorSummary;

    protected TraceRun() {
        // for JPA
    }

    public TraceRun(TraceOperationType operationType, Corpus corpus, com.prism.document.Document document,
                    User user, String operationKey, String metadataJson) {
        this.operationType = operationType;
        this.corpus = corpus;
        this.document = document;
        this.user = user;
        this.operationKey = operationKey;
        this.metadataJson = metadataJson;
        this.status = TraceRunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public void complete(TraceRunStatus finalStatus, String errorSummary, Instant now) {
        this.status = finalStatus;
        this.finishedAt = now;
        this.durationMs = java.time.Duration.between(startedAt, now).toMillis();
        // Truncated to the column budget rather than left to fail on insert: a
        // long provider error string must not be the reason a run cannot be
        // recorded as failed. The full text still reaches the per-step row,
        // where error_message has the same 2000-char limit and is written
        // independently.
        this.errorSummary = errorSummary == null || errorSummary.length() <= 2000
                ? errorSummary
                : errorSummary.substring(0, 1997) + "...";
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public Long getId() {
        return id;
    }

    public TraceOperationType getOperationType() {
        return operationType;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    /**
     * Links this run to a corpus after creation.
     *
     * <p>Chat creates its trace before it knows the corpus, because resolving the
     * session performs no model call but the trace must exist first. Every trace
     * needs a corpus so the Glass Box can authorize reads of it: a run with no
     * corpus would be either invisible or readable by anyone, and neither is
     * acceptable in an auditable system.
     */
    public void attachCorpus(Corpus corpus) {
        if (this.corpus == null) {
            this.corpus = corpus;
        }
    }

    public com.prism.document.Document getDocument() {
        return document;
    }

    public User getUser() {
        return user;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public TraceRunStatus getStatus() {
        return status;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }
}
