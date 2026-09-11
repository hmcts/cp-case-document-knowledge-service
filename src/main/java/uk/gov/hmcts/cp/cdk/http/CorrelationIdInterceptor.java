package uk.gov.hmcts.cp.cdk.http;

import uk.gov.hmcts.cp.cdk.correlation.CorrelationIds;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Propagates the in-scope correlation ID to every outbound {@code RestClient} call (DD-43183
 * Story 2, ADR-007) — attached unconditionally to every client by {@code RestClientFactoryConfig}.
 *
 * <p><strong>MDC-read-only.</strong> This class performs no {@code MDC.put} and no {@code MDC.remove}
 * at all — the historical defect it replaces put a fresh {@code UUID} into MDC and then
 * <em>removed</em> the key in {@code finally}, deleting the inbound correlation ID for the rest of
 * the request the moment CDKS made its first outbound call. Removing the ability to write MDC here
 * closes that failure class rather than patching the {@code finally} block.
 */
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
                                        final ClientHttpRequestExecution execution) throws IOException {
        final String correlationId = CorrelationIds.currentOrRandom();
        request.getHeaders().set(CorrelationIds.HEADER_CPP, correlationId);
        request.getHeaders().set(CorrelationIds.HEADER_X_CORRELATION_ID, correlationId);
        return execution.execute(request, body);
    }
}
