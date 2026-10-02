package com.prism.llm;

import com.prism.config.LlmProperties;
import com.prism.trace.TraceRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What PRISM does when the provider misbehaves.
 *
 * <p>Nothing in the suite previously made a provider fail. Every happy path in
 * this repository had been exercised against a fixture that always answers, which
 * means the retry policy, the retry bound, the permanent-failure short circuit
 * and the quarantine path were all live code with no test reaching them. Those are
 * exactly the paths that matter when a real endpoint starts returning 429s at
 * three in the morning, so this class exists to make them reachable.
 *
 * <p>Two properties are asserted that are easy to get wrong and expensive to get
 * wrong:
 *
 * <ul>
 *   <li><b>Bounded.</b> A provider that always fails must produce exactly
 *       {@code maxRetries} attempts and then stop. Unbounded retry looks like
 *       resilience and is actually a hang with extra log lines.</li>
 *   <li><b>Not retried at all when permanent.</b> A 401 or a 400 will never
 *       succeed on a second attempt, so retrying it burns the budget, delays the
 *       quarantine, and in the limit turns a misconfiguration into an
 *       outage.</li>
 * </ul>
 */
class LlmFailureRecoveryTest {

    private static final String SYSTEM = "system";
    private static final String USER = "user";

    /** A completion whose body parses cleanly, so only the transport is under test. */
    private static final String VALID_BODY = "{\"triples\":[],\"claims\":[]}";

    /**
     * A provider that runs a script before answering.
     *
     * <p>LlmClient declares three abstract methods, so it is not a functional
     * interface and cannot be a lambda. {@code steps[i]} runs before the i-th
     * response; throw from it to make that attempt fail, and pass {@code null} to
     * let that attempt succeed.
     *
     * <p>Beyond the script, the <b>last</b> step governs every remaining call.
     * So a single throwing step models a provider that never recovers, while a
     * trailing {@code null} models one that recovers on that attempt. Without
     * this, a one-step "always fails" script quietly succeeds on the second call
     * and the retry bound is never actually exercised -- which is exactly what
     * happened the first time this class was written.
     */
    private static final class Stub implements LlmClient {

        private final Runnable[] steps;
        private final AtomicInteger calls = new AtomicInteger();

        private Stub(Runnable... steps) {
            this.steps = steps;
        }

        @Override
        public LlmCompletion complete(String system, String user, LlmProperties.Purpose purpose,
                                      Double temperatureOverride) {
            int i = calls.getAndIncrement();
            Runnable step = i < steps.length ? steps[i]
                    : (steps.length == 0 ? null : steps[steps.length - 1]);
            if (step != null) {
                step.run();
            }
            return new LlmCompletion(VALID_BODY, "m", 1L, i + 1, false);
        }

        @Override
        public String providerName() {
            return "stub";
        }

        @Override
        public Map<String, String> describe() {
            return Map.of("provider", "stub", "testMode", "true");
        }
    }

    /** 1 ms backoff so the suite is not dominated by sleeping. */
    private static LlmProperties props(int maxRetries) {
        return new LlmProperties("openai", "https://example.invalid/v1", "key",
                2, 5, 0.0, 1024, "m", "m", "m", "m", "m", maxRetries, 1L);
    }

    private static RetryingLlmService service(LlmClient client, int maxRetries) {
        return new RetryingLlmService(client, props(maxRetries), mock(TraceRecorder.class));
    }

    private static LlmCompletion call(RetryingLlmService svc, String step) {
        return svc.complete(SYSTEM, USER, LlmProperties.Purpose.EXTRACT, "EXTRACT_V1",
                step, null, null);
    }

    // ---- bounded retry ------------------------------------------------------

    @Test
    @DisplayName("a transient failure is retried up to maxRetries and then gives up")
    void transientFailureIsBoundedThenThrown() {
        AtomicInteger attempts = new AtomicInteger();
        LlmClient alwaysTransient = new Stub(() -> {
            attempts.incrementAndGet();
            throw new LlmTransientException("upstream timeout", 504);
        });

        assertThatThrownBy(() -> call(service(alwaysTransient, 3), "step"))
                .isInstanceOf(LlmTransientException.class)
                // The message must say how many attempts were made, because an
                // operator reading the trace needs to distinguish "gave up after
                // three" from "never tried".
                .hasMessageContaining("3 attempts");

        assertEquals(3, attempts.get(),
                "retry must be bounded by maxRetries; unbounded retry is a hang");
    }

    @Test
    @DisplayName("a 429 is treated as transient and retried")
    void rateLimitIsRetried() {
        // Rate limiting is the most common real failure. Treating it as permanent
        // would fail the chunk immediately and quarantine a document for
        // something that resolves on its own in seconds.
        AtomicInteger attempts = new AtomicInteger();
        LlmClient rateLimitedTwice = new Stub(
                () -> {
                    attempts.incrementAndGet();
                    throw new LlmTransientException("rate limited", 429);
                },
                () -> {
                    attempts.incrementAndGet();
                    throw new LlmTransientException("rate limited", 429);
                },
                null);

        LlmCompletion completion = call(service(rateLimitedTwice, 5), "step");

        assertEquals(2, attempts.get(), "two rate-limited responses, then a success");
        assertEquals(3, completion.attempt(), "the completion must report which attempt won");
    }

