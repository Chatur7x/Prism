package com.prism.synthesis;

import com.prism.corpus.Corpus;
import com.prism.debate.Debate;
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
 * The council's structured report.
 *
 * <p>One report per debate, enforced by a unique key, so a retried synthesis
 * cannot produce two conflicting completions. The report is structured data
 * ({@link ReportBlock} plus citations), not a markdown blob: every statement in
 * it can be clicked back to the evidence that supports it.
 */
@Entity
@Table(name = "synthesis_reports",
        indexes = {
                @Index(name = "ix_report_corpus", columnList = "corpus_id"),
                @Index(name = "ix_report_contradiction", columnList = "contradiction_id")
        })
public class SynthesisReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debate_id", nullable = false)
    private Debate debate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contradiction_id", nullable = false)
    private com.prism.contradiction.Contradiction contradiction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String conclusion;

    @Column(name = "model", length = 200)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "trace_run_id")
    private Long traceRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SynthesisReport() {
        // for JPA
    }

    public SynthesisReport(Debate debate, String conclusion, String model, String promptVersion,
                           Long durationMs, Long traceRunId) {
        this.debate = debate;
        this.contradiction = debate.getContradiction();
        this.corpus = debate.getCorpus();
        this.conclusion = conclusion;
        this.model = model;
        this.promptVersion = promptVersion;
        this.durationMs = durationMs;
        this.traceRunId = traceRunId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Debate getDebate() {
        return debate;
    }

    public com.prism.contradiction.Contradiction getContradiction() {
        return contradiction;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public String getConclusion() {
        return conclusion;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Long getTraceRunId() {
        return traceRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
