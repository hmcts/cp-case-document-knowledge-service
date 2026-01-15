package uk.gov.hmcts.cp.cdk.http;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public class DebugLoggingInterceptor implements ClientHttpRequestInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DebugLoggingInterceptor.class);

    @Override
    public ClientHttpResponse intercept(final HttpRequest request,
                                        final byte[] body,
                                        final ClientHttpRequestExecution execution)
            throws IOException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("HTTP {} {}", request.getMethod(), request.getURI());
            LOGGER.debug("Headers: {}", request.getHeaders());
        }
        final ClientHttpResponse response = execution.execute(request, body);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Response: {} {}", response.getStatusCode(), response.getHeaders());
        }
        return response;
    }
}