    @Test
    @DisplayName("a 5xx is retried, then surfaces as transient when exhausted")
    void serverErrorIsRetriedThenTransient() {
        AtomicInteger attempts = new AtomicInteger();
        LlmClient broken = new Stub(() -> {
            attempts.incrementAndGet();
            throw new LlmTransientException("internal error", 500);
        });

        assertThatThrownBy(() -> call(service(broken, 2), "step"))
                .isInstanceOf(LlmTransientException.class);

        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("a success after retries does not surface the earlier failures")
    void successAfterRetryIsClean() {
        LlmClient flaky = new Stub(
                () -> {
                    throw new LlmTransientException("connection reset", 502);
                },
                () -> {
                    throw new LlmTransientException("connection reset", 502);
                },
                null);

        LlmCompletion completion = call(service(flaky, 4), "step");

        // If the transient exception leaked past the retry loop, callers would see
        // a failure even though the provider eventually answered.
        assertNotNull(completion.rawText());
        assertEquals(3, completion.attempt());
    }

    // ---- permanent failures must not retry ----------------------------------

    @Test
    @DisplayName("a permanent failure is not retried at all")
    void permanentFailureShortCircuits() {
        for (int status : List.of(400, 401, 403, 404)) {
            AtomicInteger attempts = new AtomicInteger();
            LlmClient permanent = new Stub(() -> {
                attempts.incrementAndGet();
                throw new LlmPermanentException("refused", status);
            });

            assertThatThrownBy(() -> call(service(permanent, 5), "step"))
                    .isInstanceOf(LlmPermanentException.class)
                    .hasMessageContaining("refused");

            assertEquals(1, attempts.get(),
                    "status " + status + " will never succeed on retry, so retrying it turns "
                            + "a misconfiguration into an outage");
        }
    }

    @Test
    @DisplayName("a permanent failure after a transient one still stops immediately")
    void permanentAfterTransientStopsAtTheFailure() {
        AtomicInteger attempts = new AtomicInteger();
        LlmClient flakyThenPermanent = new Stub(
                () -> {
                    attempts.incrementAndGet();
                    throw new LlmTransientException("reset", 502);
                },
                () -> {
                    attempts.incrementAndGet();
                    throw new LlmPermanentException("bad request", 400);
                });

        assertThatThrownBy(() -> call(service(flakyThenPermanent, 5), "step"))
                .isInstanceOf(LlmPermanentException.class);

        assertEquals(2, attempts.get(),
                "the transient failure earns one retry; the permanent one earns none");
    }

    // ---- retry accounting ---------------------------------------------------

    @Test
    @DisplayName("maxRetries below one still makes exactly one attempt")
    void retriesFloorAtOne() {
        AtomicInteger attempts = new AtomicInteger();
        LlmClient alwaysTransient = new Stub(() -> {
            attempts.incrementAndGet();
            throw new LlmTransientException("timeout", 504);
        });

        assertThatThrownBy(() -> call(service(alwaysTransient, 0), "step"))
                .isInstanceOf(LlmTransientException.class);

        // A misconfigured 0 must mean "try once", not "never try". Skipping the
        // call entirely would silently drop every extraction.
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("backoff grows but stays capped")
    void backoffIsCapped() {
        RetryingLlmService svc = service(new Stub(), 3);

        long first = svc.backoffMillis(1);
        long second = svc.backoffMillis(2);

        assertThat(first).as("first delay is the configured base").isEqualTo(1L);
        assertThat(second).as("second delay is doubled").isEqualTo(2L);

        for (int attempt = 1; attempt <= 40; attempt++) {
            long delay = svc.backoffMillis(attempt);
            assertThat(delay)
                    .as("backoff must never be zero, or a failing provider is hammered")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(delay)
                    .as("backoff must stay bounded; an uncapped doubling reaches hours")
                    .isLessThanOrEqualTo(15_000L);
        }
    }

    // ---- the failure is observable -----------------------------------------

    @Test
    @DisplayName("a null trace run id is handled, not dereferenced")
    void failureWithNoTraceIsSafe() {
        // The evaluation harness deliberately runs with no trace, so it must never
        // make the retry loop throw a NullPointerException on the way out. A
        // NullPointerException here would mask the real provider failure and be
        // recorded as the wrong reason.
        TraceRecorder traces = mock(TraceRecorder.class);
        LlmClient alwaysTransient = new Stub(() -> {
            throw new LlmTransientException("timeout", 504);
        });

        RetryingLlmService svc = new RetryingLlmService(alwaysTransient, props(2), traces);

        assertThatThrownBy(() -> call(svc, "step"))
                .isInstanceOf(LlmTransientException.class)
                .isNotInstanceOf(NullPointerException.class);

        verifyNoInteractions(traces);
    }

    @Test
    @DisplayName("the failure message names the step and the cause")
    void failureMessageIdentifiesTheStep() {
        LlmClient alwaysTransient = new Stub(() -> {
            throw new LlmTransientException("connection reset by peer", 503);
        });

        RetryingLlmService svc = service(alwaysTransient, 1);

        assertThatThrownBy(() -> svc.complete(SYSTEM, USER, LlmProperties.Purpose.JUDGE,
                "JUDGE_V1", "judge:claim:42", null, null))
                .hasMessageContaining("judge:claim:42")
                .hasMessageContaining("connection reset by peer");
    }
}