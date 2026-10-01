package com.prism.debate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the wire representation of a debate.
 *
 * <p>Shared rather than living in {@code DebateController} because two
 * controllers return a debate: starting, advancing, and aborting live in the
 * debate controller, while <em>convening</em> lives in the contradiction
 * controller — convening is an action on a contradiction, and routing it there
 * keeps that grouping. Convening used to return the raw {@link Debate} entity
 * while everything else returned this record, so its response had
 * {@code chairedBy} as a nested object, no {@code rounds}, and no
 * {@code stateDescription}. A client could read an id from one and a topic from
 * another only by handling both shapes.
 *
 * <p>Assembling in one place is also what makes the alternative impossible to
 * reintroduce by accident: there is no second mapper for a controller to use by
 * mistake.
 */
public final class DebateResponseAssembler {

    private DebateResponseAssembler() {
        // static utility
    }

    /** One validated citation on an argument. */
    public record ArgumentCitationResponse(Long id, String kind, Long chunkId, Long tripleId,
                                           Long claimId, String machineFactId, String excerpt) {
    }

    public record ArgumentResponse(Long id, int round, String persona, String argumentText,
                                   String stance, String model, String promptVersion,
                                   boolean failed, String failureReason, Long durationMs,
                                   Instant createdAt, Integer chairWeight, String weightedBy,
                                   List<ArgumentCitationResponse> citations) {
    }

    public record DebateRoundResponse(Long id, int roundNumber, Instant startedAt, Instant completedAt,
                                      int argumentsCompleted, int argumentsFailed,
                                      List<ArgumentResponse> arguments) {
    }

    public record DebateResponse(Long id, Long contradictionId, Long corpusId, DebateState state,
                                 String stateDescription, int currentRound, int maxRounds,
                                 String topic, String chair, Instant createdAt, Instant startedAt,
                                 Instant finishedAt, String lastError,
                                 List<DebateRoundResponse> rounds) {
    }

    /**
     * Assembles a debate.
     *
     * <p>Reads only from plain values already on the entities, so it is safe to
     * call after the transaction that loaded them has closed. Nothing here
     * dereferences a lazy association: that is the caller's job, via the
     * {@code join fetch} queries in {@link DebateRepository}.
     *
     * @param roundsById    round id to its arguments, already fetched
     * @param latestWeight  argument id to its most recent weight
     * @param weightedBy    argument id to the verifier who set that weight
     * @param citations     argument id to its validated citations
     */
    public static DebateResponse assemble(Debate debate,
                                      List<DebateRound> rounds,
                                      Map<Long, List<Argument>> argumentsByRound,
                                      Map<Long, Integer> latestWeight,
                                      Map<Long, String> weightedBy,
                                      Map<Long, List<ArgumentCitationResponse>> citations) {

        List<DebateRoundResponse> roundViews = new ArrayList<>();
        for (DebateRound round : rounds) {
            List<Argument> arguments = argumentsByRound.getOrDefault(round.getId(), List.of());
            List<ArgumentResponse> argumentViews = new ArrayList<>(arguments.size());
            for (Argument argument : arguments) {
                argumentViews.add(new ArgumentResponse(
                        argument.getId(),
                        round.getRoundNumber(),
                        argument.getPersona().name(),
                        argument.getArgumentText(),
                        argument.getStance(),
                        argument.getModel(),
                        argument.getPromptVersion(),
                        argument.isFailed(),
                        argument.getFailureReason(),
                        argument.getDurationMs(),
                        argument.getCreatedAt(),
                        latestWeight.get(argument.getId()),
                        weightedBy.get(argument.getId()),
                        citations.getOrDefault(argument.getId(), List.of())));
            }
            roundViews.add(new DebateRoundResponse(round.getId(), round.getRoundNumber(),
                    round.getStartedAt(), round.getCompletedAt(),
                    round.getArgumentsCompleted(), round.getArgumentsFailed(), argumentViews));
        }

        // The chair's name is read defensively. Convening builds a Debate in
        // memory and hands it back without a join fetch, so on that path the
        // association can be an uninitialised proxy. Returning null there is
        // honest; dereferencing it would throw and turn a successful convene into
        // a 500. Every other path fetches it explicitly, so this only ever
        // affects the freshly-created row.
        String chairName = null;
        if (debate.getChairedBy() != null && debate.getChairedBy().getUsername() != null) {
            chairName = debate.getChairedBy().getUsername();
        }

        return new DebateResponse(
                debate.getId(),
                debate.getContradiction().getId(),
                debate.getCorpus().getId(),
                debate.getState(),
                DebateEngine.describeStates().getOrDefault(debate.getState().name(), ""),
                debate.getCurrentRound(),
                debate.getMaxRounds(),
                debate.getTopic(),
                chairName,
                debate.getCreatedAt(),
                debate.getStartedAt(),
                debate.getFinishedAt(),
                debate.getLastError(),
                roundViews);
    }

    /** One validated citation on an argument. */
    public static ArgumentCitationResponse citationRow(ArgumentCitation c) {
        return new ArgumentCitationResponse(c.getId(), c.getCitationKind().name(),
                c.getChunkId(), c.getTripleId(), c.getClaimId(),
                c.getMachineFactId(), c.getExcerpt());
    }
}