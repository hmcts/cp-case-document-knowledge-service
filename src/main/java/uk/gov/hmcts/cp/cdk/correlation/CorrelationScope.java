package uk.gov.hmcts.cp.cdk.correlation;

import uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys;

import java.util.Map;

import jakarta.json.JsonObject;
import org.slf4j.MDC;

/**
 * An {@link AutoCloseable} MDC scope that seeds the correlation ID (and, from {@code jobData},
 * business identifiers) and restores the prior MDC map on close — never {@code MDC.remove(...)},
 * never {@code MDC.clear()} — so a scope is safe nested and on a pooled thread that legitimately
 * carries other context (DD-43183 Story 1, ADR-002, ADR-004).
 */
public final class CorrelationScope implements AutoCloseable {

    /** MDC key for the business case identifier, seeded only where a unit of work actually has one. */
    public static final String MDC_KEY_CASE_ID = "caseId";

    /** MDC key for the business document identifier, seeded only where present. */
    public static final String MDC_KEY_DOC_ID = "docId";

    /** MDC key for the RAG transaction identifier, seeded only where present. */
    public static final String MDC_KEY_TRANSACTION_ID = "transactionId";

    private final Map<String, String> priorContext;

    private CorrelationScope(final Map<String, String> priorContext) {
        this.priorContext = priorContext;
    }

    /** Seeds MDC with the given, explicit correlation value — always overwrites any ambient value. */
    public static CorrelationScope open(final String correlationId) {
        final Map<String, String> prior = MDC.getCopyOfContextMap();
        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        return new CorrelationScope(prior);
    }

    /**
     * Seeds MDC with a correlation value only if one is not already present and valid — for a
     * scheduler or other background entry point with no inbound request to resolve one from.
     */
    public static CorrelationScope openIfAbsent() {
        final Map<String, String> prior = MDC.getCopyOfContextMap();
        MDC.put(CorrelationIds.MDC_KEY, CorrelationIds.currentOrGenerate());
        return new CorrelationScope(prior);
    }

    /**
     * Seeds MDC for a JobManager task execution from its {@code jobData}: the correlation ID from
     * {@code requestId} (generating one if absent, blank or rejected), and — where present and
     * valid — {@code caseId}, {@code docId} and {@code transactionId} (from the persisted
     * {@code ragTransactionId} field). This is Area E's entire JobManager-side deliverable, at one
     * call site, with zero per-task edits.
     */
    public static CorrelationScope fromJobData(final JsonObject jobData) {
        final Map<String, String> prior = MDC.getCopyOfContextMap();

        final String requestId = CorrelationIds.sanitise(
                jobData.getString(JobManagerKeys.Params.REQUEST_ID, null));
        MDC.put(CorrelationIds.MDC_KEY, requestId != null ? requestId : CorrelationIds.generate());

        putIfPresent(jobData, JobManagerKeys.CTX_CASE_ID_KEY, MDC_KEY_CASE_ID);
        putIfPresent(jobData, JobManagerKeys.CTX_DOC_ID_KEY, MDC_KEY_DOC_ID);
        putIfPresent(jobData, JobManagerKeys.CTX_RAG_TRANSACTION_ID, MDC_KEY_TRANSACTION_ID);

        return new CorrelationScope(prior);
    }

    /**
     * Seeds MDC with whichever business identifiers are non-null — {@code caseId}, {@code docId},
     * {@code transactionId} — without touching the correlation ID itself (DD-43183 Story 5). For
     * the four named services and both RAG completion lines: the ambient correlation value from
     * {@code RequestContextFilter} is left exactly as-is; only the business identifiers are added,
     * additively, for the duration of the scope. No sentinel is ever written for a null argument —
     * an absent identifier means an absent JSON field, not {@code "none"}.
     */
    public static CorrelationScope withIdentifiers(final String caseId, final String docId,
                                                   final String transactionId) {
        final Map<String, String> prior = MDC.getCopyOfContextMap();
        if (caseId != null) {
            MDC.put(MDC_KEY_CASE_ID, caseId);
        }
        if (docId != null) {
            MDC.put(MDC_KEY_DOC_ID, docId);
        }
        if (transactionId != null) {
            MDC.put(MDC_KEY_TRANSACTION_ID, transactionId);
        }
        return new CorrelationScope(prior);
    }

    private static void putIfPresent(final JsonObject jobData, final String jobDataKey, final String mdcKey) {
        final String sanitised = CorrelationIds.sanitise(jobData.getString(jobDataKey, null));
        if (sanitised != null) {
            MDC.put(mdcKey, sanitised);
        }
    }

    @Override
    public void close() {
        if (priorContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(priorContext);
        }
    }
}
