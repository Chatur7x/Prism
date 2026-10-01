package com.prism.debate;

import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The debate state machine. Deterministic Java owns every transition.
 *
 * <p>The LLM has no write path to debate state at all. A model can produce
 * argument text; it cannot advance a round, weight an argument, or complete a
 * debate. That separation is the reason a debate's progress can be trusted.
 */
public enum DebateState {

    /** Created, not started. No arguments exist yet. */
    CREATED,
    /** A round is running; personas are producing arguments. */
    ROUND_ACTIVE,
    /** All arguments are in; waiting for the chair to weight them. */
    AWAITING_CHAIR,
    /** Synthesis is running. */
    SYNTHESIZING,
    /** Terminal: a report exists. */
    COMPLETED,
    /** Terminal: abandoned. */
    ABORTED;

    /** Events that may move the machine. */
    public enum Event {
        START,
        ARGUMENTS_DONE,
        WEIGHTS_SUBMITTED,
        SYNTHESIS_DONE,
        ABORT,
        /** Internal: another round is required before synthesis. */
        NEEDS_ANOTHER_ROUND
    }

    private static final Map<DebateState, Set<Event>> ALLOWED = Map.of(
            CREATED, EnumSet.of(Event.START, Event.ABORT),
            ROUND_ACTIVE, EnumSet.of(Event.ARGUMENTS_DONE, Event.ABORT),
            AWAITING_CHAIR, EnumSet.of(Event.WEIGHTS_SUBMITTED, Event.NEEDS_ANOTHER_ROUND, Event.ABORT),
            SYNTHESIZING, EnumSet.of(Event.SYNTHESIS_DONE, Event.ABORT),
            COMPLETED, EnumSet.noneOf(Event.class),
            ABORTED, EnumSet.noneOf(Event.class));

    public boolean canTransitionTo(Event event) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(event);
    }

    /** The state this event leads to, or null when the event is not legal here. */
    public DebateState next(Event event) {
        if (!canTransitionTo(event)) {
            return null;
        }
        return switch (event) {
            case START -> ROUND_ACTIVE;
            case ARGUMENTS_DONE -> AWAITING_CHAIR;
            // Which of these two applies depends on the round counter, which the
            // engine decides. Neither is a self-contained transition here.
            case WEIGHTS_SUBMITTED, NEEDS_ANOTHER_ROUND -> null;
            case SYNTHESIS_DONE -> COMPLETED;
            case ABORT -> ABORTED;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED;
    }

    /**
     * Validates a transition and throws a precise error when it is illegal.
     *
     * <p>Rejecting rather than clamping is deliberate: a caller that computes
     * the wrong next state has a bug, and silently accepting it would corrupt
     * the audit trail.
     */
    public DebateState requireTransitionTo(Event event) {
        if (!canTransitionTo(event)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "cannot apply " + event + " to a debate in state " + this
                            + "; allowed here: " + ALLOWED.getOrDefault(this, Set.of()));
        }
        DebateState target = next(event);
        if (target == null) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "event " + event + " requires an engine decision to pick the target state");
        }
        return target;
    }

    /** Whether arguments should be generated in this state. */
    public boolean expectsPersonaArguments() {
        return this == ROUND_ACTIVE;
    }
}
