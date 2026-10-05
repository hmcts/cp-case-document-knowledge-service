package uk.gov.hmcts.cp.cdk.config;

import uk.gov.hmcts.cp.cdk.correlation.CorrelationIds;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the request's correlation ID once (DD-43183 Story 1, ADR-001/ADR-002), seeds MDC and the
 * {@code X-Correlation-Id} response header, and restores — never destroys — the prior MDC map on
 * exit, so a later filter or handler on this pooled thread never inherits a stale value and never
 * loses a value the tracer itself owns.
 *
 * <p>Class and bean name kept unchanged deliberately: renaming a filter bean changes registration
 * ordering and touches unrelated tests for no behavioural gain.
 */
@Component("correlationMdcFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // NOT +1: that is ServerHttpObservationFilter's order (§2.2)
public class RequestContextFilter extends OncePerRequestFilter {

    private static final String CLUSTER = System.getenv().getOrDefault("CLUSTER_NAME", "local");
    private static final String REGION = System.getenv().getOrDefault("REGION", "local");

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        final Map<String, String> prior = MDC.getCopyOfContextMap();
        try {
            final String correlationId = CorrelationIds.resolveInbound(request);
            MDC.put(CorrelationIds.MDC_KEY, correlationId);
            MDC.put("cluster", CLUSTER);
            MDC.put("region", REGION);
            MDC.put("path", request.getRequestURI());
            // Set before the chain runs: a response cannot have a header added once committed, and
            // this makes the header survive every dispatch that runs inside this filter (AC-006).
            response.setHeader(CorrelationIds.HEADER_X_CORRELATION_ID, correlationId);
            chain.doFilter(request, response);
        } finally {
            if (prior == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(prior);
            }
        }
    }
}
