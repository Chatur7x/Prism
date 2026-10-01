package com.prism.common.error;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Consistent error body. Stack traces are never included.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String traceId,
        List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }

    public static ApiErrorResponse of(int status, String error, String message, String path, String traceId) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, traceId, List.of());
    }

    public static ApiErrorResponse of(int status, String error, String message, String path,
                                      String traceId, Map<String, String> fieldErrors) {
        List<FieldViolation> violations = fieldErrors == null ? List.of() : fieldErrors.entrySet().stream()
                .map(e -> new FieldViolation(e.getKey(), e.getValue()))
                .toList();
        return new ApiErrorResponse(Instant.now(), status, error, message, path, traceId, violations);
    }
}
