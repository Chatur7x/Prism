package com.prism.llm;

/**
 * A failure that will not be fixed by retrying: bad credentials, forbidden
 * request, malformed configuration, or an unparseable response envelope.
 * Retrying these only wastes time and can look like an attack.
 */
public class LlmPermanentException extends RuntimeException {

    private final Integer statusCode;

    public LlmPermanentException(String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public LlmPermanentException(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}
