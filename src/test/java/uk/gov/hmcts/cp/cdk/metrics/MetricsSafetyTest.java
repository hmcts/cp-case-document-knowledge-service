package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("MetricsSafety tests (DD-43182, ADR-010)")
class MetricsSafetyTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        MetricsSafety.resetForTesting();
        logger = (Logger) LoggerFactory.getLogger(MetricsSafety.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        MetricsSafety.resetForTesting();
    }

    @Test
    @DisplayName("runSafely executes the recording block normally when it does not throw")
    void runSafelyExecutesNormally() {
        final AtomicInteger ran = new AtomicInteger(0);

        MetricsSafety.runSafely(ran::incrementAndGet);

        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("runSafely catches an Exception thrown by the recording block and logs one WARN")
    void runSafelyCatchesExceptionAndWarns() {
        MetricsSafety.runSafely(() -> {
            throw new IllegalStateException("boom");
        });

        assertThat(warnEvents()).hasSize(1);
        assertThat(warnEvents().get(0).getFormattedMessage()).contains("metric recording failed");
    }

    @Test
    @DisplayName("runSafely lets an Error propagate — never caught")
    void runSafelyLetsErrorPropagate() {
        assertThatThrownBy(() -> MetricsSafety.runSafely(() -> {
            throw new OutOfMemoryError("simulated");
        })).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("at most one WARN is logged per 60-second window, using the injectable time source")
    void atMostOneWarnPer60SecondWindow() {
        MetricsSafety.epochSecondSource = () -> 1000L;
        MetricsSafety.warnThrottled("first failure");
        MetricsSafety.warnThrottled("second failure, same window");
        MetricsSafety.warnThrottled("third failure, same window");

        assertThat(warnEvents()).hasSize(1);
    }

    @Test
    @DisplayName("a new WARN fires once the 60-second window has elapsed, carrying the suppressed count")
    void newWarnFiresAfterWindowElapses() {
        MetricsSafety.epochSecondSource = () -> 1000L;
        MetricsSafety.warnThrottled("first failure");
        MetricsSafety.warnThrottled("suppressed 1");
        MetricsSafety.warnThrottled("suppressed 2");

        MetricsSafety.epochSecondSource = () -> 1061L;
        MetricsSafety.warnThrottled("fourth failure, new window");

        assertThat(warnEvents()).hasSize(2);
        assertThat(warnEvents().get(1).getFormattedMessage()).contains("suppressed 2");
    }

    @Test
    @DisplayName("the very first WARN ever is never suppressed")
    void firstWarnEverIsNeverSuppressed() {
        MetricsSafety.warnThrottled("first ever");

        assertThat(warnEvents()).hasSize(1);
        assertThat(warnEvents().get(0).getFormattedMessage()).contains("suppressed 0");
    }

    private List<ILoggingEvent> warnEvents() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }
}
