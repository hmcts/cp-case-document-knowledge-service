package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("StalledWorkMetricsRefreshJob tests")
class StalledWorkMetricsRefreshJobTest {

    @Test
    @DisplayName("run() delegates to StalledWorkMetrics.refresh()")
    void run_delegatesToRefresh() {
        final StalledWorkMetrics metrics = mock(StalledWorkMetrics.class);
        final StalledWorkMetricsRefreshJob job = new StalledWorkMetricsRefreshJob(metrics);

        job.run();

        verify(metrics).refresh();
    }

    @Test
    @DisplayName("run() catches and WARN-logs an unexpected exception from refresh() rather than "
            + "letting it escape into Spring's TaskScheduler — the last-resort backstop, since "
            + "refresh() itself already contains its own per-aggregate failures")
    void run_catchesAndLogsBackstop_whenRefreshThrowsUnexpectedly() {
        final StalledWorkMetrics metrics = mock(StalledWorkMetrics.class);
        doThrow(new RuntimeException("unexpected")).when(metrics).refresh();
        final StalledWorkMetricsRefreshJob job = new StalledWorkMetricsRefreshJob(metrics);

        final Logger logger = (Logger) LoggerFactory.getLogger(StalledWorkMetricsRefreshJob.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatCode(job::run).doesNotThrowAnyException();

            final List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
