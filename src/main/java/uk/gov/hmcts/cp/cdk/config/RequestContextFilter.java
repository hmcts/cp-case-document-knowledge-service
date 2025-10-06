package uk.gov.hmcts.cp.cdk.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;


@Component("correlationMdcFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestContextFilter implements Filter {

    private static final String CLUSTER = System.getenv().getOrDefault("CLUSTER_NAME", "local");

    private static final String REGION = System.getenv().getOrDefault("REGION", "local");

    @Override
    public void doFilter(final ServletRequest req, final ServletResponse res, final FilterChain chain)
            throws IOException, ServletException {
        try {
            final HttpServletRequest httpServletRequest = (HttpServletRequest) req;
            String cid = httpServletRequest.getHeader("X-Correlation-Id");
            if (cid == null || cid.isBlank()) {
                cid = UUID.randomUUID().toString();
            }
            MDC.put("correlationId", cid);
            MDC.put("cluster", CLUSTER);
            MDC.put("region", REGION);
            MDC.put("path", httpServletRequest.getRequestURI());
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
