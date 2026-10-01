package com.prism.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-window in-memory rate limiter keyed by caller identity.
 *
 * <p>Scope note: this is per-instance state. Behind multiple replicas the
 * effective limit is {@code replicas * limit}. The README documents this
 * limitation; a shared store (Redis) would be required for a global limit.
 */
public class FixedWindowRateLimiter {

    private final Cache<String, Window> windows;
    private final int limit;

    public FixedWindowRateLimiter(int limit, Duration window) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.windows = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterAccess(window)
                .build();
    }

    public Decision tryAcquire(String key) {
        Window window = windows.get(key, k -> new Window());
        long count = window.counter.incrementAndGet();
        long remaining = Math.max(0, limit - count);
        return new Decision(count <= limit, remaining, limit);
    }

    public void reset() {
        windows.invalidateAll();
    }

    public record Decision(boolean allowed, long remaining, long limit) {
    }

    private static final class Window {
        private final AtomicLong counter = new AtomicLong();
    }
}
