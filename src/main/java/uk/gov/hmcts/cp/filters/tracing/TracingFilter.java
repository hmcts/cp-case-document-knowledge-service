package uk.gov.hmcts.cp.filters.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Slf4j
public class TracingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String APPLICATION_NAME = "applicationName";

    private final String applicationName;

    public TracingFilter(@Value("${spring.application.name}") final String applicationName) {
        this.applicationName = applicationName;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) throws ServletException, IOException {
        MDC.put(APPLICATION_NAME, applicationName);
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
