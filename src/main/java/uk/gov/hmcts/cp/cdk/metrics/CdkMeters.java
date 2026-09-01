package uk.gov.hmcts.cp.cdk.metrics;

/**
 * Meter-name, tag-key and tag-value constants for every custom CDKS metric (ADR-001).
 *
 * <p>Every meter is registered with its lowercase, dot-separated Micrometer name — never a
 * Prometheus-rendered name, never camelCase. The Prometheus registry on this classpath does
 * not snake-case ({@code cdk.documentsStalled} would render as {@code cdk_documentsStalled}),
 * so the mapping below is exact:
 *
 * <ul>
 *   <li>{@code cdk.scheduler.runs} (Counter) renders as {@code cdk_scheduler_runs_total}</li>
 *   <li>{@code cdk.scheduler.last.success.epoch.seconds} (Gauge) renders as
 *       {@code cdk_scheduler_last_success_epoch_seconds}</li>
 *   <li>{@code cdk.scheduler.enabled} (Gauge) renders as {@code cdk_scheduler_enabled}</li>
 *   <li>{@code cdk.documents.stalled} (Gauge) renders as {@code cdk_documents_stalled}</li>
 *   <li>{@code cdk.queries.awaiting.answer} (Gauge) renders as {@code cdk_queries_awaiting_answer}</li>
 *   <li>{@code cdk.monitoring.last.refresh.epoch.seconds} (Gauge) renders as
 *       {@code cdk_monitoring_last_refresh_epoch_seconds}</li>
 * </ul>
 *
 * <p>No string literal for a meter name, tag key or tag value is registered or asserted
 * anywhere else in the codebase — everything references these constants.
 */
public final class CdkMeters {

    // Meter names — Micrometer (registration) form.
    public static final String SCHEDULER_RUNS = "cdk.scheduler.runs";
    public static final String SCHEDULER_LAST_SUCCESS = "cdk.scheduler.last.success.epoch.seconds";
    public static final String SCHEDULER_ENABLED = "cdk.scheduler.enabled";
    public static final String DOCUMENTS_STALLED = "cdk.documents.stalled";
    public static final String QUERIES_AWAITING_ANSWER = "cdk.queries.awaiting.answer";
    public static final String MONITORING_LAST_REFRESH = "cdk.monitoring.last.refresh.epoch.seconds";

    // Tag keys.
    public static final String TAG_SCHEDULER = "scheduler";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_PHASE = "phase";

    // Tag values — stalled-document phase (ADR-004: the ticket's original three plus UPLOADED).
    // Mirror uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase's enum constant names verbatim
    // (ADR-001's tag-value casing rule: a value from a database enum uses the enum constant as-is).
    public static final String PHASE_WAITING_FOR_UPLOAD = "WAITING_FOR_UPLOAD";
    public static final String PHASE_UPLOADING = "UPLOADING";
    public static final String PHASE_UPLOADED = "UPLOADED";
    public static final String PHASE_INGESTING = "INGESTING";

    // Tag values — scheduler identity (ADR-006). Fixed constants: deliberately NOT the
    // runtime-configurable ShedLock lock name and NOT the class name, either of which would
    // silently rename a production metric series if it changed.
    public static final String INTRADAY_DISCOVERY = "intraday-discovery";
    public static final String NIGHTLY_DISCOVERY = "nightly-discovery";

    // Tag values — run outcome (FR-008, literal).
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    private CdkMeters() {
        throw new AssertionError("No instances");
    }
}
