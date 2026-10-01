package com.prism.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from application.yml so tuning values are testable without Spring contexts. */
@ConfigurationProperties(prefix = "prism")
public record PrismTuningProperties(
        PrismTuning.Extraction extraction,
        PrismTuning.Chunking chunking,
        PrismTuning.Retrieval retrieval,
        PrismTuning.ClaimRules rules,
        PrismTuning.Graph graph,
        PrismTuning.Debate debate,
        PrismTuning.Trace trace,
        PrismTuning.Chat chat,
        PrismTuning.AsyncPools async,
        PrismTuning.Recovery recovery) {
}
