package com.prism.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.LlmProperties;
import com.prism.config.PrismMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Talks to any OpenAI-compatible {@code /chat/completions} endpoint.
 *
 * <p>Works against local Ollama ({@code http://localhost:11434/v1}), hosted
 * OpenAI-compatible gateways, and self-hosted inference servers without code
 * changes — the only difference is configuration.
 *
 * <p>Active unless {@code prism.llm.provider=fake}, so exactly one
 * {@link LlmClient} exists in the context. There is no fallback chain: a
 * misconfigured deployment must fail rather than quietly answer from fixtures.
 */
@Component
@ConditionalOnProperty(name = "prism.llm.provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final int MAX_BODY_CHARS = 2_000_000;

    private final LlmProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final PrismMetrics metrics;

    public OpenAiCompatibleLlmClient(LlmProperties props, RestClient.Builder builder,
                                     ObjectMapper objectMapper, PrismMetrics metrics) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.metrics = metrics;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));

        this.restClient = builder
                .baseUrl(normalizeBaseUrl(props.baseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String providerName() {
        return "openai-compatible";
    }

    @Override
    public Map<String, String> describe() {
        Map<String, String> info = new LinkedHashMap<>();
        // Never expose the API key. Presence only.
        info.put("baseUrl", normalizeBaseUrl(props.baseUrl()));
        info.put("apiKeyConfigured", props.apiKey() != null && !props.apiKey().isBlank() ? "true" : "false");
        info.put("extractModel", props.extractModel());
        info.put("judgeModel", props.judgeModel());
        info.put("debateModel", props.debateModel());
        info.put("synthesisModel", props.synthesisModel());
        info.put("chatModel", props.chatModel());
        info.put("timeoutSeconds", String.valueOf(props.timeoutSeconds()));
        return info;
    }

    @Override
    public LlmCompletion complete(String system, String user, LlmProperties.Purpose purpose,
                                   Double temperatureOverride) {
        String model = props.modelFor(purpose);
        double temperature = temperatureOverride == null ? props.temperature() : temperatureOverride;
        long startNanos = System.nanoTime();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("max_tokens", props.maxTokens());
        payload.put("stream", false);
        payload.put("messages", java.util.List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));

        try {
            var response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (props.apiKey() != null && !props.apiKey().isBlank()) {
                            h.setBearerAuth(props.apiKey());
                        }
                    })
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String detail = readErrorBody(res);
                        int code = res.getStatusCode().value();
                        if (isTransient(code)) {
                            throw new LlmTransientException(
                                    "LLM provider returned " + code + ": " + truncate(detail, 400), code);
                        }
                        throw new LlmPermanentException(
                                "LLM provider rejected the request with " + code + ": " + truncate(detail, 400), code);
                    })
                    .body(String.class);

            long elapsed = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            metrics.recordLlmLatency(purpose.name().toLowerCase(java.util.Locale.ROOT), model, elapsed, true);

            if (response == null || response.isBlank()) {
                throw new LlmTransientException("LLM provider returned an empty body", null);
            }
            if (response.length() > MAX_BODY_CHARS) {
                response = response.substring(0, MAX_BODY_CHARS);
            }
            Extracted extracted = extractContent(response, model);
            if (extracted.truncated()) {
                log.warn("LLM response from model {} hit the token limit; output is truncated", model);
            }
            return new LlmCompletion(extracted.text(), model, elapsed, 1, extracted.truncated());

        } catch (LlmTransientException ex) {
            metrics.recordLlmLatency(purpose.name().toLowerCase(java.util.Locale.ROOT), model,
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), false);
            throw ex;
        } catch (LlmPermanentException ex) {
            metrics.recordLlmLatency(purpose.name().toLowerCase(java.util.Locale.ROOT), model,
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), false);
            throw ex;
        } catch (ResourceAccessException ex) {
            metrics.recordLlmLatency(purpose.name().toLowerCase(java.util.Locale.ROOT), model,
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), false);
            // Connection refused, DNS failure, socket timeout, read timeout.
            throw new LlmTransientException("LLM provider unreachable: " + ex.getMostSpecificCause().getMessage(),
                    null, ex.getMostSpecificCause());
        }
    }

    /** Provider message content plus whether the model stopped at the token limit. */
    private record Extracted(String text, boolean truncated) {
    }

    private Extracted extractContent(String responseBody, String model) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmPermanentException("LLM response envelope contained no choices", null);
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");
            // Some servers expose the answer under a different key; prefer content.
            JsonNode content = message.path("content");
            if (content.isMissingNode() || content.isNull()) {
                content = message.path("reasoning_content");
            }
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                throw new LlmPermanentException("LLM response contained no message content", null);
            }
            boolean truncated = "length".equalsIgnoreCase(choice.path("finish_reason").asText(""));

            if (content.isArray()) {
                // Some gateways return content as an array of typed parts.
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : content) {
                    if (part.isTextual()) {
                        sb.append(part.asText());
                    } else if (part.has("text")) {
                        sb.append(part.get("text").asText());
                    }
                }
                if (sb.isEmpty()) {
                    throw new LlmPermanentException("LLM response content array was empty", null);
                }
                return new Extracted(sb.toString(), truncated);
            }
            return new Extracted(content.asText(), truncated);
        } catch (LlmPermanentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmPermanentException("LLM response was not valid JSON: " + truncate(responseBody, 300),
                    null, ex);
        }
    }

    private String readErrorBody(Object response) {
        try {
            if (response instanceof org.springframework.http.client.ClientHttpResponse r) {
                return new String(r.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // Body already consumed or unreadable; status alone is enough.
        }
        return "no body";
    }

    private static boolean isTransient(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "none";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("LLM_BASE_URL is not configured");
        }
        String trimmed = baseUrl.trim();
        // Accept a base URL with or without the /v1 suffix; add it exactly once.
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.endsWith("/v1")) {
            trimmed = trimmed + "/v1";
        }
        return trimmed;
    }
}
