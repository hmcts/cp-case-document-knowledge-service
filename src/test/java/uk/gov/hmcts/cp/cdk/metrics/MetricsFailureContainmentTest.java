package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.domain.QueryLevel;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DD-43182 Story 7 AC-005: with an injected {@link ThrowingMeterRegistry}, each of the four
 * business-path shapes this ticket instruments completes exactly as it would without
 * instrumentation — same return value, same propagated exception, no RAG response field
 * dropped or altered. The JobManager task-execution shape (the fourth path) is covered
 * separately by {@code jobmanager.correlation.TaskRetryMetricsAspectContainmentTest} because its
 * aspect's pointcut is scoped to the {@code uk.gov.hmcts.cp.cdk.jobmanager..*} package.
 */
@DisplayName("Cross-cutting metric-failure containment (DD-43182 Story 7, AC-005)")
class MetricsFailureContainmentTest {

    @Test
    @DisplayName("ingestion phase write: recordPhaseTransition never throws even when the registry does")
    void ingestionPhaseWriteIsUnaffectedByThrowingRegistry() {
        final IngestionMetrics metrics = new IngestionMetrics(new ThrowingMeterRegistry());

        assertThatCode(() -> metrics.recordPhaseTransition(DocumentIngestionPhase.INGESTED, "IDPC"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ingestion duration write: recordIngestionDuration never throws even when the registry does")
    void ingestionDurationWriteIsUnaffectedByThrowingRegistry() {
        final IngestionMetrics metrics = new IngestionMetrics(new ThrowingMeterRegistry());
        final OffsetDateTime createdAt = OffsetDateTime.now().minusMinutes(5);
        final OffsetDateTime terminalAt = OffsetDateTime.now();

        assertThatCode(() -> metrics.recordIngestionDuration(DocumentIngestionPhase.INGESTED, createdAt, terminalAt))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("outbound call, success path: record() returns the same result untouched even when "
            + "the registry throws while timing it")
    void outboundCallSuccessIsUnaffectedByThrowingRegistry() {
        final ExternalCallMetrics metrics = new ExternalCallMetrics(new ThrowingMeterRegistry());
        final Object ragResponse = new Object();

        final Object result = metrics.record(CdkMeters.DEPENDENCY_RAG, CdkMeters.OPERATION_ANSWER_USER_QUERY,
                () -> ragResponse);

        assertThat(result).isSameAs(ragResponse);
    }

    @Test
    @DisplayName("outbound call, failure path: the same exception instance the call threw still "
            + "propagates even when the registry throws while classifying/timing it")
    void outboundCallFailurePropagatesSameExceptionEvenWhenRegistryThrows() {
        final ExternalCallMetrics metrics = new ExternalCallMetrics(new ThrowingMeterRegistry());
        final RuntimeException businessFailure = new IllegalStateException("upstream RAG failure");

        assertThatThrownBy(() -> metrics.record(CdkMeters.DEPENDENCY_RAG, CdkMeters.OPERATION_ANSWER_USER_QUERY,
                () -> {
                    throw businessFailure;
                })).isSameAs(businessFailure);
    }

    @Test
    @DisplayName("answer-generation succeeded transition: recordSucceeded never throws even when the "
            + "registry does")
    void answerGenerationSucceededIsUnaffectedByThrowingRegistry() {
        final AnswerGenerationMetrics metrics = new AnswerGenerationMetrics(new ThrowingMeterRegistry());

        assertThatCode(() -> metrics.recordSucceeded(QueryLevel.CASE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("answer-generation failed transition: recordFailed never throws even when the registry does")
    void answerGenerationFailedIsUnaffectedByThrowingRegistry() {
        final AnswerGenerationMetrics metrics = new AnswerGenerationMetrics(new ThrowingMeterRegistry());

        assertThatCode(() -> metrics.recordFailed(QueryLevel.DEFENDANT)).doesNotThrowAnyException();
    }
}
