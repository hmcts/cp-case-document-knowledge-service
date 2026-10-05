package uk.gov.hmcts.cp.cdk.http;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Debug-logs outbound HTTP calls, redacting the header names known to carry the APIM credential
 * {@code ApimAuthHeaderService} injects (DD-43183, GATE-6 — a security finding this ticket
 * surfaced: this interceptor previously logged the entire outbound header map, including the
 * bearer token and/or subscription key, at DEBUG).
 *
 * <p>Deliberately a <strong>deny-list</strong> of the two known credential header names, not a
 * strict allow-list: an allow-list would also hide this ticket's own new correlation headers, and
 * every future header, from debug output by default — a bigger loss of debuggability than the risk
 * it defends against for a closed, two-name credential set.
 */
public class DebugLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(DebugLoggingInterceptor.class);
    private static final String REDACTED = "[REDACTED]";

    private static final Set<String> CREDENTIAL_HEADER_NAMES = Set.of(
            "authorization",
            "ocp-apim-subscription-key"
    );

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

    private static HttpHeaders redact(final HttpHeaders original) {
        final HttpHeaders redacted = new HttpHeaders();
        original.forEach((name, values) -> {
            if (CREDENTIAL_HEADER_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                redacted.add(name, REDACTED);
            } else {
                redacted.addAll(name, values);
            }
        });
        return redacted;
    }
}
