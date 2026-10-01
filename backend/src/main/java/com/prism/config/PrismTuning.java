package com.prism.config;

/** Pipeline-wide tunable knobs grouped so the engines stay free of Spring config plumbing. */
public final class PrismTuning {

    private PrismTuning() {
    }

    public record Extraction(int maxTriplesPerChunk, int maxClaimsPerChunk, int maxSentenceLength) {
    }

    public record Chunking(int targetTokens, int overlapTokens, int charsPerToken) {
    }

    public record Retrieval(int topK, double minScore) {
    }

    public record ClaimRules(double penaltyPerWeasel, double penaltyPerAbsolute, double maxPenalty, String version) {
    }

    public record Graph(double pagerankDamping, int pagerankIterations, double pagerankTolerance, long cacheTtlSeconds) {
    }

    public record Debate(int maxRounds, int personaTimeoutSeconds) {
    }

    public record Trace(int retentionDays, String mode) {
    }

    public record Chat(int maxRetrievalChunks) {
    }

    public record AsyncPools(
            int pipelineCorePool,
            int pipelineMaxPool,
            int pipelineQueueCapacity,
            int llmCorePool,
            int llmMaxPool,
            int llmQueueCapacity) {
    }

    public record Recovery(int staleAfterMinutes, boolean enabled) {
    }
}
