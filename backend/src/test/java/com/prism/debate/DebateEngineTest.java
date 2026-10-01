package com.prism.debate;

import com.prism.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebateEngineTest {

    // ---- legal transitions -------------------------------------------------

    @Test
    @DisplayName("CREATED + START opens round 1")
    void startOpensRoundOne() {
        DebateEngine.Decision d = DebateEngine.decide(DebateState.CREATED,
                DebateState.Event.START, 0, 3);
        assertThat(d.targetState()).isEqualTo(DebateState.ROUND_ACTIVE);
        assertThat(d.anotherRoundRequired()).isTrue();
        assertThat(d.nextRoundNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("ROUND_ACTIVE + ARGUMENTS_DONE waits for the chair")
    void argumentsDoneAwaitsChair() {
        DebateEngine.Decision d = DebateEngine.decide(DebateState.ROUND_ACTIVE,
                DebateState.Event.ARGUMENTS_DONE, 1, 3);
        assertThat(d.targetState()).isEqualTo(DebateState.AWAITING_CHAIR);
        assertThat(d.anotherRoundRequired()).isFalse();
    }

    @Test
    @DisplayName("AWAITING_CHAIR below the ceiling opens the next round")
    void weightsOpenNextRound() {
        DebateEngine.Decision d = DebateEngine.decide(DebateState.AWAITING_CHAIR,
                DebateState.Event.WEIGHTS_SUBMITTED, 1, 3);
        assertThat(d.targetState()).isEqualTo(DebateState.ROUND_ACTIVE);
        assertThat(d.nextRoundNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("AWAITING_CHAIR at the ceiling moves to synthesis")
    void ceilingMovesToSynthesis() {
        DebateEngine.Decision d = DebateEngine.decide(DebateState.AWAITING_CHAIR,
                DebateState.Event.WEIGHTS_SUBMITTED, 3, 3);
        assertThat(d.targetState()).isEqualTo(DebateState.SYNTHESIZING);
        assertThat(d.anotherRoundRequired()).isFalse();
    }

    @Test
    @DisplayName("SYNTHESIZING + SYNTHESIS_DONE completes")
    void synthesisCompletes() {
        DebateEngine.Decision d = DebateEngine.decide(DebateState.SYNTHESIZING,
                DebateState.Event.SYNTHESIS_DONE, 3, 3);
        assertThat(d.targetState()).isEqualTo(DebateState.COMPLETED);
    }

    // ---- illegal transitions ----------------------------------------------

    @Test
    @DisplayName("a debate cannot be started twice")
    void cannotStartTwice() {
        assertThatThrownBy(() -> DebateEngine.decide(DebateState.ROUND_ACTIVE,
                DebateState.Event.START, 1, 3))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot apply START");
    }

    @Test
    @DisplayName("arguments cannot be collected before the debate starts")
    void cannotCollectArgumentsBeforeStart() {
        assertThatThrownBy(() -> DebateEngine.decide(DebateState.CREATED,
                DebateState.Event.ARGUMENTS_DONE, 0, 3))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a terminal debate accepts no further events")
    void terminalStatesAreClosed() {
        for (DebateState terminal : new DebateState[]{DebateState.COMPLETED, DebateState.ABORTED}) {
            for (DebateState.Event event : DebateState.Event.values()) {
                assertThatThrownBy(() -> DebateEngine.decide(terminal, event, 3, 3))
                        .as("%s + %s must be rejected", terminal, event)
                        .isInstanceOf(ApiException.class);
            }
        }
    }

    @Test
    @DisplayName("synthesis cannot complete before synthesis starts")
    void cannotCompleteBeforeSynthesizing() {
        assertThatThrownBy(() -> DebateEngine.decide(DebateState.AWAITING_CHAIR,
                DebateState.Event.SYNTHESIS_DONE, 1, 3))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("ABORT is legal from any non-terminal state")
    void abortFromAnyLiveState() {
        for (DebateState live : new DebateState[]{DebateState.CREATED, DebateState.ROUND_ACTIVE,
                DebateState.AWAITING_CHAIR, DebateState.SYNTHESIZING}) {
            DebateEngine.Decision d = DebateEngine.decide(live, DebateState.Event.ABORT, 1, 3);
            assertThat(d.targetState()).isEqualTo(DebateState.ABORTED);
        }
    }

    // ---- ceiling enforcement ----------------------------------------------

    @Test
    @DisplayName("a round above the configured ceiling is rejected")
    void rejectsRoundAboveCeiling() {
        assertThatThrownBy(() -> DebateEngine.decide(DebateState.AWAITING_CHAIR,
                DebateState.Event.WEIGHTS_SUBMITTED, 4, 3))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exceeds the configured maximum");
    }

    @Test
    @DisplayName("a ceiling of zero is rejected")
    void rejectsZeroCeiling() {
        assertThatThrownBy(() -> DebateEngine.decide(DebateState.CREATED,
                DebateState.Event.START, 0, 0))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a full three-round debate reaches synthesis exactly at the ceiling")
    void fullDebatePath() {
        // The canonical path: CREATED -> ROUND_ACTIVE -> AWAITING_CHAIR -> ...
        assertThat(DebateEngine.decide(DebateState.CREATED, DebateState.Event.START, 0, 3)
                .targetState()).isEqualTo(DebateState.ROUND_ACTIVE);
        assertThat(DebateEngine.decide(DebateState.ROUND_ACTIVE, DebateState.Event.ARGUMENTS_DONE, 1, 3)
                .targetState()).isEqualTo(DebateState.AWAITING_CHAIR);
        assertThat(DebateEngine.decide(DebateState.AWAITING_CHAIR, DebateState.Event.WEIGHTS_SUBMITTED, 1, 3)
                .nextRoundNumber()).isEqualTo(2);
        assertThat(DebateEngine.decide(DebateState.AWAITING_CHAIR, DebateState.Event.WEIGHTS_SUBMITTED, 2, 3)
                .nextRoundNumber()).isEqualTo(3);
        DebateEngine.Decision last = DebateEngine.decide(DebateState.AWAITING_CHAIR,
                DebateState.Event.WEIGHTS_SUBMITTED, 3, 3);
        assertThat(last.targetState()).isEqualTo(DebateState.SYNTHESIZING);
        assertThat(DebateEngine.decide(DebateState.SYNTHESIZING, DebateState.Event.SYNTHESIS_DONE, 3, 3)
                .targetState()).isEqualTo(DebateState.COMPLETED);
    }

    // ---- weights -----------------------------------------------------------

    @Test
    @DisplayName("chair weights outside 1-5 are rejected")
    void validatesWeights() {
        assertThat(DebateEngine.validateWeight(1)).isEqualTo(1);
        assertThat(DebateEngine.validateWeight(5)).isEqualTo(5);
        assertThatThrownBy(() -> DebateEngine.validateWeight(0))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DebateEngine.validateWeight(6))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DebateEngine.validateWeight(null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("all three personas are required each round")
    void requiresThreePersonas() {
        assertThat(DebateEngine.requiredPersonas())
                .containsExactlyInAnyOrder(Persona.HAWK, Persona.DOVE, Persona.SKEPTIC);
    }
}
