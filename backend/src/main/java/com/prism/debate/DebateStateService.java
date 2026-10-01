package com.prism.debate;

import com.prism.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single conditional state update of a debate, in its own transaction.
 *
 * <p>{@code start} and {@code advance} in {@link DebateService} are deliberately
 * <em>not</em> transactional: both run the three personas, which means making
 * model calls, and holding a database transaction open across a network call
 * would pin a connection for the duration of someone else's latency. That is the
 * right call and it is not negotiable.
 *
 * <p>The consequence is that the one statement they must perform — the
 * conditional {@code UPDATE ... WHERE state = :expected} that makes a transition
 * legal exactly once — has no ambient transaction to run in. A
 * {@code @Modifying} query outside a transaction does not merely run slowly, it
 * throws
 * {@code No EntityManager with actual transaction available for current thread},
 * because there is no flush to participate in.
 *
 * <p>Marking {@code applyTransition} itself {@code @Transactional} would not fix
 * it: it is private, so it is self-invoked from within the same bean, and Spring's
 * transaction advice is proxy-based and never sees an internal call. The fix is a
 * separate bean, which is the same reason
 * {@code ProposalWriter}, {@code EntitySupportCounter}, {@code ChatPersistenceService},
 * {@code BackgroundJobService}, and {@code DocumentStatusService} each exist.
 *
 * <p>{@code REQUIRES_NEW} rather than the default: a transition that follows a
 * failed unit of work must still be able to commit its own outcome, and a
 * rollback-only transaction inherited from the caller would poison it.
 */
@Service
public class DebateStateService {

    private final DebateRepository debates;

    public DebateStateService(DebateRepository debates) {
        this.debates = debates;
    }

    /**
     * Applies a state transition if and only if the debate is still in the
     * expected state.
     *
     * <p>The guard is in the {@code WHERE} clause, not in a preceding read, so
     * the check and the write are one atomic statement. Two chairs pressing
     * "advance" at the same moment therefore produce exactly one transition and
     * one 409, which is the property that makes the machine safe to drive from a
     * browser.
     *
     * @return false when another writer already moved the debate on; the caller
     *         reports that as a conflict rather than retrying, because retrying
     *         would risk advancing a second round
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applyTransition(Long debateId, DebateState expected, DebateEngine.Decision decision) {
        return debates.transitionState(debateId, expected, decision.targetState(),
                decision.nextRoundNumber(), stampFor(decision.targetState())) == 1;
    }

    /**
     * The finish time to record with a transition, or null for a non-terminal
     * one.
     *
     * <p>Only {@code COMPLETED} and {@code ABORTED} are terminal; nothing
     * transitions out of either, which is what makes stamping on transition
     * sound. Enumerated as an explicit list rather than a negation of the enum,
     * so a state added to the FSM later is treated as non-terminal and simply
     * goes unstamped rather than being wrongly treated as finished.
     */
    private static java.time.Instant stampFor(DebateState target) {
        return (target == DebateState.COMPLETED || target == DebateState.ABORTED)
                ? java.time.Instant.now()
                : null;
    }

    /**
     * Re-reads a debate's state, round, and error message in a fresh
     * transaction, for callers that must observe a just-committed transition.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DebateStateService.Snapshot readState(Long debateId) {
        Debate debate = debates.findById(debateId)
                .orElseThrow(() -> ApiException.notFound("Debate", debateId));
        return new Snapshot(debate.getState(), debate.getCurrentRound(), debate.getLastError());
    }

    /** The minimal, transaction-safe view of a debate's mutable progress. */
    public record Snapshot(DebateState state, int currentRound, String lastError) {
    }
}
