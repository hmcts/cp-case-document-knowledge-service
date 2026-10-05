package uk.gov.hmcts.cp.cdk.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Captures log events for one class. Use with try-with-resources, or open in {@code @BeforeEach}
 * and {@link #close()} in {@code @AfterEach}.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(final Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture forClass(final Class<?> type) {
        return new LogCapture(type);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** Events whose formatted message would be counted by the given dashboard tile segment. */
    public List<ILoggingEvent> matching(final DashboardKql.Segment segment) {
        return appender.list.stream().filter(e -> segment.matches(e.getFormattedMessage())).toList();
    }

    /**
     * Asserts the code under test emitted exactly {@code times} lines that the dashboard tile segment
     * would count, all at {@code level}. Fails if the log text drifts from the {@code .kql} file.
     */
    public void assertDashboardLine(final DashboardKql.Segment segment, final Level level, final int times) {
        final List<ILoggingEvent> matched = matching(segment);
        assertThat(matched)
                .as("log lines counted by dashboard tile %s%nall captured messages:%n%s",
                        segment, appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .hasSize(times);
        assertThat(matched).allSatisfy(e -> assertThat(e.getLevel())
                .as("level of dashboard line %s", segment)
                .isEqualTo(level));
    }

    public void assertDashboardLine(final DashboardKql.Segment segment, final Level level) {
        assertDashboardLine(segment, level, 1);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
