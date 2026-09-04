# Requirements: Operational Metrics Instrumentation (Micrometer)

> **Stage 1 — Requirements** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43182**
> Five separable capability areas in one ticket: (A) **document-ingestion phase counters and an
> end-to-end ingestion duration timer**; (B) **outbound-dependency call timing plus an HTTP
> connection-pool gauge**; (C) **answer-generation outcome counters**; (D) **JobManager retry and
> retry-exhaustion counters**; (E) **cross-cutting scrape/cardinality/safety guarantees**. They share
> only the `MeterRegistry` and the `CdkMeters` naming contract, and should be split into separate
> stories at Stage 3.
> This ticket is the **second** metrics ticket in CDKS. DD-43185 has already established the
> Micrometer naming convention, the `CdkMeters` constants class, the tag-value casing rule and the
> cardinality-accounting habit (`adrs/DD-43185-stalled-work-scheduler-monitoring.md`, ADR-001 /
> ADR-004 / ADR-006 / ADR-008). DD-43182 **extends** that precedent rather than re-deriving it —
> see OQ-018 for the one sequencing caveat.
> Four of the ticket's eight scenarios contain a claim that does not survive contact with the
> codebase (OQ-004, OQ-005, OQ-008, OQ-011). Two of them — the ingestion duration timer and
> `cdk_task_retry_exhausted_total` — are **not implementable as written**. These are not naming
> quibbles; they need an answer before Design.

---

> **Note — do not read this document as a rejection of the ticket.** Every metric asked for is
> worth having, and the *intent* of all eight scenarios is sound and implementable. What follows
> flags where the ticket's stated trigger, tag set or threshold does not match what the code
> actually does, so that Design resolves it deliberately instead of shipping a permanently-zero
> series or an unimplementable AC.

---

## Context

CDKS's asynchronous ingestion and answer-generation pipeline is currently observable only through
logs and through DD-43185's six stuck-work / scheduler meters. There is no measurement of **rate**
(how many documents moved phase), **latency** (how long ingestion or a dependency call took), or
**failure** (how many answers failed, how many JobManager tasks gave up). DD-43185 answers "is
anything stuck right now?"; DD-43182 answers "how fast, how often, and how badly is it failing?".

### Metrics that already exist in this codebase

