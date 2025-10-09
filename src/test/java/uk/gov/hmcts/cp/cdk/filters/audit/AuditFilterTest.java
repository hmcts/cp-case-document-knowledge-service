package uk.gov.hmcts.cp.cdk.filters.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.filters.audit.service.AuditService;
import uk.gov.hmcts.cp.cdk.filters.audit.service.PayloadGenerationService;

import java.io.IOException;
import java.io.PrintWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

class AuditFilterTest {

    private AuditFilter auditFilter;
    private AuditService mockAuditService;
    private PayloadGenerationService mockPayloadGenerationService;
    private ObjectMapper mapper = new ObjectMapper();

    private MockHttpServletRequest mockRequest;
    private MockHttpServletResponse mockResponse;
    private FilterChain mockFilterChain;

    private static final String REQUEST_BODY = "{\"data\":\"test\"}";
    private static final String RESPONSE_BODY = "{\"result\":\"success\"}";
    private static final String REQUEST_URI = "/api/v1/resource/123";
    private static final String REQUEST_METHOD = "POST";
    private static final int RESPONSE_STATUS = 201;

    private final ObjectNode mockRequestAuditNode = mapper.createObjectNode().put("type", "request_audit");
    private final ObjectNode mockResponseAuditNode = mapper.createObjectNode().put("type", "response_audit");


    @BeforeEach
    void setUp() throws IOException, ServletException {
        // Mock dependencies
        mockAuditService = mock(AuditService.class);
        mockPayloadGenerationService = mock(PayloadGenerationService.class);

        // Instantiate the filter with mocks
        auditFilter = new AuditFilter(mockAuditService, mockPayloadGenerationService);

        // Setup mock servlet objects
        mockRequest = new MockHttpServletRequest(REQUEST_METHOD, REQUEST_URI);
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

        // Mock payload generation for the two new required signatures

        // 1. Mock for Request payload: generatePayload(String, Map, Map)
        when(mockPayloadGenerationService.generatePayload(any(String.class), anyMap(), anyMap())).thenReturn(mockRequestAuditNode);

        // 2. Mock for Response payload: generatePayload(String, Map)
        when(mockPayloadGenerationService.generatePayload(any(String.class), anyMap())).thenReturn(mockResponseAuditNode);
    }

    @Test
    void doFilterInternal_ShouldAuditRequestAndResponse_WhenResponseBodyIsPresent() throws ServletException, IOException {
        // 2. Act: Call the protected method directly
        assertDoesNotThrow(() -> {
            auditFilter.doFilterInternal(mockRequest, mockResponse, mockFilterChain);
        });

        // 3. Assert Filter Chain and Response Copy
        verify(mockFilterChain, times(1)).doFilter(any(), any());
        // CRITICAL: Verify that the buffered content was copied back to the real response
        assertEquals(RESPONSE_STATUS, mockResponse.getStatus());
        assertEquals(RESPONSE_BODY, mockResponse.getContentAsString());


        // 4. Assert Audit Service Calls (Should be called twice)
        ArgumentCaptor<ObjectNode> payloadCaptor = ArgumentCaptor.forClass(ObjectNode.class);

        // Verify that the AuditService was called twice (once for request, once for response)
        verify(mockAuditService, times(2)).postMessageToArtemis(payloadCaptor.capture());

        // Assertions for payloadCaptor check the mocked objects were sent in the correct order
        assertEquals("request_audit", payloadCaptor.getAllValues().get(0).get("type").asText());
        assertEquals("response_audit", payloadCaptor.getAllValues().get(1).get("type").asText());


        // 5. Assert Payload Generation Service Calls
        // Verify both signatures were called
        verify(mockPayloadGenerationService, times(1)).generatePayload(
                any(String.class), anyMap(), anyMap() // Request signature
        );
        verify(mockPayloadGenerationService, times(1)).generatePayload(
                any(String.class), anyMap() // Response signature
        );
    }

    @Test
    void doFilterInternal_ShouldOnlyAuditRequest_WhenResponseBodyIsEmpty() throws ServletException, IOException {
        // Arrange: Override the chain behavior to write nothing (empty response body)
        MockHttpServletResponse emptyMockResponse = new MockHttpServletResponse();
        doAnswer(invocation -> {
            HttpServletResponse currentResponse = (HttpServletResponse) invocation.getArguments()[1];
            currentResponse.setStatus(200);
            return null; // Don't write any body
        }).when(mockFilterChain).doFilter(any(), any());

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(emptyMockResponse);

        // 2. Act: Call the protected method directly
        assertDoesNotThrow(() -> {
            auditFilter.doFilterInternal(mockRequest, mockResponse, mockFilterChain);
        });

        // Verify that the AuditService was called only once (for the request)
        verify(mockAuditService, times(1)).postMessageToArtemis(eq(mockRequestAuditNode));

        // Verify that the response payload generation service was not called with the (String, Map) signature
        verify(mockPayloadGenerationService, never()).generatePayload(
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
