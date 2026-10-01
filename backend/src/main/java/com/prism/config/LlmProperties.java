package com.prism.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM provider configuration. Vendor-specific values live here so the
 * {@code LlmClient} interface can stay vendor-neutral.
 *
 * @param provider "openai" for any OpenAI-compatible HTTP server (incl. Ollama's
 *                 /v1 endpoint), or "fake" for the explicit offline test mode.
 */
@ConfigurationProperties(prefix = "prism.llm")
public record LlmProperties(
        String provider,
        String baseUrl,
        String apiKey,
        int connectTimeoutSeconds,
        int timeoutSeconds,
        double temperature,
        int maxTokens,
        String extractModel,
        String judgeModel,
        String debateModel,
        String synthesisModel,
        String chatModel,
        int maxRetries,
        long retryBackoffMillis) {

    public enum Purpose {
        EXTRACT, JUDGE, DEBATE, SYNTHESIS, CHAT
    }

    public String modelFor(Purpose purpose) {
        return switch (purpose) {
            case EXTRACT -> extractModel;
            case JUDGE -> judgeModel;
            case DEBATE -> debateModel;
            case SYNTHESIS -> synthesisModel;
            case CHAT -> chatModel;
        };
    }
}
