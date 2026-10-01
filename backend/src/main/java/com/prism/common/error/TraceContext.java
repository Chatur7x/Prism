package com.prism.common.error;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Per-request correlation id. Populated by {@code RequestCorrelationFilter} and
 * echoed in error bodies and the X-Trace-Id response header.
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    public static String currentTraceId() {
        String existing = MDC.get(MDC_KEY);
        return existing != null ? existing : "no-trace";
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
