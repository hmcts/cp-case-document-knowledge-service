package uk.gov.hmcts.cp.cdk.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.config.RequestContextFilter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * DD-43266 AC-003: a deliberately low-value, forward-looking regression check (ADR-008). Virtual
 * threads stay disabled in every environment; this proves the isolation claim the ADR relies on —
 * one virtual thread per task, never reused — rather than production readiness for the toggle.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MdcVirtualThreadIsolationTest.EmptyConfig.class)
@TestPropertySource(properties = "spring.threads.virtual.enabled=true")
class MdcVirtualThreadIsolationTest {

    @Configuration
    static class EmptyConfig {
    }

    @Test
    @DisplayName("AC-003: two requests each executed on their own virtual thread never share MDC state, "
            + "for both the normal-return and throwing paths")
    void requestsOnDistinctVirtualThreadsNeverBleedMdc() throws Exception {
        final RequestContextFilter filterA = new RequestContextFilter();
        final RequestContextFilter filterB = new RequestContextFilter();

        final HttpServletRequest requestA = mock(HttpServletRequest.class);
        final HttpServletResponse responseA = mock(HttpServletResponse.class);
        final FilterChain chainA = mock(FilterChain.class);
        when(requestA.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("virtual-thread-a");
        when(requestA.getRequestURI()).thenReturn("/a");
        doThrow(new ServletException("boom")).when(chainA).doFilter(requestA, responseA);

        final HttpServletRequest requestB = mock(HttpServletRequest.class);
        final HttpServletResponse responseB = mock(HttpServletResponse.class);
        final FilterChain chainB = mock(FilterChain.class);
        when(requestB.getHeader(CorrelationIds.HEADER_CPP)).thenReturn("virtual-thread-b");
        when(requestB.getRequestURI()).thenReturn("/b");
        final String[] observedByB = new String[1];
        doAnswer(invocation -> {
            observedByB[0] = MDC.get(CorrelationIds.MDC_KEY);
            return null;
        }).when(chainB).doFilter(requestB, responseB);

        try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<?> taskA = virtualExecutor.submit(() -> {
                try {
                    filterA.doFilter(requestA, responseA, chainA);
                } catch (final Exception ignored) {
                    // expected: chainA is stubbed to throw
                }
            });
            taskA.get();

            final Future<?> taskB = virtualExecutor.submit(() -> {
                try {
                    filterB.doFilter(requestB, responseB, chainB);
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            });
            taskB.get();
        }

        assertThat(observedByB[0]).isEqualTo("virtual-thread-b");
    }
}
