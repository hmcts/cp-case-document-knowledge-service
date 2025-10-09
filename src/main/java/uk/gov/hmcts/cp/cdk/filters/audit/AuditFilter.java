package uk.gov.hmcts.cp.cdk.filters.audit;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import uk.gov.hmcts.cp.cdk.filters.audit.service.AuditService;
import uk.gov.hmcts.cp.cdk.filters.audit.service.PayloadGenerationService;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private static final int CACHE_LIMIT = 65536; // 64 KB

    private final AuditService auditService;
    private final PayloadGenerationService payloadGenerationService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Exclude specific URLs from being filtered
        //FIXME: Should be configurable
        return path.contains("/health") || path.contains("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        //TODO: Future scope - audit enabled for endpoints based on whitelisting or blacklisting. Now, all endpoints are audited

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, CACHE_LIMIT);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        filterChain.doFilter(wrappedRequest, wrappedResponse);

        // 2. Perform the audit after continuing the chain
        String requestPayload = getPayload(wrappedRequest.getContentAsByteArray(), wrappedRequest.getCharacterEncoding());
        Map<String, String> headers = getHeaders(wrappedRequest);
        Map<String, String> queryParams = getQueryParams(wrappedRequest);

        //TODO: once this is populated, will be passed to payload generation service
        Map<String, String> pathParams = getPathParams(wrappedRequest);

        ObjectNode auditRequestPayload = payloadGenerationService.generatePayload(requestPayload, headers, queryParams);
        auditService.postMessageToArtemis(auditRequestPayload);

        String responsePayload = getPayload(wrappedResponse.getContentAsByteArray(), wrappedResponse.getCharacterEncoding());
        if (isNotEmpty(responsePayload)) {
            ObjectNode auditResponsePayload = payloadGenerationService.generatePayload(responsePayload, headers);
            auditService.postMessageToArtemis(auditResponsePayload);
        }

        wrappedResponse.copyBodyToResponse();
    }

    private String getPayload(byte[] content, String encoding) {
        try {
            return new String(content, encoding);
        } catch (IOException ex) {
            LOGGER.error("Unable to parse payload for audit", ex);
            return "";
        }
    }

    private Map<String, String> getHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }
        return headers;
    }

    private Map<String, String> getQueryParams(HttpServletRequest request) {
        Map<String, String> queryParams = new HashMap<>();
        request.getParameterMap().forEach((key, value) -> queryParams.put(key, String.join(",", value)));
        return queryParams;
    }

    private Map<String, String> getPathParams(HttpServletRequest request) {
        // TODO: Need to figure this out as its not straightforward to do this in a filter
        return Map.of();
    }
}