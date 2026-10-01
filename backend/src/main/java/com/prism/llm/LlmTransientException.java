package com.prism.llm;

/**
 * A failure that may succeed on retry: rate limit, upstream 5xx, timeout,
 * connection reset. Only these are eligible for bounded retry.
 */
public class LlmTransientException extends RuntimeException {

    private final Integer statusCode;

    public LlmTransientException(String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public LlmTransientException(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}