DD-43185's "there are zero custom metrics in CDKS" is **no longer true.** As of commit `885357e`
(PR #224, branch `DD-43185`) `src/main/java/uk/gov/hmcts/cp/cdk/metrics/` exists and contains:

| File | Contents |
|---|---|
| `CdkMeters.java` | Meter-name / tag-key / tag-value constants for all custom CDKS metrics. Javadoc carries the Micrometer-name → Prometheus-name mapping table. |
| `StalledWorkMetrics.java` | Three gauges: `cdk.documents.stalled`, `cdk.queries.awaiting.answer`, `cdk.monitoring.last.refresh.epoch.seconds` |
| `SchedulerMetrics.java` | `cdk.scheduler.runs` (Counter), `cdk.scheduler.last.success.epoch.seconds`, `cdk.scheduler.enabled` |
| `StalledWorkMetricsRefreshJob.java` | ShedLock-guarded refresh |

Six meters, **14 series** in total (DD-43185 NFR-002). All are gauges plus one counter — **there is
no `Timer` and no histogram anywhere in CDKS yet**, so DD-43182 introduces the first ones, and with
them the first real cardinality risk (OQ-007). The only other `io.micrometer` references in
`src/main/java` are incidental: `io.micrometer.tracing.Tracer` in `GlobalExceptionHandler` and
`io.micrometer.common.util.StringUtils.isBlank` in `RetrieveMaterialAndUploadTask`.

Established precedent to reuse verbatim (ADR-001), re-verified against the current tree:

- **Register lowercase, dot-separated Micrometer names.** The Prometheus registry renders `.` → `_`
  and **does not snake-case**; `cdk.documentsStalled` would render as `cdk_documentsStalled`.
- **Never put `.total` in a registered counter name.** `_total` is appended at the exposition layer.
  So the ticket's `cdk_document_ingestion_phase_total`, `cdk_answer_generation_total`,
  `cdk_task_retry_total` and `cdk_task_retry_exhausted_total` register as
  `cdk.document.ingestion.phase`, `cdk.answer.generation`, `cdk.task.retry` and
  `cdk.task.retry.exhausted` respectively.
- **Tag-value casing follows the source of truth**: a database-enum value is used verbatim
  (`phase="WAITING_FOR_UPLOAD"`, `query_level="CASE_ALL_DOCUMENTS"`); a value the ticket states
  literally is used as stated (`outcome="success"`, `dependency="azure_blob"`); a value CDKS invents
  is lowercase kebab-case matching its configuration key.
- **Every meter name, tag key and fixed tag value is a constant in `CdkMeters`** — no string
  literals at registration sites, so the integration test asserts against the same constants
  production registers.

`/actuator/prometheus` is exposed (`application-server-management.yml`, exposure list
`health,info,metrics,prometheus,env,loggers,threaddump`) and common tags
`service=cp-case-document-knowledge-service`, `cluster=${CLUSTER_NAME:local}`,
`region=${REGION:local}` are configured under `management.metrics.tags` — so the ticket's
common-tag scenario is already satisfied by configuration and needs assertion, not new code.
**`management.metrics.distribution.*` is not configured at all**, which is the crux of OQ-007.

### Verification of the ticket's specific claims

Every class, method, enum and number named in DD-43182 was checked against the working tree.

| Ticket statement | Verified? | Detail |
|---|---|---|
| `DocumentIngestionPhase` has 8 values `NOT_FOUND, WAITING_FOR_UPLOAD, UPLOADING, UPLOADED, INGESTING, INGESTED, FAILED, EXCEEDED_FILE_SIZE_LIMIT` | **Yes** | Exactly those eight, in that order (`domain/DocumentIngestionPhase.java`). |
| `CaseDocument.ingestionPhase` is the persisted phase | **Yes** | Column `ingestion_phase` (`document_ingestion_phase_enum`), field default `UPLOADING`, plus `ingestion_phase_at`. |
| A phase "moves between values" for all eight | **No — three phases are never persisted** | Only three write sites exist: `IdpcAvailabilityService.persistCaseDocument` → `WAITING_FOR_UPLOAD`; `RetrieveMaterialAndUploadTask` (~line 235) → `UPLOADED`; `CheckIngestionStatusForAllDefendantsTask.updateIngestionPhase` → `INGESTED`/`FAILED`/`EXCEEDED_FILE_SIZE_LIMIT`. Nothing writes `UPLOADING`, `INGESTING` or `NOT_FOUND`. Confirms DD-43185 ADR-004. See OQ-003. |
| Tag `source` | **Exists, but not as a bounded dimension** | `CaseDocument.source` is `TEXT NOT NULL DEFAULT 'IDPC'` (`V1001` line 65) with only `CHECK (length(btrim(source)) > 0)`. No production code calls `setSource`, so its value is always `IDPC`. Free-text by type. See OQ-002. |
| "Given a document enters phase `UPLOADING`" (timer start) | **No such event exists** | Nothing ever persists `UPLOADING`. The timer as specified can never start. See OQ-004. |
| `RestClientFactoryConfig` sets a 3-minute response timeout | **Yes for the default bean; no for most real calls** | `THREE_MIN` is applied to connect / connection-request / response on the shared `CloseableHttpClient`. But every client is built via `RestClientFactory.build(...)` with explicit overrides: RAG 3 s / 180 s (`CP_CDK_RAG_READ_TIMEOUT_MS:180000`), Hearing and Progression 3 s / 15 s (`CP_CDK_CQRS_READ_TIMEOUT_MS:15000`). See OQ-015. |
| `disableAutomaticRetries()` | **Yes** | On both the shared client and each per-client override. |
| `PoolingHttpClientConnectionManager` max 200 total / 50 per route | **Yes** | `setMaxConnTotal(200)`, `setMaxConnPerRoute(50)`. Single shared `@Bean`, `setConnectionManagerShared(true)` — so one gauge does cover all Apache-HttpClient traffic. |
| `RagClientException` is thrown from `RagAnswerAsyncServiceImpl` and `ApimDocumentIngestionClient` | **Yes, and from two more** | `ApimDocumentIngestionStatusClient` and `RagAnswerServiceImpl` throw it too (10 throw sites across 4 classes). |
| A `RagClientException` maps to `outcome=server_error` | **No — it conflicts with the ticket's own tag set** | Every one of the four classes wraps *both* `HttpStatusCodeException` (4xx **and** 5xx) *and* a bare `catch (Exception)` (connect/read timeouts, parse errors) into `RagClientException`. Blanket-mapping it to `server_error` makes `client_error` and `timeout` unreachable for RAG. See OQ-005. |
| `GenerateAnswerForQueryTask` and `CheckStatusOfAnswerGenerationTask` exist | **Yes** | `jobmanager/queryflow/`, `@Task(GENERATE_ANSWER_FOR_QUERY)` / `@Task(CHECK_STATUS_OF_ANSWER_GENERATION)`. |
| Answer-generation terminal states `succeeded / failed / timed_out` | **Partly** | `CheckStatusOfAnswerGenerationTask` handles `ANSWER_GENERATED` and `ANSWER_GENERATION_FAILED`; `ANSWER_GENERATION_PENDING` returns `INPROGRESS`+retry. **There is no `timed_out` state or code path.** `ANSWER_GENERATION_FAILED` is also not terminal — it re-dispatches `GENERATE_ANSWER_FOR_QUERY` until `CTX_ANSWER_RETRY_COUNT` reaches `questions-retry.max-attempts`. See OQ-011. |
| `query_level` is one of `CASE, DEFENDANT, CASE_ALL_DOCUMENTS` | **Yes, plus a null path** | `domain/QueryLevel.java` has exactly those three. But `TaskUtils.parseQueryLevel` returns `null` for missing/invalid input and the task has an explicit `case null, default:` branch. See OQ-011. |
| `TaskNames` is the source of task-name values | **Yes** | Seven constants: `GET_CASES_FOR_HEARING`, `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS`, `RETRIEVE_MATERIAL_AND_UPLOAD`, `CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS`, `CHECK_ALL_DOCUMENTS_INGESTION_STATUS`, `GENERATE_ANSWER_FOR_QUERY`, `CHECK_STATUS_OF_ANSWER_GENERATION`. |
| `ExecutionStatus.INPROGRESS` with `shouldRetry=true` is the retry signal | **Yes** | `ExecutionStatus` has exactly `STARTED, INPROGRESS, COMPLETED`. All seven CDKS tasks build `INPROGRESS`+`withShouldRetry(true)` on their failure path. |
| Retry attempt defaults: default **3**, verify-document-status **50**, questions-retry **100** | **Yes — all three correct** | `application-cdk.yml` lines 42–50: `CDK_JOBMANAGER_RETRY_DEFAULT_MAX_ATTEMPTS:3` / `…VERIFY_DOC_MAX_ATTEMPTS:50` / `…QUESTIONS_MAX_ATTEMPTS:100`, matching `JobManagerRetryProperties.RetryConfig` field defaults (3 / 20 s). |
| …and they are per-task | **No — they are per-*config-key*, many-to-one onto tasks** | `default-retry` → `GET_CASES_FOR_HEARING`, `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS`, `RETRIEVE_MATERIAL_AND_UPLOAD`; `verify-document-status` → `CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS` **and** `CHECK_ALL_DOCUMENTS_INGESTION_STATUS`; `questions-retry` → `CHECK_STATUS_OF_ANSWER_GENERATION`. `GENERATE_ANSWER_FOR_QUERY` maps to **none** — see next row. |
| `cdk_task_retry_exhausted_total` is obtainable | **No — no extension point exists** | Verified by decompiling `task-manager-service` **1.0.11** (`gradle.properties: version.jobManager=1.0.11`; note `tech-stack.md` still says 1.0.10). Retry accounting is entirely inside the package-private `TaskExecutor`: `canRetry(task, info) == info.isShouldRetry() && job.getRetryAttemptsRemaining() != null && > 0 && task.getRetryDurationsInSecs().isPresent()`, and `performRetry` calls `JobService.updateNextTaskRetryDetails(...)` + `releaseJob(...)`. `ExecutionService` exposes only `executeWith(ExecutionInfo)`. No event, callback, listener or hook fires on exhaustion. See OQ-008. |
| — | **Additional finding** | `ExecutableTask.getRetryDurationsInSecs()` defaults to `Optional.empty()`, and `GenerateAnswerForQueryTask` **does not override it**. By `canRetry` above, `GENERATE_ANSWER_FOR_QUERY` returns `INPROGRESS`+`shouldRetry=true` but **can never actually be retried**. A naive `cdk_task_retry_total` would count retries for it that never happen. See OQ-010. |
| Azure Blob is a dependency to time | **Yes, with one live operation** | `StorageService` declares `exists`, `getBlobSize`, `copyFromUrl`; only `copyFromUrl` has a production call site (`RetrieveMaterialAndUploadTask` ~line 120). It is a poll-to-completion (`cp.cdk.storage.copy-poll-interval-ms`, default 1 s; `copy-timeout-seconds`, default **120 s**) on the **Azure SDK's own HTTP stack** — not the Apache pool. See OQ-014. |
| Common tags `service`, `cluster`, `region` from `management.metrics.tags` | **Yes** | Already configured; needs assertion only. |
| Scrape "under 1 second with fewer than 2,000 series per pod" | **Unverifiable as stated, and at risk** | No current series count is recorded anywhere. The `operation` × `outcome` fan-out plus histogram buckets makes 2,000 a live constraint rather than headroom. See OQ-007 and OQ-016. |

### Outbound-dependency and operation inventory (input to FR-003 and OQ-006/OQ-007)

| `dependency` | Class | Operations with production call sites | Effective read timeout |
|---|---|---|---|
| `rag` | `ApimDocumentIngestionClient` | `initiateDocumentUpload` | 180 s |
| `rag` | `ApimDocumentIngestionStatusClient` | `documentStatusByReference` | 180 s |
| `rag` | `RagAnswerAsyncServiceImpl` | `answerUserQueryAsync`, `answerUserQueryStatus` | 180 s |
| `rag` | `RagAnswerServiceImpl` | `answerUserQuery` (synchronous) | 180 s |
| `progression` | `ProgressionClientImpl` | `getCourtDocuments`, `getCourtDocumentsForAllDefendants`, `getMaterialDownloadUrl` | 15 s |
| `hearing` | `HearingClientImpl` | `getHearingsAndCases`, `getHearingCasesForDay` | 15 s |
| `azure_blob` | `AzureBlobStorageService` | `copyFromUrl` | 120 s poll timeout (not HTTP) |

**11 live operations.** Note `RagAnswerAsyncServiceImpl` and `RagAnswerServiceImpl` are annotated
`@RestController` while also acting as outbound clients — they implement the RAG OpenAPI interfaces
and are registered as MVC controllers, so any instrumentation must be careful not to conflate
CDKS's own inbound surface with its outbound calls.

### Actors

| Actor | Interest in this change |
|---|---|
| Production support engineer | Primary. Needs rate/latency/error signals — "RAG is slow", "ingestion is failing", "tasks are giving up" — instead of grepping logs. |
| Platform / SRE (Prometheus + alerting owners) | Consume `/actuator/prometheus`; own the scrape config, the series budget and the alert rules, none of which live in this repo (OQ-019). The 2,000-series and 1-second budgets are theirs. |
| CDKS engineers | Diagnose from the same meters; must not regress request-path or JobManager-task latency, and must not introduce a new failure mode (NFR-004). |
| Security / data-protection reviewer | Confirms the new metric surface exposes counts and durations only — no case identifiers, no case content (NFR-001, OQ-020). |
| JobManager / `task-manager-service` maintainers | Owners of the only component that knows when a retry budget is exhausted (OQ-008). A library change may be needed. |

**Note on source:** derived from the pasted Jira text at `00-input-brief.md`. The ticket itself was
not fetched in this session — no Jira/Atlassian MCP tool is available here, so no summary comment
has been posted to the epic (OQ-001).

---

## Functional Requirements

### Area A — document-ingestion phase counters and duration

| ID | Requirement |
|----|-------------|
| FR-001 | A counter `cdk_document_ingestion_phase_total` is incremented **once per persisted transition** of `CaseDocument.ingestionPhase`, tagged `phase` and `source`. `phase` values are drawn from the `DocumentIngestionPhase` enum constants verbatim; the emitted subset is subject to OQ-003. `source` is subject to OQ-002 — it must be a bounded, fixed set of values, not the free-text `case_documents.source` column as-is. |
| FR-002 | No tag on `cdk_document_ingestion_phase_total` — or on any meter added by this ticket — may carry a `caseId`, `docId`, `defendantId`, `materialId`, `courtdocId`, court centre/room id, court reference number, `CJSCPPUID`, RAG transaction id, blob URI or document name. Unbounded cardinality and OFFICIAL-SENSITIVE data are both prohibited. |
| FR-003 | A timer `cdk_document_ingestion_duration_seconds` records the elapsed wall-clock time for a document's ingestion from its start phase to a terminal phase (`INGESTED` or `FAILED` per the ticket; `EXCEEDED_FILE_SIZE_LIMIT` is a third terminal phase the ticket omits — OQ-004). The start phase as specified (`UPLOADING`) is never persisted, so the start anchor must be redefined at Design (OQ-004). |
| FR-004 | `cdk_document_ingestion_duration_seconds` publishes histogram buckets such that p50, p95 and p99 are queryable **in Prometheus** (i.e. server-side, from `_bucket` series — not client-side pre-computed percentiles, which are not aggregatable across pods). Bucket strategy is constrained by the series budget (NFR-002, OQ-007). |

### Area B — outbound dependency calls and connection pool

| ID | Requirement |
|----|-------------|
| FR-005 | A timer `cdk_external_call_duration_seconds` records every outbound call to RAG, Progression, Hearing or Azure Blob, on **both** the completion and the throw path, tagged `dependency` (exactly `rag`, `progression`, `hearing`, `azure_blob`), `operation` (bounded set per OQ-006) and `outcome` (exactly `success`, `client_error`, `server_error`, `timeout`). |
| FR-006 | An exception raised on an outbound call is **recorded and rethrown, never swallowed** — instrumentation must not alter the existing exception contract of any client. Specifically, the `RagClientException` thrown by `RagAnswerAsyncServiceImpl` and `ApimDocumentIngestionClient` (and by `ApimDocumentIngestionStatusClient` and `RagAnswerServiceImpl`) continues to propagate exactly as it does today, with the same type, message and cause. The mapping from exception/status to `outcome` is subject to OQ-005. |
| FR-007 | When an outbound call exceeds its configured response timeout, `cdk_external_call_duration_seconds` records `outcome=timeout`, distinguishable from `server_error`. The effective timeout is per-dependency, not a uniform 3 minutes (OQ-015). |
| FR-008 | A gauge `cdk_http_pool_connections_leased` is published over the shared `PoolingHttpClientConnectionManager` bean so that pool exhaustion against its configured limits (200 total / 50 per route) is visible **before** it causes an outage. This implies the configured maxima and the pending-request count must also be visible, not the leased count alone — a leased count without its ceiling cannot express "exhausted". Meter naming/provenance is subject to OQ-012. |

### Area C — answer-generation outcomes

| ID | Requirement |
|----|-------------|
| FR-009 | A counter `cdk_answer_generation_total` is incremented **once** when an answer-generation transaction driven by `GenerateAnswerForQueryTask` and `CheckStatusOfAnswerGenerationTask` reaches a terminal state, tagged `outcome` (exactly `succeeded`, `failed`, `timed_out`) and `query_level` (`CASE`, `DEFENDANT`, `CASE_ALL_DOCUMENTS`). What constitutes "terminal", how `timed_out` is detected, and what `query_level` carries when `parseQueryLevel` yields `null` are all subject to OQ-011. |

### Area D — JobManager retry behaviour

| ID | Requirement |
|----|-------------|
| FR-010 | A counter `cdk_task_retry_total` is incremented when a JobManager task yields `ExecutionStatus.INPROGRESS` with `shouldRetry=true`, tagged `task_name` with a value from `TaskNames`. Whether this counts *requested* or *granted* retries — and how tasks whose exceptions are converted to `INPROGRESS` by `TaskExecutor` outside CDKS code are handled — is subject to OQ-010. |
| FR-011 | A counter `cdk_task_retry_exhausted_total`, tagged `task_name` from `TaskNames`, is incremented when a task exhausts its configured attempt budget (`default-retry` 3, `verify-document-status` 50, `questions-retry` 100 — all three verified). Exhaustion is not currently observable from CDKS code; the mechanism is subject to OQ-008. |
| FR-012 | `cdk_task_retry_exhausted_total` is **documented in-repo** as the primary "work is being silently abandoned" signal, with the documentation stating which `task_name` values can appear, which attempt budget governs each, and that `GENERATE_ANSWER_FOR_QUERY` currently cannot be retried at all (OQ-010). Documentation location per OQ-017. |

### Area E — cross-cutting: exposure, cardinality, safety

| ID | Requirement |
|----|-------------|
| FR-013 | Every `cdk_*` meter introduced by this ticket is present on `/actuator/prometheus` and carries the existing common tags `service`, `cluster` and `region` from `management.metrics.tags`. Counters that have not yet been incremented must still appear (a zero series is a signal; a missing series is ambiguous), following DD-43185's precedent. |
| FR-014 | Metric recording **never changes business behaviour.** If any meter registration, tag computation or record call throws, the business operation completes unaffected, the original exception (if any) propagates unchanged, and no HTTP response, JobManager `ExecutionInfo`, persisted phase or RAG response field is altered or dropped. |
| FR-015 | A metric-recording failure is logged at **WARN**, rate-limited to **at most once per minute**, never once per occurrence. Mechanism per OQ-017. |

---

## Out of scope

- **Prometheus alert rules, recording rules, Grafana dashboards, SLO definitions or on-call
  routing.** These live outside this repository; this ticket delivers signals only (OQ-019).
- **Fixing the ingestion phase model.** `UPLOADING` and `INGESTING` being unreachable, and
  `NOT_FOUND` being a response-only value, is a pre-existing modelling defect (DD-43185 ADR-004's
  recorded follow-up). DD-43182 observes the model as it is; it must not add phase writes to make
  its own metrics more interesting.
- **Changing any timeout, retry budget, pool size, `disableAutomaticRetries()`, or the
  `RestClientFactory` build path's behaviour.** Only observability is added around them. This
  includes the shared-connection-manager mutation noted in OQ-015 — flagged, not fixed here.
- **Fixing `GenerateAnswerForQueryTask`'s missing `getRetryDurationsInSecs()` override.** DD-43182
  surfaces it (FR-012); repairing it changes retry behaviour and needs its own ticket.
- **Any new or changed REST endpoint.** The metrics surface is the existing `/actuator/prometheus`;
  `api-cp-crime-caseadmin-case-document-knowledge` and `version.cdk` are untouched.
- **Any Flyway migration.** Nothing here needs a schema change. (Current highest is `V1014`, from
  DD-43185; next free is `V1015`.)
- **Distributed tracing, log correlation, or OTLP metric export.** `management.otlp.metrics.export`
  and `management.tracing` stay at their current disabled defaults.
- **Re-litigating the meter-naming convention.** Settled by DD-43185 ADR-001; DD-43182 applies it.
- **Metrics for Artemis audit publishing, HikariCP, JVM, or the inbound HTTP request path.** Not
  asked for, and the last three are already framework-supplied.
- **Retrospective/backfilled metrics** for ingestions or answers completed before first deployment.

---

## Non-Functional Requirements

Trimmed to NFRs carrying ticket-specific decision content. Migration governance, PMD/JaCoCo,
platform versions, JSON-logging format and Managed-Identity rules are covered generically by
CLAUDE.md's hard rules and are not repeated here.

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-001 | Data protection | No `case_id`, `doc_id`, `defendant_id`, `material_id`, `courtdoc_id`, court centre/room id, court reference number, `CJSCPPUID`, RAG transaction id, blob URI/name, document name, `llm_input` or answer text may appear in a metric name, tag key, tag value, or in any log line added by this change. Every tag value must come from a `CdkMeters` constant, a Java enum constant, or an explicitly enumerated bounded set — never from request data, job data, a URI path variable, or a free-text database column. |
| NFR-002 | Cardinality | The **total** series on `/actuator/prometheus` must stay under **2,000 per pod**, inclusive of framework-supplied series and DD-43185's 14. Every meter added by this ticket must have its series count computed and recorded (DD-43185 NFR-002's precedent), with histogram buckets counted explicitly. On the ticket's tag sets as written this budget is likely breached — see OQ-007; resolving it may require reducing a tag set or a bucket strategy, which is a functional change and must be gated. |
| NFR-003 | Performance / isolation | Instrumentation adds no measurable latency to the request path or to JobManager task execution. No metric may be computed by querying the database or a remote service **on scrape**; gauges serve in-memory values. `/actuator/prometheus` must complete in **under 1 second** (measurement method per OQ-016). |
| NFR-004 | Availability | No new failure mode. A failing meter registration, a failing tag computation, or a full/contended registry must not fail a request, fail a JobManager task, alter an `ExecutionInfo`, fail startup, or affect `/actuator/health`. |
| NFR-005 | Backward compatibility | Purely additive. DD-43185's six meters and 14 series are unchanged; existing framework metrics are unchanged; `ActuatorHttpLiveTest`, `MonitoringMetricsHttpLiveTest` and `SchedulerMetricsHttpLiveTest` pass with their existing assertions unmodified. No client, task, service or repository signature changes in a way that alters behaviour. |
| NFR-006 | RAG data preservation | Instrumenting the RAG clients must not drop, reorder or transform any field of a RAG response (`doc_id`, `llm_input`, `llmResponse`, `documentChunks`, `transactionId`, status). If instrumentation is applied by wrapping or intercepting a client, an explicit test must assert response-field parity before and after. (CLAUDE.md hard rule.) |
| NFR-007 | Testability | Unit coverage for every counter/timer/gauge increment path **and** for the FR-014 failure-containment path (a throwing registry must not break the business operation). `integrationTest` coverage asserting each new metric name appears on `/actuator/prometheus` against the compose stack, following `MonitoringMetricsHttpLiveTest`'s pattern, plus a test that bounds the total series count (NFR-002). `gradle clean build` (including `integration`) passes. |
| NFR-008 | Naming consistency | Every new meter name, tag key and fixed tag value is added to the existing `uk.gov.hmcts.cp.cdk.metrics.CdkMeters` and follows DD-43185 ADR-001: lowercase dot-separated registration names, no `.total` segment on counters, tag values mirroring their source-of-truth token. `CdkMeters`' Javadoc mapping table is extended to cover the new meters. |
| NFR-009 | Configurability | If instrumentation is made switchable (per-area or globally) or if histogram buckets are configurable, it follows the repo's existing `application-cdk.yml` + `CP_CDK_*` convention, consistent with DD-43185's `cdk.monitoring.*` / `CP_CDK_MONITORING_*` namespace (ADR-002). Whether a kill-switch is wanted at all is OQ-013. |

