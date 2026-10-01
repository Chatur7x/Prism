package com.prism.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes the audit trail.
 *
 * <p>Each step is committed in its own transaction ({@code REQUIRES_NEW}) so a
 * step survives the rollback of the operation it describes. An LLM call that
 * later fails must still leave evidence that the call happened — that evidence
 * is the point of the Glass Box.
 */
@Service
public class TraceRecorder {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TraceRecorder.class);

    private final TraceRunRepository runs;
    /** Owns the transactional step write; see its class comment on proxy self-invocation. */
    private final TraceStepWriter stepWriter;
    private final ObjectMapper objectMapper;

    public TraceRecorder(TraceRunRepository runs, TraceStepWriter stepWriter, ObjectMapper objectMapper) {
        this.runs = runs;
        this.stepWriter = stepWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TraceRun startRun(TraceOperationType type, com.prism.corpus.Corpus corpus,
                             com.prism.document.Document document, com.prism.user.User user,
                             String operationKey, String metadataJson) {
        return runs.save(new TraceRun(type, corpus, document, user, operationKey, metadataJson));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(Long runId, TraceRunStatus status, String errorSummary) {
        TraceRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        // duration is computed here rather than in run.complete() so the
        // caller's value reaches the column. Leaving it null in the database
        // would make a completed run's duration unreadable after the session
        // closed, which is the one number the Glass Box always shows.
        Instant finishedAt = Instant.now();
        Long durationMs = Duration.between(run.getStartedAt(), finishedAt).toMillis();
        // Targeted column update, deliberately not runs.save(run). save() writes
        // every column including step_seq, so a finish that races a concurrent
        // step increment would write back a stale counter and collide with
        // uk_step_run_seq -- and would hold the run's row lock for the whole
        // write. Naming only the three columns the completion actually changes
        // removes both problems.
        runs.completeRun(runId, status, truncate(errorSummary, 2000), finishedAt, durationMs);
    }

    /**
     * Records one step. Never throws: losing the audit trail must not be able to
     * abort the business operation it is observing. Failures are logged instead.
     *
     * <p><b>Not transactional, and that is deliberate.</b> The try/catch that
     * makes this method safe has to sit <em>outside</em> the transaction, so the
     * work is delegated to {@link TraceStepWriter}, which owns it. A catch inside
     * the transactional method would not achieve the goal: the exception marks
     * the transaction rollback-only, the catch hides it, and then the proxy
     * throws {@code UnexpectedRollbackException} at commit — outside the method
     * body, outside the catch. The "safe" wrapper would then be the thing that
     * fails the operation. That is not hypothetical: it is how a duplicate step
     * sequence during a parallel debate round turned three persona successes
     * into three {@code UnexpectedRollbackException}s.
     *
     * <p>The delegation also has to cross a bean boundary. Calling a
     * {@code @Transactional} method on {@code this} would bypass the proxy and
     * rejoin the caller's transaction, which self-deadlocks against
     * {@link #finishRun}. See {@link TraceStepWriter}.
     */
    public TraceStep record(Long runId, TraceStep parentStep, ActorType actor, TraceEventType event,
                            String name, StepPayload payload) {
        try {
            return stepWriter.write(runId, parentStep, actor, event, name, payload);
        } catch (RuntimeException ex) {
            // Swallow deliberately: auditing must not break the operation. The
            // write's own transaction has already rolled back, so nothing is
            // left half-written and no rollback marker leaks to the caller.
            log.warn("Failed to record trace step for run {}: {}", runId, ex.toString());
            return null;
        }
    }


    public TraceStep simple(Long runId, TraceStep parentStep, ActorType actor, TraceEventType event, String name) {
        return record(runId, parentStep, actor, event, name, StepPayload.ok());
    }

    public TraceStep failure(Long runId, TraceStep parentStep, ActorType actor, TraceEventType event,
                             String name, String errorMessage) {
        return record(runId, parentStep, actor, event, name,
                StepPayload.builder().status("ERROR").errorMessage(truncate(errorMessage, 1900)));
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + ex.getOriginalMessage() + "\"}";
        }
    }

    /**
     * Steps of a run in sequence order.
     *
     * <p>Read through {@link TraceStepWriter} so the lookup stays in one place
     * for everything trace-step related. Not a write, so no transaction boundary
     * of its own is needed here.
     */
    public List<TraceStep> stepsOf(Long runId) {
        return stepWriter.stepsOf(runId);
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** Mutable builder; steps accumulate detail across a span of work. */
    public static final class StepPayload {
        private String status = "OK";
        private String inputSummary;
        private String inputReferenceIds;
        private String outputSummary;
        private String outputReferenceIds;
        private String ruleVersion;
        private String promptVersion;
        private String model;
        private Long durationMs;
        private String errorMessage;
        private int attempt = 1;

        public static StepPayload ok() {
            return new StepPayload();
        }

        /** Named to read naturally at call sites; the payload is itself the builder. */
        public static StepPayload builder() {
            return new StepPayload();
        }

        /** Terminal builder call. Returns this instance; present for readability. */
        public StepPayload build() {
            return this;
        }

        public StepPayload status(String status) {
            this.status = status;
            return this;
        }

        public StepPayload inputSummary(String value) {
            this.inputSummary = TraceRecorder.truncate(value, 990);
            return this;
        }

        public StepPayload inputRefs(List<Long> ids) {
            this.inputReferenceIds = renderIds(ids);
            return this;
        }

        public StepPayload outputSummary(String value) {
            this.outputSummary = TraceRecorder.truncate(value, 1990);
            return this;
        }

        public StepPayload outputRefs(List<Long> ids) {
            this.outputReferenceIds = renderIds(ids);
            return this;
        }

        public StepPayload ruleVersion(String value) {
            this.ruleVersion = value;
            return this;
        }

        public StepPayload promptVersion(String value) {
            this.promptVersion = value;
            return this;
        }

        public StepPayload model(String value) {
            this.model = value;
            return this;
        }

        public StepPayload durationMs(Long value) {
            this.durationMs = value;
            return this;
        }

        public StepPayload durationSince(long startNanos) {
            this.durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            return this;
        }

        public StepPayload errorMessage(String value) {
            this.errorMessage = TraceRecorder.truncate(value, 1900);
            return this;
        }

        public StepPayload attempt(int value) {
            this.attempt = value;
            return this;
        }

        private static String renderIds(List<Long> ids) {
            if (ids == null || ids.isEmpty()) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ids.get(i));
            }
            return sb.append(']').toString();
        }

        public String status() {
            return status;
        }

        public String inputSummary() {
            return inputSummary;
        }

        public String inputReferenceIds() {
            return inputReferenceIds;
        }

        public String outputSummary() {
            return outputSummary;
        }

        public String outputReferenceIds() {
            return outputReferenceIds;
        }

        public String ruleVersion() {
            return ruleVersion;
        }

        public String promptVersion() {
            return promptVersion;
        }

        public String model() {
            return model;
        }

        public Long durationMs() {
            return durationMs;
        }

        public String errorMessage() {
            return errorMessage;
        }

        public int attempt() {
            return attempt;
        }
    }
}
