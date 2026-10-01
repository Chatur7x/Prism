package com.prism.debate;

import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * The debate rules engine.
 *
 * <p>Pure and deterministic: given a state, a round number, and a max, decide
 * what happens next. No persistence, no clock, no model. Every transition in
 * {@link DebateState} is routed through {@link #decide}, so the legality of
 * every state change is testable without a database.
 */
public final class DebateEngine {

    public static final int DEFAULT_MAX_ROUNDS = 3;
    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 5;

    private DebateEngine() {
    }

    /** What the engine decided should happen next. */
    public record Decision(DebateState targetState, boolean anotherRoundRequired, int nextRoundNumber,
                           String reason) {
    }

    /**
     * Decides the outcome of applying an event.
     *
     * @param current    the debate's current state
     * @param event      the event being applied
     * @param currentRound the round just completed, 0 when none has
     * @param maxRounds  the configured round ceiling
     * @throws ApiException 409 when the event is illegal in this state, or when
     *                      the round ceiling would be exceeded
     */
    public static Decision decide(DebateState current, DebateState.Event event,
                                  int currentRound, int maxRounds) {
        validateRounds(currentRound, maxRounds);
        if (!current.canTransitionTo(event)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "cannot apply " + event + " while the debate is " + current);
        }

        return switch (event) {
            case START ->
                    new Decision(DebateState.ROUND_ACTIVE, true, 1,
                            "debate started; round 1 is now active");

            case ARGUMENTS_DONE ->
                    new Decision(DebateState.AWAITING_CHAIR, false, currentRound,
                            "all persona arguments are in; awaiting chair weights");

            case WEIGHTS_SUBMITTED, NEEDS_ANOTHER_ROUND -> {
                if (currentRound < maxRounds) {
                    int nextRound = currentRound + 1;
                    yield new Decision(DebateState.ROUND_ACTIVE, true, nextRound,
                            "round " + currentRound + " is weighted; starting round " + nextRound
                                    + " of " + maxRounds);
                }
                yield new Decision(DebateState.SYNTHESIZING, false, currentRound,
                        "round ceiling of " + maxRounds + " reached; moving to synthesis");
            }

            case SYNTHESIS_DONE ->
                    new Decision(DebateState.COMPLETED, false, currentRound,
                            "synthesis is complete; the contradiction is resolved by the report");

            case ABORT ->
                    new Decision(DebateState.ABORTED, false, currentRound,
                            "debate aborted by the chair");
        };
    }

    private static void validateRounds(int currentRound, int maxRounds) {
        if (maxRounds < 1) {
            throw ApiException.validation("maxRounds must be at least 1");
        }
        if (currentRound < 0) {
            throw ApiException.validation("currentRound must not be negative");
        }
        if (currentRound > maxRounds) {
            throw ApiException.stateConflict(
                    "round " + currentRound + " exceeds the configured maximum of " + maxRounds);
        }
    }

    /**
     * Validates a human chair weight.
     *
     * <p>Ranged strictly, because a weight of 0 would silently drop an argument
     * and a weight of 10 would let one voice dominate the synthesis.
     */
    public static int validateWeight(Integer weight) {
        if (weight == null) {
            throw ApiException.validation("weight is required");
        }
        if (weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
            throw ApiException.validation("weight must be between " + MIN_WEIGHT + " and " + MAX_WEIGHT);
        }
        return weight;
    }

    /** Personas that must produce an argument each round. */
    public static Set<Persona> requiredPersonas() {
        return Set.of(Persona.HAWK, Persona.DOVE, Persona.SKEPTIC);
    }

    /** State names for API responses and the frontend FSM display. */
    public static Map<String, String> describeStates() {
        return Map.of(
                DebateState.CREATED.name(), "Created but not started",
                DebateState.ROUND_ACTIVE.name(), "Personas are arguing this round",
                DebateState.AWAITING_CHAIR.name(), "Waiting for the chair to weight arguments",
                DebateState.SYNTHESIZING.name(), "Generating the synthesis report",
                DebateState.COMPLETED.name(), "Finished; a report is available",
                DebateState.ABORTED.name(), "Abandoned");
    }
}
