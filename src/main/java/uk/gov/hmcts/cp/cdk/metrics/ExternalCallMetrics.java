package uk.gov.hmcts.cp.cdk.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Times every outbound call to RAG, Progression, Hearing and Azure Blob, on both the success and
 * failure path, without altering what any client throws (DD-43182 Story 3, ADR-003, ADR-004).
 *
 * <p>The business call is never wrapped in any sense that could change its outcome: {@link #record}
 * returns {@code call.get()}'s result untouched (never inspected, copied, or mapped — protects
 * {@code doc_id}, {@code llm_input}, {@code llmResponse}, {@code documentChunks},
 * {@code transactionId}, status), and on failure rethrows the <strong>same exception instance</strong>
 * the call threw — type, message, cause and stack trace are structurally identical with and without
 * instrumentation, because the exception is never reconstructed.
 *
 * <p>The 11 {@code (dependency, operation)} pairs' {@code outcome="success"} series are pre-registered
 * at construction (33 series: count+sum+max × 11) — the four non-success outcomes materialise on
 * first occurrence (worst case 165), per {@code 02-design.md} §12's cardinality accounting.
 */
@Component
public class ExternalCallMetrics {

    private static final Map<String, String> OPERATION_TO_DEPENDENCY = buildOperationToDependency();

    private final MeterRegistry registry;
    private final Map<String, Timer> successTimers;

    public ExternalCallMetrics(final MeterRegistry registry) {
        this.registry = registry;
        this.successTimers = new LinkedHashMap<>();
        OPERATION_TO_DEPENDENCY.forEach((operation, dependency) ->
                successTimers.put(operation, Timer.builder(CdkMeters.EXTERNAL_CALL_DURATION)
                        .tag(CdkMeters.TAG_DEPENDENCY, dependency)
                        .tag(CdkMeters.TAG_OPERATION, operation)
                        .tag(CdkMeters.TAG_OUTCOME, CdkMeters.OUTCOME_SUCCESS)
                        .register(registry)));
    }

    /**
     * Times {@code call}, recording one observation tagged {@code dependency}/{@code operation}/
     * {@code outcome}. {@code dependency} and {@code operation} must be literal constants from
     * {@link CdkMeters} at the call site — never derived from a method name, class name or URI.
     */
    public <T> T record(final String dependency, final String operation, final Supplier<T> call) {
        final long startNanos = System.nanoTime();
        try {
            final T result = call.get();
            MetricsSafety.runSafely(() -> observe(dependency, operation, startNanos, CdkMeters.OUTCOME_SUCCESS));
            return result;
        } catch (final RuntimeException e) {
            MetricsSafety.runSafely(() -> observe(dependency, operation, startNanos, OutcomeClassifier.classify(e)));
            throw e;
        }
    }

    /**
     * Records an outcome directly, for the one call site ({@code AzureBlobStorageService.copyFromUrl})
     * whose own existing {@code try}/{@code catch} already knows the precise outcome — its timeout
     * path discards the identifying {@code TimeoutException} cause before it would reach {@link
     * OutcomeClassifier}, so classification happens inside the method, not at this wrapper.
     */
    public void recordOutcome(final String dependency, final String operation, final long startNanos,
                              final String outcome) {
        MetricsSafety.runSafely(() -> observe(dependency, operation, startNanos, outcome));
    }

    /**
     * Same as {@link #recordOutcome(String, String, long, String)}, but classifies {@code throwable}
     * via {@link OutcomeClassifier} <em>inside</em> the safety wrapper — never as an eagerly-evaluated
     * caller-side argument — so a future defect in classification itself can never propagate out and
     * replace the real business exception the caller is already unwinding with.
     */
    public void recordOutcomeClassifying(final String dependency, final String operation, final long startNanos,
                                         final Throwable throwable) {
        MetricsSafety.runSafely(() -> observe(dependency, operation, startNanos, OutcomeClassifier.classify(throwable)));
    }

    public long startTimer() {
        return System.nanoTime();
    }

    private void observe(final String dependency, final String operation, final long startNanos,
                         final String outcome) {
        final long elapsedNanos = System.nanoTime() - startNanos;
        final Timer timer = CdkMeters.OUTCOME_SUCCESS.equals(outcome) ? successTimers.get(operation) : null;
        if (timer != null) {
            timer.record(elapsedNanos, TimeUnit.NANOSECONDS);
            return;
        }
        Timer.builder(CdkMeters.EXTERNAL_CALL_DURATION)
                .tag(CdkMeters.TAG_DEPENDENCY, dependency)
                .tag(CdkMeters.TAG_OPERATION, operation)
                .tag(CdkMeters.TAG_OUTCOME, outcome)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private static Map<String, String> buildOperationToDependency() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put(CdkMeters.OPERATION_INITIATE_DOCUMENT_UPLOAD, CdkMeters.DEPENDENCY_RAG);
        map.put(CdkMeters.OPERATION_DOCUMENT_STATUS_BY_REFERENCE, CdkMeters.DEPENDENCY_RAG);
        map.put(CdkMeters.OPERATION_ANSWER_USER_QUERY_ASYNC, CdkMeters.DEPENDENCY_RAG);
        map.put(CdkMeters.OPERATION_ANSWER_USER_QUERY_STATUS, CdkMeters.DEPENDENCY_RAG);
        map.put(CdkMeters.OPERATION_ANSWER_USER_QUERY, CdkMeters.DEPENDENCY_RAG);
        map.put(CdkMeters.OPERATION_GET_COURT_DOCUMENTS, CdkMeters.DEPENDENCY_PROGRESSION);
        map.put(CdkMeters.OPERATION_GET_COURT_DOCUMENTS_ALL_DEFENDANTS, CdkMeters.DEPENDENCY_PROGRESSION);
        map.put(CdkMeters.OPERATION_GET_MATERIAL_DOWNLOAD_URL, CdkMeters.DEPENDENCY_PROGRESSION);
        map.put(CdkMeters.OPERATION_GET_HEARINGS_AND_CASES, CdkMeters.DEPENDENCY_HEARING);
        map.put(CdkMeters.OPERATION_GET_HEARING_CASES_FOR_DAY, CdkMeters.DEPENDENCY_HEARING);
        map.put(CdkMeters.OPERATION_COPY_FROM_URL, CdkMeters.DEPENDENCY_AZURE_BLOB);
        return map;
    }
}
