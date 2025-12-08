package uk.gov.hmcts.cp.cdk.controllers;


import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.openapi.model.cdk.ErrorResponse;

import java.util.List;
import java.util.Set;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.hibernate.validator.internal.engine.path.PathImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@DisplayName("Global Exception Handler tests")
class GlobalExceptionHandlerTest {
    @Mock
    private Tracer tracer;
    @Mock
    private Span span;
    @Mock
    private TraceContext context;
    @Mock
    private HttpInputMessage httpInputMessage;
    @Mock
    private ConstraintViolation<Object> violation;
    @Mock
    private BindingResult bindingResult;
    @Mock
    private MethodArgumentNotValidException methodArgumentNotValidException;

    @BeforeEach
    public void setup() {
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
    }

    @Test
    @DisplayName("Handle Response Status Exception Should Return Error Response With Correct Fields")
    void handleResponseStatusExceptionShouldReturnErrorResponseWithCorrectFields() {
        when(context.traceId()).thenReturn("test-trace-id");

        final GlobalExceptionHandler handler = new GlobalExceptionHandler(tracer);

        String reason = "Test error";
        final ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, reason);

        // Act
        final var response = handler.onResponseStatus(ex);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ErrorResponse error = response.getBody();
        assertNotNull(error);
        assertEquals("404", error.getError());
        assertEquals(reason, error.getMessage());
        assertNotNull(error.getTimestamp());
        assertEquals("test-trace-id", error.getTraceId());
    }

    @Test
    void onValidation_shouldReturnBAD_REQUEST() {
        when(span.context().traceId()).thenReturn("tId");

        FieldError fieldError = new FieldError("obj", "caseId", "cannot be null");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        when(methodArgumentNotValidException.getBindingResult()).thenReturn(bindingResult);

        final GlobalExceptionHandler handler = new GlobalExceptionHandler(tracer);
        ResponseEntity<ErrorResponse> res = handler.onValidation(methodArgumentNotValidException);

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("400", res.getBody().getError());
        assertEquals("caseId cannot be null", res.getBody().getMessage());
        assertEquals("tId", res.getBody().getTraceId());
    }

    @Test
    void onConstraint_shouldReturnBAD_REQUEST() {
        when(span.context().traceId()).thenReturn("tId");

        when(violation.getPropertyPath()).thenReturn(PathImpl.createPathFromString("name"));
        when(violation.getMessage()).thenReturn("must not be blank");

        final ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        final GlobalExceptionHandler handler = new GlobalExceptionHandler(tracer);
        final ResponseEntity<ErrorResponse> res = handler.onConstraint(ex);

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("400", res.getBody().getError());
        assertEquals("name must not be blank", res.getBody().getMessage());
    }

    @Test
    void onUnreadable_shouldReturnMalformedBody() {
        when(span.context().traceId()).thenReturn("xyz");

        final HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad payload", httpInputMessage);

        final GlobalExceptionHandler handler = new GlobalExceptionHandler(tracer);
        final ResponseEntity<ErrorResponse> result = handler.onUnreadable(ex);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals("400", result.getBody().getError());
        assertEquals("Malformed request body", result.getBody().getMessage());
        assertEquals("xyz", result.getBody().getTraceId());
    }

    @Test
    void onUnexpected_shouldReturn500() {
        when(span.context().traceId()).thenReturn("zzz");

        final Exception ex = new RuntimeException("Error!!");

        final GlobalExceptionHandler handler = new GlobalExceptionHandler(tracer);
        final ResponseEntity<ErrorResponse> res = handler.onUnexpected(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertEquals("500", res.getBody().getError());
        assertEquals("Unexpected error", res.getBody().getMessage());
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void traceId_shouldReturnNull_ifTracerThrows() {
        when(tracer.currentSpan()).thenThrow(RuntimeException.class);

        final GlobalExceptionHandler handler = new GlobalExceptionHandler(tracer);
        final ResponseEntity<ErrorResponse> res = handler.onUnexpected(new RuntimeException());

        assertThat(res.getBody().getTraceId()).isNull();
    }
}