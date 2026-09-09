package uk.gov.hmcts.cp.cdk.http;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public class DebugLoggingInterceptor implements ClientHttpRequestInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DebugLoggingInterceptor.class);
    private static final String REDACTED = "REDACTED";

    /**
     * Header names that carry credentials (APIM Managed-Identity bearer token, APIM subscription
     * key) and must never appear in a debug log line. Compared case-insensitively per RFC 7230
     * (header names are case-insensitive).
     */
    private static final List<String> SENSITIVE_HEADER_NAMES =
            List.of("authorization", "ocp-apim-subscription-key");

    @Override
    public ClientHttpResponse intercept(final HttpRequest request,
                                        final byte[] body,
                                        final ClientHttpRequestExecution execution)
            throws IOException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("HTTP {} {}", request.getMethod(), request.getURI());
            LOGGER.debug("Headers: {}", redact(request.getHeaders()));
        }
        final ClientHttpResponse response = execution.execute(request, body);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Response: {} {}", response.getStatusCode(), redact(response.getHeaders()));
        }
        return response;
    }

    private static HttpHeaders redact(final HttpHeaders headers) {
        final HttpHeaders sanitised = new HttpHeaders();
        headers.forEach((name, values) -> sanitised.put(name,
                SENSITIVE_HEADER_NAMES.contains(name.toLowerCase(Locale.ROOT))
                        ? List.of(REDACTED)
                        : values));
        return sanitised;
    }
}
