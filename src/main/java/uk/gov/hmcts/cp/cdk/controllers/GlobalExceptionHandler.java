package uk.gov.hmcts.cp.cdk.controllers;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.openapi.model.cdk.ErrorResponse;

import java.util.Objects;
import java.util.stream.Collectors;

import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centralised exception mapping for REST controllers.
 * Keeps controllers clean and ensures consistent, traceable errors.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Tracer tracer;

    private String traceId() {
        String traceId = null;
        try {
            traceId = Objects.requireNonNull(tracer.currentSpan()).context().traceId();
        } catch (Exception ignored) {
        }
        return traceId;
    }

    private ErrorResponse base(final String code, final String message) {
        return new ErrorResponse()
                .error(code)
                .message(message)
                .timestamp(utcNow())
                .traceId(traceId());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> onResponseStatus(final ResponseStatusException responseStatusException) {
        log.warn("ResponseStatusException status={} reason={}", responseStatusException.getStatusCode(), responseStatusException.getReason(), responseStatusException);
        final ErrorResponse err = base(String.valueOf(responseStatusException.getStatusCode().value()),
                responseStatusException.getReason() != null ? responseStatusException.getReason() : responseStatusException.getMessage());
        return ResponseEntity.status(responseStatusException.getStatusCode()).body(err);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidation(final MethodArgumentNotValidException methodArgumentNotValidException) {
        final String details = methodArgumentNotValidException.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", details);
        final ErrorResponse err = base(String.valueOf(HttpStatus.BAD_REQUEST.value()), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> onConstraint(final ConstraintViolationException constraintViolationException) {
        final String details = constraintViolationException.getConstraintViolations()
                .stream()
                .map(GlobalExceptionHandler::formatViolation)
                .collect(Collectors.joining("; "));
        log.warn("Constraint violation: {}", details);
        final ErrorResponse err = base(String.valueOf(HttpStatus.BAD_REQUEST.value()), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadable(final HttpMessageNotReadableException httpMessageNotReadableException) {
        log.warn("Malformed request body: {}", httpMessageNotReadableException.getMessage());
        final ErrorResponse err = base(String.valueOf(HttpStatus.BAD_REQUEST.value()), "Malformed request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(final Exception exception) {
        log.error("Unexpected error", exception);
        final ErrorResponse err = base(String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value()), "Unexpected error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
    }

    private static String formatFieldError(final FieldError fieldError) {
        return fieldError.getField() + " " + (fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "is invalid");
    }

    private static String formatViolation(final ConstraintViolation<?> constraintViolation) {
        return constraintViolation.getPropertyPath() + " " + (constraintViolation.getMessage() != null ? constraintViolation.getMessage() : "is invalid");
    }
}
