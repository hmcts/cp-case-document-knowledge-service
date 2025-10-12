package uk.gov.hmcts.cp.cdk.filters.audit;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import uk.gov.hmcts.cp.cdk.filters.audit.model.AuditPayload;
import uk.gov.hmcts.cp.cdk.filters.audit.service.AuditPayloadGenerationService;
import uk.gov.hmcts.cp.cdk.filters.audit.service.AuditService;
import uk.gov.hmcts.cp.cdk.filters.audit.service.PathParameterService;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@AllArgsConstructor
public class AuditFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditFilter.class);

    private static final int CACHE_LIMIT = 65_536; // 64 KB

    private final AuditService auditService;
    private final AuditPayloadGenerationService auditPayloadGenerationService;
    private PathParameterService pathParameterService;

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        final String path = request.getRequestURI();
        return path.contains("/health") || path.contains("/actuator");
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {

        final ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, CACHE_LIMIT);
        final ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        filterChain.doFilter(wrappedRequest, wrappedResponse);

        performAudit(wrappedRequest, wrappedResponse);

        wrappedResponse.copyBodyToResponse();
    }

    private void performAudit(final ContentCachingRequestWrapper wrappedRequest, final ContentCachingResponseWrapper wrappedResponse) {
        final String contextPath = wrappedRequest.getContextPath();
        final String requestPath = wrappedRequest.getServletPath();
        final String requestPayload = getPayload(wrappedRequest.getContentAsByteArray(), wrappedRequest.getCharacterEncoding());
        final Map<String, String> headers = getHeaders(wrappedRequest);
        final Map<String, String> queryParams = getQueryParams(wrappedRequest);
        final Map<String, String> pathParams = pathParameterService.getPathParameters(requestPath);

        final AuditPayload auditRequestPayload = auditPayloadGenerationService.generatePayload(contextPath, requestPayload, headers, queryParams, pathParams);
        auditService.postMessageToArtemis(auditRequestPayload);

        final String responsePayload = getPayload(wrappedResponse.getContentAsByteArray(), wrappedResponse.getCharacterEncoding());
        if (isNotEmpty(responsePayload)) {
            final AuditPayload auditResponsePayload = auditPayloadGenerationService.generatePayload(contextPath, responsePayload, headers);
            auditService.postMessageToArtemis(auditResponsePayload);
        }
    }

    private String getPayload(final byte[] content, final String encoding) {
        try {
            return new String(content, encoding);
        } catch (IOException ex) {
            LOGGER.error("Unable to parse payload for audit", ex);
            return "";
        }
    }

    private Map<String, String> getHeaders(final HttpServletRequest request) {
        final Map<String, String> headers = new HashMap<>();
        final Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            final String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }
        return headers;
    }

    private Map<String, String> getQueryParams(final HttpServletRequest request) {
        final Map<String, String> queryParams = new HashMap<>();
        request.getParameterMap().forEach((key, value) -> queryParams.put(key, String.join(",", value)));
        return queryParams;
    }

}