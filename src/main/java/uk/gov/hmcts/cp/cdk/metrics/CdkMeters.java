package uk.gov.hmcts.cp.cdk.metrics;

import java.time.Duration;
import java.util.List;

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
 *   <li>{@code cdk.http.pool.connections.leased} (Gauge) renders as
 *       {@code cdk_http_pool_connections_leased} — deliberate exception: an alias of Micrometer's
 *       own framework-registered {@code httpcomponents_httpclient_pool_*} series (DD-43182 Story 4),
 *       not a metric this class fully owns the naming of.</li>
 *   <li>{@code cdk.document.ingestion.phase} (Counter) renders as
 *       {@code cdk_document_ingestion_phase_total}</li>
 *   <li>{@code cdk.document.ingestion.duration} (Timer) renders as
 *       {@code cdk_document_ingestion_duration_seconds_{bucket,count,sum,max}}. <strong>Read
 *       together with {@link #DOCUMENTS_STALLED}</strong>: this timer is systematically
 *       success-biased by construction — a document that never reaches a terminal phase
 *       contributes no observation, ever, so it cannot detect a stall. {@code
 *       cdk_documents_stalled{phase="UPLOADED"}} is the detector for exactly that population
 *       (DD-43182 ADR-002(5)).</li>
 *   <li>{@code cdk.external.call.duration} (Timer, no percentile histogram) renders as
 *       {@code cdk_external_call_duration_seconds_{count,sum,max}} — never {@code _bucket}
 *       (DD-43182 Story 3, ADR-005: buckets are on the ingestion-duration timer only, never here,
 *       which is where most of the cardinality headroom in {@code 02-design.md} §12 comes from).</li>
 *   <li>{@code cdk.task.retry} (Counter) renders as {@code cdk_task_retry_total} (DD-43182 Story 6).
 *       <strong>There is no {@code cdk_task_retry_exhausted_total} and never will be under the
 *       current {@code task-manager-service} contract (ADR-011):</strong> a task execution can
 *       never observe an exhausted retry budget — the library abandons the job silently between
 *       executions, with no task run at all to record the exhaustion from. {@code cdk_task_retry_total}
 *       counts <em>granted</em> retries only; it is not, and cannot be turned into, a
 *       work-is-being-silently-abandoned signal. That signal does not exist anywhere in CDKS today.
 *       The follow-up that closes this gap is a {@code task-manager-service} exhaustion event
 *       (ADR-011(4)), owned outside this repository.</li>
 *   <li>{@code cdk.answer.generation} (Counter) renders as {@code cdk_answer_generation_total}
 *       (DD-43182 Story 5). Its total is an <strong>undercount</strong> of ended transactions — a
 *       transaction abandoned while {@code ANSWER_GENERATION_PENDING}, or abandoned from a task's
 *       {@code catch} path, records nothing (ADR-011). {@code succeeded / (succeeded + failed)}
 *       must therefore never be published as a success rate.</li>
 * </ul>
 *
 * <p><strong>Timer naming rule:</strong> a registered Timer name never carries a {@code .seconds}
 * (or any base-unit) segment — {@code cdk.document.ingestion.duration}, not
 * {@code cdk.document.ingestion.duration.seconds} — because Micrometer's Prometheus registry
 * appends the base time unit itself at render time; declaring it in the registration name would
 * double it up as {@code _seconds_seconds}.
 *
 * <p><strong>{@link #OUTCOME_FAILED} ({@code "failed"}, on {@link #ANSWER_GENERATION} only) and
 * {@link #OUTCOME_FAILURE} ({@code "failure"}, on {@link #SCHEDULER_RUNS} only) are two
 * deliberately distinct values</strong> (OQ-031) — neither compiles silently in place of the
 * other, and each belongs to exactly one meter; do not use one where the other's meter is being
 * recorded.
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
    public static final String HTTP_POOL_CONNECTIONS_LEASED = "cdk.http.pool.connections.leased";
    public static final String DOCUMENT_INGESTION_PHASE = "cdk.document.ingestion.phase";
    public static final String DOCUMENT_INGESTION_DURATION = "cdk.document.ingestion.duration";
    public static final String EXTERNAL_CALL_DURATION = "cdk.external.call.duration";
    public static final String TASK_RETRY = "cdk.task.retry";
    public static final String ANSWER_GENERATION = "cdk.answer.generation";

    // Tag keys.
    public static final String TAG_SCHEDULER = "scheduler";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_PHASE = "phase";
    public static final String TAG_SOURCE = "source";
    public static final String TAG_DEPENDENCY = "dependency";
    public static final String TAG_OPERATION = "operation";
    public static final String TAG_TASK_NAME = "task_name";
    public static final String TAG_RETRY_POLICY = "retry_policy";
    public static final String TAG_QUERY_LEVEL = "query_level";

    // Tag values — stalled-document phase (ADR-004: the ticket's original three plus UPLOADED).
    // Mirror uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase's enum constant names verbatim
    // (ADR-001's tag-value casing rule: a value from a database enum uses the enum constant as-is).
    public static final String PHASE_WAITING_FOR_UPLOAD = "WAITING_FOR_UPLOAD";
    public static final String PHASE_UPLOADING = "UPLOADING";
    public static final String PHASE_UPLOADED = "UPLOADED";
    public static final String PHASE_INGESTING = "INGESTING";

    // Tag values — the three ingestion-phase-counter values DD-43185 did not already declare
    // (DD-43182 Story 1, ADR-009). Also the ingestion-duration timer's three terminal phases.
    public static final String PHASE_INGESTED = "INGESTED";
    public static final String PHASE_FAILED = "FAILED";
    public static final String PHASE_EXCEEDED_FILE_SIZE_LIMIT = "EXCEEDED_FILE_SIZE_LIMIT";

    // Tag values — ingestion-phase-counter `source` (DD-43182 Story 1, ADR-009). Membership-checked
    // against CaseDocument.source, never read through as free text, so the tag stays a fixed,
    // closed set even if the column later gains an unanticipated value.
    public static final String SOURCE_IDPC = "IDPC";
    public static final String SOURCE_UNKNOWN = "unknown";

    // Ingestion-duration timer SLO boundaries (DD-43182 Story 2, ADR-002(3)) — declared in code as
    // the authoritative default; management.metrics.distribution.slo.cdk.document.ingestion.duration
    // is the runtime override lever.
    public static final List<Duration> INGESTION_DURATION_SLOS = List.of(
            Duration.ofSeconds(15), Duration.ofSeconds(30),
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ofMinutes(30), Duration.ofHours(1)
    );

    // Tag values — outbound dependency call outcome (DD-43182 Story 3, ADR-003, GATE-1). OUTCOME_SUCCESS
    // above is shared with the scheduler-run outcome tag; the four below are new to this meter.
    public static final String OUTCOME_CLIENT_ERROR = "client_error";
    public static final String OUTCOME_SERVER_ERROR = "server_error";
    public static final String OUTCOME_TIMEOUT = "timeout";
    public static final String OUTCOME_ERROR = "error";

    // Tag values — `dependency` (DD-43182 Story 3, ticket literals).
    public static final String DEPENDENCY_RAG = "rag";
    public static final String DEPENDENCY_PROGRESSION = "progression";
    public static final String DEPENDENCY_HEARING = "hearing";
    public static final String DEPENDENCY_AZURE_BLOB = "azure_blob";

    // Tag values — `operation` (DD-43182 Story 3, ADR-004). Each is a literal argument at its call
    // site — never derived from a method name, class name, or URI — so a path variable (a RAG
    // document reference or transaction id) can never become a tag value.
    public static final String OPERATION_INITIATE_DOCUMENT_UPLOAD = "initiate-document-upload";
    public static final String OPERATION_DOCUMENT_STATUS_BY_REFERENCE = "document-status-by-reference";
    public static final String OPERATION_ANSWER_USER_QUERY_ASYNC = "answer-user-query-async";
    public static final String OPERATION_ANSWER_USER_QUERY_STATUS = "answer-user-query-status";
    public static final String OPERATION_ANSWER_USER_QUERY = "answer-user-query";
    public static final String OPERATION_GET_COURT_DOCUMENTS = "get-court-documents";
    public static final String OPERATION_GET_COURT_DOCUMENTS_ALL_DEFENDANTS = "get-court-documents-all-defendants";
    public static final String OPERATION_GET_MATERIAL_DOWNLOAD_URL = "get-material-download-url";
    public static final String OPERATION_GET_HEARINGS_AND_CASES = "get-hearings-and-cases";
    public static final String OPERATION_GET_HEARING_CASES_FOR_DAY = "get-hearing-cases-for-day";
    public static final String OPERATION_COPY_FROM_URL = "copy-from-url";

    // Tag values — `retry_policy` (DD-43182 Story 6, ADR-006, GATE-3). Functionally determined by
    // `task_name`, at zero extra series cost. "none" means the task has no getRetryDurationsInSecs()
    // override at all (GENERATE_ANSWER_FOR_QUERY) — it can never be retried.
    public static final String RETRY_POLICY_DEFAULT = "default-retry";
    public static final String RETRY_POLICY_VERIFY_DOCUMENT_STATUS = "verify-document-status";
    public static final String RETRY_POLICY_QUESTIONS = "questions-retry";
    public static final String RETRY_POLICY_NONE = "none";

    // Tag values — answer-generation outcome (DD-43182 Story 5, ADR-007/ADR-011). Deliberately
    // distinct, both in spelling and meaning, from OUTCOME_SUCCESS ("success", scheduler/external-call
    // outcome) and OUTCOME_FAILURE ("failure", scheduler outcome) above — neither of those compiles
    // silently in place of these (OQ-031). outcome=timed_out is withdrawn (ADR-011) and not built.
    public static final String OUTCOME_SUCCEEDED = "succeeded";
    public static final String OUTCOME_FAILED = "failed";

    // Tag values — `query_level` (DD-43182 Story 5). Mirrors domain.QueryLevel's enum constants
    // verbatim, plus "unknown" when TaskUtils.parseQueryLevel(...) returns null.
    public static final String QUERY_LEVEL_CASE = "CASE";
    public static final String QUERY_LEVEL_DEFENDANT = "DEFENDANT";
    public static final String QUERY_LEVEL_CASE_ALL_DOCUMENTS = "CASE_ALL_DOCUMENTS";
    public static final String QUERY_LEVEL_UNKNOWN = "unknown";

    // Tag values — scheduler identity (ADR-006). Fixed constants: deliberately NOT the
    // runtime-configurable ShedLock lock name and NOT the class name, either of which would
    // silently rename a production metric series if it changed.
    public static final String INTRADAY_DISCOVERY = "intraday-discovery";
    public static final String NIGHTLY_DISCOVERY = "nightly-discovery";

    // Tag values — run outcome (FR-008, literal).
    public static final String OUTCOME_SUCCESS = "success";
    // DD-43185's own scheduler-run outcome value. Distinct in spelling and meaning from DD-43182's
    // cdk.answer.generation OUTCOME_FAILED ("failed") below — the two are never interchangeable,
    // each belongs to exactly one meter, and neither compiles silently in place of the other (OQ-031).
    public static final String OUTCOME_FAILURE = "failure";

    private CdkMeters() {
        throw new AssertionError("No instances");
    }
}
