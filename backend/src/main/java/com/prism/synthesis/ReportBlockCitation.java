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
import jakarta.persistence.ManyToOne;
import com.prism.corpus.Corpus;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One provenance link from a report block to a source object.
 *
 * <p>Exactly one target column is populated, matching {@code citation_kind}.
 * The database enforces both the target-present and single-target rules.
 */
@Entity
@Table(name = "report_block_citations",
        indexes = {
                @Index(name = "ix_blockcite_block", columnList = "report_block_id"),
                @Index(name = "ix_blockcite_report", columnList = "report_id"),
                @Index(name = "ix_blockcite_chunk", columnList = "chunk_id"),
                @Index(name = "ix_blockcite_argument", columnList = "argument_id")
        })
public class ReportBlockCitation {

    public enum Kind {
        CHUNK,
        TRIPLE,
        CLAIM,
        VERDICT,
        ARGUMENT,
        MACHINE_FACT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_block_id", nullable = false)
    private ReportBlock block;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private SynthesisReport report;

    /**
     * The owning corpus, denormalised onto the citation.
     *
     * <p>Not merely a convenience: corpus_id is NOT NULL with no default, so
     * omitting this mapping means synthesis fails at the insert with "Field
     * 'corpus_id' doesn't have a default value". It went unnoticed because
     * ddl-auto: validate cannot see a column the entity never mentions --
     * validation compares the mapping to the schema, it does not compare the
     * schema to the mapping.
     *
     * <p>Having it on the citation also means a block's provenance can be
     * filtered by corpus without walking back through the report, which is what
     * the citation check needs to confirm every citation resolves inside the
     * corpus the report is about.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    // @Enumerated is required: without it JPA defaults to ORDINAL and maps the
    // enum to a tinyint, which the varchar column in the migration rejects.
    @Enumerated(EnumType.STRING)
    @Column(name = "citation_kind", nullable = false, length = 16)
    private Kind citationKind;

    @Column(name = "chunk_id")
    private Long chunkId;

    @Column(name = "triple_id")
    private Long tripleId;

    @Column(name = "claim_id")
    private Long claimId;

    @Column(name = "verdict_id")
    private Long verdictId;

    @Column(name = "argument_id")
    private Long argumentId;

    @Column(name = "machine_fact_id", length = 64)
    private String machineFactId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReportBlockCitation() {
        // for JPA
    }

    public ReportBlockCitation(ReportBlock block, SynthesisReport report, Kind kind,
                               Long chunkId, Long tripleId, Long claimId, Long verdictId,
                               Long argumentId, String machineFactId) {
        this.block = block;
        this.report = report;
        this.corpus = report.getCorpus();
        this.citationKind = kind;
        this.chunkId = chunkId;
        this.tripleId = tripleId;
        this.claimId = claimId;
        this.verdictId = verdictId;
        this.argumentId = argumentId;
        this.machineFactId = machineFactId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ReportBlock getBlock() {
        return block;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public SynthesisReport getReport() {
        return report;
    }

    public Kind getCitationKind() {
        return citationKind;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public Long getTripleId() {
        return tripleId;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Long getVerdictId() {
        return verdictId;
    }

    public Long getArgumentId() {
        return argumentId;
    }

    public String getMachineFactId() {
        return machineFactId;
    }
}
