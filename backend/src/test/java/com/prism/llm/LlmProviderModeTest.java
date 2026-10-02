package com.prism.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.LlmProperties;
import com.prism.config.PrismMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline fixture must never be reachable by accident.
 *
 * <p>This is the one place a "quality" number could be produced by something that
 * is not a model while looking entirely normal. Every other guard in PRISM fails
 * loudly; this class fails quietly, and its output is a number someone might act
 * on. So the selection rule is asserted rather than trusted, and the assertions
 * are about the annotations themselves, not about a running context -- the
 * property is what the container consults, so the property is what to check.
 */
class LlmProviderModeTest {

    private static String conditionalValue(Class<?> type) {
        var annotation = type.getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        return annotation == null ? null : annotation.havingValue();
    }

    private static boolean matchesIfMissing(Class<?> type) {
        var annotation = type.getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        return annotation != null && annotation.matchIfMissing();
    }

    @Test
    @DisplayName("the offline fixture activates only on an explicit provider=fake")
    void fakeRequiresExplicitOptIn() {
        assertEquals("fake", conditionalValue(FakeLlmClient.class),
                "the offline fixture must require provider=fake and nothing else");
        assertFalse(matchesIfMissing(FakeLlmClient.class),
                "the offline fixture must never be the default; if it were, an unset or "
                        + "misspelt provider value would silently downgrade a real deployment "
                        + "to canned responses");
    }

    @Test
    @DisplayName("an unset or misspelt provider selects the real client, not the fixture")
    void realProviderIsTheDefault() {
        // The counterpart to the assertion above, and the more important one. An
        // operator who sets LLM_PROVIDER=openai-compatibl by mistake must get a
        // failure that says so, not a working system that is secretly a fixture.
        assertEquals("openai", conditionalValue(OpenAiCompatibleLlmClient.class));
        assertTrue(matchesIfMissing(OpenAiCompatibleLlmClient.class),
                "the real client must be the matchIfMissing default, so an unset or invalid "
                        + "provider value cannot resolve to the fixture");
    }

    @Test
    @DisplayName("the two providers are mutually exclusive on their declared value")
    void providersCannotBothActivate() {
        assertNotEquals(conditionalValue(FakeLlmClient.class),
                conditionalValue(OpenAiCompatibleLlmClient.class));
    }

    @Test
    @DisplayName("the fixture declares itself as test mode on every surface")
    void fixtureSelfIdentifies() {
        // providerName and describe() are what the admin API and the evaluation
        // report read. If either stops saying "fake", a fixture run becomes
        // indistinguishable from a model run in the artifact of record.
        var fixture = new FakeLlmClient(new ObjectMapper());
        assertEquals("fake", fixture.providerName());

        Map<String, String> info = fixture.describe();
        assertEquals("true", info.get("testMode"));
        assertTrue(info.get("warning").contains("FAKE / TEST MODE"),
                "the warning must name the mode, not merely hint at it: " + info.get("warning"));
        // Must not claim to be a model, or carry anything resembling a quality claim.
        assertFalse(info.toString().toLowerCase(java.util.Locale.ROOT).contains("accurate"));
    }

    @Test
    @DisplayName("the real provider declares test mode false, and never carries the fixture warning")
    void realProviderSelfIdentifies() {
        Map<String, String> info = realClient("key").describe();
        assertEquals("false", info.get("testMode"));
        assertEquals("openai-compatible", info.get("provider"));
        // The fixture warning must never appear on a real provider. Its presence
        // would mean the wrong bean was wired, which is precisely the failure
        // this whole class is about.
        assertFalse(info.containsKey("warning"));
    }

    @Test
    @DisplayName("the real provider never leaks the API key into its description")
    void apiKeyIsNeverDescribed() {
        // describe() reaches the admin API, so a key appearing here would be a
        // credential disclosure through a debugging convenience.
        Map<String, String> info = realClient("sk-secret-value").describe();
        assertFalse(info.toString().contains("sk-secret-value"),
                "describe() must never expose the API key");
        // Presence only, which is what makes it safe to publish at all.
        assertEquals("true", info.get("apiKeyConfigured"));
    }

    /** A real client wired for metadata inspection. Never used to make a request. */
    private static OpenAiCompatibleLlmClient realClient(String apiKey) {
        LlmProperties props = new LlmProperties("openai", "https://example.invalid/v1", apiKey,
                5, 60, 0.0, 1024, "m", "m", "m", "m", "m", 3, 100L);
        return new OpenAiCompatibleLlmClient(props, RestClient.builder(),
                new ObjectMapper(), new PrismMetrics(new SimpleMeterRegistry()));
    }
}