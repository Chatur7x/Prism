package com.prism.synthesis;

import com.prism.debate.DebateState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The wire representation of a synthesis report.
 *
 * <p>A typed record rather than the {@code Map<String, Object>} this endpoint
 * used to build by hand. Two reasons, and the first is the one that mattered in
 * practice: an untyped map appears in the OpenAPI schema as a bare
 * {@code object}, which tells a client nothing about which keys exist, and the
 * hand-built map had silently grown a gap — it published only {@code kind},
 * {@code argumentId}, {@code machineFactId}, and {@code chunkId}, omitting
 * {@code tripleId}, {@code claimId}, and {@code verdictId}. A citation into
 * those three kinds therefore came back with every field null, and a client
 * enforcing "every citation must resolve" had no way to tell a valid citation
 * from an empty one. The gap was invisible precisely because the contract was
 * invisible.
 *
 * <p>The second reason is that this makes the citation contract checkable in one
 * place. {@link #citationNamesATarget} states the invariant the column constraint
 * already implies, in Java, where a test can assert it.
 */
public final class SynthesisReportResponse {

    private SynthesisReportResponse() {
        // static factory and records only
    }

    /**
     * One citation attached to a block.
     *
     * <p>Exactly one of the six target fields is non-null; {@code kind} says
     * which. A citation naming two targets, or none, is malformed — hence
     * {@link #citationNamesATarget}.
     */
    public record CitationResponse(Long id, String kind, Long chunkId, Long tripleId,
                                   Long claimId, Long verdictId, Long argumentId,
                                   String machineFactId) {
    }

    public record BlockResponse(Long id, int sequenceNo, String blockType, String text,
                                List<CitationResponse> citations) {
    }

    public record ReportResponse(Long reportId, Long debateId, Long corpusId,
                                 DebateState debateState, String conclusion,
                                 String model, String promptVersion, Long durationMs,
                                 Long traceRunId, Instant createdAt,
                                 int blockCount, int citationCount,
                                 List<BlockResponse> blocks) {
    }

    /**
     * Whether a citation names exactly one resolvable target.
     *
     * <p>Exactly one, not at least one. A citation pointing at two sources is not
     * a stronger citation, it is an unattributed one: a reader cannot tell which
     * of the two supports the sentence, and the report's provenance graph becomes
     * ambiguous in a way that no later step can repair. The database enforces
     * {@code CHECK} on the kind but not on the mutual exclusivity of the target
     * columns, so this is checked here instead.
     */
    public static boolean citationNamesATarget(CitationResponse c) {
        int named = 0;
        if (c.chunkId() != null) named++;
        if (c.tripleId() != null) named++;
        if (c.claimId() != null) named++;
        if (c.verdictId() != null) named++;
        if (c.argumentId() != null) named++;
        if (c.machineFactId() != null && !c.machineFactId().isBlank()) named++;
        return named == 1;
    }

    /**
     * Assembles a report from its stored parts.
     *
     * <p>Reads only plain values already loaded. Callers must have fetched the
     * blocks and citations; nothing here dereferences a lazy association, so it
     * is safe to call after the transaction that loaded them has closed.
     *
     * @param citationsByBlock block id to its citations
     */
    public static ReportResponse assemble(SynthesisReport report,
                                          List<ReportBlock> blocks,
                                          Map<Long, List<ReportBlockCitation>> citationsByBlock) {
        List<BlockResponse> blockViews = new java.util.ArrayList<>(blocks.size());
        int citationCount = 0;
        for (ReportBlock block : blocks) {
            List<ReportBlockCitation> citations =
                    citationsByBlock.getOrDefault(block.getId(), List.of());
            citationCount += citations.size();
            blockViews.add(new BlockResponse(
                    block.getId(),
                    block.getSequenceNo(),
                    block.getBlockType().name(),
                    block.getText(),
                    citations.stream().map(SynthesisReportResponse::citation).toList()));
        }
        return new ReportResponse(
                report.getId(),
                report.getDebate().getId(),
                report.getCorpus().getId(),
                report.getDebate().getState(),
                report.getConclusion(),
                report.getModel(),
                report.getPromptVersion(),
                report.getDurationMs(),
                report.getTraceRunId(),
                report.getCreatedAt(),
                blockViews.size(),
                citationCount,
                blockViews);
    }

    public static CitationResponse citation(ReportBlockCitation c) {
        return new CitationResponse(c.getId(), c.getCitationKind().name(),
                c.getChunkId(), c.getTripleId(), c.getClaimId(), c.getVerdictId(),
                c.getArgumentId(), c.getMachineFactId());
    }
}