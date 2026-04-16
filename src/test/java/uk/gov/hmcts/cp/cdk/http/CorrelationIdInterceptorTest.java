package uk.gov.hmcts.cp.cdk.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

class CorrelationIdInterceptorTest {

    private final CorrelationIdInterceptor interceptor = new CorrelationIdInterceptor();

    @Test
    void shouldUseExistingCorrelationId_whenHeaderPresent() throws IOException {
        // given
        final HttpRequest request = mock(HttpRequest.class);
        final HttpHeaders headers = new HttpHeaders();
        headers.add(CorrelationIdInterceptor.HEADER, "existing-id");

        when(request.getHeaders()).thenReturn(headers);

        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        final ClientHttpResponse response = mock(ClientHttpResponse.class);

        when(execution.execute(any(), any())).thenAnswer(invocation -> {
            // assert MDC is set before execution
            assertEquals("existing-id", MDC.get(CorrelationIdInterceptor.MDC_KEY));
            return response;
        });

        // when
        final ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        // then
        assertSame(response, result);
        assertEquals("existing-id", headers.getFirst(CorrelationIdInterceptor.HEADER));

        // MDC should be cleared after execution
        assertNull(MDC.get(CorrelationIdInterceptor.MDC_KEY));
    }

    @Test
    void shouldGenerateCorrelationId_whenHeaderMissing() throws IOException {
        // given
        final HttpRequest request = mock(HttpRequest.class);
        final HttpHeaders headers = new HttpHeaders();

        when(request.getHeaders()).thenReturn(headers);

        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        final ClientHttpResponse response = mock(ClientHttpResponse.class);

        when(execution.execute(any(), any())).thenAnswer(invocation -> {
            final String cid = headers.getFirst(CorrelationIdInterceptor.HEADER);

            assertNotNull(cid);
            assertFalse(cid.isBlank());

            // validate UUID format
            assertDoesNotThrow(() -> UUID.fromString(cid));

            // MDC should contain same value
            assertEquals(cid, MDC.get(CorrelationIdInterceptor.MDC_KEY));

            return response;
        });

        // when
        final ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        // then
        assertSame(response, result);

        // MDC cleared
        assertNull(MDC.get(CorrelationIdInterceptor.MDC_KEY));
    }

    @Test
    void shouldGenerateCorrelationId_whenHeaderBlank() throws IOException {
        // given
        final HttpRequest request = mock(HttpRequest.class);
        final HttpHeaders headers = new HttpHeaders();
        headers.add(CorrelationIdInterceptor.HEADER, "   ");

        when(request.getHeaders()).thenReturn(headers);

        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        final ClientHttpResponse response = mock(ClientHttpResponse.class);

        when(execution.execute(any(), any())).thenAnswer(invocation -> {
            String cid = headers.getFirst(CorrelationIdInterceptor.HEADER);

            assertNotNull(cid);
            assertFalse(cid.isBlank());
            return response;
        });

        // when
        interceptor.intercept(request, new byte[0], execution);

        // then
        assertNull(MDC.get(CorrelationIdInterceptor.MDC_KEY));
    }

    @Test
    void shouldClearMdc_evenWhenExecutionThrows() throws IOException {
        // given
        final HttpRequest request = mock(HttpRequest.class);
        final HttpHeaders headers = new HttpHeaders();

        when(request.getHeaders()).thenReturn(headers);

        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);

        when(execution.execute(any(), any())).thenAnswer(invocation -> {
            assertNotNull(MDC.get(CorrelationIdInterceptor.MDC_KEY));
            throw new RuntimeException("boom");
        });

        // when / then
        assertThrows(RuntimeException.class,
                () -> interceptor.intercept(request, new byte[0], execution));

        // MDC must still be cleared
        assertNull(MDC.get(CorrelationIdInterceptor.MDC_KEY));
    }
}