---

## Acceptance Criteria

Derived one-for-one from the eight Gherkin scenarios in `00-input-brief.md`. Nothing here extends
the ticket's scope; where the ticket is silent or contradicts the code, the AC records the
requirement as stated and an open question is raised against it rather than an answer being
invented. ACs marked **(blocked)** cannot be met as written and depend on the named open question.

**Document ingestion phase transitions are counted (FR-001, FR-002)**
- AC-001: Given a `CaseDocument.ingestionPhase` value is persisted, when the write commits, then `cdk_document_ingestion_phase_total` is incremented by exactly 1 for the phase written — once per persisted transition, not once per read and not once per enclosing task invocation.
- AC-002: `cdk_document_ingestion_phase_total` carries exactly the tags `phase` and `source` (plus the common tags), and `phase` values are `DocumentIngestionPhase` constant names verbatim.
- AC-003: No tag key or tag value on `cdk_document_ingestion_phase_total` contains a case id, document id, defendant id, material id, court reference or any other case identifier; a test asserts every emitted tag value is a member of a fixed, enumerated set.
- AC-004: The `source` tag has a bounded, enumerated value set that cannot grow from database or request content (OQ-002).

**Ingestion duration is measured end to end (FR-003, FR-004)**
- AC-005 **(blocked, OQ-004)**: Given a document's ingestion begins, when it reaches `INGESTED` or `FAILED`, then `cdk_document_ingestion_duration_seconds` records the elapsed time between those two points. The ticket's stated start trigger — entering phase `UPLOADING` — never occurs in production, so the start anchor must be redefined before this AC is testable.
- AC-006: `cdk_document_ingestion_duration_seconds` publishes `_bucket` series on `/actuator/prometheus` sufficient for `histogram_quantile` to return p50, p95 and p99, and the bucket set is asserted by an integration test.
- AC-007: A document that reaches a terminal phase records exactly one observation on the timer; a document still in a non-terminal phase records none.

