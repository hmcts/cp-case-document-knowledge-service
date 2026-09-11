package uk.gov.hmcts.cp.cdk.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.correlation.CorrelationIds;
import uk.gov.hmcts.cp.openapi.model.cdk.ErrorResponse;

import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.hibernate.validator.internal.engine.path.PathImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler tests (DD-43183 Story 4 — rewritten, no Tracer dependency)")
class GlobalExceptionHandlerTest {

    @Mock
    private HttpInputMessage httpInputMessage;
    @Mock
    private ConstraintViolation<Object> violation;
    @Mock
    private BindingResult bindingResult;
    @Mock
    private MethodArgumentNotValidException methodArgumentNotValidException;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @BeforeEach
    void setUp() {
        MDC.put(CorrelationIds.MDC_KEY, "ambient-correlation-id");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("AC-001/AC-003: onResponseStatus's traceId equals the ambient correlation ID")
    void onResponseStatus_traceIdEqualsAmbientCorrelationId() {
        final String reason = "Test error";
        final ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, reason);

        final ResponseEntity<ErrorResponse> response = handler.onResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        final ErrorResponse error = response.getBody();
        assertThat(error).isNotNull();
        assertThat(error.getError()).isEqualTo("404");
        assertThat(error.getMessage()).isEqualTo(reason);
        assertThat(error.getTimestamp()).isNotNull();
        assertThat(error.getTraceId()).isEqualTo("ambient-correlation-id");
    }

    @Test
    @DisplayName("AC-003: onValidation's traceId equals the ambient correlation ID")
    void onValidation_traceIdEqualsAmbientCorrelationId() {
        final FieldError fieldError = new FieldError("obj", "caseId", "cannot be null");
        org.mockito.Mockito.when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        org.mockito.Mockito.when(methodArgumentNotValidException.getBindingResult()).thenReturn(bindingResult);

        final ResponseEntity<ErrorResponse> res = handler.onValidation(methodArgumentNotValidException);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getError()).isEqualTo("400");
        assertThat(res.getBody().getMessage()).isEqualTo("caseId cannot be null");
        assertThat(res.getBody().getTraceId()).isEqualTo("ambient-correlation-id");
    }

    @Test
    @DisplayName("AC-003: onConstraint's traceId equals the ambient correlation ID")
    void onConstraint_traceIdEqualsAmbientCorrelationId() {
        org.mockito.Mockito.when(violation.getPropertyPath()).thenReturn(PathImpl.createPathFromString("name"));
        org.mockito.Mockito.when(violation.getMessage()).thenReturn("must not be blank");
        final ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        final ResponseEntity<ErrorResponse> res = handler.onConstraint(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getError()).isEqualTo("400");
        assertThat(res.getBody().getMessage()).isEqualTo("name must not be blank");
        assertThat(res.getBody().getTraceId()).isEqualTo("ambient-correlation-id");
    }

    @Test
    @DisplayName("AC-003: onUnreadable's traceId equals the ambient correlation ID")
    void onUnreadable_traceIdEqualsAmbientCorrelationId() {
        final HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad payload", httpInputMessage);

        final ResponseEntity<ErrorResponse> result = handler.onUnreadable(ex);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getMessage()).isEqualTo("Malformed request body");
        assertThat(result.getBody().getTraceId()).isEqualTo("ambient-correlation-id");
    }

    @Test
    @DisplayName("AC-003: onUnexpected's traceId equals the ambient correlation ID")
    void onUnexpected_traceIdEqualsAmbientCorrelationId() {
        final ResponseEntity<ErrorResponse> res = handler.onUnexpected(new RuntimeException("Error!!"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().getMessage()).isEqualTo("Unexpected error");
        assertThat(res.getBody().getTraceId()).isEqualTo("ambient-correlation-id");
    }

    @Test
    @DisplayName("AC-003: onMethodNotSupported's traceId equals the ambient correlation ID")
    void onMethodNotSupported_traceIdEqualsAmbientCorrelationId() throws Exception {
        final HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        final ResponseEntity<ErrorResponse> res = handler.onMethodNotSupported(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getBody().getTraceId()).isEqualTo("ambient-correlation-id");
    }

    @Test
    @DisplayName("AC-001: with no ambient correlation value at all, traceId is still non-blank — "
            + "never the empty string the historical defect returned")
    void traceIdIsNonBlankEvenWithNoAmbientValue() {
        MDC.clear();

        final ResponseEntity<ErrorResponse> res = handler.onUnexpected(new RuntimeException());

        assertThat(res.getBody().getTraceId()).isNotBlank();
    }

    @Test
    @DisplayName("AC-004: GlobalExceptionHandler has no Tracer dependency at all")
    void noTracerConstructorDependency() {
        assertThat(GlobalExceptionHandler.class.getDeclaredConstructors()).allSatisfy(constructor ->
                assertThat(constructor.getParameterCount()).isZero());
    }
}
