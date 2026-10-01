package com.prism.llm;

import com.prism.config.LlmProperties;
import com.prism.trace.ActorType;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceRecorder;
import com.prism.trace.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Bounded retry around {@link LlmClient}, with every attempt traced.
 *
 * <p><b>Retry policy.</b> Only failures that may succeed on a second attempt are
 * retried: 429, 5xx, timeouts, and connection failures. 400, 401, 403, and
 * configuration errors are permanent — retrying them burns time and, for 401/403,
 * can look like credential stuffing to the provider.
 *
 * <p>Backoff is exponential with a cap. Every attempt is recorded in the trace
 * with its own attempt number, so a slow path is visible in the Glass Box rather
 * than appearing as one long opaque call.
 */
@Service
public class RetryingLlmService {

    private static final Logger log = LoggerFactory.getLogger(RetryingLlmService.class);
    private static final long MAX_BACKOFF_MILLIS = 15_000L;

    private final LlmClient client;
    private final LlmProperties props;
    private final TraceRecorder traces;

    public RetryingLlmService(LlmClient client, LlmProperties props, TraceRecorder traces) {
        this.client = client;
        this.props = props;
        this.traces = traces;
    }

    /**
     * @param traceRunId  run to attach attempt records to; may be null
     * @param parentStep parent step for nesting; may be null
     * @param stepName   name recorded in the trace
     * @return the completion on success
     * @throws LlmTransientException when the bounded retries are exhausted
     * @throws LlmPermanentException when the failure will not resolve on retry
     */
    public LlmCompletion complete(String system, String user, LlmProperties.Purpose purpose,
                                  String promptVersion, String stepName,
                                  Long traceRunId, TraceStep parentStep) {
        int maxAttempts = Math.max(1, props.maxRetries());
        LlmTransientException lastTransient = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                LlmCompletion completion = client.complete(system, user, purpose, null);
                if (traceRunId != null) {
                    traces.record(traceRunId, parentStep, ActorType.LLM, TraceEventType.LLM_RESPONSE,
                            stepName,
                            TraceRecorder.StepPayload.builder()
                                    .status(completion.truncated() ? "TRUNCATED" : "OK")
                                    .promptVersion(promptVersion)
                                    .model(completion.model())
                                    .durationMs(completion.durationMs())
                                    .attempt(attempt)
                                    .outputSummary("received " + safeLength(completion.rawText())
                                            + " chars"
                                            + (completion.truncated() ? " (hit token limit)" : ""))
                                    .build());
                }
                return new LlmCompletion(completion.rawText(), completion.model(),
                        completion.durationMs(), attempt, completion.truncated());

            } catch (LlmPermanentException ex) {
                // Not retryable. Record and propagate immediately.
                recordFailure(traceRunId, parentStep, stepName, promptVersion, attempt, ex);
                throw ex;

            } catch (LlmTransientException ex) {
                lastTransient = ex;
                log.warn("Transient LLM failure on attempt {}/{} for {}: {}",
                        attempt, maxAttempts, stepName, ex.getMessage());

                if (traceRunId != null) {
                    traces.record(traceRunId, parentStep, ActorType.LLM, TraceEventType.LLM_RESPONSE,
                            stepName,
                            TraceRecorder.StepPayload.builder()
                                    .status("RETRYING")
                                    .promptVersion(promptVersion)
                                    .attempt(attempt)
                                    .errorMessage(ex.getMessage())
                                    .outputSummary("transient failure; will retry if attempts remain")
                                    .build());
                }
                if (attempt < maxAttempts) {
                    sleep(backoffMillis(attempt));
                }
            }
        }

        throw new LlmTransientException(
                "LLM call '" + stepName + "' failed after " + maxAttempts + " attempts: "
                        + (lastTransient == null ? "unknown" : lastTransient.getMessage()),
                lastTransient == null ? null : lastTransient.getStatusCode(),
                lastTransient);
    }

    private void recordFailure(Long traceRunId, TraceStep parentStep, String stepName,
                               String promptVersion, int attempt, RuntimeException ex) {
        if (traceRunId == null) {
            return;
        }
        traces.record(traceRunId, parentStep, ActorType.LLM, TraceEventType.LLM_RESPONSE, stepName,
                TraceRecorder.StepPayload.builder()
                        .status("FAILED")
                        .promptVersion(promptVersion)
                        .attempt(attempt)
                        .errorMessage(ex.getMessage())
                        .outputSummary(ex instanceof LlmPermanentException
                                ? "permanent provider failure; not retried"
                                : "transient provider failure")
                        .build());
    }

    /** Exponential backoff: base * 2^(attempt-1), capped. */
    long backoffMillis(int attempt) {
        long base = props.retryBackoffMillis();
        if (base <= 0) {
            return 0;
        }
        long delay = base * (1L << Math.min(attempt - 1, 10));
        return Math.min(delay, MAX_BACKOFF_MILLIS);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            // Preserve the interrupt so the executor can wind the task down.
            Thread.currentThread().interrupt();
            throw new LlmTransientException("LLM retry interrupted", null, ex);
        }
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