**Every outbound dependency call is timed and counted (FR-005, FR-006)**
- AC-008: Given a call to RAG, Progression, Hearing or Azure Blob, when it completes normally, then `cdk_external_call_duration_seconds` records one observation with `outcome=success` and the correct `dependency` and `operation` tags.
- AC-009: Given the same call throws, then one observation is still recorded, with a non-`success` `outcome`, and the original exception propagates to the caller unchanged in type, message and cause.
- AC-010 **(blocked, OQ-005)**: Given `RagAnswerAsyncServiceImpl` or `ApimDocumentIngestionClient` throws `RagClientException`, then the call is recorded with a `server_error` outcome and the exception is not swallowed. As written this conflicts with the presence of `client_error` and `timeout` in the same tag set, because `RagClientException` also wraps 4xx responses and timeouts.
- AC-011: `dependency` takes exactly one of `rag`, `progression`, `hearing`, `azure_blob`, and `operation` takes a value from a fixed enumerated set that can never include a URI path variable.

**The 3-minute no-retry HTTP configuration is observable (FR-007, FR-008)**
- AC-012: Given a downstream call exceeds its configured response timeout, when the timeout fires, then `cdk_external_call_duration_seconds` records `outcome=timeout` for that `dependency` and `operation`, distinguishable from `server_error`.
- AC-013: A gauge reporting leased connections on the shared `PoolingHttpClientConnectionManager` is present on `/actuator/prometheus`, alongside enough companion series (configured total max 200, per-route max 50, pending) for a consumer to express "the pool is approaching exhaustion" without hard-coding the limits into an alert rule.
- AC-014: The pool gauge reads from the single shared `PoolingHttpClientConnectionManager` bean, so it reflects all Apache-HttpClient outbound traffic regardless of which `RestClient` issued the call. It is documented as **not** covering `dependency=azure_blob` (OQ-014).

