package uk.gov.hmcts.cp.cdk.metrics;

import uk.gov.hmcts.cp.cdk.domain.QueryLevel;

import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counts answer-generation transactions at every point CDKS can actually observe one ending
 * (DD-43182 Story 5, ADR-007, superseded in part by ADR-011).
 *
 * <p><strong>{@code outcome=timed_out} is withdrawn and not built</strong> (ADR-011): a task
 * execution can never observe an exhausted polling budget. A transaction abandoned while
 * {@code ANSWER_GENERATION_PENDING}, or abandoned from a task's {@code catch} path, records
 * nothing on this counter — the total is consequently an <strong>undercount</strong> of ended
 * transactions, and {@code succeeded / (succeeded + failed)} must not be published as a success
 * rate. See {@code task-manager-service} exhaustion-event follow-up (ADR-011(4)).
 */
@Component
public class AnswerGenerationMetrics {

    private final Map<String, Counter> succeededByLevel;
    private final Map<String, Counter> failedByLevel;

    public AnswerGenerationMetrics(final MeterRegistry registry) {
        this.succeededByLevel = new LinkedHashMap<>();
        this.failedByLevel = new LinkedHashMap<>();
        for (final String level : allQueryLevels()) {
            succeededByLevel.put(level, register(registry, CdkMeters.OUTCOME_SUCCEEDED, level));
            failedByLevel.put(level, register(registry, CdkMeters.OUTCOME_FAILED, level));
        }
    }

    /** AC-001: an answer-generation transaction reached ANSWER_GENERATED. */
    public void recordSucceeded(final QueryLevel level) {
        MetricsSafety.runSafely(() -> increment(succeededByLevel, level));
    }

    /**
     * AC-002/AC-004: a transaction ended in a genuine, observed failure — the re-dispatch retry
     * budget is spent (AC-002), or one of {@code GenerateAnswerForQueryTask}'s three terminal
     * abandonment paths (AC-004). Never called for the withdrawn {@code PENDING}/{@code catch}
     * paths (AC-003) — those record nothing on any series.
     */
    public void recordFailed(final QueryLevel level) {
        MetricsSafety.runSafely(() -> increment(failedByLevel, level));
    }

    private static void increment(final Map<String, Counter> countersByLevel, final QueryLevel level) {
        final String tagValue = level == null ? CdkMeters.QUERY_LEVEL_UNKNOWN : level.name();
        final Counter counter = countersByLevel.get(tagValue);
        if (counter != null) {
            counter.increment();
        }
    }

    private static String[] allQueryLevels() {
        return new String[] {
                QueryLevel.CASE.name(), QueryLevel.DEFENDANT.name(), QueryLevel.CASE_ALL_DOCUMENTS.name(),
                CdkMeters.QUERY_LEVEL_UNKNOWN
        };
    }

    private static Counter register(final MeterRegistry registry, final String outcome, final String level) {
        return Counter.builder(CdkMeters.ANSWER_GENERATION)
                .tag(CdkMeters.TAG_OUTCOME, outcome)
                .tag(CdkMeters.TAG_QUERY_LEVEL, level)
                .register(registry);
    }
}
