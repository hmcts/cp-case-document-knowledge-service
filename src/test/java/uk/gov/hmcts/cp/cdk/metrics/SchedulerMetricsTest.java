package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.INTRADAY_DISCOVERY;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.NIGHTLY_DISCOVERY;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.OUTCOME_FAILURE;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.OUTCOME_SUCCESS;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.SCHEDULER_ENABLED;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.SCHEDULER_LAST_SUCCESS;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.SCHEDULER_RUNS;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.TAG_OUTCOME;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.TAG_SCHEDULER;

import uk.gov.hmcts.cp.cdk.scheduler.IntradayDiscoveryScheduler;
import uk.gov.hmcts.cp.cdk.scheduler.NightlyDiscoveryScheduler;
import uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("SchedulerMetrics tests")
class SchedulerMetricsTest {

    @Test
    @DisplayName("all six Story-1 series exist at 0 immediately after construction (AC-005)")
    void shouldPreRegisterAllRunAndHeartbeatSeriesAtZero_whenConstructed() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry, true, true);

        for (final String schedulerTag : new String[] {INTRADAY_DISCOVERY, NIGHTLY_DISCOVERY}) {
            for (final String outcome : new String[] {OUTCOME_SUCCESS, OUTCOME_FAILURE}) {
                assertThat(counter(registry, schedulerTag, outcome))
                        .as("runs counter scheduler=%s outcome=%s", schedulerTag, outcome)
                        .isZero();
            }
            assertThat(heartbeat(registry, schedulerTag))
                    .as("heartbeat gauge scheduler=%s", schedulerTag)
                    .isZero();
        }
    }

    @Test
    @DisplayName("recordRun mutates only the series it should (AC-001, AC-002)")
    void shouldIncrementOnlyTheMatchingCounter_andAdvanceHeartbeatOnSuccessOnly() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final SchedulerMetrics metrics = newMetrics(registry, true, true);

        metrics.recordRun(INTRADAY_DISCOVERY, true);
        metrics.recordRun(NIGHTLY_DISCOVERY, false);

        assertThat(counter(registry, INTRADAY_DISCOVERY, OUTCOME_SUCCESS)).isEqualTo(1.0);
        assertThat(counter(registry, INTRADAY_DISCOVERY, OUTCOME_FAILURE)).isZero();
        assertThat(heartbeat(registry, INTRADAY_DISCOVERY)).isGreaterThan(0.0);

        assertThat(counter(registry, NIGHTLY_DISCOVERY, OUTCOME_FAILURE)).isEqualTo(1.0);
        assertThat(counter(registry, NIGHTLY_DISCOVERY, OUTCOME_SUCCESS)).isZero();
        assertThat(heartbeat(registry, NIGHTLY_DISCOVERY))
                .as("a failed run must never advance the heartbeat (FR-009)")
                .isZero();
    }

    @Test
    @DisplayName("enabledGauge reports 0 for both schedulers when both flags are false, "
            + "even though neither scheduler bean exists (AC-001, Scenario 2.1)")
    void enabledGauge_shouldReportZeroForBothSchedulers_whenBothFlagsAreFalse() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // SchedulerMetrics holds only ObjectProviders, never a direct scheduler-bean dependency,
        // so it can be constructed with providers that resolve to nothing at all — exactly the
        // "bean was never created" case @ConditionalOnProperty produces when a flag is false.
        newMetrics(registry, false, false, absentProvider(), absentProvider());

        assertThat(enabledGauge(registry, INTRADAY_DISCOVERY)).isZero();
        assertThat(enabledGauge(registry, NIGHTLY_DISCOVERY)).isZero();
    }

    @ParameterizedTest(name = "intraday={0} nightly={1} -> intradayGauge={2} nightlyGauge={3}")
    @CsvSource({
            "true,  false, 1.0, 0.0",
            "false, true,  0.0, 1.0",
            "true,  true,  1.0, 1.0",
            "false, false, 0.0, 0.0"
    })
    @DisplayName("enabledGauge reports per-scheduler state independently (AC-002, Scenario 2.2)")
    void enabledGauge_shouldReportPerSchedulerState_whenFlagsDiffer(final boolean intradayEnabled,
                                                                      final boolean nightlyEnabled,
                                                                      final double expectedIntraday,
                                                                      final double expectedNightly) {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry, intradayEnabled, nightlyEnabled);

        assertThat(enabledGauge(registry, INTRADAY_DISCOVERY)).isEqualTo(expectedIntraday);
        assertThat(enabledGauge(registry, NIGHTLY_DISCOVERY)).isEqualTo(expectedNightly);
    }

    @Test
    @DisplayName("startup listener logs INFO exactly once per scheduler, never from the constructor (AC-003, Scenario 2.3)")
    void shouldLogEnabledStateOncePerScheduler_onApplicationReady() {
        final Logger logger = (Logger) LoggerFactory.getLogger(SchedulerMetrics.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        final SchedulerMetrics metrics;
        try {
            metrics = newMetrics(new SimpleMeterRegistry(), true, false);
            assertThat(appender.list)
                    .as("no log line is emitted from the constructor")
                    .isEmpty();

            metrics.logConfiguredStateAndCheckForDrift();
        } finally {
            logger.detachAppender(appender);
        }

        final List<ILoggingEvent> infoEvents = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents).hasSize(2);
        assertThat(infoEvents.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(m -> m.contains(INTRADAY_DISCOVERY))
                .anyMatch(m -> m.contains(NIGHTLY_DISCOVERY));
    }

    @Test
    @DisplayName("startup listener warns without throwing when the bound flag disagrees with bean presence (AC-004, Scenario 2.4)")
    void shouldWarn_whenBoundEnabledFlagDisagreesWithBeanPresence() {
        final Logger logger = (Logger) LoggerFactory.getLogger(SchedulerMetrics.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // flag says enabled=true, but the provider resolves to no bean at all -> drift.
            final SchedulerMetrics metrics =
                    newMetrics(new SimpleMeterRegistry(), true, false, absentProvider(), absentProvider());
            assertThatCode(metrics::logConfiguredStateAndCheckForDrift).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(appender);
        }

        final List<ILoggingEvent> warnEvents = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnEvents).hasSize(1);
        assertThat(warnEvents.get(0).getFormattedMessage()).contains(INTRADAY_DISCOVERY);
    }

    @Test
    @DisplayName("startup listener does not warn when the bound flag agrees with bean presence")
    void shouldNotWarn_whenBoundEnabledFlagAgreesWithBeanPresence() {
        final Logger logger = (Logger) LoggerFactory.getLogger(SchedulerMetrics.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            @SuppressWarnings("unchecked")
            final ObjectProvider<IntradayDiscoveryScheduler> intradayProvider = mock(ObjectProvider.class);
            when(intradayProvider.getIfAvailable()).thenReturn(mock(IntradayDiscoveryScheduler.class));

            final SchedulerMetrics metrics =
                    newMetrics(new SimpleMeterRegistry(), true, false, intradayProvider, absentProvider());
            metrics.logConfiguredStateAndCheckForDrift();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("all three scheduler meter families agree on the scheduler tag value (AC-006, Scenario 2.6)")
    void shouldUseTheSameSchedulerTagValuesAcrossAllThreeMeterFamilies() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final SchedulerMetrics metrics = newMetrics(registry, true, true);
        metrics.recordRun(INTRADAY_DISCOVERY, true);
        metrics.recordRun(NIGHTLY_DISCOVERY, true);

        assertThat(tagValuesFor(registry, SCHEDULER_ENABLED))
                .isEqualTo(Set.of("intraday-discovery", "nightly-discovery"));
        assertThat(tagValuesFor(registry, SCHEDULER_LAST_SUCCESS))
                .isEqualTo(Set.of("intraday-discovery", "nightly-discovery"));
        assertThat(tagValuesFor(registry, SCHEDULER_RUNS))
                .isEqualTo(Set.of("intraday-discovery", "nightly-discovery"));
    }

    private static Set<String> tagValuesFor(final SimpleMeterRegistry registry, final String meterName) {
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(meterName))
                .map(m -> m.getId().getTag(TAG_SCHEDULER))
                .collect(Collectors.toSet());
    }

    private static SchedulerMetrics newMetrics(final SimpleMeterRegistry registry,
                                                final boolean intradayEnabled,
                                                final boolean nightlyEnabled) {
        return newMetrics(registry, intradayEnabled, nightlyEnabled, absentProvider(), absentProvider());
    }

    private static SchedulerMetrics newMetrics(final SimpleMeterRegistry registry,
                                                final boolean intradayEnabled,
                                                final boolean nightlyEnabled,
                                                final ObjectProvider<IntradayDiscoveryScheduler> intradayProvider,
                                                final ObjectProvider<NightlyDiscoveryScheduler> nightlyProvider) {
        final SchedulerProperties properties = new SchedulerProperties();
        properties.getIntradayDiscovery().setEnabled(intradayEnabled);
        properties.getNightlyDiscovery().setEnabled(nightlyEnabled);
        return new SchedulerMetrics(registry, properties, intradayProvider, nightlyProvider);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absentProvider() {
        return mock(ObjectProvider.class);
    }

    private static double counter(final SimpleMeterRegistry registry, final String schedulerTag,
                                   final String outcome) {
        return registry.get(SCHEDULER_RUNS)
                .tag(TAG_SCHEDULER, schedulerTag)
                .tag(TAG_OUTCOME, outcome)
                .counter().count();
    }

    private static double heartbeat(final SimpleMeterRegistry registry, final String schedulerTag) {
        return registry.get(SCHEDULER_LAST_SUCCESS)
                .tag(TAG_SCHEDULER, schedulerTag)
                .gauge().value();
    }

    private static double enabledGauge(final SimpleMeterRegistry registry, final String schedulerTag) {
        return registry.get(SCHEDULER_ENABLED)
                .tag(TAG_SCHEDULER, schedulerTag)
                .gauge().value();
    }
}
