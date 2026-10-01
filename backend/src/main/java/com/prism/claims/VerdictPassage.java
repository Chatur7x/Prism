package com.prism.claims;

import com.prism.corpus.Corpus;
import com.prism.document.Document;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One retrieved passage that was offered to the judge for a verdict.
 *
 * <p>{@code chunkText} is denormalised on purpose. The Glass Box must show the
 * exact text the judge saw at judgment time. Re-reading the chunk later would
 * show whatever the chunk says <em>now</em>, which silently changes the evidence
 * a historical verdict appears to rest on if the document is ever re-ingested.
 */
@Entity
@Table(name = "verdict_passages",
        indexes = {
                @Index(name = "ix_verdict_passage_verdict", columnList = "verdict_id"),
                @Index(name = "ix_verdict_passage_chunk", columnList = "chunk_id"),
                @Index(name = "ix_verdict_passage_corpus", columnList = "corpus_id")
        })
public class VerdictPassage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verdict_id", nullable = false)
    private Verdict verdict;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chunk_id", nullable = false)
    private com.prism.document.DocumentChunk chunk;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Lob
    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    @Column(name = "retrieval_rank", nullable = false)
    private int retrievalRank;

    @Column(name = "retrieval_score", precision = 10, scale = 4)
    private BigDecimal retrievalScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected VerdictPassage() {
        // for JPA
    }

    public VerdictPassage(Verdict verdict, Corpus corpus, com.prism.document.DocumentChunk chunk,
                          Document document, String chunkText, int retrievalRank, Double retrievalScore) {
        this.verdict = verdict;
        this.corpus = corpus;
        this.chunk = chunk;
        this.document = document;
        this.chunkText = chunkText;
        this.retrievalRank = retrievalRank;
        this.retrievalScore = retrievalScore == null ? null
                : BigDecimal.valueOf(Math.round(retrievalScore * 10000.0) / 10000.0);
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public Long getChunkId() {
        return chunk.getId();
    }

    public Long getDocumentId() {
        return document.getId();
    }

    public String getDocumentTitle() {
        return document.getTitle();
    }

    public String getChunkText() {
        return chunkText;
    }

    public int getRetrievalRank() {
        return retrievalRank;
    }

    public BigDecimal getRetrievalScore() {
        return retrievalScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
