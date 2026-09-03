package uk.gov.hmcts.cp.cdk.metrics;

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
import uk.gov.hmcts.cp.cdk.util.TimeUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Stuck-work gauges over {@code case_documents} and {@code case_query_status} (DD-43185, FR-001,
 * FR-002, design §8). Always present — meter registration does not depend on
 * {@code cdk.monitoring.enabled}; only the scheduled refresh ({@link StalledWorkMetricsRefreshJob})
 * is gated by that flag, so every series exists (at its last-known value) regardless.
 *
 * <p>Because the refresh is ShedLock-guarded (FR-004), only one pod's copy is fresh on any given
 * tick (ADR-008) — {@link CdkMeters#MONITORING_LAST_REFRESH} lets a consumer tell a current
 * reading from a stale one.
 */
@Slf4j
@Component
public class StalledWorkMetrics {

    private static final List<String> MONITORED_PHASES = List.of(
            PHASE_WAITING_FOR_UPLOAD, PHASE_UPLOADING, PHASE_UPLOADED, PHASE_INGESTING);

    private final CaseDocumentRepository caseDocumentRepository;
    private final CaseQueryStatusRepository caseQueryStatusRepository;
    private final MonitoringProperties monitoringProperties;

    private final Map<String, AtomicLong> stalledByPhase;
    private final AtomicLong queriesAwaitingAnswer = new AtomicLong(0L);
    private final AtomicLong lastRefreshEpochSeconds = new AtomicLong(0L);

    public StalledWorkMetrics(final MeterRegistry registry,
                               final CaseDocumentRepository caseDocumentRepository,
                               final CaseQueryStatusRepository caseQueryStatusRepository,
                               final MonitoringProperties monitoringProperties) {
        this.caseDocumentRepository = caseDocumentRepository;
        this.caseQueryStatusRepository = caseQueryStatusRepository;
        this.monitoringProperties = monitoringProperties;

        this.stalledByPhase = Map.of(
                PHASE_WAITING_FOR_UPLOAD, new AtomicLong(0L),
                PHASE_UPLOADING, new AtomicLong(0L),
                PHASE_UPLOADED, new AtomicLong(0L),
                PHASE_INGESTING, new AtomicLong(0L));

        for (final String phase : MONITORED_PHASES) {
            final AtomicLong value = stalledByPhase.get(phase);
            Gauge.builder(DOCUMENTS_STALLED, value, AtomicLong::doubleValue)
                    .description("Count of case_documents rows stalled in this ingestion phase, "
                            + "older than the configured threshold")
                    .tag(TAG_PHASE, phase)
                    .strongReference(true)
                    .register(registry);
        }

        Gauge.builder(QUERIES_AWAITING_ANSWER, queriesAwaitingAnswer, AtomicLong::doubleValue)
                .description("Count of case_query_status rows awaiting an answer, "
                        + "older than the configured threshold")
                .strongReference(true)
                .register(registry);

        Gauge.builder(MONITORING_LAST_REFRESH, lastRefreshEpochSeconds, AtomicLong::doubleValue)
                .description("Epoch seconds this pod last successfully refreshed at least one "
                        + "stuck-work aggregate")
                .strongReference(true)
                .register(registry);
    }

    /**
     * Refreshes every stuck-work gauge. The two aggregates are independent: a failure in one
     * leaves its gauges at their last successfully-computed value and does not prevent the other
     * from updating (FR-006). Never throws — the caller ({@link StalledWorkMetricsRefreshJob})
     * relies on that so nothing escapes into Spring's scheduler.
     */
    public void refresh() {
        MDC.put("job", "stalled-work-metrics-refresh");
        MDC.put("correlationId", UUID.randomUUID().toString());
        try {
            final OffsetDateTime cutoff = TimeUtils.utcNow().minus(monitoringProperties.getStalledThreshold());

            final boolean documentsRefreshed = refreshStalledDocuments(cutoff);
            final boolean queriesRefreshed = refreshQueriesAwaitingAnswer(cutoff);

            if (documentsRefreshed || queriesRefreshed) {
                lastRefreshEpochSeconds.set(TimeUtils.utcNow().toEpochSecond());
            }
        } finally {
            MDC.remove("job");
            MDC.remove("correlationId");
        }
    }

    private boolean refreshStalledDocuments(final OffsetDateTime cutoff) {
        try {
            final List<PhaseCount> counts = caseDocumentRepository.countStalledByPhase(cutoff);
            final Map<String, Long> byPhase = counts.stream()
                    .collect(Collectors.toMap(PhaseCount::getPhase, PhaseCount::getTotal));
            for (final String phase : MONITORED_PHASES) {
                stalledByPhase.get(phase).set(byPhase.getOrDefault(phase, 0L));
            }
            return true;
        } catch (final Exception e) {
            log.warn("Stalled-documents aggregate refresh failed; gauges retain their last value", e);
            return false;
        }
    }

    private boolean refreshQueriesAwaitingAnswer(final OffsetDateTime cutoff) {
        try {
            queriesAwaitingAnswer.set(caseQueryStatusRepository.countAwaitingAnswerOlderThan(cutoff));
            return true;
        } catch (final Exception e) {
            log.warn("Queries-awaiting-answer aggregate refresh failed; gauge retains its last value", e);
            return false;
        }
    }
}
