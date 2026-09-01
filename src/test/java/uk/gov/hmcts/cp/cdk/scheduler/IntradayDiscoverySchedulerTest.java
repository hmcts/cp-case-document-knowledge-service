package uk.gov.hmcts.cp.cdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.INTRADAY_DISCOVERY;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.OUTCOME_FAILURE;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.OUTCOME_SUCCESS;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.SCHEDULER_RUNS;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.TAG_OUTCOME;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.TAG_SCHEDULER;

import uk.gov.hmcts.cp.cdk.metrics.SchedulerMetrics;
import uk.gov.hmcts.cp.cdk.services.DiscoveryService;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class IntradayDiscoverySchedulerTest {

    @Mock
    private DiscoveryService discoveryService;

    @Mock
    private SchedulerMetrics schedulerMetrics;

    private IntradayDiscoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new IntradayDiscoveryScheduler(discoveryService, schedulerMetrics);
    }

    @Test
    void run_shouldTriggerIntradayDiscovery() {
        // when
        scheduler.run();

        // then
        verify(discoveryService, times(1)).runIntradayDiscovery();
        verifyNoMoreInteractions(discoveryService);
    }

    @Test
    void run_shouldBeCallableMultipleTimes() {
        // when
        scheduler.run();
        scheduler.run();

        // then
        verify(discoveryService, times(2)).runIntradayDiscovery();
    }

    @Test
    void run_shouldRecordSuccessExactlyOnce_whenDiscoveryCompletes() {
        // when
        scheduler.run();

        // then
        verify(schedulerMetrics, times(1)).recordRun(INTRADAY_DISCOVERY, true);
        verifyNoMoreInteractions(schedulerMetrics);
    }

    @Test
    void run_shouldContainAndCountFailure_whenDiscoveryThrows() {
        doThrow(new RuntimeException("boom")).when(discoveryService).runIntradayDiscovery();

        final Logger logger = (Logger) LoggerFactory.getLogger(IntradayDiscoveryScheduler.class);
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
        verify(schedulerMetrics, times(1)).recordRun(INTRADAY_DISCOVERY, false);

        final List<ILoggingEvent> errorEvents = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .toList();
        assertThat(errorEvents).hasSize(1);
        final ILoggingEvent errorEvent = errorEvents.get(0);
        assertThat(errorEvent.getThrowableProxy()).isNotNull();
        assertThat(errorEvent.getFormattedMessage()).contains(INTRADAY_DISCOVERY);
    }

    @Test
    void run_shouldRecordExactlyOneOutcomePerInvocation_whenRunsSucceedThenFail() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final SchedulerMetrics realMetrics = new SchedulerMetrics(registry, new SchedulerProperties(),
                mockObjectProvider(), mockObjectProvider());
        final IntradayDiscoveryScheduler realScheduler =
                new IntradayDiscoveryScheduler(discoveryService, realMetrics);

        // first run succeeds
        realScheduler.run();
        // second run fails
        doThrow(new RuntimeException("boom")).when(discoveryService).runIntradayDiscovery();
        realScheduler.run();

        final double success = registry.get(SCHEDULER_RUNS)
                .tag(TAG_SCHEDULER, INTRADAY_DISCOVERY).tag(TAG_OUTCOME, OUTCOME_SUCCESS).counter().count();
        final double failure = registry.get(SCHEDULER_RUNS)
                .tag(TAG_SCHEDULER, INTRADAY_DISCOVERY).tag(TAG_OUTCOME, OUTCOME_FAILURE).counter().count();

        assertThat(success).isEqualTo(1.0);
        assertThat(failure).isEqualTo(1.0);
        assertThat(success + failure).isEqualTo(2.0);
    }

    @Test
    void run_shouldPropagateError_andStillRecordExactlyOneFailure_whenDiscoveryThrowsError() {
        doThrow(new TestError()).when(discoveryService).runIntradayDiscovery();

        final Logger logger = (Logger) LoggerFactory.getLogger(IntradayDiscoveryScheduler.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when / then
            assertThatCode(scheduler::run).isInstanceOf(TestError.class);
            verify(schedulerMetrics, times(1)).recordRun(eq(INTRADAY_DISCOVERY), eq(false));

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

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockObjectProvider() {
        return mock(ObjectProvider.class);
    }
}