**Answer generation outcomes are counted (FR-009)**
- AC-015: Given an answer-generation transaction reaches a terminal state, when that state is determined, then `cdk_answer_generation_total` is incremented by exactly 1 with the corresponding `outcome` and `query_level` tags — exactly once per transaction, not once per poll of `CheckStatusOfAnswerGenerationTask`.
- AC-016: `outcome` takes exactly one of `succeeded`, `failed`, `timed_out`, and `query_level` exactly one of `CASE`, `DEFENDANT`, `CASE_ALL_DOCUMENTS`.
- AC-017 **(blocked, OQ-011)**: `outcome=timed_out` is recorded when an answer-generation transaction times out. No code path currently produces or detects such a state, and `ANSWER_GENERATION_FAILED` is itself non-terminal until the `questions-retry` budget is spent, so both `timed_out` and the timing of `failed` are undefined.

**JobManager retry behaviour is measurable (FR-010, FR-011, FR-012)**
- AC-018: Given a JobManager task yields `ExecutionStatus.INPROGRESS` with `shouldRetry=true`, then `cdk_task_retry_total` is incremented by 1 with `task_name` set to that task's `TaskNames` constant.
- AC-019: `task_name` takes only values defined in `TaskNames`; a test asserts no other value can be emitted.
- AC-020 **(blocked, OQ-008)**: Given a task exhausts its configured attempts (`default-retry` 3, `verify-document-status` 50, `questions-retry` 100), then `cdk_task_retry_exhausted_total` is incremented by 1 with the corresponding `task_name`. `task-manager-service` 1.0.11 exposes no event, callback or extension point at the point of exhaustion, so this is not achievable from CDKS code without a new mechanism.
- AC-021: In-repo documentation identifies `cdk_task_retry_exhausted_total` as the primary "work is being silently abandoned" signal, enumerates the `task_name` values that can appear, and states each one's governing attempt budget.

**Metrics are scrapeable and correctly tagged (FR-013, NFR-002, NFR-003)**
- AC-022: When `/actuator/prometheus` is scraped against the compose stack, then every metric named in this ticket is present, including counters that have not yet been incremented.
- AC-023: Every `cdk_*` series carries the common tags `service`, `cluster` and `region`.
- AC-024 **(at risk, OQ-007/OQ-016)**: The scrape completes in under 1 second and the response contains fewer than 2,000 series per pod. Both bounds must be asserted by an automated test; on the tag sets as written the series bound is unlikely to hold.

**Instrumentation does not change behaviour (FR-014, FR-015, NFR-004, NFR-006)**
- AC-025: Given metric recording throws for any reason (registry failure, tag-computation failure, meter-limit breach), when the surrounding business operation runs, then it completes exactly as it would without instrumentation: same HTTP status and body, same persisted phase, same `ExecutionInfo`, same propagated exception, and no RAG response field dropped or altered.
- AC-026: Given repeated metric-recording failures, when they occur, then a WARN is logged at most once per minute — not once per occurrence — and the log line contains no case content, case identifier or `CJSCPPUID` and is emitted as structured JSON via the existing `logback-spring.xml`.
- AC-027: A test exercises the FR-014 containment path explicitly with a registry or meter that throws, asserting the business outcome is unchanged.

**No regression**
- AC-028: `gradle clean build` (including `integration`) passes; PMD and JaCoCo are green at existing, unmodified thresholds; CodeQL and the secrets scanner are clean.
- AC-029: `ActuatorHttpLiveTest`, `MonitoringMetricsHttpLiveTest` and `SchedulerMetricsHttpLiveTest` pass with their existing assertions unmodified; DD-43185's six meter names, its 14 series, and every existing timeout, retry budget, pool size and cron expression are unchanged.
- AC-030: The diff introduces no PII, case content, court reference number or `CJSCPPUID` into code, config, tests or fixtures; all test data is synthetic.

---

## Candidate Sub-Stories (preview for Stage 3)

Indicative breakdown; each needs its own Jira sub-ticket before Test Specs, per the CLAUDE.md rule
that every story has a linked ticket. Story 0 is a hard prerequisite for everything that follows,
because three of the four capability areas have a blocked AC.

