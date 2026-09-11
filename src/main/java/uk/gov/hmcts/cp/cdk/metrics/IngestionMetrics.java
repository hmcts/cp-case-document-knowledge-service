package uk.gov.hmcts.cp.cdk.metrics;

import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.EXCEEDED_FILE_SIZE_LIMIT;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.FAILED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.INGESTED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.UPLOADED;
import static uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase.WAITING_FOR_UPLOAD;

import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * The document-ingestion phase counter and end-to-end duration timer (DD-43182 Stories 1–2, ADR-002,
 * ADR-009). Shares one class and the same three {@code saveAndFlush} call sites deliberately — see
 * both stories' Notes.
 */
@Component
public class IngestionMetrics {

    /** The only {@code phase} values this counter can ever register — a transition counter for a
     * phase nothing writes ({@code UPLOADING}, {@code INGESTING}, {@code NOT_FOUND}) is not a
     * missed-failure risk the way a stall gauge's missing series would be (ADR-009(4)). */
    private static final Set<DocumentIngestionPhase> REACHABLE_PHASES = EnumSet.of(
            WAITING_FOR_UPLOAD, UPLOADED, INGESTED, FAILED, EXCEEDED_FILE_SIZE_LIMIT);

    /** The three real terminal phases the duration timer stops at (ADR-002(2)). */
    private static final Set<DocumentIngestionPhase> TERMINAL_PHASES = EnumSet.of(
            INGESTED, FAILED, EXCEEDED_FILE_SIZE_LIMIT);

    private final MeterRegistry registry;
    private final Map<DocumentIngestionPhase, Counter> phaseCountersForIdpc;
    private final Map<DocumentIngestionPhase, Timer> durationTimers;

    public IngestionMetrics(final MeterRegistry registry) {
        this.registry = registry;
        this.phaseCountersForIdpc = new EnumMap<>(DocumentIngestionPhase.class);
        this.durationTimers = new EnumMap<>(DocumentIngestionPhase.class);

        for (final DocumentIngestionPhase phase : REACHABLE_PHASES) {
            phaseCountersForIdpc.put(phase, Counter.builder(CdkMeters.DOCUMENT_INGESTION_PHASE)
                    .tag(CdkMeters.TAG_PHASE, phase.name())
                    .tag(CdkMeters.TAG_SOURCE, CdkMeters.SOURCE_IDPC)
                    .register(registry));
        }

        final Duration[] slos = CdkMeters.INGESTION_DURATION_SLOS.toArray(new Duration[0]);
        for (final DocumentIngestionPhase phase : TERMINAL_PHASES) {
            durationTimers.put(phase, Timer.builder(CdkMeters.DOCUMENT_INGESTION_DURATION)
                    .tag(CdkMeters.TAG_PHASE, phase.name())
                    .publishPercentileHistogram(false)
                    .serviceLevelObjectives(slos)
                    .register(registry));
        }
    }

    /**
     * Increments the ingestion-phase transition counter (Story 1, AC-001–AC-006). Called once,
     * immediately after the {@code saveAndFlush} that actually persisted {@code phase} — never on a
     * read.
     */
    public void recordPhaseTransition(final DocumentIngestionPhase phase, final String rawSource) {
        MetricsSafety.runSafely(() -> {
            if (phase == null || !REACHABLE_PHASES.contains(phase)) {
                return;
            }
            final String source = resolveSource(rawSource);
            if (CdkMeters.SOURCE_IDPC.equals(source)) {
                phaseCountersForIdpc.get(phase).increment();
            } else {
                registry.counter(CdkMeters.DOCUMENT_INGESTION_PHASE,
                        CdkMeters.TAG_PHASE, phase.name(),
                        CdkMeters.TAG_SOURCE, CdkMeters.SOURCE_UNKNOWN).increment();
            }
        });
    }

    /**
     * Records one ingestion-duration observation (Story 2, AC-001–AC-006) computed from two
     * persisted timestamps — never an in-process timer/sample, because the start and terminal
     * writes happen in different JobManager tasks, potentially different pods, minutes to hours
     * apart. A non-terminal phase, or either timestamp being null, records nothing.
     */
    public void recordIngestionDuration(final DocumentIngestionPhase terminalPhase,
                                        final OffsetDateTime createdAt, final OffsetDateTime terminalAt) {
        MetricsSafety.runSafely(() -> {
            final Timer timer = terminalPhase == null ? null : durationTimers.get(terminalPhase);
            if (timer == null || createdAt == null || terminalAt == null) {
                return;
            }
            Duration elapsed = Duration.between(createdAt, terminalAt);
            if (elapsed.isNegative()) {
                elapsed = Duration.ZERO;
                MetricsSafety.warnThrottled("ingestion duration clamped to zero (clock skew)");
            }
            timer.record(elapsed);
        });
    }

    private static String resolveSource(final String rawSource) {
        return CdkMeters.SOURCE_IDPC.equals(rawSource) ? CdkMeters.SOURCE_IDPC : CdkMeters.SOURCE_UNKNOWN;
    }
}
