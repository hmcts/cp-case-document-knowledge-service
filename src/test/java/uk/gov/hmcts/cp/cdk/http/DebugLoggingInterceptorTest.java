package uk.gov.hmcts.cp.cdk.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

@DisplayName("DebugLoggingInterceptor tests (DD-43183 GATE-6 — credential redaction)")
class DebugLoggingInterceptorTest {

    private final DebugLoggingInterceptor interceptor = new DebugLoggingInterceptor();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(DebugLoggingInterceptor.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("the raw Authorization header value never appears in the formatted debug log output")
    void redactsAuthorizationHeader() throws IOException {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer super-secret-token-value");
        headers.set("Content-Type", "application/json");

        invokeInterceptor(headers);

        final String allLogOutput = formattedLogOutput();
        assertThat(allLogOutput).doesNotContain("super-secret-token-value");
        assertThat(allLogOutput).contains("[REDACTED]");
        assertThat(allLogOutput).contains("Content-Type");
        assertThat(allLogOutput).contains("application/json");
    }

    @Test
    @DisplayName("the raw Ocp-Apim-Subscription-Key value never appears in the formatted debug log output, "
            + "matched case-insensitively")
    void redactsSubscriptionKeyHeaderCaseInsensitively() throws IOException {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("ocp-apim-subscription-key", "super-secret-subscription-key");

        invokeInterceptor(headers);

        final String allLogOutput = formattedLogOutput();
        assertThat(allLogOutput).doesNotContain("super-secret-subscription-key");
        assertThat(allLogOutput).contains("[REDACTED]");
    }

    @Test
    @DisplayName("a non-sensitive header remains fully visible in the debug output")
    void nonSensitiveHeaderRemainsVisible() throws IOException {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("CPPCLIENTCORRELATIONID", "abc-123");

        invokeInterceptor(headers);

        assertThat(formattedLogOutput()).contains("abc-123");
    }

    @Test
    @DisplayName("the response's own headers are also redacted")
    void redactsResponseHeadersToo() throws IOException {
        final HttpRequest request = mock(HttpRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getURI()).thenReturn(URI.create("https://example.test/api"));
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        final ClientHttpResponse response = mock(ClientHttpResponse.class);
        final HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("Authorization", "Bearer response-side-secret");
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);

        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor.intercept(request, new byte[0], execution);

        assertThat(formattedLogOutput()).doesNotContain("response-side-secret");
    }

    private void invokeInterceptor(final HttpHeaders headers) throws IOException {
        final HttpRequest request = mock(HttpRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getURI()).thenReturn(URI.create("https://example.test/api"));
        when(request.getHeaders()).thenReturn(headers);

        final ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);

        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor.intercept(request, new byte[0], execution);
    }

    private String formattedLogOutput() {
        final StringBuilder sb = new StringBuilder();
        appender.list.forEach(event -> sb.append(event.getFormattedMessage()).append('\n'));
        return sb.toString();
    }
}
