package com.prism.common.error;

import org.springframework.http.HttpStatus;

/**
 * Closed set of client-visible error codes. Never invent codes at a call site.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    STATE_CONFLICT(HttpStatus.CONFLICT),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    LLM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    LLM_INVALID_OUTPUT(HttpStatus.UNPROCESSABLE_ENTITY),
    CITATION_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),
    PIPELINE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
