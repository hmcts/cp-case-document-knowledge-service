package uk.gov.hmcts.cp.filters.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Slf4j
public class TracingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) throws ServletException, IOException {
        if (request.getHeader(TRACE_ID) != null) {
            MDC.put(TRACE_ID, request.getHeader(TRACE_ID));
            response.setHeader(TRACE_ID, request.getHeader(TRACE_ID));
        }
        if (request.getHeader(SPAN_ID) != null) {
            MDC.put(SPAN_ID, request.getHeader(SPAN_ID));
            response.setHeader(SPAN_ID, request.getHeader(SPAN_ID));
        }
        filterChain.doFilter(request, response);
    }
}
