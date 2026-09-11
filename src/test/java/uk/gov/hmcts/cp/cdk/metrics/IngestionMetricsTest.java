package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.EXCEEDED_FILE_SIZE_LIMIT;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.FAILED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.INGESTED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.INGESTING;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.NOT_FOUND;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.UPLOADED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.UPLOADING;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.WAITING_FOR_UPLOAD;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IngestionMetrics tests (DD-43182 Stories 1–2)")
class IngestionMetricsTest {

    private SimpleMeterRegistry registry;
    private IngestionMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new IngestionMetrics(registry);
    }

    @Test
    @DisplayName("AC-005: all five phase x source=IDPC series exist at value 0 immediately after construction")
    void allFivePhaseSeriesPreRegisteredAtZero() {
        for (final String phase : new String[] {"WAITING_FOR_UPLOAD", "UPLOADED", "INGESTED", "FAILED",
                "EXCEEDED_FILE_SIZE_LIMIT"}) {
            assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_PHASE)
                    .tag(CdkMeters.TAG_PHASE, phase)
                    .tag(CdkMeters.TAG_SOURCE, CdkMeters.SOURCE_IDPC)
                    .counter().count()).isEqualTo(0.0);
        }
    }

    @Test
    @DisplayName("AC-001: one increment per recorded transition, tagged with the written phase")
    void oneIncrementPerRecordedTransition() {
        metrics.recordPhaseTransition(WAITING_FOR_UPLOAD, "IDPC");

        assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_PHASE)
                .tag(CdkMeters.TAG_PHASE, "WAITING_FOR_UPLOAD")
                .tag(CdkMeters.TAG_SOURCE, CdkMeters.SOURCE_IDPC)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-002: UPLOADING, INGESTING and NOT_FOUND are never registered and never increment")
    void unreachablePhasesNeverIncrement() {
        metrics.recordPhaseTransition(UPLOADING, "IDPC");
        metrics.recordPhaseTransition(INGESTING, "IDPC");
        metrics.recordPhaseTransition(NOT_FOUND, "IDPC");

        assertThat(registry.getMeters()).noneMatch(m ->
                "UPLOADING".equals(m.getId().getTag(CdkMeters.TAG_PHASE))
                        || "INGESTING".equals(m.getId().getTag(CdkMeters.TAG_PHASE))
                        || "NOT_FOUND".equals(m.getId().getTag(CdkMeters.TAG_PHASE)));
    }

    @Test
    @DisplayName("AC-003: source is resolved through the allow-list — IDPC passes through, "
            + "anything else becomes 'unknown'")
    void sourceResolvedThroughAllowList() {
        metrics.recordPhaseTransition(INGESTED, "IDPC");
        metrics.recordPhaseTransition(INGESTED, "some-future-value");
        metrics.recordPhaseTransition(INGESTED, null);

        assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_PHASE)
                .tag(CdkMeters.TAG_PHASE, "INGESTED").tag(CdkMeters.TAG_SOURCE, CdkMeters.SOURCE_IDPC)
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_PHASE)
                .tag(CdkMeters.TAG_PHASE, "INGESTED").tag(CdkMeters.TAG_SOURCE, CdkMeters.SOURCE_UNKNOWN)
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("AC-004: no tag value on this meter is anything other than a fixed, enumerated member")
    void everyTagValueIsFromTheFixedSet() {
        metrics.recordPhaseTransition(INGESTED, "IDPC");
        metrics.recordPhaseTransition(FAILED, "unexpected-value");

        registry.getMeters().stream()
                .filter(m -> CdkMeters.DOCUMENT_INGESTION_PHASE.equals(m.getId().getName()))
                .forEach(m -> {
                    assertThat(m.getId().getTag(CdkMeters.TAG_PHASE)).isIn(
                            "WAITING_FOR_UPLOAD", "UPLOADED", "INGESTED", "FAILED", "EXCEEDED_FILE_SIZE_LIMIT");
                    assertThat(m.getId().getTag(CdkMeters.TAG_SOURCE)).isIn("IDPC", "unknown");
                });
    }

    @Test
    @DisplayName("AC-006: a metric-recording failure is contained — a null phase does not throw")
    void nullPhaseIsContainedNotThrown() {
        metrics.recordPhaseTransition(null, "IDPC");
        // no exception — the surrounding business call would complete exactly as without instrumentation
    }

    // --- Story 2: ingestion duration timer ---

    @Test
    @DisplayName("AC-001: a terminal phase records one duration observation equal to the elapsed time")
    void terminalPhaseRecordsDuration() {
        final OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        final OffsetDateTime terminalAt = createdAt.plusMinutes(3);

        metrics.recordIngestionDuration(INGESTED, createdAt, terminalAt);

        assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_DURATION)
                .tag(CdkMeters.TAG_PHASE, "INGESTED").timer().count()).isEqualTo(1);
        assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_DURATION)
                .tag(CdkMeters.TAG_PHASE, "INGESTED").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(180.0);
    }

    @Test
    @DisplayName("AC-002: a non-terminal phase (UPLOADED) contributes no duration observation")
    void nonTerminalPhaseRecordsNoDuration() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        metrics.recordIngestionDuration(UPLOADED, now, now);

        assertThat(registry.getMeters()).noneMatch(m ->
                CdkMeters.DOCUMENT_INGESTION_DURATION.equals(m.getId().getName())
                        && "UPLOADED".equals(m.getId().getTag(CdkMeters.TAG_PHASE)));
    }

    @Test
    @DisplayName("AC-004: a negative computed duration (clock skew) is clamped to zero, not negative")
    void negativeDurationIsClampedToZero() {
        final OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        final OffsetDateTime terminalAt = createdAt.minusSeconds(30); // terminal "before" created — clock skew

        metrics.recordIngestionDuration(FAILED, createdAt, terminalAt);

        assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_DURATION)
                .tag(CdkMeters.TAG_PHASE, "FAILED").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(0.0);
        assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_DURATION)
                .tag(CdkMeters.TAG_PHASE, "FAILED").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-003: the three terminal phases all have SLO bucket series present")
    void allThreeTerminalPhasesHaveSloBuckets() {
        for (final var phase : new uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase[] {
                INGESTED, FAILED, EXCEEDED_FILE_SIZE_LIMIT}) {
            assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_DURATION)
                    .tag(CdkMeters.TAG_PHASE, phase.name()).timer()).isNotNull();
        }
    }

    @Test
    @DisplayName("AC-006: a null timestamp is contained — records nothing, does not throw")
    void nullTimestampRecordsNothingAndDoesNotThrow() {
        metrics.recordIngestionDuration(INGESTED, null, OffsetDateTime.now(ZoneOffset.UTC));
        metrics.recordIngestionDuration(INGESTED, OffsetDateTime.now(ZoneOffset.UTC), null);

        assertThat(registry.get(CdkMeters.DOCUMENT_INGESTION_DURATION)
                .tag(CdkMeters.TAG_PHASE, "INGESTED").timer().count()).isEqualTo(0);
    }
}
