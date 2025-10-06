package uk.gov.hmcts.cp.cdk.controllers;

import io.micrometer.tracing.Tracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.util.TimeUtils;
import uk.gov.hmcts.cp.openapi.model.cdk.ErrorResponse;

import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Tracer tracer;

    public GlobalExceptionHandler(final Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(final ResponseStatusException responseStatusException) {
        final ErrorResponse error = new ErrorResponse()
                .error(String.valueOf(responseStatusException.getStatusCode().value()))
                .message(responseStatusException.getReason() != null ? responseStatusException.getReason() : responseStatusException.getMessage())
                .timestamp(TimeUtils.utcNow())
                .traceId(Objects.requireNonNull(tracer.currentSpan()).context().traceId());


        return ResponseEntity
                .status(responseStatusException.getStatusCode())
                .body(error);
    }
}