package com.prism.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.prism.config.LlmProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the constrained-generation fallback over real HTTP without touching
 * any provider: when the server rejects {@code response_format: json_object}
 * with {@code json_validate_failed} (observed on Groq
 * {@code openai/gpt-oss-20b} as HTTP 400 with an empty
 * {@code failed_generation}), the client retries exactly once without the
 * constraint instead of quarantining a request that was never the model's
 * fault. Any other 400 propagates untouched.
 *
 * <p>Uses the JDK's built-in HTTP server: no extra test dependencies, fully
 * deterministic scripted responses, and the request bodies are asserted, so
 * the second attempt is proven to drop the format.
 */
class OpenAiCompatibleLlmClientTest {

    private static final String ENVELOPE =
            "{\"choices\":[{\"message\":{\"content\":\"{\\\"triples\\\":[],\\\"claims\\\":[]}\"},"
                    + "\"finish_reason\":\"stop\"}]}";
    private static final String VALIDATE_FAILED =
            "{\"error\":{\"message\":\"Failed to validate JSON. Please adjust your prompt.\","
                    + "\"type\":\"invalid_request_error\",\"code\":\"json_validate_failed\"}}";
    private static final String UNKNOWN_MODEL =
            "{\"error\":{\"message\":\"Unknown model\",\"code\":\"model_not_found\"}}";

    private HttpServer server;
    private final Deque<StubResponse> script = new ArrayDeque<>();
    private final List<String> requestBodies = new ArrayList<>();
    private OpenAiCompatibleLlmClient client;

    private record StubResponse(int status, String body) {
    }

    private static LlmProperties props(String baseUrl) {
        return new LlmProperties("openai", baseUrl, "key",
                2, 5, 0.0, 1024, "m", "m", "m", "m", "m", 3, 1L, 0L, true);
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            synchronized (requestBodies) {
                requestBodies.add(new String(request, StandardCharsets.UTF_8));
            }
            StubResponse next;
            synchronized (script) {
                next = script.pollFirst();
            }
            if (next == null) {
                next = new StubResponse(500, "{\"error\":\"no scripted response\"}");
            }
            byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(next.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        client = new OpenAiCompatibleLlmClient(props(baseUrl), RestClient.builder(),
                new ObjectMapper(),
                new com.prism.config.PrismMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private LlmCompletion call() {
        return client.complete("system", "user", LlmProperties.Purpose.EXTRACT, null);
    }

    @Test
    @DisplayName("json_validate_failed retries once without the format and returns the reply")
    void jsonValidateFailedFallsBackOnce() {
        script.add(new StubResponse(400, VALIDATE_FAILED));
        script.add(new StubResponse(200, ENVELOPE));

        LlmCompletion completion = call();

        assertThat(completion.rawText()).contains("\"claims\"");
        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(0)).contains("response_format");
        assertThat(requestBodies.get(1)).doesNotContain("response_format");
    }

    @Test
    @DisplayName("any other 400 propagates without a second request")
    void otherBadRequestPropagates() {
        script.add(new StubResponse(400, UNKNOWN_MODEL));

        assertThatThrownBy(this::call)
                .isInstanceOf(LlmPermanentException.class)
                .hasMessageContaining("400");
        assertThat(requestBodies).hasSize(1);
    }

    @Test
    @DisplayName("a first-try success makes exactly one request")
    void successMakesOneRequest() {
        script.add(new StubResponse(200, ENVELOPE));

        LlmCompletion completion = call();

        assertThat(completion.rawText()).contains("\"claims\"");
        assertThat(requestBodies).hasSize(1);
    }
}
