package com.prism.extraction;

import com.prism.document.Document;
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
 * One attempt to extract knowledge from one document.
 *
 * <p>Carries its own recovery state ({@code attemptCount}, {@code heartbeatAt},
 * {@code lastError}) so a process restart can identify and requeue work that was
 * in flight when the JVM went down. Without those columns a crash silently
 * strands a document in EXTRACTING forever.
 */
@Entity
@Table(name = "extraction_runs",
        indexes = {
                @Index(name = "ix_extraction_document", columnList = "document_id"),
                @Index(name = "ix_extraction_status", columnList = "status"),
                @Index(name = "ix_extraction_corpus", columnList = "corpus_id"),
                @Index(name = "ix_extraction_status_heartbeat", columnList = "status,heartbeat_at")
        })
public class ExtractionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private com.prism.corpus.Corpus corpus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExtractionStatus status = ExtractionStatus.PENDING;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "processed_chunks", nullable = false)
    private int processedChunks;

    @Column(name = "triples_found", nullable = false)
    private int triplesFound;

    @Column(name = "claims_found", nullable = false)
    private int claimsFound;

    @Column(name = "quarantined_count", nullable = false)
    private int quarantinedCount;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(name = "model", length = 200)
    private String model;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ExtractionRun() {
        // for JPA
    }

    public ExtractionRun(Document document, int totalChunks, String promptVersion, String model) {
        this.document = document;
        this.corpus = document.getCorpus();
        this.totalChunks = totalChunks;
        this.promptVersion = promptVersion;
        this.model = model;
        this.status = ExtractionStatus.PENDING;
        this.startedAt = Instant.now();
    }

    public void markRunning(Instant now) {
        this.status = ExtractionStatus.RUNNING;
        this.attemptCount++;
        this.heartbeatAt = now;
        this.startedAt = now;
    }

    public void heartbeat(Instant now) {
        this.heartbeatAt = now;
    }

    public void recordProgress(int triples, int claims, int quarantined, Instant now) {
        this.triplesFound += triples;
        this.claimsFound += claims;
        this.quarantinedCount += quarantined;
        this.heartbeatAt = now;
    }

    public void markSucceeded(Instant now) {
        this.status = ExtractionStatus.SUCCEEDED;
        this.processedChunks = this.totalChunks;
        this.finishedAt = now;
        this.heartbeatAt = now;
        this.lastError = null;
    }

    public void markFailed(String error, Instant now) {
        this.status = ExtractionStatus.FAILED;
        this.finishedAt = now;
        this.lastError = error == null ? null
                : (error.length() > 1900 ? error.substring(0, 1900) : error);
    }

    public boolean isStale(Instant now, int staleAfterMinutes) {
        if (status != ExtractionStatus.RUNNING) {
            return false;
        }
        if (heartbeatAt == null) {
            return true;
        }
        return heartbeatAt.isBefore(now.minusSeconds(staleAfterMinutes * 60L));
    }

    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public com.prism.corpus.Corpus getCorpus() {
        return corpus;
    }

    public ExtractionStatus getStatus() {
        return status;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public int getProcessedChunks() {
        return processedChunks;
    }

    public void setProcessedChunks(int processedChunks) {
        this.processedChunks = processedChunks;
    }

    public int getTriplesFound() {
        return triplesFound;
    }

    public int getClaimsFound() {
        return claimsFound;
    }

    public int getQuarantinedCount() {
        return quarantinedCount;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getAttemptCount() {
        return attemptCount;
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

    public long getVersion() {
        return version;
    }
}
