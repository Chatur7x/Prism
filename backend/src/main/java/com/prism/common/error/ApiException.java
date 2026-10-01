package com.prism.common.error;

/**
 * The single exception type the application layer is allowed to throw for
 * expected, client-visible failures. Carries a closed-set {@link ErrorCode}
 * so the HTTP surface is stable and never leaks internal types.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found: " + id);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException accessDenied(String message) {
        return new ApiException(ErrorCode.ACCESS_DENIED, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException stateConflict(String message) {
        return new ApiException(ErrorCode.STATE_CONFLICT, message);
    }

    public static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }
}
