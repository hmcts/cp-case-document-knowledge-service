package uk.gov.hmcts.cp.cdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.NIGHTLY_DISCOVERY;

import uk.gov.hmcts.cp.cdk.metrics.SchedulerMetrics;
import uk.gov.hmcts.cp.cdk.services.DiscoveryService;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
@DisplayName("Nightly Discovery Scheduler tests")
class NightlyDiscoverySchedulerTest {

    @Mock
    private DiscoveryService discoveryService;

    @Mock
    private SchedulerMetrics schedulerMetrics;

    private NightlyDiscoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new NightlyDiscoveryScheduler(discoveryService, schedulerMetrics);
    }

    @Test
    @DisplayName("run should trigger nightly discovery")
    void run_shouldTriggerNightlyDiscovery() {
        // when
        scheduler.run();

        // then
        verify(discoveryService, times(1)).runNightlyDiscovery();
        verifyNoMoreInteractions(discoveryService);
    }

    @Test
    @DisplayName("run should be callable multiple times")
    void run_shouldBeCallableMultipleTimes() {
        // when
        scheduler.run();
        scheduler.run();

        // then
        verify(discoveryService, times(2)).runNightlyDiscovery();
    }

    @Test
    @DisplayName("run should record success exactly once when discovery completes")
    void run_shouldRecordSuccessExactlyOnce_whenDiscoveryCompletes() {
        // when
        scheduler.run();

        // then
        verify(schedulerMetrics, times(1)).recordRun(NIGHTLY_DISCOVERY, true);
        verifyNoMoreInteractions(schedulerMetrics);
    }

    @Test
    @DisplayName("run should contain and count failure when discovery throws")
    void run_shouldContainAndCountFailure_whenDiscoveryThrows() {
        doThrow(new RuntimeException("boom")).when(discoveryService).runNightlyDiscovery();

        final Logger logger = (Logger) LoggerFactory.getLogger(NightlyDiscoveryScheduler.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            assertThatCode(scheduler::run).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
        }

        // then
        verify(schedulerMetrics, times(1)).recordRun(NIGHTLY_DISCOVERY, false);

        final List<ILoggingEvent> errorEvents = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .toList();
        assertThat(errorEvents).hasSize(1);
        final ILoggingEvent errorEvent = errorEvents.get(0);
        assertThat(errorEvent.getThrowableProxy()).isNotNull();
        assertThat(errorEvent.getFormattedMessage()).contains(NIGHTLY_DISCOVERY);
    }

    @Test
    @DisplayName("run should propagate an Error and still record exactly one failure")
    void run_shouldPropagateError_andStillRecordExactlyOneFailure_whenDiscoveryThrowsError() {
        doThrow(new TestError()).when(discoveryService).runNightlyDiscovery();

        final Logger logger = (Logger) LoggerFactory.getLogger(NightlyDiscoveryScheduler.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when / then
            assertThatCode(scheduler::run).isInstanceOf(TestError.class);
            verify(schedulerMetrics, times(1)).recordRun(eq(NIGHTLY_DISCOVERY), eq(false));

            // N-7: catch (Exception e) does not catch an Error, so the scheduler's own catch
            // block must not have logged anything — confirms the catch really is Exception, not
            // the wider (and wrong) Throwable.
            final List<ILoggingEvent> errorEvents = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errorEvents).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static final class TestError extends Error {
        private static final long serialVersionUID = 1L;
    }
}
