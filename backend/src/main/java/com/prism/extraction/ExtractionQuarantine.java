package com.prism.extraction;

import com.prism.document.Document;
import com.prism.document.DocumentChunk;
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
 * A model response that was rejected, kept with enough detail to audit why.
 *
 * <p>Quarantine is deliberately not silent failure. A model that produces
 * malformed JSON on 30% of chunks is a fact about the system that an operator
 * needs; discarding the response would hide it.
 */
@Entity
@Table(name = "extraction_quarantine",
        indexes = {
                @Index(name = "ix_quarantine_run", columnList = "extraction_run_id"),
                @Index(name = "ix_quarantine_document", columnList = "document_id"),
                @Index(name = "ix_quarantine_corpus", columnList = "corpus_id"),
                @Index(name = "ix_quarantine_chunk", columnList = "chunk_id"),
                @Index(name = "ix_quarantine_error_type", columnList = "error_type")
        })
public class ExtractionQuarantine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "extraction_run_id", nullable = false)
    private ExtractionRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private com.prism.corpus.Corpus corpus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_id")
    private DocumentChunk chunk;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(name = "model", length = 200)
    private String model;

    /** The verbatim provider response, retained for audit. Never fed back into a prompt. */
    @Lob
    @Column(name = "raw_response", columnDefinition = "LONGTEXT")
    private String rawResponse;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", nullable = false, length = 64)
    private QuarantineReason errorType;

    @Column(name = "validation_message", length = 2000)
    private String validationMessage;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ExtractionQuarantine() {
        // for JPA
    }

    public ExtractionQuarantine(ExtractionRun run, Document document, DocumentChunk chunk,
                                String promptVersion, String model, String rawResponse,
                                QuarantineReason errorType, String validationMessage, int attempt) {
        this.run = run;
        this.document = document;
        this.corpus = document.getCorpus();
        this.chunk = chunk;
        this.chunkIndex = chunk == null ? null : chunk.getChunkIndex();
        this.promptVersion = promptVersion;
        this.model = model;
        this.rawResponse = truncateForStorage(rawResponse);
        this.errorType = errorType;
        this.validationMessage = truncate(validationMessage, 1900);
        this.attempt = attempt;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ExtractionRun getRun() {
        return run;
    }

    public Document getDocument() {
        return document;
    }

    public com.prism.corpus.Corpus getCorpus() {
        return corpus;
    }

    public DocumentChunk getChunk() {
        return chunk;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getModel() {
        return model;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public QuarantineReason getErrorType() {
        return errorType;
    }

    public String getValidationMessage() {
        return validationMessage;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Caps stored response size. A runaway model generating megabytes would
     * otherwise be able to exhaust the database.
     */
    private static String truncateForStorage(String value) {
        final int max = 100_000;
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n…[truncated]";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
