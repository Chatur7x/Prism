package com.prism.trace;

import com.prism.common.error.ApiException;
import com.prism.corpus.CorpusAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Glass Box.
 *
 * <p>Serves stored trace events for replay. A replay reads what was recorded at
 * the time, never the current database state — otherwise a trace would show
 * what the system looks like now while claiming to show what it did then.
 *
 * <p>Authorization is re-checked on every read against the run's corpus, so a
 * trace can never be read by someone who cannot read the underlying data.
 */
@RestController
@RequestMapping("/api/traces")
@Tag(name = "Glass Box", description = "Trace runs, step DAG, and deterministic replay")
public class TraceController {

    private final TraceRunRepository runs;
    private final TraceStepRepository steps;
    private final CorpusAccessService access;

    public TraceController(TraceRunRepository runs, TraceStepRepository steps, CorpusAccessService access) {
        this.runs = runs;
        this.steps = steps;
        this.access = access;
    }

    /**
     * A run in the Glass Box list.
     *
     * <p>{@code errorMessage} is the run-level reason, not the failing step's
     * message. Both matter: the step says which operation failed, this says why
     * the run as a whole ended the way it did, and it is the only reason visible
     * without opening every step.
     */
    public record RunSummary(Long id, TraceOperationType operationType, Long corpusId, Long documentId,
                             String status, String operationKey, Instant startedAt, Instant finishedAt,
                             Long durationMs, String errorMessage, long stepCount) {
    }

    public record StepView(Long id, Long parentStepId, Long seq, ActorType actorType,
                           TraceEventType eventType, String name, String status,
                           String inputSummary, String inputReferenceIds,
                           String outputSummary, String outputReferenceIds,
                           String ruleVersion, String promptVersion, String model,
                           Long durationMs, String errorMessage, int attempt, Instant createdAt,
                           List<Long> children) {
    }

