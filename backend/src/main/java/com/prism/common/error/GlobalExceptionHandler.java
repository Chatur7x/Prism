package com.prism.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single place where exceptions become HTTP. Nothing internal escapes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        return build(ex.code().status(), ex.code().name(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex,
                                                                  HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage() == null ? "invalid value" : fe.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> fields.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage() == null
                        ? "invalid request" : ge.getDefaultMessage()));
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCode.VALIDATION_ERROR.name(),
                "Request validation failed",
                request.getRequestURI(),
                TraceContext.currentTraceId(),
                fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        // Jackson messages can echo payload fragments; use a fixed message instead.
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST.name(),
                "Request body is malformed or contains unsupported types", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(),
                "Parameter '" + ex.getName() + "' has the wrong type", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(),
                "Missing required parameter '" + ex.getParameterName() + "'", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadSize(MaxUploadSizeExceededException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.name(),
                "Uploaded file exceeds the configured size limit", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED.name(),
                "Access denied for this resource", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuth(AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED.name(),
                "Authentication required", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                                  HttpServletRequest request) {
        // Two writers raced. The loser must see a conflict, not a silent overwrite.
        return build(HttpStatus.CONFLICT, ErrorCode.STATE_CONFLICT.name(),
                "The resource was modified concurrently; reload and retry", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                            HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, ErrorCode.CONFLICT.name(),
                "The operation violates a data integrity constraint", request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(NoHandlerFoundException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.name(), "No such endpoint", request);
    }

    /**
     * An unmatched path is a client mistake, not a server fault.
     *
     * <p>Spring Boot 3.2 replaced {@code NoHandlerFoundException} with
     * {@link NoResourceFoundException} for paths that reach the static-resource
     * handler, so the handler above alone no longer covers this case. Without
     * this, a typo in a path returns 500 and writes a full stack trace to the
     * log, which both misdirects an operator and makes a trivial client error
     * look like an incident.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                             HttpServletRequest request) {
        // The requested path is echoed because it is the client's own input and
        // naming it back is what makes the error actionable. Nothing else about
        // the internal resource tree is disclosed.
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.name(),
                "No such endpoint: " + ex.getResourcePath(), request);
    }

    /**
     * A wrong HTTP verb is a client mistake, not a server fault.
     *
     * <p>Without this handler the exception falls through to the generic
     * {@code Exception} handler and returns 500, telling an operator the service
     * broke when in fact the caller sent POST to a GET-only route. That
     * misdirection is exactly what an audit-focused system must not do.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        String allowed = ex.getSupportedHttpMethods() == null
                ? "the documented methods"
                : ex.getSupportedHttpMethods().stream()
                        .map(org.springframework.http.HttpMethod::name)
                        .sorted()
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("the documented methods");
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.VALIDATION_ERROR.name(),
                "Method " + ex.getMethod() + " is not supported here. Allowed: " + allowed, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Log the detail server-side; return an opaque message to the client.
        log.error("Unhandled exception on {} [{}]", request.getRequestURI(), TraceContext.currentTraceId(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(),
                "An unexpected internal error occurred", request);
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String message,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(
                status.value(), code, message, request.getRequestURI(), TraceContext.currentTraceId()));
    }
}
