package com.prism.llm;

import com.prism.config.LlmProperties;

import java.util.Map;

/**
 * Vendor-neutral model interface.
 *
 * <p>Every caller goes through this. No business service may know that the
 * transport is HTTP, nor which vendor is behind the endpoint.
 */
public interface LlmClient {

    /**
     * Performs a completion.
     *
     * <p>Implementations must throw {@link LlmTransientException} for retryable
     * failures (429, 5xx, timeouts, connection resets) and
     * {@link LlmPermanentException} for failures that will never succeed on
     * retry (401, 403, 400, unparseable response envelope).
     *
     * @param system   privileged instructions. Source documents must never be
     *                 concatenated into this field.
     * @param user     the user-role turn, including any clearly delimited
     *                 untrusted DATA blocks.
     */
    LlmCompletion complete(String system, String user, LlmProperties.Purpose purpose, Double temperatureOverride);

    /** Convenience overload using the configured default temperature. */
    default LlmCompletion complete(String system, String user, LlmProperties.Purpose purpose) {
        return complete(system, user, purpose, null);
    }

    /** Identifies the provider in diagnostics and the trace. */
    String providerName();

    /** Extra provider metadata for health output. Never includes the API key. */
    Map<String, String> describe();
}