0. **Story 0 — Resolve the blocking open questions.** Not code. Close OQ-002, OQ-003, OQ-004, OQ-005, OQ-006, OQ-007, OQ-008, OQ-011 at the Stage-2 gate and record each in `adrs/DD-43182-operational-metrics-instrumentation.md`. Without this, Stories 1, 3 and 4 cannot be specified.
1. **Story 1 — Ingestion phase counter.** `cdk.document.ingestion.phase` at the three persisted write sites, with the agreed `phase`/`source` value sets. Covers FR-001, FR-002, AC-001 – AC-004. Depends on OQ-002/OQ-003.
2. **Story 2 — Outbound dependency timer.** `cdk.external.call.duration` across the four dependencies with the agreed instrumentation mechanism and `outcome` derivation. Covers FR-005, FR-006, FR-007, AC-008 – AC-012. Depends on OQ-005/OQ-006/OQ-013.
3. **Story 3 — HTTP connection-pool visibility.** Register the pool meters over the shared `PoolingHttpClientConnectionManager`. The smallest and lowest-risk story here — a single `@Bean` if OQ-012 chooses the framework binder. Covers FR-008, AC-013, AC-014.
4. **Story 4 — Ingestion duration timer.** `cdk.document.ingestion.duration.seconds` plus its bucket strategy, once OQ-004 has fixed the start anchor. Covers FR-003, FR-004, AC-005 – AC-007.
5. **Story 5 — Answer-generation outcome counter.** `cdk.answer.generation` at the terminal points of the queryflow, once OQ-011 has defined "terminal", `timed_out`, and the null-`query_level` case. Covers FR-009, AC-015 – AC-017.
6. **Story 6 — JobManager retry counter.** `cdk.task.retry` at the seven tasks' retry paths. Covers FR-010, AC-018, AC-019. Depends on OQ-010.
7. **Story 7 — Retry-exhaustion counter and documentation.** `cdk.task.retry.exhausted` via whichever mechanism OQ-008 selects, plus the FR-012 documentation. **Highest-risk story: may require a `task-manager-service` change and therefore an external dependency and its own lead time.** Covers FR-011, FR-012, AC-020, AC-021.
8. **Story 8 — Cardinality, scrape budget and safety harness.** Series-count and scrape-time assertions; the FR-014/FR-015 containment-and-throttle harness; extend `CdkMeters` and its Javadoc mapping table. Covers FR-013, FR-014, FR-015, NFR-002, NFR-003, NFR-004, NFR-007, NFR-008, AC-022 – AC-030.

Explicitly **not** a story here: alert rules, dashboards, phase-model repair,
`GenerateAnswerForQueryTask`'s retry-config fix, or any new API endpoint.

---

## Open Questions

