package com.prism.debate;

import com.prism.claims.RetrievalService;
import com.prism.common.CitationValidator;
import com.prism.config.LlmProperties;
import com.prism.document.DocumentChunk;
import com.prism.document.DocumentChunkRepository;
import com.prism.llm.LlmCompletion;
import com.prism.llm.LlmPermanentException;
import com.prism.llm.LlmTransientException;
import com.prism.llm.Prompts;
import com.prism.llm.RetryingLlmService;
import com.prism.trace.ActorType;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceRecorder;
import com.prism.trace.TraceStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Runs Hawk, Dove, and Skeptic concurrently.
 *
 * <p>All three are independent, so they run in parallel on the dedicated LLM
 * executor. One failure must not destroy the round: each persona's result is
 * captured independently and a failure becomes a recorded
 * {@code Argument.failed} row rather than aborting the debate.
 *
 * <p>Each persona is time-bounded. A model that hangs must not hold the round
 * open indefinitely, so a timeout produces the same recorded-failure path as
 * any other error.
 */
@Service
public class PersonaRunner {

    private static final Logger log = LoggerFactory.getLogger(PersonaRunner.class);

    private final RetryingLlmService llm;
    private final ObjectMapper objectMapper;
    private final TraceRecorder traces;
    private final LlmProperties llmProperties;
    private final int timeoutSeconds;

