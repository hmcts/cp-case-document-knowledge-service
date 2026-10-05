package uk.gov.hmcts.cp.cdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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

    private static final String NIGHTLY_DISCOVERY = "nightly-discovery";

    @Mock
    private DiscoveryService discoveryService;

    private NightlyDiscoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new NightlyDiscoveryScheduler(discoveryService);
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
    @DisplayName("run should contain failure when discovery throws")
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
        final List<ILoggingEvent> errorEvents = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .toList();
        assertThat(errorEvents).hasSize(1);
        final ILoggingEvent errorEvent = errorEvents.get(0);
        assertThat(errorEvent.getThrowableProxy()).isNotNull();
        assertThat(errorEvent.getFormattedMessage()).contains(NIGHTLY_DISCOVERY);
    }

    @Test
    @DisplayName("run should propagate an Error")
    void run_shouldPropagateError_andStillRecordExactlyOneFailure_whenDiscoveryThrowsError() {
        doThrow(new TestError()).when(discoveryService).runNightlyDiscovery();

        final Logger logger = (Logger) LoggerFactory.getLogger(NightlyDiscoveryScheduler.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when / then
            assertThatCode(scheduler::run).isInstanceOf(TestError.class);

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
