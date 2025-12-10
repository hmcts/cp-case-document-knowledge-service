package uk.gov.hmcts.cp.cdk.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class RequestContextFilterTest {
    @Mock
    private HttpServletRequest request;
    @Mock
    private ServletResponse response;
    @Mock
    private FilterChain chain;

    private RequestContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestContextFilter();
    }

    @AfterEach
    void tearDown() {
        MDC.clear(); // Ensure no MDC leakage between tests
    }

    @Test
    void setsCorrelationIdFromHeaderAndMdcValues() throws IOException, ServletException {
        final String cid = UUID.randomUUID().toString();
        when(request.getHeader("X-Correlation-Id")).thenReturn(cid);
        when(request.getRequestURI()).thenReturn("/test/path");

        doAnswer(invocation -> {
            // This is executed *during* the filter
            assertEquals(cid, MDC.get("correlationId"));
            assertEquals(System.getenv().getOrDefault("CLUSTER_NAME", "local"), MDC.get("cluster"));
            assertEquals(System.getenv().getOrDefault("REGION", "local"), MDC.get("region"));
            assertEquals("/test/path", MDC.get("path"));
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        // After filter completes, MDC is cleared
        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("cluster"));
        assertNull(MDC.get("region"));
        assertNull(MDC.get("path"));

        verify(chain).doFilter(request, response);
    }

    @Test
    void clearsMdcEvenIfChainThrowsException() throws IOException, ServletException {
        when(request.getHeader("X-Correlation-Id")).thenReturn("abc");
        when(request.getRequestURI()).thenReturn("/bar");

        doThrow(new ServletException("Error")).when(chain).doFilter(request, response);

        ServletException ex = assertThrows(ServletException.class, () ->
                filter.doFilter(request, response, chain));

        assertEquals("Error", ex.getMessage());

        // MDC must be cleared even on exception
        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("cluster"));
        assertNull(MDC.get("region"));
        assertNull(MDC.get("path"));
    }
}