package com.prism.pipeline;

import com.prism.document.Document;
import com.prism.document.DocumentChunk;
import com.prism.document.DocumentStatus;
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
 * Durable bookkeeping for an asynchronous job.
 *
 * <p>Exists so a process restart can find work that was in flight when the JVM
 * went down. Without it, a crash mid-extraction leaves a document stuck in
 * EXTRACTING with nobody aware, and the operator has no way to see that the
 * system is silently broken.
 *
 * <p>{@code jobKey} is the idempotency key. Re-enqueueing the same logical work
 * updates the existing row rather than creating a duplicate, so a retried
 * request cannot run the same extraction twice and create duplicate proposals.
 */
@Entity
@Table(name = "background_jobs",
        indexes = {
                @Index(name = "idx_job_status_heartbeat", columnList = "status,heartbeat_at"),
                @Index(name = "idx_job_type", columnList = "job_type"),
                @Index(name = "idx_job_corpus", columnList = "corpus_id")
        })
public class BackgroundJob {

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        /** Exceeded max attempts; needs human intervention. */
        ABANDONED
    }

    public enum Type {
        EXTRACTION,
        VERIFICATION,
        SYNTHESIS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Idempotency key, unique across all jobs. */
    @Column(name = "job_key", nullable = false, length = 200)
    private String jobKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 40)
    private Type jobType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corpus_id")
    private com.prism.corpus.Corpus corpus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Lob
    @Column(name = "payload_json", columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected BackgroundJob() {
        // for JPA
    }

    public BackgroundJob(String jobKey, Type jobType, com.prism.corpus.Corpus corpus,
                         Document document, String payloadJson, int maxAttempts) {
        this.jobKey = jobKey;
        this.jobType = jobType;
        this.corpus = corpus;
        this.document = document;
        this.payloadJson = payloadJson;
        this.maxAttempts = maxAttempts;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Claims the job for execution. Returns false when already claimed. */
    public boolean markRunning(Instant now) {
        if (status != Status.PENDING) {
            return false;
        }
        this.status = Status.RUNNING;
        this.attemptCount++;
        this.startedAt = now;
        this.heartbeatAt = now;
        this.updatedAt = now;
        return true;
    }

    public void heartbeat(Instant now) {
        this.heartbeatAt = now;
        this.updatedAt = now;
    }

    public void markSucceeded(Instant now) {
        this.status = Status.SUCCEEDED;
        this.finishedAt = now;
        this.heartbeatAt = now;
        this.lastError = null;
        this.updatedAt = now;
    }

    /**
     * Records a failure. The job returns to PENDING for another attempt until
     * the ceiling is reached, after which it is ABANDONED and needs a human.
     */
    public void markFailed(String error, Instant now) {
        this.lastError = truncate(error);
        this.updatedAt = now;
        this.heartbeatAt = now;
        if (attemptCount >= maxAttempts) {
            this.status = Status.ABANDONED;
            this.finishedAt = now;
        } else {
            this.status = Status.PENDING;
        }
    }

    /**
     * Resets a job to PENDING after a restart, in place.
     *
     * <p>The row is reused rather than replaced so the attempt history and the
     * job key stay continuous: a crash loop is still bounded by
     * {@code maxAttempts}, and the operator's job list does not grow a duplicate
     * orphan on every restart.
     */
    public void requeueAfterRestart() {
        this.status = Status.PENDING;
        this.finishedAt = null;
        this.heartbeatAt = null;
        this.lastError = "requeued after an application restart";
        this.updatedAt = Instant.now();
    }

    /** Whether a restart should requeue this job. */
    public boolean isStale(Instant cutoff) {
        if (status != Status.RUNNING) {
            return false;
        }
        return heartbeatAt == null || heartbeatAt.isBefore(cutoff);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1900 ? value : value.substring(0, 1900) + "…";
    }

    public Long getId() {
        return id;
    }

    public String getJobKey() {
        return jobKey;
    }

    public Type getJobType() {
        return jobType;
    }

    public com.prism.corpus.Corpus getCorpus() {
        return corpus;
    }

    public Document getDocument() {
        return document;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
