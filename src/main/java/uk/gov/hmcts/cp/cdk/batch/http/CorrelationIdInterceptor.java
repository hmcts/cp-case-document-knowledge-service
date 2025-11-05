package uk.gov.hmcts.cp.cdk.batch.http;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.UUID;

public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {
    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    public ClientHttpResponse intercept(final HttpRequest request, final byte[] body, final ClientHttpRequestExecution execution)
            throws IOException {
        String cid = request.getHeaders().getFirst(HEADER);
        if (cid == null || cid.isBlank()) {
            cid = UUID.randomUUID().toString();
            request.getHeaders().add(HEADER, cid);
        }
        MDC.put(MDC_KEY, cid);
        try {
            return execution.execute(request, body);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
