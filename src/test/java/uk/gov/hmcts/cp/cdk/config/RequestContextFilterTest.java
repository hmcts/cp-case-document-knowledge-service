package uk.gov.hmcts.cp.cdk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.correlation.CorrelationIds;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class RequestContextFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private RequestContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestContextFilter();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void resolvesCanonicalHeaderAndSetsMdcAndResponseHeader() throws Exception {
        final String cid = UUID.randomUUID().toString();
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn(cid);
        when(request.getRequestURI()).thenReturn("/test/path");

        doAnswer(invocation -> {
            assertEquals(cid, MDC.get(CorrelationIds.MDC_KEY));
            assertEquals(System.getenv().getOrDefault("CLUSTER_NAME", "local"), MDC.get("cluster"));
            assertEquals(System.getenv().getOrDefault("REGION", "local"), MDC.get("region"));
            assertEquals("/test/path", MDC.get("path"));
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        verify(response).setHeader(CorrelationIds.HEADER_X_CORRELATION_ID, cid);
        verify(chain).doFilter(request, response);
        assertNull(MDC.get(CorrelationIds.MDC_KEY));
    }

    @Test
    void resolvesAliasHeaderWhenCanonicalAbsent() throws Exception {
        final String cid = "alias-value-123";
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn(null);
        when(request.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID)).thenReturn(cid);
        when(request.getRequestURI()).thenReturn("/foo");

        filter.doFilter(request, response, chain);

        verify(response).setHeader(CorrelationIds.HEADER_X_CORRELATION_ID, cid);
    }

    @Test
    void canonicalHeaderWinsOverAliasWhenBothPresentWithDifferentValues() throws Exception {
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("canonical-value");
        // Proves precedence short-circuits: the alias is never even consulted once the canonical
        // header resolves, so this stub is legitimately unused on the success path.
        org.mockito.Mockito.lenient().when(request.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID))
                .thenReturn("alias-value");
        when(request.getRequestURI()).thenReturn("/foo");

        filter.doFilter(request, response, chain);

        verify(response).setHeader(CorrelationIds.HEADER_X_CORRELATION_ID, "canonical-value");
    }

    @Test
    void generatesAValueWhenNeitherHeaderIsPresent() throws Exception {
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn(null);
        when(request.getHeader(CorrelationIds.HEADER_X_CORRELATION_ID)).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/foo");

        filter.doFilter(request, response, chain);

        verify(response).setHeader(eq(CorrelationIds.HEADER_X_CORRELATION_ID),
                org.mockito.ArgumentMatchers.argThat(v -> v != null && !v.isBlank()));
    }

    @Test
    void setsTheResponseHeaderBeforeInvokingTheChain() throws Exception {
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("abc-123");
        when(request.getRequestURI()).thenReturn("/foo");

        filter.doFilter(request, response, chain);

        final InOrder order = inOrder(response, chain);
        order.verify(response).setHeader(eq(CorrelationIds.HEADER_X_CORRELATION_ID), eq("abc-123"));
        order.verify(chain).doFilter(request, response);
    }

    @Test
    void restoresPriorMdcMapRatherThanClearingItEntirely() throws Exception {
        MDC.put("preExistingKey", "preExistingValue");
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("abc-123");
        when(request.getRequestURI()).thenReturn("/foo");

        filter.doFilter(request, response, chain);

        assertThat(MDC.get("preExistingKey")).isEqualTo("preExistingValue");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenIfChainThrowsException() throws IOException, ServletException {
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("abc");
        when(request.getRequestURI()).thenReturn("/bar");

        doThrow(new ServletException("Error")).when(chain).doFilter(request, response);

        final ServletException ex = assertThrows(ServletException.class, () ->
                filter.doFilter(request, response, chain));

        assertEquals("Error", ex.getMessage());

        assertNull(MDC.get(CorrelationIds.MDC_KEY));
        assertNull(MDC.get("cluster"));
        assertNull(MDC.get("region"));
        assertNull(MDC.get("path"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("AC-002 (DD-43266): request B on the same thread sees none of "
            + "request A's MDC, whether A returned normally or threw")
    void sequentialRequestsOnSameThreadDoNotBleedMdc_whenFirstReturnsNormally() throws Exception {
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("request-a-value");
        when(request.getRequestURI()).thenReturn("/a");

        filter.doFilter(request, response, chain);
        assertNull(MDC.get(CorrelationIds.MDC_KEY));

        final HttpServletRequest requestB = org.mockito.Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse responseB = org.mockito.Mockito.mock(HttpServletResponse.class);
        final FilterChain chainB = org.mockito.Mockito.mock(FilterChain.class);
        when(requestB.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("request-b-value");
        when(requestB.getRequestURI()).thenReturn("/b");
        doAnswer(invocation -> {
            assertEquals("request-b-value", MDC.get(CorrelationIds.MDC_KEY));
            assertEquals("/b", MDC.get("path"));
            return null;
        }).when(chainB).doFilter(requestB, responseB);

        filter.doFilter(requestB, responseB, chainB);

        assertNull(MDC.get(CorrelationIds.MDC_KEY));
        verify(chainB).doFilter(requestB, responseB);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("AC-002 (DD-43266): request B on the same thread sees none of "
            + "request A's MDC when A's chain threw")
    void sequentialRequestsOnSameThreadDoNotBleedMdc_whenFirstThrows() throws Exception {
        when(request.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("request-a-value");
        when(request.getRequestURI()).thenReturn("/a");
        doThrow(new ServletException("boom")).when(chain).doFilter(request, response);

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));
        assertNull(MDC.get(CorrelationIds.MDC_KEY));

        final HttpServletRequest requestB = org.mockito.Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse responseB = org.mockito.Mockito.mock(HttpServletResponse.class);
        final FilterChain chainB = org.mockito.Mockito.mock(FilterChain.class);
        when(requestB.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("request-b-value");
        when(requestB.getRequestURI()).thenReturn("/b");
        doAnswer(invocation -> {
            assertEquals("request-b-value", MDC.get(CorrelationIds.MDC_KEY));
            return null;
        }).when(chainB).doFilter(requestB, responseB);

        filter.doFilter(requestB, responseB, chainB);

        assertNull(MDC.get(CorrelationIds.MDC_KEY));
        verify(chainB).doFilter(requestB, responseB);
    }
}
