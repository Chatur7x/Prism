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

/**
 * One citation attached to an argument.
 *
 * <p><b>Note on the primary key.</b> This table has a surrogate {@code id}
 * primary key. The tempting alternative — a composite key over a nullable
 * {@code triple_id} — is not usable: a nullable primary key column cannot
 * express "exactly one of these four targets is set", and MySQL silently
 * degrades such a key to the first column alone. Uniqueness is instead
 * enforced by four partial-effect unique indexes, and the invariant that
 * exactly one target is populated is enforced by the
 * {@code ck_citation_*} check constraints in the schema.
 */
@Entity
@Table(name = "argument_citations",
        indexes = {
                @Index(name = "ix_citation_argument", columnList = "argument_id"),
                @Index(name = "ix_citation_chunk", columnList = "chunk_id"),
                @Index(name = "ix_citation_triple", columnList = "triple_id"),
                @Index(name = "ix_citation_claim", columnList = "claim_id"),
                @Index(name = "ix_citation_debate", columnList = "debate_id")
        })
public class ArgumentCitation {

    /** What kind of source this citation points at. */
    public enum Kind {
        CHUNK,
        TRIPLE,
        CLAIM,
        MACHINE_FACT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "argument_id", nullable = false)
    private Argument argument;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debate_id", nullable = false)
    private Debate debate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    // @Enumerated is required: without it JPA defaults to ORDINAL and maps the
    // enum to a tinyint, which the varchar column in the migration rejects.
    @Enumerated(EnumType.STRING)
    @Column(name = "citation_kind", nullable = false, length = 16)
    private Kind citationKind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_id")
    private com.prism.document.DocumentChunk chunk;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triple_id")
    private com.prism.knowledge.Triple triple;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id")
    private com.prism.claims.Claim claim;

    /** Identifier of a machine fact in the Skeptic brief, e.g. {@code PR-12}. */
    @Column(name = "machine_fact_id", length = 64)
    private String machineFactId;

    /** The text the citation pointed at, for display without a join. */
    @Lob
    @Column(name = "excerpt", columnDefinition = "TEXT")
    private String excerpt;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt = java.time.Instant.now();

    protected ArgumentCitation() {
        // for JPA
    }

    private ArgumentCitation(Argument argument, Debate debate, Corpus corpus, Kind kind,
                             com.prism.document.DocumentChunk chunk, com.prism.knowledge.Triple triple,
                             com.prism.claims.Claim claim, String machineFactId, String excerpt) {
        this.argument = argument;
        this.debate = debate;
        this.corpus = corpus;
        this.citationKind = kind;
        this.chunk = chunk;
        this.triple = triple;
        this.claim = claim;
        this.machineFactId = machineFactId;
        this.excerpt = excerpt;
        this.createdAt = java.time.Instant.now();
    }

    public static ArgumentCitation toChunk(Argument argument, Debate debate, Corpus corpus,
                                           com.prism.document.DocumentChunk chunk, String excerpt) {
        return new ArgumentCitation(argument, debate, corpus, Kind.CHUNK, chunk, null, null, null, excerpt);
    }

    public static ArgumentCitation toTriple(Argument argument, Debate debate, Corpus corpus,
                                            com.prism.knowledge.Triple triple, String excerpt) {
        return new ArgumentCitation(argument, debate, corpus, Kind.TRIPLE, null, triple, null, null, excerpt);
    }

    public static ArgumentCitation toClaim(Argument argument, Debate debate, Corpus corpus,
                                           com.prism.claims.Claim claim, String excerpt) {
        return new ArgumentCitation(argument, debate, corpus, Kind.CLAIM, null, null, claim, null, excerpt);
    }

    public static ArgumentCitation toMachineFact(Argument argument, Debate debate, Corpus corpus,
                                                 String machineFactId, String excerpt) {
        return new ArgumentCitation(argument, debate, corpus, Kind.MACHINE_FACT, null, null, null,
                machineFactId, excerpt);
    }

    public Long getId() {
        return id;
    }

    public Argument getArgument() {
        return argument;
    }

    public Kind getCitationKind() {
        return citationKind;
    }

    public Long getChunkId() {
        return chunk == null ? null : chunk.getId();
    }

    public Long getTripleId() {
        return triple == null ? null : triple.getId();
    }

    public Long getClaimId() {
        return claim == null ? null : claim.getId();
    }

    public String getMachineFactId() {
        return machineFactId;
    }

    public String getExcerpt() {
        return excerpt;
    }
}