- **OQ-001 (source of truth):** Jira DD-43182 was not fetched in this session — no Jira/Atlassian MCP tool is available, so this document is grounded solely in the pasted text at `00-input-brief.md`, and **no summary comment has been posted to the epic**. Confirm the pasted brief is the complete and current ticket text (no later comments, no revised ACs) and post the Stage-1 summary manually. — Owner: requester · Due: before Stage 2.
- **OQ-002 (what is the `source` tag?):** `CaseDocument.source` exists but is `TEXT NOT NULL DEFAULT 'IDPC'` (`V1001` line 65) with only a not-blank check, and **no production code calls `setSource`** — so today it has exactly one value, making the tag informationally empty, while being *unbounded by type*, which violates the ticket's own cardinality rule. Three candidate meanings: (a) the `case_documents.source` column as-is; (b) the ingestion trigger origin — manual "Process IDPC" versus scheduled discovery, which is a genuinely useful dimension and already distinguished in code by `JobPriority.HIGH` vs `DEFAULT`; (c) the writing component (`IdpcAvailabilityService` / `RetrieveMaterialAndUploadTask` / `CheckIngestionStatusForAllDefendantsTask`). Pick one and enumerate its values as `CdkMeters` constants. — Owner: requester · Due: Stage 2, ADR required.
- **OQ-003 (three of the eight phases are never persisted):** the ticket enumerates all eight `DocumentIngestionPhase` values as `phase` tag values. Verified (and consistent with DD-43185 ADR-004): only `WAITING_FOR_UPLOAD`, `UPLOADED`, `INGESTED`, `FAILED` and `EXCEEDED_FILE_SIZE_LIMIT` are ever written. `UPLOADING` exists only as the Java field initialiser and the `V1001` column default; `INGESTING` appears only in two test fixtures; `NOT_FOUND` is a response-only DTO value in `IngestionService`. A *transition* counter for a phase nothing writes can never increment. Decide: register all eight (permanently-zero series are cheap and future-proof, matching ADR-004's reasoning) or only the five reachable ones. — Owner: requester · Due: Stage 2.
- **OQ-004 (the ingestion duration timer has no start event — blocking):** the scenario is "Given a document enters phase `UPLOADING`, when it reaches `INGESTED` or `FAILED`". Nothing persists `UPLOADING` (OQ-003), so as written the timer never starts. Four decisions needed: (a) the real start anchor — `case_documents.created_at`, set at `WAITING_FOR_UPLOAD` by `IdpcAvailabilityService`, is the only durable one, since `ingestion_phase_at` is overwritten on every transition; (b) whether `EXCEEDED_FILE_SIZE_LIMIT` is a third terminal stop (it is a real terminal phase the scenario omits); (c) the mechanism — start and end occur in **different JobManager tasks, potentially on different pods, minutes-to-hours apart**, so this cannot be an in-process `Timer.Sample`; it must be `Timer.record(Duration)` computed from persisted timestamps at the terminal transition; (d) what happens to documents that never reach a terminal phase — they contribute no observation, which means the timer is systematically biased towards successes and *cannot* detect the stall case (that is DD-43185's `cdk_documents_stalled`, and the two metrics should be documented as complementary). — Owner: requester + design reviewers · Due: Stage 2, ADR required.
- **OQ-005 (`RagClientException` → `server_error` contradicts the `outcome` tag set — blocking):** all four RAG client classes wrap *both* `HttpStatusCodeException` (4xx **and** 5xx) *and* a bare `catch (Exception)` (connect/read timeout, parse failure) into `RagClientException`. So "a `RagClientException` is recorded as `outcome=server_error`" would make `client_error` and `timeout` permanently unreachable for `dependency=rag` — contradicting the same scenario's own tag enumeration. Almost certainly the intent is that `outcome` derives from the HTTP status code (4xx → `client_error`, 5xx → `server_error`) or the underlying cause (`SocketTimeoutException` / `ConnectTimeoutException` → `timeout`), with `RagClientException` merely being *the* thing that must not be swallowed. Confirm. Also confirm the rule extends to `ApimDocumentIngestionStatusClient` and `RagAnswerServiceImpl`, which the ticket does not name but which throw the same exception. — Owner: requester · Due: Stage 2, ADR required.
- **OQ-006 (`operation` tag value convention):** undefined in the ticket. 11 live operations were inventoried (see Context). Fix the convention — Java method name (`answerUserQueryAsync`), OpenAPI `operationId`, or a templated path — and guarantee it can **never** interpolate a path variable: `PATH_DOCUMENT_STATUS_BY_REFERENCE` and `PATH_ANSWER_USER_QUERY_STATUS` both carry `{...}` segments whose expanded form would be unbounded and would leak a RAG transaction id / document reference into a tag value (NFR-001). Also decide whether the three dead `StorageService` methods (`exists`, `getBlobSize` — no production call sites) get `operation` values at all. — Owner: requester + design reviewers · Due: Stage 2.
- **OQ-007 (the 2,000-series budget is very likely breached by FR-004 + FR-005 — blocking):** `management.metrics.distribution.*` is not configured today and CDKS has no `Timer` at all yet. FR-005's tag set gives up to 11 `operation` values × 4 `outcome` values = **44 timers**, on top of FR-004's ingestion timer. A plain Micrometer `Timer` is ~3 series; enabling `percentiles-histogram` makes Micrometer emit a wide default bucket set (tens of `_bucket` series per timer), which puts FR-005 alone into the low thousands and breaks the ticket's own budget in the same ticket that states it. Options, all of which are functional changes needing the gate: explicit `distribution.slo` buckets (a handful per timer) instead of `percentiles-histogram`; histogram buckets on the FR-004 ingestion timer only, with FR-005 as count+sum; dropping or coarsening the `operation` tag; or raising the budget with SRE. Whatever is chosen, NFR-002 requires the resulting series count to be computed and recorded, DD-43185 NFR-002 style. — Owner: requester + platform/SRE · Due: Stage 2, ADR required.
- **OQ-008 (`cdk_task_retry_exhausted_total` is not obtainable from CDKS — blocking):** verified against `task-manager-service` 1.0.11 by decompilation. Retry accounting is entirely internal: `TaskExecutor.canRetry(...)` and `performRetry(...)` are private, `Job.retryAttemptsRemaining` is library-owned, and the only public surface is `ExecutionService.executeWith(ExecutionInfo)`. **No event, listener, callback or hook fires when a retry budget is exhausted** (`TaskFoundEvent` is about task registration, not execution). Options: (a) raise a change against `task-manager-service` to publish an exhaustion event — cleanest, but an external dependency with its own lead time; (b) poll the task-manager `jobs` table for rows with `retry_attempts_remaining = 0`, in the style of DD-43185's ShedLock-guarded refresh job — reuses an established pattern but reaches into another component's schema and yields a gauge, not a counter; (c) count attempts inside CDKS job data, as `CheckStatusOfAnswerGenerationTask` already does with `CTX_ANSWER_RETRY_COUNT` — but that only works for CDKS-driven re-dispatch, not for framework-driven retries, so it would cover one task of seven. This is the ticket's own nominated "primary signal" (FR-012), so it cannot simply be dropped. — Owner: requester + `task-manager-service` maintainers · Due: Stage 2, ADR required.
- **OQ-009 (retry budgets are per-config-key, not per-task):** the three verified budgets (3 / 50 / 100) are keyed by `cdk.jobmanager.retry.{default,verify-document-status,questions-retry}`, whereas the tag is `task_name` from `TaskNames`. The mapping is many-to-one: `verify-document-status` (50) governs **two** tasks (`CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS`, `CHECK_ALL_DOCUMENTS_INGESTION_STATUS`); `default-retry` (3) governs three; `questions-retry` (100) governs one. So a `task_name`-tagged exhaustion counter cannot be interpreted without a documented task→budget table (FR-012). Confirm `task_name` is the right dimension, or whether a second tag naming the retry policy is wanted. — Owner: requester · Due: Stage 2.
- **OQ-010 (`GENERATE_ANSWER_FOR_QUERY` can never be retried, and "requested" ≠ "granted"):** two findings. (a) `canRetry` requires `task.getRetryDurationsInSecs().isPresent()`, and `GenerateAnswerForQueryTask` **does not override** the interface default `Optional.empty()` — so it returns `INPROGRESS`+`shouldRetry=true` on every failure and is never actually retried. A CDKS-side `cdk_task_retry_total` would therefore report retries for it that do not happen. (b) More generally, a task that *throws* is converted to `INPROGRESS`+`shouldRetry` by `TaskExecutor` itself, outside CDKS code, so a CDKS-side increment at the `retry(...)` helper misses the throw path entirely. Decide whether FR-010 counts retry *requests* (cheap, CDKS-local, but not what actually happened) or retry *grants* (accurate, but only observable inside the library — same problem as OQ-008), and whether the `GENERATE_ANSWER_FOR_QUERY` defect is documented here (FR-012) or fixed in a separate ticket. — Owner: requester + `task-manager-service` maintainers · Due: Stage 2, ADR required.
- **OQ-011 (answer-generation terminal states don't match the code — blocking):** three mismatches in one scenario. (a) **`timed_out` has no code path.** `CheckStatusOfAnswerGenerationTask` sees only `ANSWER_GENERATED`, `ANSWER_GENERATION_FAILED` and `ANSWER_GENERATION_PENDING` (→ `INPROGRESS`+retry). Nothing detects or records a timeout. Should `timed_out` mean "the `questions-retry` budget of 100 was spent while still `PENDING`" — which, per OQ-008, CDKS cannot observe? (b) **`failed` is not terminal.** On `ANSWER_GENERATION_FAILED` the task re-dispatches `GENERATE_ANSWER_FOR_QUERY` while `CTX_ANSWER_RETRY_COUNT < 100`, so the counter must increment only when the budget is spent (the `log.warn("Max retries reached…")` branch), not on every failure — otherwise one transaction can contribute up to 100 increments. (c) **`query_level` can be null.** `TaskUtils.parseQueryLevel` returns `null` for missing or invalid input and the task has an explicit `case null, default:` branch that calls `answerGenerationService.upsertAnswer`. Micrometer rejects null tag values; decide on `unknown` or on omitting the increment. — Owner: requester · Due: Stage 2, ADR required.
- **OQ-012 (reuse Micrometer's HTTP-pool binder, or hand-roll `cdk_http_pool_connections_leased`?):** `io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder` is already on the classpath (micrometer-core 1.16.5) and publishes exactly the pool-exhaustion signal FR-008 asks for — `httpcomponents_httpclient_pool_total_connections{state="leased"|"available"}`, `httpcomponents_httpclient_pool_total_max`, `httpcomponents_httpclient_pool_total_pending`, `httpcomponents_httpclient_pool_route_max_default` — for a one-line `@Bean` over the existing shared connection manager. It is **not** registered today (no `httpcomponents_*` series exist). Trade-off: the binder gives four maintained series including the configured maxima (which a bare "leased" gauge lacks — see AC-013) but under a non-`cdk_` name, so it will not match an alert rule written against `cdk_http_pool_connections_leased`. Decide: register the binder and have SRE alert on the standard name; register the binder *and* add a thin `cdk.*` alias; or hand-roll. — Owner: requester + platform/SRE · Due: Stage 2, ADR required.
- **OQ-013 (how is outbound-call timing attached?):** three viable mechanisms, with materially different cardinality and blast radius. (a) **Spring Boot's own `http.client.requests` observation** — note this is *not* active today, because `RestClientFactory.build(...)` calls the static `RestClient.builder()` rather than injecting the auto-configured `RestClient.Builder` bean, so no `ObservationRegistry` is attached. Switching to the auto-configured builder would give framework-standard timing for free, but tags on `uri` and would change the RestClient construction path for every client. (b) **A fourth `ClientHttpRequestInterceptor`** alongside `CorrelationIdInterceptor` and `DebugLoggingInterceptor` — CDKS-controlled tags, one insertion point, but it sees only URIs, so the `operation` tag needs mapping (OQ-006), and it does not cover Azure Blob. (c) **Explicit instrumentation inside each client method** — most accurate `operation` tags, but touches all four RAG classes plus Hearing, Progression and `AzureBlobStorageService`, and is the highest-risk option against NFR-006. Also decide whether a global or per-area kill-switch is wanted (NFR-009). — Owner: design reviewers · Due: Stage 2, ADR required.
- **OQ-014 (the pool gauge tells you nothing about Azure Blob):** `AzureBlobStorageService` uses the Azure SDK's own HTTP stack, not the shared `PoolingHttpClientConnectionManager`, so FR-008 gives no visibility for `dependency=azure_blob`. Its failure semantics also differ: `copyFromUrl` is a poll-to-completion with `cp.cdk.storage.copy-timeout-seconds` (default **120 s**, not 3 minutes) and surfaces a timeout as an `IllegalStateException` whose cause is a `TimeoutException`, and a failed/aborted copy as an `IllegalStateException` with no HTTP status at all. Confirm `outcome=timeout` for `azure_blob` means "the copy poll timed out", and accept that `client_error`/`server_error` may not be distinguishable for that dependency. — Owner: requester · Due: Stage 2.
- **OQ-015 ("the 3-minute no-retry HTTP configuration" is not the effective timeout for most calls):** verified — the `RestClientFactoryConfig` default bean is 3 min connect / 3 min connection-request / 3 min response with `disableAutomaticRetries()`, but every real client overrides it via `RestClientFactory.build(...)`: RAG 3 s connect / **180 s** read (coincidentally 3 min), Hearing and Progression 3 s connect / **15 s** read. So `outcome=timeout` fires at 15 s for two of the four dependencies and at 3 s on connect for all of them, and any alert or dashboard annotation assuming a uniform 180 s will be wrong. Separately, and worth recording: `RestClientFactory.build(...)` calls `connectionManager.setDefaultConnectionConfig(...)` on the **shared** connection-manager bean on every invocation, so the effective *connect* timeout is whichever client was constructed last — a pre-existing defect that FR-007's timeout metric will make visible. Confirm the metric is expected to expose per-dependency timeouts, and confirm the shared-mutation defect is logged as a separate ticket rather than fixed here (it is out of scope above). — Owner: requester + design reviewers · Due: Stage 2.
- **OQ-016 (how are the scrape-time and series-count budgets measured and enforced?):** "the scrape completes in under 1 second with fewer than 2,000 series per pod" is currently unverifiable — no baseline series count exists for CDKS. Decide: (a) where it is measured — an `integrationTest` against the compose stack (repeatable, but the compose app's series count is not production's, since `cluster`/`region` collapse and some conditional beans differ) or a one-off production scrape captured on the ticket; (b) whether it is a **merge-blocking automated assertion** or documented evidence; (c) whether the 2,000 budget counts only `cdk_*` series or the whole endpoint including framework metrics — the ticket says "per pod", implying the whole endpoint, which makes it a much tighter constraint. — Owner: requester + platform/SRE · Due: Stage 2.
- **OQ-017 (the WARN-once-per-minute mechanism, and where FR-012's documentation lives):** two parts. (a) No log rate-limiting exists in this codebase — `logback-spring.xml` is a `LogstashEncoder` `ConsoleAppender` behind an `AsyncAppender`, with no `DuplicateMessageFilter` or `TurboFilter`. Choose: a Logback filter (global, affects all logging), an in-code throttle in the metrics helper (local, testable, the likely answer), or declare the requirement satisfied by design because Micrometer meter registration is idempotent and `Counter.increment()` / `Timer.record()` do not throw in normal operation — in which case FR-015 is really about the code that *computes* tag values, and should say so. (b) FR-012 requires `cdk_task_retry_exhausted_total` to be "documented" — confirm the location: `CdkMeters` Javadoc (consistent with DD-43185's precedent), a new `docs/` runbook page, or the ADR. — Owner: requester + design reviewers · Due: Stage 2.
- **OQ-018 (DD-43185 is not yet on `main` — sequencing):** the `metrics` package, `CdkMeters` and ADR-001's convention exist on branch `DD-43185` (commit `885357e`, PR #224) but **`main` is still at `ae2205e` and has no `metrics` package**. DD-43182 must extend `CdkMeters` rather than create a parallel constants class, so either DD-43185 merges first or DD-43182 branches from it and accepts the merge dependency. Confirm the order. Also still open from DD-43185's own OQ-002, and now affecting eight more meter names: **confirm with SRE that the platform's Prometheus scrape config and alert rules expect the `cdk_` prefix** — that cannot be verified from inside this repository, and renaming after alert rules exist is a coordinated cross-repo change. — Owner: requester + platform/SRE · Due: before Stage 2.
- **OQ-019 (alerting ownership — inherited from DD-43185 OQ-011):** DD-43182's whole value is alerts and dashboards nobody has committed to building, and FR-012 explicitly frames `cdk_task_retry_exhausted_total` as an alerting signal. Prometheus rules are not held in this repository. Confirm alert definition is out of scope for DD-43182, identify the owning team and the follow-up ticket, and specifically confirm ownership of a threshold for the pool gauge (`leased / total_max` ratio) and for the retry-exhaustion counter. — Owner: requester + platform/SRE · Due: before Stage 3.
- **OQ-020 (metrics endpoint exposure — inherited from DD-43185 OQ-012, with a new dimension):** `/actuator` is excluded from `cp-auth-rules-filter` and `prometheus` is on the exposure list. DD-43185 established that publishing aggregate *counts* there is acceptable for an OFFICIAL-SENSITIVE service. DD-43182 adds something DD-43185 did not: the `dependency`, `operation` and `task_name` tags publish CDKS's **internal call topology and workflow structure**, and the duration histograms publish its performance profile. No case data, but more architectural detail than before. Re-confirm with the security reviewer that the actuator port/path is not externally reachable and that this level of internal detail is acceptable. — Owner: security reviewer · Due: before merge.
- **OQ-021 (is an answer-generation *duration* deliberately omitted?):** the ticket asks for an answer-generation outcome *counter* but no answer-generation duration timer, while `cdk_external_call_duration_seconds{dependency="rag"}` will time only the individual HTTP hops (sub-second to 180 s), not the end-to-end async answer, which spans `GenerateAnswerForQueryTask` → N polls of `CheckStatusOfAnswerGenerationTask` at 10 s intervals and can legitimately run for many minutes. So after this ticket, "how long does an answer take?" is still unanswerable. Confirm that is intentional (and, if so, note it as the obvious follow-up) rather than an omission — it is the direct analogue of FR-003 for the queryflow, and would be cheap once OQ-004 has settled the cross-task duration pattern. — Owner: requester · Due: Stage 2, non-blocking.
