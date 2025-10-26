package uk.gov.hmcts.cp.cdk.filters.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import uk.gov.hmcts.cp.cdk.filters.audit.model.AuditPayload;
import uk.gov.hmcts.cp.cdk.filters.audit.service.AuditPayloadGenerationService;
import uk.gov.hmcts.cp.cdk.filters.audit.service.AuditService;
import uk.gov.hmcts.cp.cdk.filters.audit.service.PathParameterService;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class AuditFilterTest {

    private static final String CONTEXT_PATH = "test-context-path";
    private static final String CONTEXT_PATH_WITH_LEADING_SLASH = "/" + CONTEXT_PATH;
    private static final String SERVLET_PATH = "/test-servlet-path";
    private static final String REQUEST_BODY = "{\"data\":\"test\"}";
    private static final String RESPONSE_BODY = "{\"result\":\"success\"}";
    private static final String REQUEST_URI = "/api/v1/resource/123";
    private static final String REQUEST_METHOD = "POST";
    private static final int RESPONSE_STATUS = 201;
    private final AuditPayload mockRequestAuditNode = mock(AuditPayload.class);
    private final AuditPayload mockResponseAuditNode = mock(AuditPayload.class);
    private AuditFilter auditFilter;
    private AuditService mockAuditService;
    private AuditPayloadGenerationService mockAuditPayloadGenerationService;
    private MockHttpServletRequest mockRequest;
    private MockHttpServletResponse mockResponse;
    private FilterChain mockFilterChain;

    /**
     * Helper method to safely create a type-specific ArgumentCaptor for Map<String, String>.
     * This is the recommended way to handle generic capture with Mockito's type erasure issues.
     */
    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> argumentCaptorForMapStringString() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @BeforeEach
    void setUp() throws IOException, ServletException {
        // Mock dependencies
        mockAuditService = mock(AuditService.class);
        mockAuditPayloadGenerationService = mock(AuditPayloadGenerationService.class);
        final PathParameterService mockPathParameterService = mock(PathParameterService.class);

        // Instantiate the filter with mocks
        auditFilter = new AuditFilter(mockAuditService, mockAuditPayloadGenerationService, mockPathParameterService);

        // Setup mock servlet objects
        mockRequest = new MockHttpServletRequest(REQUEST_METHOD, REQUEST_URI);
        mockRequest.setContextPath(CONTEXT_PATH_WITH_LEADING_SLASH);
        mockRequest.setServletPath(SERVLET_PATH);
        mockRequest.setContent(REQUEST_BODY.getBytes());
        mockRequest.addHeader("Authorization", "Bearer token");
        mockRequest.addParameter("param1", "value1");

        // The filter chain logic writes to the response wrapper
        mockResponse = new MockHttpServletResponse();
        mockFilterChain = mock(FilterChain.class);

        // Simulate the controller/next filter writing to the response
        doAnswer(invocation -> {
            HttpServletResponse currentResponse = (HttpServletResponse) invocation.getArguments()[1];
            currentResponse.setStatus(RESPONSE_STATUS);
            currentResponse.setContentType("application/json");
            PrintWriter writer = currentResponse.getWriter();
            writer.write(RESPONSE_BODY);
            writer.flush(); // Ensure content is buffered
            return null;
        }).when(mockFilterChain).doFilter(any(), any());

        when(mockPathParameterService.getPathParameters(any())).thenReturn(Map.of("pathparam1", "pathvalue1"));

        // 1. Mock for Request payload: generatePayload(String, String, Map, Map)
        when(mockAuditPayloadGenerationService.generatePayload(eq(CONTEXT_PATH), any(String.class), anyMap(), anyMap(), anyMap())).thenReturn(mockRequestAuditNode);

        // 2. Mock for Response payload: generatePayload(String, String, Map)
        when(mockAuditPayloadGenerationService.generatePayload(eq(CONTEXT_PATH), any(String.class), anyMap())).thenReturn(mockResponseAuditNode);
    }

    @Test
    void doFilterInternal_ShouldAuditRequestAndResponse_WhenResponseBodyIsPresent() throws ServletException, IOException {
        assertDoesNotThrow(() -> {
            auditFilter.doFilterInternal(mockRequest, mockResponse, mockFilterChain);
        });

        verify(mockFilterChain).doFilter(any(ContentCachingRequestWrapper.class), any(ContentCachingResponseWrapper.class));
        // CRITICAL: Verify that the buffered content was copied back to the real response
        assertEquals(RESPONSE_STATUS, mockResponse.getStatus());
        assertEquals(RESPONSE_BODY, mockResponse.getContentAsString());

        ArgumentCaptor<Map<String, String>> headerPayloadCaptor = argumentCaptorForMapStringString();
        ArgumentCaptor<Map<String, String>> queryParamsPayloadCaptor = argumentCaptorForMapStringString();
        ArgumentCaptor<Map<String, String>> pathParamsPayloadCaptor = argumentCaptorForMapStringString();

        verify(mockAuditService).postMessageToArtemis(mockRequestAuditNode);
        verify(mockAuditService).postMessageToArtemis(mockResponseAuditNode);


        verify(mockAuditPayloadGenerationService).generatePayload(eq(CONTEXT_PATH), any(String.class), headerPayloadCaptor.capture(), queryParamsPayloadCaptor.capture(), pathParamsPayloadCaptor.capture());
        assertEquals(1, headerPayloadCaptor.getAllValues().getFirst().size());
        assertEquals("Bearer token", headerPayloadCaptor.getAllValues().getFirst().get("Authorization"));

        assertEquals(1, queryParamsPayloadCaptor.getAllValues().getFirst().size());
        assertEquals("value1", queryParamsPayloadCaptor.getAllValues().getFirst().get("param1"));

        assertEquals(1, pathParamsPayloadCaptor.getAllValues().getFirst().size());
        assertEquals("pathvalue1", pathParamsPayloadCaptor.getAllValues().getFirst().get("pathparam1"));

        verify(mockAuditPayloadGenerationService).generatePayload(eq(CONTEXT_PATH), eq(RESPONSE_BODY), headerPayloadCaptor.capture());
        assertEquals(1, headerPayloadCaptor.getAllValues().get(1).size());
        assertEquals("Bearer token", headerPayloadCaptor.getAllValues().get(1).get("Authorization"));
    }

    @Test
    void doFilterInternal_ShouldOnlyAuditRequest_WhenResponseBodyIsEmpty() throws ServletException, IOException {
        // Arrange: Override the chain behavior to write nothing (empty response body)
        doAnswer(invocation -> {
            HttpServletResponse currentResponse = (HttpServletResponse) invocation.getArguments()[1];
            currentResponse.setStatus(200);
            // Only status is set with no content returned
            return null;
        }).when(mockFilterChain).doFilter(any(), any());

        assertDoesNotThrow(() -> {
            auditFilter.doFilterInternal(mockRequest, mockResponse, mockFilterChain);
        });

        // Verify that the AuditService was called only once (for the request)
        verify(mockAuditService).postMessageToArtemis(mockRequestAuditNode);

        // Verify that the response payload generation service was not called with the (String, Map) signature
        verify(mockAuditPayloadGenerationService, never()).generatePayload(eq(CONTEXT_PATH),
                any(String.class), anyMap() // Response signature
        );

        assertEquals(200, mockResponse.getStatus());
        assertEquals("", mockResponse.getContentAsString());
    }

    @Test
    void shouldNotFilter_ReturnsTrueForExcludedPaths() {
        MockHttpServletRequest healthRequest = new MockHttpServletRequest("GET", "/health");
        MockHttpServletRequest actuatorRequest = new MockHttpServletRequest("GET", "/actuator/metrics");

        assertTrue(auditFilter.shouldNotFilter(healthRequest));
        assertTrue(auditFilter.shouldNotFilter(actuatorRequest));
    }

    @Test
    void shouldNotFilter_ReturnsFalseForAuditedPaths() {
        MockHttpServletRequest apiRequest = new MockHttpServletRequest("POST", "/api/data");

        assertFalse(auditFilter.shouldNotFilter(apiRequest));
    }
}
