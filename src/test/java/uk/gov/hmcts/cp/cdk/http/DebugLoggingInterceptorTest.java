package uk.gov.hmcts.cp.cdk.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

class DebugLoggingInterceptorTest {

    private final DebugLoggingInterceptor interceptor = new DebugLoggingInterceptor();

    @Test
    void intercept_shouldRedactAuthorizationAndSubscriptionKey_butKeepOtherHeaders() throws IOException {
        final Logger logger = (Logger) LoggerFactory.getLogger(DebugLoggingInterceptor.class);
        logger.setLevel(Level.DEBUG);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        final HttpRequest request = mock(HttpRequest.class);
        final HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.add("Authorization", "Bearer super-secret-token");
        requestHeaders.add("Ocp-Apim-Subscription-Key", "super-secret-key");
        requestHeaders.add("Content-Type", "application/json");
        when(request.getHeaders()).thenReturn(requestHeaders);
        when(request.getMethod()).thenReturn(org.springframework.http.HttpMethod.GET);
        when(request.getURI()).thenReturn(java.net.URI.create("https://example.test/resource"));

        final ClientHttpResponse response = mock(ClientHttpResponse.class);
        final HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Set-Cookie", "irrelevant=true");
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(response.getStatusCode()).thenReturn(HttpStatusCode.valueOf(200));

        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        try {
            final ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);
            assertThat(result).isSameAs(response);
        } finally {
            logger.detachAppender(appender);
        }

        final String allLogOutput = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(allLogOutput)
                .as("no debug log line may contain the raw credential values")
                .doesNotContain("super-secret-token")
                .doesNotContain("super-secret-key");
        assertThat(allLogOutput)
                .as("non-sensitive headers must still be visible for debugging")
                .contains("application/json");
    }

    @Test
    void intercept_shouldStillReturnResponse_whenDebugLoggingDisabled() throws IOException {
        final Logger logger = (Logger) LoggerFactory.getLogger(DebugLoggingInterceptor.class);
        logger.setLevel(Level.INFO);

        final HttpRequest request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        final ClientHttpResponse response = mock(ClientHttpResponse.class);
        final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);

        final ClientHttpResponse result = interceptor.intercept(request, new byte[0], execution);

        assertThat(result).isSameAs(response);
    }
}
