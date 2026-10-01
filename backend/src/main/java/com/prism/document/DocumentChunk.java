package com.prism.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A citation-addressable slice of a document.
 *
 * <p>Character offsets are retained so any verdict, argument, or chat citation
 * can point back to exactly the text that justified it. Losing offsets would
 * break reproducibility of evidence.
 */
@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    @Column(name = "token_estimate", nullable = false)
    private int tokenEstimate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DocumentChunk() {
        // for JPA
    }

    public DocumentChunk(Document document, int chunkIndex, String content,
                         int startOffset, int endOffset, int tokenEstimate) {
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.tokenEstimate = tokenEstimate;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public int getTokenEstimate() {
        return tokenEstimate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