    @GetMapping
    @Operation(summary = "List trace runs visible to the caller")
    public Map<String, Object> list(@RequestParam(required = false) Long corpusId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        Long userId = access.requireCurrentUserId();
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));

        org.springframework.data.domain.Page<TraceRun> result;
        if (corpusId == null) {
            result = runs.findAllByOrderByStartedAtDesc(pageable);
        } else {
            access.requireAccessible(corpusId, userId);
            result = runs.findByCorpusIdOrderByStartedAtDesc(corpusId, pageable);
        }

        List<RunSummary> rows = new ArrayList<>();
        for (TraceRun run : result.getContent()) {
            if (run.getCorpus() != null) {
                // Defence in depth: a run may carry a corpus the caller has
                // since lost access to.
                access.requireAccessible(run.getCorpus().getId(), userId);
            }
            rows.add(new RunSummary(run.getId(), run.getOperationType(),
                    run.getCorpus() == null ? null : run.getCorpus().getId(),
                    run.getDocument() == null ? null : run.getDocument().getId(),
                    run.getStatus().name(), run.getOperationKey(), run.getStartedAt(),
                    run.getFinishedAt(), run.getDurationMs(), run.getErrorSummary(),
                    steps.countByRunId(run.getId())));
        }
        return Map.of("content", rows, "total", result.getTotalElements(),
                "page", result.getNumber(), "size", result.getSize());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One trace run with its complete step DAG, ordered for replay")
    public Map<String, Object> get(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        TraceRun run = loadAuthorized(id, userId);
        List<TraceStep> ordered = steps.findByRunIdOrderBySeqAsc(id);

        Map<Long, List<Long>> children = new LinkedHashMap<>();
        for (TraceStep step : ordered) {
            if (step.getParentStep() != null) {
                children.computeIfAbsent(step.getParentStep().getId(), k -> new ArrayList<>())
                        .add(step.getId());
            }
        }

        List<StepView> stepRows = ordered.stream()
                .map(s -> new StepView(s.getId(),
                        s.getParentStep() == null ? null : s.getParentStep().getId(),
                        s.getSeq(), s.getActorType(), s.getEventType(), s.getName(), s.getStatus(),
                        s.getInputSummary(), s.getInputReferenceIds(),
                        s.getOutputSummary(), s.getOutputReferenceIds(),
                        s.getRuleVersion(), s.getPromptVersion(), s.getModel(),
                        s.getDurationMs(), s.getErrorMessage(), s.getAttempt(), s.getCreatedAt(),
                        children.getOrDefault(s.getId(), List.of())))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run", new RunSummary(run.getId(), run.getOperationType(),
                run.getCorpus() == null ? null : run.getCorpus().getId(),
                run.getDocument() == null ? null : run.getDocument().getId(),
                run.getStatus().name(), run.getOperationKey(), run.getStartedAt(),
                run.getFinishedAt(), run.getDurationMs(), run.getErrorSummary(), stepRows.size()));
        body.put("metadata", run.getMetadataJson());
        body.put("steps", stepRows);
        return body;
    }
    @GetMapping("/{id}/steps")
    @Operation(summary = "Flat, ordered step list for a replay timeline")
    public List<StepView> stepList(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        loadAuthorized(id, userId);
        return steps.findByRunIdOrderBySeqAsc(id).stream()
                .map(s -> new StepView(s.getId(),
                        s.getParentStep() == null ? null : s.getParentStep().getId(),
                        s.getSeq(), s.getActorType(), s.getEventType(), s.getName(), s.getStatus(),
                        s.getInputSummary(), s.getInputReferenceIds(),
                        s.getOutputSummary(), s.getOutputReferenceIds(),
                        s.getRuleVersion(), s.getPromptVersion(), s.getModel(),
                        s.getDurationMs(), s.getErrorMessage(), s.getAttempt(), s.getCreatedAt(),
                        List.of()))
                .toList();
    }

    /**
     * The X-Ray projection: the causal chain, in execution order, grouped by
     * actor. Built purely from stored events.
     */
    @GetMapping("/{id}/xray")
    @Operation(summary = "Causal chain for the X-Ray view, grouped by ENGINE / LLM / HUMAN")
    public Map<String, Object> xray(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        TraceRun run = loadAuthorized(id, userId);
        List<TraceStep> ordered = steps.findByRunIdOrderBySeqAsc(id);

        List<Map<String, Object>> engine = new ArrayList<>();
        List<Map<String, Object>> llm = new ArrayList<>();
        List<Map<String, Object>> human = new ArrayList<>();
        for (TraceStep s : ordered) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("stepId", s.getId());
            node.put("parentStepId", s.getParentStep() == null ? null : s.getParentStep().getId());
            node.put("seq", s.getSeq());
            node.put("eventType", s.getEventType().name());
            node.put("name", s.getName());
            node.put("status", s.getStatus());
            node.put("inputSummary", s.getInputSummary());
            node.put("outputSummary", s.getOutputSummary());
            node.put("ruleVersion", s.getRuleVersion());
            node.put("promptVersion", s.getPromptVersion());
            node.put("model", s.getModel());
            node.put("durationMs", s.getDurationMs());
            node.put("errorMessage", s.getErrorMessage());
            node.put("at", s.getCreatedAt());
            switch (s.getActorType()) {
                case ENGINE -> engine.add(node);
                case LLM -> llm.add(node);
                case HUMAN -> human.add(node);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.getId());
        body.put("operationType", run.getOperationType().name());
        body.put("status", run.getStatus().name());
        body.put("startedAt", run.getStartedAt());
        body.put("finishedAt", run.getFinishedAt());
        body.put("durationMs", run.getDurationMs());
        body.put("engine", engine);
        body.put("llm", llm);
        body.put("human", human);
        body.put("totalSteps", ordered.size());
        return body;
    }

    private TraceRun loadAuthorized(Long id, Long userId) {
        TraceRun run = runs.findById(id)
                .orElseThrow(() -> ApiException.notFound("Trace run", id));
        if (run.getCorpus() != null) {
            // Trace data can duplicate sensitive document content, so access is
            // re-verified here rather than assumed from the run row.
            access.requireAccessible(run.getCorpus().getId(), userId);
        } else {
            access.requireCurrentUserId();
        }
        return run;
    }
}
