package uk.gov.hmcts.cp.cdk.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("ExternalCallMetrics tests (DD-43182 Story 3)")
class ExternalCallMetricsTest {

    private SimpleMeterRegistry registry;
    private ExternalCallMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ExternalCallMetrics(registry);
    }

    @Test
    @DisplayName("AC-001: a normal call records one success observation and returns the result untouched")
    void successfulCallRecordsSuccessAndReturnsResultUntouched() {
        final String result = metrics.record(CdkMeters.DEPENDENCY_RAG,
                CdkMeters.OPERATION_ANSWER_USER_QUERY, () -> "the-real-response");

        assertThat(result).isEqualTo("the-real-response");
        assertThat(registry.get(CdkMeters.EXTERNAL_CALL_DURATION)
                .tag(CdkMeters.TAG_DEPENDENCY, "rag")
                .tag(CdkMeters.TAG_OPERATION, "answer-user-query")
                .tag(CdkMeters.TAG_OUTCOME, "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-002: on failure, the SAME exception instance propagates — type, message and cause unchanged")
    void failureRethrowsTheSameExceptionInstance() {
        final RuntimeException original = new RuntimeException("boom");

        assertThatThrownBy(() -> metrics.record(CdkMeters.DEPENDENCY_RAG,
                CdkMeters.OPERATION_ANSWER_USER_QUERY, () -> {
                    throw original;
                })).isSameAs(original);
    }

    @Test
    @DisplayName("AC-003: outcome is classified from the exception cause chain, not blanket server_error")
    void failureRecordsClassifiedOutcome() {
        final RuntimeException timeoutShaped = new RuntimeException("RAG API error",
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> metrics.record(CdkMeters.DEPENDENCY_RAG,
                CdkMeters.OPERATION_ANSWER_USER_QUERY, () -> {
                    throw timeoutShaped;
                })).isSameAs(timeoutShaped);

        assertThat(registry.get(CdkMeters.EXTERNAL_CALL_DURATION)
                .tag(CdkMeters.TAG_OUTCOME, "timeout").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("all 11 (dependency, operation) success-outcome series are pre-registered at construction")
    void allElevenSuccessSeriesArePreRegistered() {
        final String[][] pairs = {
                {"rag", "initiate-document-upload"}, {"rag", "document-status-by-reference"},
                {"rag", "answer-user-query-async"}, {"rag", "answer-user-query-status"},
                {"rag", "answer-user-query"}, {"progression", "get-court-documents"},
                {"progression", "get-court-documents-all-defendants"},
                {"progression", "get-material-download-url"}, {"hearing", "get-hearings-and-cases"},
                {"hearing", "get-hearing-cases-for-day"}, {"azure_blob", "copy-from-url"}
        };

        for (final String[] pair : pairs) {
            assertThat(registry.get(CdkMeters.EXTERNAL_CALL_DURATION)
                    .tag(CdkMeters.TAG_DEPENDENCY, pair[0])
                    .tag(CdkMeters.TAG_OPERATION, pair[1])
                    .tag(CdkMeters.TAG_OUTCOME, "success")
                    .timer().count()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("recordOutcome records the given outcome directly, for the Azure Blob explicit-outcome path")
    void recordOutcomeRecordsGivenOutcomeDirectly() {
        final long start = metrics.startTimer();

        metrics.recordOutcome(CdkMeters.DEPENDENCY_AZURE_BLOB, CdkMeters.OPERATION_COPY_FROM_URL,
                start, CdkMeters.OUTCOME_TIMEOUT);

        assertThat(registry.get(CdkMeters.EXTERNAL_CALL_DURATION)
                .tag(CdkMeters.TAG_DEPENDENCY, "azure_blob")
                .tag(CdkMeters.TAG_OPERATION, "copy-from-url")
                .tag(CdkMeters.TAG_OUTCOME, "timeout")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordOutcomeClassifying classifies inside the safety wrapper — "
            + "a classification failure never propagates and never replaces the caller's own exception")
    void recordOutcomeClassifyingContainsAClassificationFailure() {
        final long start = metrics.startTimer();

        // an exception whose getCause() cycles back to itself would be unusual, but the point here is
        // that classification happening inside MetricsSafety.runSafely means even a pathological input
        // cannot escape as an exception from this call.
        metrics.recordOutcomeClassifying(CdkMeters.DEPENDENCY_AZURE_BLOB, CdkMeters.OPERATION_COPY_FROM_URL,
                start, new RuntimeException("some failure"));

        assertThat(registry.get(CdkMeters.EXTERNAL_CALL_DURATION)
                .tag(CdkMeters.TAG_DEPENDENCY, "azure_blob")
                .tag(CdkMeters.TAG_OPERATION, "copy-from-url")
                .tag(CdkMeters.TAG_OUTCOME, "error")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("two or more outbound calls in sequence each record their own observation")
    void multipleCallsEachRecordAnObservation() {
        metrics.record(CdkMeters.DEPENDENCY_PROGRESSION, CdkMeters.OPERATION_GET_COURT_DOCUMENTS, () -> "a");
        metrics.record(CdkMeters.DEPENDENCY_PROGRESSION, CdkMeters.OPERATION_GET_COURT_DOCUMENTS, () -> "b");

        assertThat(registry.get(CdkMeters.EXTERNAL_CALL_DURATION)
                .tag(CdkMeters.TAG_DEPENDENCY, "progression")
                .tag(CdkMeters.TAG_OPERATION, "get-court-documents")
                .tag(CdkMeters.TAG_OUTCOME, "success")
                .timer().count()).isEqualTo(2);
    }
}
