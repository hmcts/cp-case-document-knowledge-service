package uk.gov.hmcts.cp.cdk.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

public class DebugLoggingInterceptor implements ClientHttpRequestInterceptor {
    private static final Logger log = LoggerFactory.getLogger(DebugLoggingInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("HTTP {} {}", request.getMethod(), request.getURI());
            log.debug("Headers: {}", request.getHeaders());
        }
        ClientHttpResponse response = execution.execute(request, body);
        if (log.isDebugEnabled()) {
            log.debug("Response: {} {}", response.getStatusCode(), response.getHeaders());
        }
        return response;
    }
}
