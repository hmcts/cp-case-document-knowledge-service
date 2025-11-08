package uk.gov.hmcts.cp.cdk.controllers;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.openapi.model.cdk.ErrorResponse;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("Global Exception Handler tests")
class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("Handle Response Status Exception Should Return Error Response With Correct Fields")
    void handleResponseStatusExceptionShouldReturnErrorResponseWithCorrectFields() {
        // Arrange
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);

        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("test-trace-id");

        GlobalExceptionHandler handler = new GlobalExceptionHandler(tracer);

        String reason = "Test error";
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, reason);

        // Act
        var response = handler.onResponseStatus(ex);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ErrorResponse error = response.getBody();
        assertNotNull(error);
        assertEquals("404", error.getError());
        assertEquals(reason, error.getMessage());
        assertNotNull(error.getTimestamp());
        assertEquals("test-trace-id", error.getTraceId());
    }
}