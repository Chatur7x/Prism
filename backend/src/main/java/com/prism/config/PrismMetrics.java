package com.prism.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Thin, low-cardinality metrics facade. Only aggregate counters and timers —
 * never document content, claim text, or any LLM payload.
 */
@Component
public class PrismMetrics {

    private final MeterRegistry registry;

    public PrismMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordLlmLatency(String operation, String model, long millis, boolean success) {
        Timer.builder("prism.llm.duration")
                .tag("operation", operation)
                .tag("model", model == null ? "unknown" : model)
                .tag("outcome", success ? "success" : "failure")
                .publishPercentileHistogram()
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    public void increment(String counter, String... tags) {
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException("tags must be key/value pairs");
        }
        registry.counter(counter, tags).increment();
    }

    public void recordPipelineDuration(String stage, long millis) {
        Timer.builder("prism.pipeline.duration")
                .tag("stage", stage)
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordDebateDuration(long millis) {
        Timer.builder("prism.debate.duration")
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }
}
