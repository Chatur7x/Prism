package com.prism.debate;

import com.prism.claims.RetrievalService;
import com.prism.common.error.ApiException;
import com.prism.claims.RetrievalService.RetrievedPassage;
import com.prism.common.CitationValidator;
import com.prism.config.PrismTuningProperties;
import com.prism.contradiction.Contradiction;
import com.prism.contradiction.ContradictionRepository;
import com.prism.contradiction.ContradictionStatus;
import com.prism.corpus.CorpusAccessService;
import com.prism.document.DocumentChunk;
import com.prism.document.DocumentChunkRepository;
import com.prism.llm.Prompts;
import com.prism.trace.ActorType;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceOperationType;
import com.prism.trace.TraceRecorder;
import com.prism.trace.TraceRun;
import com.prism.trace.TraceRunStatus;
import com.prism.trace.TraceStep;
import com.prism.user.User;
import com.prism.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Orchestrates the Council.
 *
 * <p>All state changes route through {@link DebateEngine} and are applied with a
 * conditional SQL update, so two concurrent advances produce one success and one
 * 409. The LLM has no path to debate state: it can only produce argument text.
 */
@Service
public class DebateService {

    private static final Logger log = LoggerFactory.getLogger(DebateService.class);

    private final DebateRepository debates;
    private final DebateRoundRepository rounds;
    private final ArgumentRepository arguments;
    private final ArgumentCitationRepository citations;
    private final ArgumentWeightRepository weights;
    private final ContradictionRepository contradictions;
    private final PersonaRunner personas;
    private final SkepticBriefBuilder skepticBriefs;
    private final RetrievalService retrieval;
    private final DebateEventBroker broker;
    private final TraceRecorder traces;
    private final CorpusAccessService access;
    private final UserService users;
    private final DocumentChunkRepository chunks;
    /** Owns the one transactional statement this non-transactional class needs. */
    private final DebateStateService stateService;
    private final ArgumentPersistenceService argumentPersistence;
    private final Executor llmExecutor;
    private final int maxRounds;

    public DebateService(DebateRepository debates, DebateRoundRepository rounds,
                         ArgumentRepository arguments, ArgumentCitationRepository citations,
                         ArgumentWeightRepository weights, ContradictionRepository contradictions,
                         PersonaRunner personas, SkepticBriefBuilder skepticBriefs,
                         RetrievalService retrieval, DebateEventBroker broker,
                         TraceRecorder traces, CorpusAccessService access, UserService users,
                         DocumentChunkRepository chunks, PrismTuningProperties tuning,
                         DebateStateService stateService,
                         ArgumentPersistenceService argumentPersistence,
                         @Qualifier(com.prism.config.AsyncAndCacheConfig.LLM_EXECUTOR)
                         ThreadPoolTaskExecutor llmExecutor) {
        this.debates = debates;
        this.rounds = rounds;
        this.arguments = arguments;
        this.citations = citations;
        this.weights = weights;
        this.contradictions = contradictions;
        this.personas = personas;
        this.skepticBriefs = skepticBriefs;
        this.retrieval = retrieval;
        this.broker = broker;
        this.traces = traces;
        this.access = access;
        this.users = users;
        this.chunks = chunks;
        this.stateService = stateService;
        this.argumentPersistence = argumentPersistence;
        this.llmExecutor = llmExecutor;
        this.maxRounds = tuning.debate().maxRounds();
    }

    // ---- lifecycle ---------------------------------------------------------

    @Transactional
    public Debate convene(Long userId, Long contradictionId, String topic) {
        access.requireVerifier(userId);
        // Load by id, then authorize against the corpus that actually owns the
        // row. Passing userId to findByIdAndCorpusId would compare a user id
        // against a corpus id: it 404s for most callers, hiding the mistake, and
        // in the case where the two ids coincide it would convene a Council over
        // a contradiction in a corpus the caller cannot reach.
        Contradiction contradiction = contradictions.findById(contradictionId)
                .orElseThrow(() -> ApiException.notFound("Contradiction", contradictionId));
        access.requireAccessible(contradiction.getCorpus().getId(), userId);
        if (contradiction.getStatus() != ContradictionStatus.OPEN) {
            throw ApiException.stateConflict("contradiction " + contradictionId
                    + " is " + contradiction.getStatus() + "; only OPEN contradictions can convene a debate");
        }
        // One debate per contradiction, enforced by a unique key. A second call is
        // a conflict, not a second council.
        debates.findByContradictionId(contradictionId).ifPresent(existing -> {
            throw ApiException.conflict("contradiction " + contradictionId
                    + " already has debate " + existing.getId());
        });

        User chair = users.getById(userId);
        String resolvedTopic = (topic == null || topic.isBlank())
                ? buildDefaultTopic(contradiction) : topic.trim();
        Debate debate = debates.save(new Debate(contradiction, chair, resolvedTopic, maxRounds));
        contradiction.markInDebate(Instant.now());
        contradictions.save(contradiction);

        TraceRun traceRun = traces.startRun(TraceOperationType.DEBATE, contradiction.getCorpus(),
                null, chair, "debate:create:" + debate.getId(), null);
        traces.record(traceRun.getId(), null, ActorType.HUMAN, TraceEventType.DEBATE_CREATED,
                "Debate convened",
                TraceRecorder.StepPayload.builder()
                        .inputSummary(resolvedTopic)
                        .outputRefs(List.of(debate.getId()))
                        .outputSummary("state=" + debate.getState() + " maxRounds=" + maxRounds)
                        .build());
        traces.finishRun(traceRun.getId(), TraceRunStatus.SUCCEEDED, null);
        broker.publish(debate.getId(), DebateEventBroker.EVENT_PHASE,
                Map.of("state", debate.getState().name(), "round", 0));
        return debate;
    }

