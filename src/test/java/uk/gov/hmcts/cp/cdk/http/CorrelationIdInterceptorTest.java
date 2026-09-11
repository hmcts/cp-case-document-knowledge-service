package uk.gov.hmcts.cp.cdk.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.correlation.CorrelationIds;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

@DisplayName("CorrelationIdInterceptor tests (DD-43183 Story 2 — rewritten for AC-002/AC-003/AC-008)")
class CorrelationIdInterceptorTest {

    private final CorrelationIdInterceptor interceptor = new CorrelationIdInterceptor();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("AC-002: the in-scope correlation value is transmitted verbatim on both outbound headers, "
            + "never a fresh UUID")
    void transmitsTheInScopeValueVerbatimOnBothHeaders() throws IOException {
        MDC.put(CorrelationIds.MDC_KEY, "in-scope-value");
        final HttpRequest request = mock(HttpRequest.class);
        final HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        final ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(execution.execute(any(), any())).thenReturn(response);

        final ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        assertThat(result).isSameAs(response);
        assertThat(headers.getFirst(CorrelationIds.HEADER_CPP)).isEqualTo("in-scope-value");
        assertThat(headers.getFirst(CorrelationIds.HEADER_X_CORRELATION_ID)).isEqualTo("in-scope-value");
    }

    @Test
    @DisplayName("AC-002: X-Request-ID and the interceptor's own MDC_KEY constant no longer exist on the class")
    void deletedConstantsNoLongerExistOnTheClass() {
        final java.lang.reflect.Field[] fields = CorrelationIdInterceptor.class.getDeclaredFields();

        assertThat(fields).noneMatch(f -> "HEADER".equals(f.getName()) || "MDC_KEY".equals(f.getName()));
    }

    @Test
    @DisplayName("AC-003: MDC is byte-for-byte unchanged immediately before, during, and immediately after "
            + "intercept(...) — the direct test for the historical destruction bug")
    void mdcIsUnchangedBeforeDuringAndAfter() throws IOException {
        MDC.put(CorrelationIds.MDC_KEY, "abc-123");
        MDC.put("otherKey", "otherValue");
        final var before = MDC.getCopyOfContextMap();

        final HttpRequest request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        final ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(execution.execute(any(), any())).thenAnswer(invocation -> {
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(before);
            return response;
        });

        interceptor.intercept(request, new byte[0], execution);

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-003: no try/finally at all — a no-arg constructor check that the class performs "
            + "no MDC write even when the downstream call throws")
    void mdcIsUntouchedEvenWhenExecutionThrows() throws IOException {
        MDC.put(CorrelationIds.MDC_KEY, "abc-123");
        final var before = MDC.getCopyOfContextMap();

        final HttpRequest request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], execution))
                .isInstanceOf(IOException.class);

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-006: with no ambient correlation value at all, the outbound request still carries "
            + "a non-blank value via currentOrRandom()'s defensive last-resort branch")
    void generatesANonBlankValueWhenNoAmbientContextExists() throws IOException {
        final HttpRequest request = mock(HttpRequest.class);
        final HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        final ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor.intercept(request, new byte[0], execution);

        assertThat(headers.getFirst(CorrelationIds.HEADER_CPP)).isNotBlank();
        assertThat(headers.getFirst(CorrelationIds.HEADER_CPP))
                .isEqualTo(headers.getFirst(CorrelationIds.HEADER_X_CORRELATION_ID));
    }
}
