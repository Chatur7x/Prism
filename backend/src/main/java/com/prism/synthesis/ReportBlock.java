package com.prism.synthesis;

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

/** One ordered, individually cited statement in a synthesis report. */
@Entity
@Table(name = "report_blocks",
        indexes = {
                @Index(name = "ix_block_report", columnList = "report_id"),
                @Index(name = "ix_block_debate", columnList = "debate_id"),
                @Index(name = "ix_block_corpus", columnList = "corpus_id")
        })
public class ReportBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private SynthesisReport report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debate_id", nullable = false)
    private com.prism.debate.Debate debate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private com.prism.corpus.Corpus corpus;

    /** Position in the report. Unique per report, so ordering is total. */
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 32)
    private BlockType blockType;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReportBlock() {
        // for JPA
    }

    public ReportBlock(SynthesisReport report, com.prism.debate.Debate debate, int sequenceNo,
                       BlockType blockType, String text) {
        this.report = report;
        this.debate = debate;
        this.corpus = report.getCorpus();
        this.sequenceNo = sequenceNo;
        this.blockType = blockType;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public BlockType getBlockType() {
        return blockType;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