    /**
     * Starts the debate: CREATED → ROUND_ACTIVE, then runs round 1's personas.
     */
    public Debate start(Long userId, Long debateId) {
        access.requireVerifier(userId);
        Debate debate = loadForUser(debateId, userId);
        User chair = users.getById(userId);

        TraceRun traceRun = traces.startRun(TraceOperationType.DEBATE, debate.getCorpus(),
                null, chair, "debate:start:" + debateId, null);
        TraceStep root = traces.simple(traceRun.getId(), null, ActorType.HUMAN,
                TraceEventType.DEBATE_STARTED, "Debate start requested");

        DebateEngine.Decision decision = DebateEngine.decide(debate.getState(),
                DebateState.Event.START, debate.getCurrentRound(), debate.getMaxRounds());

        if (!applyTransition(debate.getId(), DebateState.CREATED, decision)) {
            throw ApiException.stateConflict("debate " + debateId + " is no longer CREATED");
        }
        traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.DEBATE_STARTED,
                "Debate started",
                TraceRecorder.StepPayload.builder().outputSummary(decision.reason()).build());

        Debate reloaded = loadForUser(debateId, userId);
        runRound(userId, reloaded, traceRun.getId(), root);
        return loadForUser(debateId, userId);
    }

    /**
     * Applies WEIGHTS_SUBMITTED: either opens the next round or moves to
     * synthesis when the ceiling is reached.
     */
    public Debate advance(Long userId, Long debateId) {
        access.requireVerifier(userId);
        Debate debate = loadForUser(debateId, userId);
        User chair = users.getById(userId);

        TraceRun traceRun = traces.startRun(TraceOperationType.DEBATE, debate.getCorpus(),
                null, chair, "debate:advance:" + debateId, null);
        TraceStep root = traces.simple(traceRun.getId(), null, ActorType.HUMAN,
                TraceEventType.ROUND_COMPLETED, "Advance requested");

        if (debate.getState() != DebateState.AWAITING_CHAIR) {
            throw ApiException.stateConflict("debate " + debateId + " is " + debate.getState()
                    + "; weights can only be submitted while AWAITING_CHAIR");
        }
        long unweighted = arguments.findByDebateIdOrderByDebateRoundIdAscIdAsc(debateId).stream()
                .filter(a -> !a.isFailed())
                .filter(a -> weights.findByArgumentIdOrderByIdDesc(a.getId()).isEmpty())
                .count();
        if (unweighted > 0) {
            throw ApiException.stateConflict(unweighted
                    + " argument(s) still need a chair weight before this round can advance");
        }

        DebateEngine.Decision decision = DebateEngine.decide(debate.getState(),
                DebateState.Event.WEIGHTS_SUBMITTED, debate.getCurrentRound(), debate.getMaxRounds());

        if (!applyTransition(debate.getId(), DebateState.AWAITING_CHAIR, decision)) {
            traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, "concurrent advance");
            throw ApiException.stateConflict("debate " + debateId
                    + " was advanced concurrently; reload to see the current state");
        }

        traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.ROUND_COMPLETED,
                decision.anotherRoundRequired() ? "Next round opened" : "Round ceiling reached",
                TraceRecorder.StepPayload.builder()
                        .outputSummary(decision.reason())
                        .outputSummary(decision.targetState().name())
                        .build());

        if (decision.anotherRoundRequired()) {
            Debate reloaded = loadForUser(debateId, userId);
            broker.publish(debateId, DebateEventBroker.EVENT_PHASE,
                    Map.of("state", reloaded.getState().name(), "round", decision.nextRoundNumber()));
            runRound(userId, reloaded, traceRun.getId(), root);
        } else {
            broker.publish(debateId, DebateEventBroker.EVENT_PHASE,
                    Map.of("state", decision.targetState().name(), "round", debate.getCurrentRound()));
        }
        return loadForUser(debateId, userId);
    }

    @Transactional
    public Debate abort(Long userId, Long debateId) {
        access.requireVerifier(userId);
        Debate debate = loadForUser(debateId, userId);
        DebateEngine.Decision decision = DebateEngine.decide(debate.getState(),
                DebateState.Event.ABORT, debate.getCurrentRound(), debate.getMaxRounds());
        if (!applyTransition(debate.getId(), debate.getState(), decision)) {
            throw ApiException.stateConflict("debate " + debateId + " changed concurrently");
        }
        contradictions.findById(debate.getContradiction().getId()).ifPresent(c -> {
            c.reopen(Instant.now());
            contradictions.save(c);
        });
        broker.completeAll(debateId);
        return loadForUser(debateId, userId);
    }

    /**
     * Conditional state update. Returns false when another writer won the race,
     * which the caller reports as a conflict rather than retrying.
     *
     * <p>Delegated to {@link DebateStateService} because this class is not
     * transactional — it must not be, since it makes model calls — so a
     * {@code @Modifying} query has no ambient transaction to use. See that class
     * for the full explanation.
     */
    private boolean applyTransition(Long debateId, DebateState expected, DebateEngine.Decision decision) {
        return stateService.applyTransition(debateId, expected, decision);
    }

    // ---- rounds ------------------------------------------------------------

    /**
     * Runs one round: build the machine brief, run the three personas in
     * parallel, persist their arguments and citations, then move to
     * AWAITING_CHAIR.
     */
    public void runRound(Long userId, Debate debate, Long traceRunId, TraceStep parent) {
        int roundNumber = debate.getCurrentRound();
        DebateRound round = rounds.findByDebateIdAndRoundNumber(debate.getId(), roundNumber)
                .orElseGet(() -> rounds.save(new DebateRound(debate, roundNumber)));

        // ---- evidence for the personas ----
        RetrievalService.RetrievalResult evidence =
                retrieval.retrieve(debate.getCorpus().getId(), debate.getTopic());
        List<Prompts.Passage> passages = evidence.passages().stream()
                .map(p -> new Prompts.Passage(p.chunkId(), p.text()))
                .toList();
        Set<Long> allowedChunks = passages.stream().map(Prompts.Passage::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        // ---- the Skeptic's machine evidence, built from live state ----
        SkepticBriefBuilder.BriefResult brief =
                skepticBriefs.build(userId, debate.getCorpus().getId(), debate.getContradiction(), traceRunId);

        traces.record(traceRunId, parent, ActorType.ENGINE, TraceEventType.RETRIEVAL_COMPLETED,
                "Evidence assembled for round " + roundNumber,
                TraceRecorder.StepPayload.builder()
                        .inputSummary(debate.getTopic())
                        .outputSummary(passages.size() + " passages and "
                                + brief.brief().factCount() + " machine facts")
                        .outputRefs(new ArrayList<>(allowedChunks))
                        .build());

        // ---- personas, in parallel ----
        List<PersonaRunner.PersonaResult> results = personas.runAll(new PersonaRunner.PersonaRequest(
                debate.getContradiction().getSubjectText() + " " + debate.getTopic(),
                passages,
                allowedChunks,
                brief.rendered(),
                brief.brief().allowedIds(),
                traceRunId,
                parent,
                llmExecutor));

        int succeeded = 0;
        for (PersonaRunner.PersonaResult result : results) {
            Argument saved = argumentPersistence.write(round, debate, result, evidence.passages());
            if (result.succeeded()) {
                succeeded++;
                broker.publish(debate.getId(), DebateEventBroker.EVENT_ARGUMENT,
                        argumentEventPayload(saved, result));
            } else {
                broker.publish(debate.getId(), DebateEventBroker.EVENT_ERROR,
                        Map.of("persona", result.persona().name(),
                                "reason", String.valueOf(result.failureReason())));
            }
        }

        round.complete(Instant.now());
        rounds.save(round);

        traces.record(traceRunId, parent, ActorType.LLM, TraceEventType.ARGUMENT_CREATED,
                "Round " + roundNumber + " arguments complete",
                TraceRecorder.StepPayload.builder()
                        .outputSummary(succeeded + " of " + results.size() + " personas produced an argument")
                        .build());

        // ---- move to AWAITING_CHAIR ----
        DebateEngine.Decision decision = DebateEngine.decide(DebateState.ROUND_ACTIVE,
                DebateState.Event.ARGUMENTS_DONE, roundNumber, debate.getMaxRounds());
        if (applyTransition(debate.getId(), DebateState.ROUND_ACTIVE, decision)) {
            broker.publish(debate.getId(), DebateEventBroker.EVENT_AWAITING_CHAIR,
                    Map.of("debateId", debate.getId(), "round", roundNumber,
                            "message", "chair weights are required before advancing"));
        }
        traces.finishRun(traceRunId, TraceRunStatus.SUCCEEDED, null);
    }

    private Map<String, Object> argumentEventPayload(Argument argument, PersonaRunner.PersonaResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("argumentId", argument.getId());
        payload.put("persona", argument.getPersona().name());
        payload.put("round", argument.getDebateRound().getRoundNumber());
        payload.put("argument", argument.getArgumentText());
        payload.put("stance", argument.getStance());
        payload.put("model", argument.getModel());
        payload.put("promptVersion", argument.getPromptVersion());
        payload.put("citedChunkIds", result.citedChunkIds());
        payload.put("citedMachineFactIds", result.citedMachineFactIds());
        payload.put("createdAt", argument.getCreatedAt().toString());
        return payload;
    }

    // ---- chair actions -----------------------------------------------------

    /**
     * Records a chair weight. Append-only: revising a weight creates a new row.
     */
    @Transactional
    public ArgumentWeight weigh(Long userId, Long debateId, Long argumentId, Integer weight, String note) {
        access.requireVerifier(userId);
        Debate debate = loadForUser(debateId, userId);
        if (debate.getState() != DebateState.AWAITING_CHAIR) {
            throw ApiException.stateConflict("weights can only be recorded while the debate is "
                    + "AWAITING_CHAIR; it is currently " + debate.getState());
        }
        DebateEngine.validateWeight(weight);
        Argument argument = arguments.findByIdAndDebateId(argumentId, debateId)
                .orElseThrow(() -> ApiException.notFound("Argument", argumentId));
        if (argument.isFailed()) {
            throw ApiException.stateConflict("argument " + argumentId
                    + " records a persona failure and cannot be weighted");
        }
        User verifier = users.getById(userId);
        ArgumentWeight saved = weights.save(new ArgumentWeight(argument, debate, weight, verifier, note));

        TraceRun traceRun = traces.startRun(TraceOperationType.DEBATE, debate.getCorpus(),
                null, verifier, "debate:weight:" + saved.getId(), null);
        traces.record(traceRun.getId(), null, ActorType.HUMAN, TraceEventType.ARGUMENT_WEIGHTED,
                "Argument weighted by " + verifier.getUsername(),
                TraceRecorder.StepPayload.builder()
                        .inputSummary("argument " + argumentId + " (" + argument.getPersona() + ")")
                        .outputSummary("weight=" + weight
                                + (note == null || note.isBlank() ? "" : " note=\"" + note + "\""))
                        .outputRefs(List.of(argumentId, saved.getId()))
                        .build());
        traces.finishRun(traceRun.getId(), TraceRunStatus.SUCCEEDED, null);

        broker.publish(debateId, DebateEventBroker.EVENT_PHASE,
                Map.of("argumentId", argumentId, "weight", weight));
        return saved;
    }

    // ---- reads -------------------------------------------------------------

    @Transactional(readOnly = true)
    public Debate loadForUser(Long debateId, Long userId) {
        Debate debate = debates.findById(debateId)
                .orElseThrow(() -> ApiException.notFound("Debate", debateId));
        access.requireAccessible(debate.getCorpus().getId(), userId);
        return debate;
    }

    @Transactional(readOnly = true)
    public List<DebateRound> roundsOf(Long debateId) {
        return rounds.findByDebateIdOrderByRoundNumberAsc(debateId);
    }

    /** The debate convened for a contradiction, if any. */
    @Transactional(readOnly = true)
    public java.util.Optional<Debate> findByContradiction(Long contradictionId) {
        return debates.findByContradictionId(contradictionId);
    }

    @Transactional(readOnly = true)
    public List<Argument> argumentsOf(Long debateId) {
        return arguments.findByDebateIdOrderByDebateRoundIdAscIdAsc(debateId);
    }

    @Transactional(readOnly = true)
    public List<ArgumentWeight> latestWeights(Long debateId) {
        return weights.findLatestWeightsForDebate(debateId);
    }

    @Transactional(readOnly = true)
    public List<ArgumentCitation> citationsOf(Long debateId) {
        return citations.findByDebateId(debateId);
    }

    private String buildDefaultTopic(Contradiction contradiction) {
        return "Resolve: " + contradiction.getSubjectText()
                + (contradiction.getPredicate() == null ? "" : " " + contradiction.getPredicate())
                + " — " + contradiction.getContradictionType();
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
