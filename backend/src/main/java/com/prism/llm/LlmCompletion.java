package com.prism.llm;

import com.prism.config.LlmProperties;

/**
 * A completed model call plus the metadata the Glass Box needs to record it.
 *
 * @param rawText   verbatim provider output, before any parsing. Stored for audit
 *                 and quarantine; never fed back into a prompt.
 * @param model     the model identifier actually used, for the trace.
 * @param durationMs wall-clock duration, for latency metrics.
 */
public record LlmCompletion(
        String rawText,
        String model,
        long durationMs,
        int attempt,
        boolean truncated) {
}
