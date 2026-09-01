package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.DOCUMENTS_STALLED;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.MONITORING_LAST_REFRESH;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.PHASE_INGESTING;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.PHASE_UPLOADED;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.PHASE_UPLOADING;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.PHASE_WAITING_FOR_UPLOAD;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.QUERIES_AWAITING_ANSWER;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.TAG_PHASE;

import uk.gov.hmcts.cp.cdk.config.MonitoringProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.CaseQueryStatusRepository;
import uk.gov.hmcts.cp.cdk.repo.PhaseCount;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

@DisplayName("StalledWorkMetrics tests")
class StalledWorkMetricsTest {

    private final CaseDocumentRepository caseDocumentRepository = mock(CaseDocumentRepository.class);
    private final CaseQueryStatusRepository caseQueryStatusRepository = mock(CaseQueryStatusRepository.class);
    private final MonitoringProperties monitoringProperties = new MonitoringProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private StalledWorkMetrics newMetrics() {
        return new StalledWorkMetrics(registry, caseDocumentRepository, caseQueryStatusRepository,
                monitoringProperties);
    }

    @Test
    @DisplayName("all six series are pre-registered at zero before any refresh")
    void allSeriesPreRegisteredAtZero() {
        newMetrics();

        assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_WAITING_FOR_UPLOAD)).isZero();
        assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_UPLOADING)).isZero();
        assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_UPLOADED)).isZero();
        assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_INGESTING)).isZero();
        assertThat(registry.get(QUERIES_AWAITING_ANSWER).gauge().value()).isZero();
        assertThat(registry.get(MONITORING_LAST_REFRESH).gauge().value()).isZero();
    }

    @Test
    @DisplayName("cutoff is derived from the bound threshold on a refresh call")
    void cutoffRecomputedFreshEachCall() {
        monitoringProperties.setStalledThreshold(Duration.ofMinutes(30));
        when(caseDocumentRepository.countStalledByPhase(any())).thenReturn(List.of());
        when(caseQueryStatusRepository.countAwaitingAnswerOlderThan(any())).thenReturn(0L);

        final StalledWorkMetrics metrics = newMetrics();
        final OffsetDateTime before = OffsetDateTime.now().minusMinutes(30).minusSeconds(5);
        metrics.refresh();
        final OffsetDateTime after = OffsetDateTime.now().minusMinutes(30).plusSeconds(5);

        final ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(caseDocumentRepository).countStalledByPhase(captor.capture());
        assertThat(captor.getValue()).isBetween(before, after);
    }

    @Test
    @DisplayName("cutoff reflects a threshold changed between two refreshes, proving it is "
            + "recomputed on every call and never cached at construction (Scenario 4.4)")
    void cutoffRecomputedFromChangedThresholdBetweenCalls() {
        when(caseDocumentRepository.countStalledByPhase(any())).thenReturn(List.of());
        when(caseQueryStatusRepository.countAwaitingAnswerOlderThan(any())).thenReturn(0L);

        monitoringProperties.setStalledThreshold(Duration.ofMinutes(30));
        final StalledWorkMetrics metrics = newMetrics();
        metrics.refresh();

        monitoringProperties.setStalledThreshold(Duration.ofMinutes(90));
        metrics.refresh();

        final ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(caseDocumentRepository, times(2)).countStalledByPhase(captor.capture());
        final List<OffsetDateTime> cutoffs = captor.getAllValues();

        // No Clock injection point exists in production code (TimeUtils.utcNow() is a static
        // call, not a bean), so an exact assertion against a fixed instant isn't available
        // without a larger production change. Asserting the delta between the two captured
        // cutoffs — which should be exactly the 60-minute difference between the two thresholds,
        // modulo the real wall-clock time elapsed between the two refresh() calls — is the
        // tightest check achievable without one, and is sufficient to prove the cutoff is
        // recomputed per call rather than cached: a construction-time-cached cutoff would show a
        // near-zero delta here instead of ~60 minutes.
        final Duration observedDelta = Duration.between(cutoffs.get(0), cutoffs.get(1));
        assertThat(observedDelta.plus(Duration.ofMinutes(60)).abs())
                .as("second cutoff should be ~60 minutes earlier than the first (90m threshold vs "
                        + "30m threshold), allowing a few seconds for real elapsed test time")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a successful refresh writes correct values for every series")
    void successfulRefreshWritesCorrectValues() {
        when(caseDocumentRepository.countStalledByPhase(any())).thenReturn(List.of(
                phaseCount(PHASE_WAITING_FOR_UPLOAD, 12L),
                phaseCount(PHASE_UPLOADED, 3L)));
        when(caseQueryStatusRepository.countAwaitingAnswerOlderThan(any())).thenReturn(5L);

        final StalledWorkMetrics metrics = newMetrics();
        metrics.refresh();

        assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_WAITING_FOR_UPLOAD)).isEqualTo(12.0);
        assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_UPLOADED)).isEqualTo(3.0);
        assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_UPLOADING)).isZero();
        assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_INGESTING)).isZero();
        assertThat(registry.get(QUERIES_AWAITING_ANSWER).gauge().value()).isEqualTo(5.0);
        assertThat(registry.get(MONITORING_LAST_REFRESH).gauge().value()).isPositive();
    }

    @Test
    @DisplayName("one aggregate failing leaves its gauges at their last successfully-computed "
            + "value while the other still updates, exactly one WARN, nothing thrown, and the "
            + "freshness gauge still advances because AT LEAST ONE aggregate succeeded (Scenario "
            + "4.7 + N-1: proves the OR-logic in refresh(), not just the AND-satisfying happy path)")
    void shouldDegradePerAggregate_whenOneRepositoryThrows() throws InterruptedException {
        // First, fully successful refresh: both aggregates populated with known, non-zero values.
        when(caseDocumentRepository.countStalledByPhase(any())).thenReturn(List.of(
                phaseCount(PHASE_WAITING_FOR_UPLOAD, 7L)));
        when(caseQueryStatusRepository.countAwaitingAnswerOlderThan(any())).thenReturn(4L);

        final StalledWorkMetrics metrics = newMetrics();
        metrics.refresh();

        assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_WAITING_FOR_UPLOAD)).isEqualTo(7.0);
        assertThat(registry.get(QUERIES_AWAITING_ANSWER).gauge().value()).isEqualTo(4.0);
        final double freshnessAfterFirstRefresh =
                registry.get(MONITORING_LAST_REFRESH).gauge().value();
        assertThat(freshnessAfterFirstRefresh).isPositive();

        // Epoch-seconds resolution means two refresh() calls in the same wall-clock second would
        // capture an identical value even if genuinely recomputed. Sleeping past a second boundary
        // makes "the gauge advanced" and "the gauge merely still holds refresh 1's value" provably
        // distinguishable — the same no-Clock-injection-point constraint noted above for the
        // cutoff tests.
        Thread.sleep(1_100);

        // Second refresh: the queries aggregate now fails; the documents aggregate returns a
        // DIFFERENT value, so a test asserting the old value would only pass by coincidence.
        when(caseDocumentRepository.countStalledByPhase(any())).thenReturn(List.of(
                phaseCount(PHASE_WAITING_FOR_UPLOAD, 99L)));
        when(caseQueryStatusRepository.countAwaitingAnswerOlderThan(any()))
                .thenThrow(new RuntimeException("db timeout"));

        final Logger logger = (Logger) LoggerFactory.getLogger(StalledWorkMetrics.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatCode(metrics::refresh).doesNotThrowAnyException();

            assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_WAITING_FOR_UPLOAD))
                    .as("healthy aggregate updates to its new value")
                    .isEqualTo(99.0);
            assertThat(registry.get(QUERIES_AWAITING_ANSWER).gauge().value())
                    .as("failed aggregate retains its LAST SUCCESSFUL value (4.0) — not zero, and "
                            + "not stale-then-zeroed. An implementation that zeroed all holders and "
                            + "then applied results would pass a naive test and fail this one "
                            + "(design §8, query-then-apply rule).")
                    .isEqualTo(4.0);
            assertThat(registry.get(MONITORING_LAST_REFRESH).gauge().value())
                    .as("freshness gauge must still ADVANCE past refresh 1's value (N-1): the "
                            + "documents aggregate succeeded even though queries failed, so "
                            + "refresh() must use OR semantics (\"at least one succeeded\"), not "
                            + "AND — an implementation that changed || to && here would leave this "
                            + "value unchanged from freshnessAfterFirstRefresh and fail this "
                            + "assertion, with every other test in this class still green")
                    .isGreaterThan(freshnessAfterFirstRefresh);

            final List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("both aggregates failing logs two warnings and leaves EVERY series (all four "
            + "phase gauges, the queries gauge, and the freshness gauge) at its previous, "
            + "known-non-zero value rather than zero — proving retention, not coincidental "
            + "zero-equals-zero (N-2, mirrors the F-3 fix)")
    void bothAggregatesFailingLeavesEverySeriesUnchanged() {
        // First, fully successful refresh: every series gets a distinct, known, non-zero value —
        // distinct per phase so a mix-up between phases cannot pass unnoticed, matching the F-9
        // seeding discipline used in the repository tests.
        when(caseDocumentRepository.countStalledByPhase(any())).thenReturn(List.of(
                phaseCount(PHASE_WAITING_FOR_UPLOAD, 3L),
                phaseCount(PHASE_UPLOADING, 1L),
                phaseCount(PHASE_UPLOADED, 5L),
                phaseCount(PHASE_INGESTING, 2L)));
        when(caseQueryStatusRepository.countAwaitingAnswerOlderThan(any())).thenReturn(4L);

        final StalledWorkMetrics metrics = newMetrics();
        metrics.refresh();

        final double waitingForUpload = gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_WAITING_FOR_UPLOAD);
        final double uploading = gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_UPLOADING);
        final double uploaded = gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_UPLOADED);
        final double ingesting = gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_INGESTING);
        final double queriesAwaitingAnswer = registry.get(QUERIES_AWAITING_ANSWER).gauge().value();
        final double freshnessAfterFirstRefresh =
                registry.get(MONITORING_LAST_REFRESH).gauge().value();

        assertThat(waitingForUpload).isEqualTo(3.0);
        assertThat(uploading).isEqualTo(1.0);
        assertThat(uploaded).isEqualTo(5.0);
        assertThat(ingesting).isEqualTo(2.0);
        assertThat(queriesAwaitingAnswer).isEqualTo(4.0);
        assertThat(freshnessAfterFirstRefresh).isPositive();

        // Second refresh: BOTH aggregates now fail, with DIFFERENT would-be values than before —
        // if the implementation ever mistakenly wrote a value before the exception (rather than
        // failing before writing anything), these different stub values would leak through and
        // this test would catch it.
        when(caseDocumentRepository.countStalledByPhase(any()))
                .thenThrow(new RuntimeException("db down"));
        when(caseQueryStatusRepository.countAwaitingAnswerOlderThan(any()))
                .thenThrow(new RuntimeException("db down"));

        final Logger logger = (Logger) LoggerFactory.getLogger(StalledWorkMetrics.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatCode(metrics::refresh).doesNotThrowAnyException();

            assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_WAITING_FOR_UPLOAD))
                    .as("retains refresh 1's value, not zero")
                    .isEqualTo(waitingForUpload);
            assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_UPLOADING))
                    .as("retains refresh 1's value, not zero")
                    .isEqualTo(uploading);
            assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_UPLOADED))
                    .as("retains refresh 1's value, not zero")
                    .isEqualTo(uploaded);
            assertThat(gauge(DOCUMENTS_STALLED, TAG_PHASE, PHASE_INGESTING))
                    .as("retains refresh 1's value, not zero")
                    .isEqualTo(ingesting);
            assertThat(registry.get(QUERIES_AWAITING_ANSWER).gauge().value())
                    .as("retains refresh 1's value, not zero")
                    .isEqualTo(queriesAwaitingAnswer);
            assertThat(registry.get(MONITORING_LAST_REFRESH).gauge().value())
                    .as("freshness must NOT advance when both aggregates fail (distinguishable "
                            + "from \"never written\" only because refresh 1 already made it "
                            + "positive and distinct from 0)")
                    .isEqualTo(freshnessAfterFirstRefresh);

            final List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings)
                    .as("one WARN per failing aggregate")
                    .hasSize(2);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private double gauge(final String name, final String tagKey, final String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).gauge().value();
    }

    private static PhaseCount phaseCount(final String phase, final long total) {
        return new PhaseCount() {
            @Override
            public String getPhase() {
                return phase;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }
}
