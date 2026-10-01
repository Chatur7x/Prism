package com.prism.synthesis;

import com.prism.common.error.ApiException;
import com.prism.contradiction.Contradiction;
import com.prism.contradiction.ContradictionRepository;
import com.prism.debate.Debate;
import com.prism.debate.DebateEngine;
import com.prism.debate.DebateRepository;
import com.prism.debate.DebateState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Commits a synthesis report and closes out the debate it resolves.
 *
 * <p><b>Separate bean, one transaction, and both facts are load-bearing.</b>
 * These writes lived on {@code SynthesisService}, which is deliberately
 * non-transactional because it makes model calls and must never hold a connection
 * open across one. Two consequences followed. The writes ran without a
 * transaction, so a {@code @Modifying} state transition threw
 * {@code No EntityManager with actual transaction available for current thread}
 * and synthesis failed outright; and even had it not, the report, its blocks, its
 * citations, the debate's completion, and the contradiction's resolution would
 * each have committed separately. A report whose citations were lost, on a debate
 * still marked SYNTHESIZING, on a contradiction still OPEN, is precisely the
 * state an audit system must never be able to reach.
 *
 * <p>Calling from another bean is also what makes the transaction apply at all.
 * Spring's transaction advice is proxy-based, so a {@code @Transactional} method
 * invoked on {@code this} silently runs without one. See
 * {@code TraceStepWriter}, {@code ArgumentPersistenceService},
 * {@code DebateStateService} for the same rule applied elsewhere.
 *
 * <p>{@code REQUIRES_NEW} so the report commits even though the caller may have
 * inherited a rollback-only transaction from a failed attempt.
 */
@Service
public class SynthesisPersistenceService {

    private final SynthesisReportRepository reports;
    private final ReportBlockRepository blocks;
    private final ReportBlockCitationRepository blockCitations;
    private final DebateRepository debates;
    private final ContradictionRepository contradictions;

    public SynthesisPersistenceService(SynthesisReportRepository reports,
                                       ReportBlockRepository blocks,
                                       ReportBlockCitationRepository blockCitations,
                                       DebateRepository debates,
                                       ContradictionRepository contradictions) {
        this.reports = reports;
        this.blocks = blocks;
        this.blockCitations = blockCitations;
        this.debates = debates;
        this.contradictions = contradictions;
    }

    /** What was written, for the caller's event publication and trace summary. */
    public record Persisted(SynthesisReport report, int blockCount, int citationCount) {
    }

    /**
     * Writes the report, its blocks, and their citations, then completes the
     * debate and resolves the contradiction — all or nothing.
     *
     * <p>The state transition is executed as a guarded {@code UPDATE} inside this
     * transaction rather than through {@code DebateStateService}, whose
     * {@code REQUIRES_NEW} would suspend and commit independently. Same guard,
     * same single-writer guarantee; the difference is that here it cannot commit
     * a COMPLETED debate for a report that then fails to insert. If the guard
     * matches nothing the whole transaction is rolled back and the caller is told
     * the debate moved on underneath it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Persisted persist(Debate debate, ParsedSynthesis parsed, String model,
                             String promptVersion, Long durationMs, Long traceRunId,
                             Set<Long> allowedArgumentIds, Set<String> allowedFactIds) {

        SynthesisReport report = reports.save(new SynthesisReport(debate, parsed.conclusion(),
                model, promptVersion, durationMs, traceRunId));

        int blockCount = 0;
        int citationCount = 0;
        int sequenceNo = 0;
        for (ParsedBlock block : parsed.blocks()) {
            ReportBlock savedBlock = blocks.save(
                    new ReportBlock(report, debate, sequenceNo++, block.type(), block.text()));
            blockCount++;
            citationCount += writeCitations(savedBlock, report, block,
                    allowedArgumentIds, allowedFactIds);
        }

        DebateEngine.Decision done = DebateEngine.decide(
                DebateState.SYNTHESIZING, DebateState.Event.SYNTHESIS_DONE,
                debate.getCurrentRound(), debate.getMaxRounds());
        int moved = debates.transitionState(debate.getId(), DebateState.SYNTHESIZING,
                done.targetState(), done.nextRoundNumber(), Instant.now());
        if (moved != 1) {
            // Rolled back by throwing. Committing the report here would leave a
            // finished report attached to a debate that is not COMPLETED, which
            // is the exact orphan this method exists to make impossible.
            throw ApiException.stateConflict("debate " + debate.getId()
                    + " was no longer SYNTHESIZING when the report was ready to commit");
        }

        contradictions.findById(debate.getContradiction().getId()).ifPresent(c -> {
            c.markResolved(Instant.now());
            contradictions.save(c);
        });

        return new Persisted(report, blockCount, citationCount);
    }

    /**
     * Writes one block's citations, dropping any id not in the allowed set.
     *
     * <p>Re-checked here even though parsing already rejected hallucinated ids.
     * Parsing is the model-facing guard and the allowed set it checked is the one
     * handed in by the caller; re-checking binds the write to the same set that
     * was actually offered to the model, so a caller that supplied a wider set
     * cannot widen what ends up in the provenance graph.
     */
    private int writeCitations(ReportBlock block, SynthesisReport report, ParsedBlock parsed,
                               Set<Long> allowedArgumentIds, Set<String> allowedFactIds) {
        int written = 0;
        for (Long argumentId : parsed.argumentIds()) {
            if (allowedArgumentIds.contains(argumentId)) {
                blockCitations.save(new ReportBlockCitation(block, report,
                        ReportBlockCitation.Kind.ARGUMENT,
                        null, null, null, null, argumentId, null));
                written++;
            }
        }
        for (String factId : parsed.factIds()) {
            if (allowedFactIds.contains(factId)) {
                blockCitations.save(new ReportBlockCitation(block, report,
                        ReportBlockCitation.Kind.MACHINE_FACT,
                        null, null, null, null, null, factId));
                written++;
            }
        }
        return written;
    }
}