    public PersonaRunner(RetryingLlmService llm, ObjectMapper objectMapper, TraceRecorder traces,
                         LlmProperties llmProperties,
                         com.prism.config.PrismTuningProperties tuning) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.traces = traces;
        this.llmProperties = llmProperties;
        this.timeoutSeconds = tuning.debate().personaTimeoutSeconds();
    }

    /** The result of one persona call. */
    public record PersonaResult(
            Persona persona,
            boolean succeeded,
            String argumentText,
            String stance,
            String model,
            String promptVersion,
            Long durationMs,
            List<Long> citedChunkIds,
            List<String> citedMachineFactIds,
            List<CitationValidator.Rejection> rejectedCitations,
            String failureReason) {

        static PersonaResult failure(Persona persona, String promptVersion, String reason) {
            return new PersonaResult(persona, false, null, null, null, promptVersion, null,
                    List.of(), List.of(), List.of(), reason);
        }
    }
    /**
     * Runs all three personas concurrently and returns their results.
     *
     * <p>Order is always HAWK, DOVE, SKEPTIC regardless of completion order, so
     * the round's output is stable.
     */
    public List<PersonaResult> runAll(PersonaRequest request) {
        Executor executor = request.executor();
        List<CompletableFuture<PersonaResult>> futures = new ArrayList<>();

        for (Persona persona : Persona.values()) {
            futures.add(CompletableFuture.supplyAsync(() -> runOne(persona, request), executor)
                    // A throw inside supplyAsync would surface here; convert it
                    // into a recorded failure so the round still completes.
                    .exceptionally(throwable -> {
                        Throwable cause = unwrap(throwable);
                        log.warn("Persona {} failed: {}", persona, cause.getMessage());
                        return PersonaResult.failure(persona, promptVersionFor(persona),
                                "unhandled persona failure: " + cause.getClass().getSimpleName()
                                        + (cause.getMessage() == null ? "" : " — " + cause.getMessage()));
                    }));
        }

        List<PersonaResult> results = new ArrayList<>();
        for (CompletableFuture<PersonaResult> future : futures) {
            try {
                results.add(future.get(timeoutSeconds + 30L, TimeUnit.SECONDS));
            } catch (TimeoutException ex) {
                // The task may still be running; record the timeout as a failure.
                results.add(PersonaResult.failure(null, null,
                        "a persona call exceeded the " + timeoutSeconds + "s budget"));
            } catch (Exception ex) {
                Throwable cause = unwrap(ex);
                results.add(PersonaResult.failure(null, null,
                        "a persona call failed: " + cause.getMessage()));
            }
        }
        return results;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        if (throwable instanceof java.util.concurrent.ExecutionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    /** Everything a persona call needs, gathered before any model is invoked. */
    public record PersonaRequest(
            String subject,
            List<Prompts.Passage> passages,
            Set<Long> allowedChunkIds,
            String machineEvidence,
            Set<String> allowedMachineFactIds,
            Long traceRunId,
            TraceStep parentStep,
            Executor executor) {
    }

    private PersonaResult runOne(Persona persona, PersonaRequest request) {
        String promptVersion = promptVersionFor(persona);
        long started = System.nanoTime();
        try {
            String system = switch (persona) {
                case HAWK -> Prompts.hawkSystem();
                case DOVE -> Prompts.doveSystem();
                case SKEPTIC -> Prompts.skepticSystem();
            };
            String user = persona == Persona.SKEPTIC
                    ? Prompts.skepticUser(request.subject(), request.machineEvidence(), request.passages())
                    : Prompts.personaUser(persona.name(), request.subject(), request.passages());

            if (request.traceRunId() != null) {
                traces.record(request.traceRunId(), request.parentStep(), ActorType.LLM,
                        TraceEventType.LLM_REQUEST, persona.name() + " argument request",                        TraceRecorder.StepPayload.builder()
                                .inputSummary(request.passages().size() + " passages"
                                        + (persona == Persona.SKEPTIC
                                        ? " + " + request.allowedMachineFactIds().size() + " machine facts"
                                        : ""))
                                .promptVersion(promptVersion)
                                .model(llmProperties.debateModel())
                                .build());
            }

            LlmCompletion completion = llm.complete(system, user, LlmProperties.Purpose.DEBATE,
                    promptVersion, "debate:" + persona.name(), request.traceRunId(), request.parentStep());

            return parsePersonaOutput(persona, completion, request, promptVersion,
                    (System.nanoTime() - started) / 1_000_000L);

        } catch (LlmPermanentException ex) {
            return failPersona(persona, promptVersion, request, started,
                    "provider permanently rejected the request: " + ex.getMessage());
        } catch (LlmTransientException ex) {
            return failPersona(persona, promptVersion, request, started,
                    "provider retries exhausted: " + ex.getMessage());
        } catch (RuntimeException ex) {
            return failPersona(persona, promptVersion, request, started,
                    "unexpected failure: " + ex.getClass().getSimpleName() + " — " + ex.getMessage());
        }
    }

    private PersonaResult failPersona(Persona persona, String promptVersion, PersonaRequest request,
                                      long startedNanos, String reason) {
        if (request.traceRunId() != null) {
            traces.failure(request.traceRunId(), request.parentStep(), ActorType.LLM,
                    TraceEventType.ARGUMENT_FAILED, persona.name() + " produced no argument", reason);
        }
        return new PersonaResult(persona, false, null, null, null, promptVersion,
                (System.nanoTime() - startedNanos) / 1_000_000L,
                List.of(), List.of(), List.of(), reason);
    }

    /**
     * Parses and validates a persona's output.
     *
     * <p>Citations are validated against what was actually supplied. A persona
     * that invents a passage id is treated as having produced an unusable
     * argument, not as having produced an argument with a broken link.
     */
    private PersonaResult parsePersonaOutput(Persona persona, LlmCompletion completion,
                                             PersonaRequest request, String promptVersion,
                                             long durationMs) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(completion.rawText()));
        } catch (Exception ex) {
            return failPersona(persona, promptVersion, request, 0,
                    "response was not valid JSON: " + ex.getMessage());
        }
        if (root == null || !root.isObject()) {
            return failPersona(persona, promptVersion, request, 0, "response was not a JSON object");
        }

        String argument = root.path("argument").asText("").trim();
        if (argument.isEmpty()) {
            return failPersona(persona, promptVersion, request, 0,
                    "response contained no 'argument' field");
        }
        if (argument.length() > 8000) {
            argument = argument.substring(0, 8000);
        }
        String stance = root.path("stance").asText("");
        if (stance.length() > 500) {
            stance = stance.substring(0, 500);
        }

        List<Long> citedChunks = new ArrayList<>();
        JsonNode passageIds = root.path("passage_ids");
        if (passageIds.isArray()) {
            for (JsonNode id : passageIds) {
                citedChunks.add(id.isNumber() ? id.asLong() : null);
            }
        }
        CitationValidator.Result<Long> chunkCitations =
                CitationValidator.validateChunkIds(citedChunks, request.allowedChunkIds());

        List<String> citedFacts = new ArrayList<>();
        JsonNode factIds = root.path("machine_fact_ids");
        if (factIds.isArray()) {
            for (JsonNode id : factIds) {
                citedFacts.add(id.isTextual() ? id.asText() : null);
            }
        }
        CitationValidator.Result<String> factCitations =
                CitationValidator.validateStringIds(citedFacts, request.allowedMachineFactIds());

        List<CitationValidator.Rejection> rejected = new ArrayList<>();
        rejected.addAll(chunkCitations.rejected());
        rejected.addAll(factCitations.rejected());

        if (!rejected.isEmpty()) {
            // Fabricated references mean the argument cannot be trusted, whatever
            // it says. Record the failure rather than the text.
            String reason = persona.name() + " cited ids that were not supplied: "
                    + rejected.stream().map(CitationValidator.Rejection::id).toList();
            if (request.traceRunId() != null) {
                traces.failure(request.traceRunId(), request.parentStep(), ActorType.ENGINE,
                        TraceEventType.CITATION_REJECTED, persona.name() + " citations rejected", reason);
            }
            return new PersonaResult(persona, false, null, null, completion.model(), promptVersion,
                    durationMs, List.of(), List.of(), rejected, reason);
        }

        if (request.traceRunId() != null) {
            traces.record(request.traceRunId(), request.parentStep(), ActorType.ENGINE,
                    TraceEventType.CITATION_VALIDATED, persona.name() + " citations validated",
                    TraceRecorder.StepPayload.builder()
                            .outputSummary("accepted " + chunkCitations.validCount() + " passage citations and "
                                    + factCitations.validCount() + " machine fact citations")
                            .outputRefs(chunkCitations.valid())
                            .build());
        }

        return new PersonaResult(persona, true, argument, stance, completion.model(), promptVersion,
                durationMs, chunkCitations.valid(), factCitations.valid(), List.of(), null);
    }

    static String promptVersionFor(Persona persona) {
        if (persona == null) {
            return null;
        }
        return switch (persona) {
            case HAWK -> Prompts.HAWK_V1;
            case DOVE -> Prompts.DOVE_V1;
            case SKEPTIC -> Prompts.SKEPTIC_V1;
        };
    }

    private static String stripFence(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }
}
