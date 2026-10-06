package com.prism.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Explicit, bounded thread pools and caches.
 *
 * <p>Two separate executors so a slow LLM burst can never starve document
 * ingestion, and so saturation shows up in distinct metrics.
 */
@Configuration
public class AsyncAndCacheConfig {

    public static final String PIPELINE_EXECUTOR = "pipelineExecutor";
    public static final String LLM_EXECUTOR = "llmExecutor";

    @Bean(name = PIPELINE_EXECUTOR)
    public ThreadPoolTaskExecutor pipelineExecutor(PrismTuningProperties tuning) {
        PrismTuning.AsyncPools pools = tuning.async();
        return baseExecutor("prism-pipeline-", pools.pipelineCorePool(), pools.pipelineMaxPool(),
                pools.pipelineQueueCapacity());
    }

    @Bean(name = LLM_EXECUTOR)
    public ThreadPoolTaskExecutor llmExecutor(PrismTuningProperties tuning) {
        PrismTuning.AsyncPools pools = tuning.async();
        // Debate personas must run concurrently, so LLM core pool is sized for
        // at least three simultaneous persona calls.
        return baseExecutor("prism-llm-", pools.llmCorePool(), pools.llmMaxPool(),
                pools.llmQueueCapacity());
    }

    private ThreadPoolTaskExecutor baseExecutor(String prefix, int core, int max, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queueCapacity);
        // Reject rather than grow without bound. Callers handle RejectedExecutionException
        // by leaving the job recoverable instead of silently dropping work.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(2_000)
                .expireAfterWrite(Duration.ofMinutes(5)));
        // Explicit allowlist: no dynamic cache names, so a typo cannot silently
        // create an unbounded cache.
        //
        // The `*View` caches are separate from their raw counterparts because the
        // raw and view projections of the same graph share every input — same
        // corpus, same scope, same verified-id set — and so would share a cache
        // key. Two methods under one cache name with one key means whichever runs
        // first populates it and the other reads the other's type back through
        // an unchecked cast, which is a ClassCastException at runtime rather than
        // a compile error. Splitting the names makes each projection cache only
        // its own shape.
        manager.setCacheNames(java.util.Set.of(
                "graphSnapshot", "pagerank", "pagerankView", "communities", "communitiesView",
                "verdictSummary", "skepticBrief"));
        return manager;
    }
}
