package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import uk.gov.hmcts.cp.openapi.model.cdk.DiscoveryOperation;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskExecutor;

@DisplayName("DiscoveryTriggerService tests (Story 3, DD-43062; logging tests Story 4, DD-43063)")
class DiscoveryTriggerServiceTest {

    private final DiscoveryService discoveryService = mock(DiscoveryService.class);
    private final TaskExecutor discoveryTriggerExecutor = mock(TaskExecutor.class);
    private final DiscoveryTriggerService service =
            new DiscoveryTriggerService(discoveryService, discoveryTriggerExecutor);

    @Test
    @DisplayName("INTRADAY routes to runIntradayDiscovery via the executor, not the calling thread")
    void trigger_intraday_routesToRunIntradayDiscovery_offCallingThread() {
        service.trigger(DiscoveryOperation.INTRADAY);

        verify(discoveryService, never()).runIntradayDiscovery();
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(discoveryTriggerExecutor).execute(captor.capture());

        captor.getValue().run();
        verify(discoveryService, times(1)).runIntradayDiscovery();
        verify(discoveryService, never()).runNightlyDiscovery();
    }

    @Test
    @DisplayName("NIGHTLY routes to runNightlyDiscovery via the executor, not the calling thread")
    void trigger_nightly_routesToRunNightlyDiscovery_offCallingThread() {
        service.trigger(DiscoveryOperation.NIGHTLY);

        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(discoveryTriggerExecutor).execute(captor.capture());

        captor.getValue().run();
        verify(discoveryService, times(1)).runNightlyDiscovery();
        verify(discoveryService, never()).runIntradayDiscovery();
    }

    @Test
    @DisplayName("an exception escaping the delegated run is caught and logged, not rethrown (AC-022)")
    void trigger_delegateThrows_isCaughtNotRethrown() {
        doThrow(new RuntimeException("boom")).when(discoveryService).runIntradayDiscovery();

        service.trigger(DiscoveryOperation.INTRADAY);

        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(discoveryTriggerExecutor).execute(captor.capture());

        assertThatCode(() -> captor.getValue().run()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("worker run logs one correlated start + finished pair, tagged trigger=manual, no PII (AC-018-020)")
    void trigger_emitsSingleCorrelatedStartFinishedLogPair_onSuccess() {
        final Logger logger = (Logger) LoggerFactory.getLogger(DiscoveryTriggerService.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            MDC.put("correlationId", "corr-xyz");
            service.trigger(DiscoveryOperation.NIGHTLY);
            final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(discoveryTriggerExecutor).execute(captor.capture());
            captor.getValue().run();
        } finally {
            logger.detachAppender(appender);
            MDC.clear();
        }

        final List<ILoggingEvent> events = appender.list;
        final List<ILoggingEvent> startEvents = events.stream()
                .filter(e -> e.getFormattedMessage().contains("starting")).toList();
        final List<ILoggingEvent> completionEvents = events.stream()
                .filter(e -> e.getFormattedMessage().contains("finished")).toList();

        assertThat(startEvents).hasSize(1);
        assertThat(completionEvents).hasSize(1);
        for (final ILoggingEvent event : events) {
            assertThat(event.getMDCPropertyMap())
                    .containsEntry("correlationId", "corr-xyz")
                    .containsEntry("trigger", "manual")
                    .containsKey("discoveryOperation");
            assertThat(event.getFormattedMessage())
                    .doesNotContainIgnoringCase("cjscppuid")
                    .doesNotContainIgnoringCase("courtCentre")
                    .doesNotContainIgnoringCase("caseId");
        }
    }

    @Test
    @DisplayName("worker run logs one correlated start + failed pair on error, MDC discriminators present (AC-018-020)")
    void trigger_emitsSingleCorrelatedStartFailedLogPair_onException() {
        doThrow(new RuntimeException("boom")).when(discoveryService).runIntradayDiscovery();

        final Logger logger = (Logger) LoggerFactory.getLogger(DiscoveryTriggerService.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            MDC.put("correlationId", "corr-fail");
            service.trigger(DiscoveryOperation.INTRADAY);
            final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(discoveryTriggerExecutor).execute(captor.capture());
            captor.getValue().run();
        } finally {
            logger.detachAppender(appender);
            MDC.clear();
        }

        final List<ILoggingEvent> events = appender.list;
        final List<ILoggingEvent> startEvents = events.stream()
                .filter(e -> e.getFormattedMessage().contains("starting")).toList();
        final List<ILoggingEvent> failedEvents = events.stream()
                .filter(e -> e.getFormattedMessage().contains("failed")).toList();

        assertThat(startEvents).hasSize(1);
        assertThat(failedEvents).hasSize(1);
        for (final ILoggingEvent event : events) {
            assertThat(event.getMDCPropertyMap()).containsEntry("trigger", "manual").containsKey("discoveryOperation");
        }
    }

    @Test
    @DisplayName("MDC discriminator fields (trigger, discoveryOperation) are cleared after the run completes")
    void trigger_clearsMdcDiscriminatorFields_afterRun() {
        service.trigger(DiscoveryOperation.INTRADAY);
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(discoveryTriggerExecutor).execute(captor.capture());
        captor.getValue().run();

        assertThat(MDC.get("trigger")).isNull();
        assertThat(MDC.get("discoveryOperation")).isNull();
    }
}
