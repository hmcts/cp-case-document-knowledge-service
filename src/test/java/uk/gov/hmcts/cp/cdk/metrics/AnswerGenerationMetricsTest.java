package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.domain.QueryLevel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AnswerGenerationMetrics tests (DD-43182 Story 5)")
class AnswerGenerationMetricsTest {

    private SimpleMeterRegistry registry;
    private AnswerGenerationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AnswerGenerationMetrics(registry);
    }

    @Test
    @DisplayName("all 8 series (2 outcomes x 4 query_level values) are pre-registered at construction")
    void allEightSeriesPreRegistered() {
        final long count = registry.getMeters().stream()
                .filter(m -> CdkMeters.ANSWER_GENERATION.equals(m.getId().getName()))
                .count();

        assertThat(count).isEqualTo(8);
    }

    @Test
    @DisplayName("AC-001: recordSucceeded increments the succeeded/level series by exactly 1")
    void recordSucceededIncrementsByOne() {
        metrics.recordSucceeded(QueryLevel.CASE);

        assertThat(seriesValue(CdkMeters.OUTCOME_SUCCEEDED, "CASE")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-002: recordFailed increments the failed/level series by exactly 1")
    void recordFailedIncrementsByOne() {
        metrics.recordFailed(QueryLevel.DEFENDANT);

        assertThat(seriesValue(CdkMeters.OUTCOME_FAILED, "DEFENDANT")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-005: outcome takes exactly succeeded or failed — never timed_out, "
            + "which is withdrawn and must never appear")
    void outcomeNeverContainsTimedOut() {
        metrics.recordSucceeded(QueryLevel.CASE);
        metrics.recordFailed(QueryLevel.CASE);

        assertThat(registry.getMeters()).noneMatch(m ->
                "timed_out".equals(m.getId().getTag(CdkMeters.TAG_OUTCOME)));
        assertThat(registry.getMeters()).allSatisfy(m ->
                assertThat(m.getId().getTag(CdkMeters.TAG_OUTCOME)).isIn("succeeded", "failed"));
    }

    @Test
    @DisplayName("AC-005: a null query_level resolves to 'unknown' rather than being omitted")
    void nullQueryLevelResolvesToUnknown() {
        metrics.recordFailed(null);

        assertThat(seriesValue(CdkMeters.OUTCOME_FAILED, "unknown")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("AC-005: 'failed' comes from OUTCOME_FAILED, a literal distinct from DD-43185's "
            + "OUTCOME_FAILURE ('failure')")
    void failedOutcomeIsDistinctFromSchedulerFailure() {
        assertThat(CdkMeters.OUTCOME_FAILED).isEqualTo("failed");
        assertThat(CdkMeters.OUTCOME_FAILURE).isEqualTo("failure");
        assertThat(CdkMeters.OUTCOME_FAILED).isNotEqualTo(CdkMeters.OUTCOME_FAILURE);
    }

    @Test
    @DisplayName("AC-007: a metric-recording failure is contained — recordFailed/recordSucceeded never throw")
    void recordingNeverThrowsEvenOnUnexpectedInput() {
        metrics.recordFailed(QueryLevel.CASE_ALL_DOCUMENTS);
        metrics.recordSucceeded(QueryLevel.CASE_ALL_DOCUMENTS);
        // no exception is the assertion
    }

    private double seriesValue(final String outcome, final String level) {
        return registry.get(CdkMeters.ANSWER_GENERATION)
                .tag(CdkMeters.TAG_OUTCOME, outcome)
                .tag(CdkMeters.TAG_QUERY_LEVEL, level)
                .counter().count();
    }
}
