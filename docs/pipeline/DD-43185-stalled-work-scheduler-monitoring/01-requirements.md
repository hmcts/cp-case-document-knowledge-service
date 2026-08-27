# Requirements: Stalled-Work Gauges and Scheduler Heartbeat Observability

> **Stage 1 — Requirements** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43185**
> Two related but separable capability areas in one ticket: (A) **stuck-work gauges** over
> `case_documents` and `case_query_status`, refreshed on a
> ShedLock-guarded schedule; (B) **scheduler heartbeat/liveness** for `IntradayDiscoveryScheduler`
> and `NightlyDiscoveryScheduler`. They share no code beyond the Micrometer registry and should be
> split into separate stories at Stage 3.
> Metric naming (OQ-002), threshold property shape (OQ-003), index support (OQ-004) and the
> multi-pod/restart semantics of the heartbeat gauge (OQ-010) are deliberately left open for the
> Design stage; each is expected to be recorded in
> `adrs/DD-43185-stalled-work-scheduler-monitoring.md`.

---

> **Note — `document_verification_task` excluded.** This ticket covers two stuck-work gauges
> (`case_documents`, `case_query_status`), not three. A `document_verification_task` gauge was
> considered and excluded: the table is dead — a leftover from an old Spring Batch-based flow,
> superseded by the JobManager framework, with nothing writing to it, confirmed both by static
> analysis and by a live query against the running database. No metric is built for a dead table.
> Table cleanup itself (dropping the entity/table) is a separate future ticket, not part of
> DD-43185.

---

## Context

CDKS moves case documents through an asynchronous, multi-hop pipeline: `IdpcAvailabilityService`
creates a `case_documents` row at `WAITING_FOR_UPLOAD`, `RetrieveMaterialAndUploadTask` drives it to
`UPLOADING` / `UPLOADED`, and `CheckIngestionStatusForAllDefendantsTask` resolves it to `INGESTED`,
`FAILED` or `EXCEEDED_FILE_SIZE_LIMIT`. Alongside it, `case_query_status` rows sit at
`ANSWER_NOT_AVAILABLE` until a RAG answer lands. Every one of these transitions is stamped with a
timestamp (`ingestion_phase_at`, `status_at`), and neither is currently watched.

Today the service publishes **no custom application metrics at all**. Micrometer and the Prometheus
registry are on the classpath (`build.gradle`: `spring-boot-starter-actuator`,
`micrometer-registry-prometheus`), `/actuator/prometheus` is exposed
(`application-server-management.yml`) and already smoke-tested
(`src/integrationTest/.../actuator/ActuatorHttpLiveTest.prometheus_is_exposed`), and common tags
`service` / `cluster` / `region` are configured — but no `MeterRegistry` is injected anywhere in
`src/main/java`. Everything on `/actuator/prometheus` is framework-supplied. A document that stops
progressing at `INGESTING`, or a query that never gets an answer, is therefore invisible from
outside the pod: it surfaces only when a legal adviser in a live hearing finds an empty AI Search
result.

The scheduler side has a matching gap, and it is verifiably worse than the ticket implies:

- `IntradayDiscoveryScheduler.run()` and `NightlyDiscoveryScheduler.run()` are three lines each —
  an INFO "starting" log, a bare call into `DiscoveryService`, an INFO "finished" log. Neither has a
  `try`/`catch`, so an exception from `runIntradayDiscovery()` / `runNightlyDiscovery()` propagates
  into Spring's `TaskScheduler`, which logs it via `ErrorHandler` and swallows it. There is **no
  in-service record that a run happened, succeeded, or failed** — only the absence of a "finished"
  log line, which nothing alerts on.
- Both beans are annotated `@ConditionalOnProperty(name = "scheduler.<x>-discovery.enabled",
  havingValue = "true", matchIfMissing = true)`, and `application-cdk.yml` binds each to
  `CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED:false` /
  `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED:false`. So the ticket's "both default to false" is
  correct — **and** a disabled scheduler's bean does not exist at all. A flag left off after a
  release produces a pod that looks entirely healthy and does nothing. This also constrains the
  fix: the `cdk_scheduler_enabled` gauge and the startup INFO log cannot be emitted by the scheduler
  bean itself (see FR-010 and OQ-007).

Facts confirmed against the codebase, which the FRs below rely on:

| Ticket statement | Verified? | Detail |
|---|---|---|
| `case_documents` carries `ingestion_phase` + `ingestion_phase_at` | Yes | `V1001`; enum `document_ingestion_phase_enum`, Java `DocumentIngestionPhase` |
| Non-terminal phases `WAITING_FOR_UPLOAD`, `UPLOADING`, `INGESTING` | Yes | Enum also has `NOT_FOUND` and `UPLOADED`, which the ticket does **not** include — see OQ-005 |
| `case_query_status` carries `status` + `status_at` | Yes | `V1001`; `query_lifecycle_status_enum` has exactly two values, `ANSWER_NOT_AVAILABLE` / `ANSWER_AVAILABLE` |
| `case_query_status` "indexed by `idx_cqs_status_at_desc`" | **Partly** | That index is on `(status_at DESC)` **alone** — it does not include `status`. See OQ-004 |
| `DocumentVerificationTaskRepository.countByStatus(...)` exists | Yes | Single derived-query method, no call sites; `document_verification_task` itself is a dead table (see note above) — not used by this ticket |
| Intraday cron `0 0/10 7-19 * * MON-FRI` | Yes | Default in `application-cdk.yml` and in the `@Scheduled` fallback |
| Nightly cron `0 0 2 * * *` | Yes | Overridable via `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_CRON` |
| `CP_CDK_SCHEDULER_{INTRADAY,NIGHTLY}_DISCOVERY_ENABLED` default `false` | Yes | `application-cdk.yml` lines 66 and 72 |
| Neither scheduler has a try/catch or writes any state | Yes | Confirmed by reading both classes |

ShedLock is already wired (`ShedLockConfig`, `@EnableSchedulerLock(defaultLockAtMostFor = "PT30S",
interceptMode = PROXY_METHOD)`, JDBC provider, `shedlock` table from `V1010`), so guarding a new
refresh job is a matter of one more `@SchedulerLock` with its own lock name — noting that the
30-second global default `lockAtMostFor` is shorter than the ≥60s refresh cadence and must be
overridden explicitly for the new lock.

### Actors

| Actor | Interest in this change |
|---|---|
| Production support engineer | Primary. Needs an alert-able signal for stalled ingestion and for a scheduler that never fired. |
| Platform / SRE (Prometheus + alerting owners) | Consume `/actuator/prometheus`; own the alert rules and dashboards, which are **not** in this repo (OQ-011). |
| CDKS engineers | Diagnose from the same gauges; must not regress request-path latency or scheduler correctness. |
| Security / data-protection reviewer | Confirms the new metric surface exposes counts only — no case identifiers, no case content (NFR-001, OQ-012). |

**Note on source:** derived from the pasted Jira text at
`00-input-brief.md`. The ticket itself was not fetched in this session — no Jira/Atlassian MCP tool
is available here, so no summary comment has been posted to the epic either (OQ-001).

---

## Functional Requirements

### Area A — stuck-work gauges

| ID | Requirement |
|----|-------------|
| FR-001 | A gauge `cdk_documents_stalled` publishes a count of `case_documents` rows whose `ingestion_phase` is one of `WAITING_FOR_UPLOAD`, `UPLOADING`, `INGESTING` **and** whose `ingestion_phase_at` is older than a configurable threshold. Tagged **by phase only** — no case, document, court centre or defendant dimension. Default threshold 30 minutes, configured by property `cdk.monitoring.stalled-threshold` (exact type/format and env-var binding per OQ-003). |
| FR-002 | A gauge `cdk_queries_awaiting_answer` publishes the count of `case_query_status` rows with `status = ANSWER_NOT_AVAILABLE` whose `status_at` is older than the same threshold. The ticket specifies no tags for this gauge; it is therefore untagged beyond the globally-configured common tags. |
| FR-004 | The two gauges (`cdk_documents_stalled`, `cdk_queries_awaiting_answer`) are refreshed together on a fixed schedule that runs **no more often than every 60 seconds**, guarded by ShedLock under its own lock name so that only one pod in the cluster performs the refresh. The new lock must set its own `lockAtMostFor` — `ShedLockConfig`'s global `defaultLockAtMostFor = PT30S` is shorter than the refresh cadence. |
| FR-005 | Each aggregate is a single indexed aggregate query that reads only the phase/status and timestamp columns needed for the count. It must not select, join to, or scan document content, answer text, `llm_input`, or blob payloads. Target: each query completes in **under 500 ms** against production-scale data, evidenced by an EXPLAIN plan (deliverable and environment per OQ-005/OQ-009). |
| FR-006 | Refresh failure degrades safely: if an aggregate query fails or times out, the affected gauges retain their last known value, the failure is logged at **WARN**, and no user-facing request path is affected. The refresh must not propagate an exception into the Spring scheduler and must not mark the pod unhealthy. |

### Area B — scheduler heartbeat and liveness

| ID | Requirement |
|----|-------------|
| FR-007 | When `IntradayDiscoveryScheduler.run()` or `NightlyDiscoveryScheduler.run()` completes **without throwing**, a gauge `cdk_scheduler_last_success_epoch_seconds` is updated to that completion time, tagged by scheduler name (tag value convention per OQ-008). |
| FR-008 | Every run of either scheduler increments a counter `cdk_scheduler_runs_total` with tags `scheduler` and `outcome`, where `outcome` is exactly one of `success` or `failure`. Exactly one increment per invocation of `run()`. |
| FR-009 | Each scheduler's `run()` catches any exception thrown by its `DiscoveryService` call, logs it at **ERROR** with the exception object (full stack trace) and the scheduler name, increments `cdk_scheduler_runs_total{outcome="failure"}`, and **does not rethrow** into the Spring scheduler. The `cdk_scheduler_last_success_epoch_seconds` gauge is left unchanged on a failed run. |
| FR-010 | A gauge `cdk_scheduler_enabled` reports `0` or `1` per scheduler, and the enabled state of each scheduler is logged at **INFO** at startup. Because both scheduler beans are `@ConditionalOnProperty`-gated, the disabled case produces no bean — so this gauge and log must be emitted from an always-present component that reads the configured flags, not from the scheduler classes themselves (OQ-007). |

---

## Out of scope

- **Prometheus alert rules, recording rules, Grafana dashboards, or on-call routing.** These live outside this repository; this ticket delivers the signals only (OQ-011).
- **Any remediation of stalled work** — no auto-retry, no requeue, no phase reset, no cleanup job. The gauges observe; they do not act.
- **Any new or changed REST endpoint.** The metrics surface is the existing `/actuator/prometheus`; `api-cp-crime-caseadmin-case-document-knowledge` and `version.cdk` are untouched.
- **Any change to discovery behaviour** — cron expressions, ShedLock lock durations, `daysAhead`, `DiscoveryService` logic, and the existing enabled-flag defaults all stay exactly as they are. Only observability is added around them.
- **Metrics for any other pipeline stage** — JobManager task outcomes, RAG client latency/error rates, Artemis audit publishing, Azure Blob operations. Not asked for.
- **Persisting scheduler heartbeat state to the database** (as opposed to an in-memory gauge) — not requested; but see OQ-010, which may force this back into scope.
- **Backfill or historical reconstruction** of stall counts prior to first deployment.
- **Changing the actuator exposure list or the `/actuator` auth exclusion** in `application-other.yml`.
- **Monitoring `document_verification_task`, or any cleanup of that table.** The table is dead (Spring Batch-era, superseded by JobManager, no writer). Cleanup/removal of the table itself is a separate future ticket.

---

## Non-Functional Requirements

Trimmed to NFRs carrying ticket-specific decision content. Migration governance, PMD/JaCoCo,
platform versions, JSON-logging format and Managed-Identity rules are covered generically by
CLAUDE.md's hard rules and are not repeated here.

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-001 | Data protection | Every new metric publishes an **aggregate count only**. No `case_id`, `doc_id`, `defendant_id`, `material_id`, court centre/room id, court reference number, `CJSCPPUID`, document name, or answer text may appear in a metric name, tag key, tag value, or in any log line added by this change. Tag values are restricted to the fixed enum sets in FR-001 and the two scheduler names. |
| NFR-002 | Cardinality | Total added series: 4 (`cdk_documents_stalled` phases, including `UPLOADED` per ADR-004) + 1 (`cdk_queries_awaiting_answer`) + 1 (`cdk_monitoring_last_refresh_epoch_seconds`, ADR-008) + 2×2 (`cdk_scheduler_runs_total`) + 2 (`cdk_scheduler_last_success_epoch_seconds`) + 2 (`cdk_scheduler_enabled`) = **14** — see `02-design.md` §2 / ADR-001. No unbounded or user-supplied tag value may be introduced. |
| NFR-003 | Performance / isolation | The refresh runs on a scheduler thread, never on a request thread. Each aggregate < 500 ms (FR-005); the refresh must not exhaust the HikariCP pool (max 20, min 5) nor hold a connection beyond the refresh, and must not lengthen `/actuator/prometheus` scrape time — gauges serve last-refreshed values, they do not query on scrape. |
| NFR-004 | Availability | No new failure mode for the service. A failing monitoring query, a failing meter registration, or a ShedLock contention must not fail a request, fail a discovery run, fail startup, or affect `/actuator/health`. |
| NFR-005 | Backward compatibility | Purely additive. Existing metrics on `/actuator/prometheus` are unchanged; `ActuatorHttpLiveTest` passes unmodified; no existing scheduler test, JobManager test, or repository signature is broken. If FR-005 requires new indexes (OQ-004), they arrive as a single **append-only** Flyway migration at the next free version (**`V1014`** — `V1013` is consumed by DD-43083), routed through `migration-reviewer`. |
| NFR-006 | Testability | Unit coverage for the metric-refresh component and for both schedulers' success/failure paths (including the exception-containment path, FR-009), plus `integrationTest` coverage asserting the new metric names appear on `/actuator/prometheus` against the compose stack. `gradle clean build` (including `integration`) passes. |
| NFR-007 | Configurability | The threshold (FR-001/FR-002) and the refresh cadence (FR-004) are externally configurable following the repo's existing `application-cdk.yml` + `CP_CDK_*` env-var convention, with the ticket's stated defaults (30 minutes; no more often than 60 s) as the shipped values. |

---

## Acceptance Criteria

Derived one-for-one from the Gherkin scenarios in `00-input-brief.md`. Nothing here extends the
ticket's scope; where the ticket is silent, an open question is raised instead.

**Documents stalled in a non-terminal phase (FR-001)**
- AC-001: Given `case_documents` rows exist with `ingestion_phase` in (`WAITING_FOR_UPLOAD`, `UPLOADING`, `INGESTING`) and `ingestion_phase_at` older than the configured threshold, when the monitoring gauge is refreshed, then `cdk_documents_stalled` publishes a separate count per phase matching the row counts for those phases.
- AC-002: Given rows in those three phases whose `ingestion_phase_at` is **newer** than the threshold, when the gauge is refreshed, then those rows are excluded from the count.
- AC-003: Given rows in `UPLOADED`, `INGESTED`, `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT` or `NOT_FOUND` of any age, when the gauge is refreshed, then those rows are excluded from `cdk_documents_stalled` entirely (subject to OQ-005 on `NOT_FOUND`/`UPLOADED`).
- AC-004: `cdk_documents_stalled` carries a `phase` tag and no other ticket-specific tag; no case, document, defendant or court identifier appears in any tag.
- AC-005: With `cdk.monitoring.stalled-threshold` unset, the effective threshold is 30 minutes; when it is set to a different value, the next refresh applies the new value without a restart requirement being introduced beyond the repo's existing config-reload behaviour (OQ-003 governs the exact property type and whether hot-reload is expected).
- AC-006: The SQL executed for this gauge selects no document content, name, blob URI, or answer text column, and its EXPLAIN plan shows an index scan rather than a sequential scan of `case_documents` (OQ-004).

**Queries stuck without an answer (FR-002)**
- AC-007: Given `case_query_status` rows with `status = ANSWER_NOT_AVAILABLE` and `status_at` older than the threshold, when the gauge is refreshed, then `cdk_queries_awaiting_answer` publishes exactly that count; rows with `status = ANSWER_AVAILABLE`, and rows newer than the threshold, are excluded.

**Refresh cost and safety (FR-004, FR-005, FR-006)**
- AC-010: The refresh is scheduled at an interval of **at least 60 seconds**; a unit or config test asserts the configured interval is not shorter than 60 s.
- AC-011: The refresh method is ShedLock-guarded under its own lock name with an explicit `lockAtMostFor` longer than the refresh cadence; an integration test asserts a `shedlock` row exists for that lock name after the refresh has run.
- AC-012: Each aggregate query completes in under 500 ms against production-scale data, evidenced by an EXPLAIN plan (deliverable, environment and data volume per OQ-005/OQ-009).
- AC-013: Given a monitoring aggregate query fails or times out, when the refresh runs, then the affected gauges continue to report their last successfully-computed value, a single WARN log entry is written, no exception escapes into the Spring scheduler, and a concurrently-running API request completes normally and unaffected.

**Scheduler success heartbeat (FR-007, FR-008)**
- AC-014: Given `IntradayDiscoveryScheduler.run()` completes without throwing, then `cdk_scheduler_last_success_epoch_seconds` for that scheduler is updated to the run's completion time (epoch seconds), and `cdk_scheduler_runs_total{outcome="success"}` for that scheduler is incremented by exactly 1.
- AC-015: The same holds for `NightlyDiscoveryScheduler.run()`, under its own distinct `scheduler` tag value.
- AC-016: The `scheduler` tag distinguishes the two schedulers unambiguously, and both appear as distinct series on `/actuator/prometheus` when both are enabled.

**Exception containment (FR-009)**
- AC-017: Given `DiscoveryService.runIntradayDiscovery()` throws, when `IntradayDiscoveryScheduler.run()` executes, then the exception is caught, logged at ERROR with the exception object and the scheduler name, `cdk_scheduler_runs_total{outcome="failure"}` is incremented by 1, the exception is **not** rethrown, and `cdk_scheduler_last_success_epoch_seconds` for that scheduler is left unchanged.
- AC-018: The equivalent holds for `DiscoveryService.runNightlyDiscovery()` and `NightlyDiscoveryScheduler.run()`.
- AC-019: The ERROR log line contains no case content, case id, document id or `CJSCPPUID`, and is emitted as structured JSON via the existing `logback-spring.xml` configuration.

**Disabled scheduler visibility (FR-010)**
- AC-020: Given `CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED` and `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED` are both unset (both therefore `false`), when the service starts, then `cdk_scheduler_enabled` reports `0` for both schedulers — **including** for the scheduler beans that `@ConditionalOnProperty` has not created.
- AC-021: Given either flag is set to `true`, when the service starts, then `cdk_scheduler_enabled` reports `1` for that scheduler and `0` for the other.
- AC-022: The enabled state of each scheduler is logged at INFO exactly once at startup, naming the scheduler and its state.

**No regression**
- AC-023: `gradle clean build` (including `integration`) passes; PMD and JaCoCo are green at existing, unmodified thresholds; CodeQL and the secrets scanner are clean.
- AC-024: `ActuatorHttpLiveTest`, `IntradayDiscoverySchedulerLiveTest` and `NightlyDiscoverySchedulerLiveTest` pass with their existing assertions unmodified; no existing metric name, actuator endpoint, cron expression, ShedLock lock name or lock duration changes.
- AC-025: The diff introduces no PII, case content, court reference number, or `CJSCPPUID` into code, config, tests or fixtures; any test data used to populate stall counts is synthetic.

---

## Candidate Sub-Stories (preview for Stage 3)

Indicative breakdown; each needs its own Jira sub-ticket before Test Specs, per the CLAUDE.md rule
that every story has a linked ticket. Areas A and B are independent and can be delivered in either
order — Area B is the smaller and lower-risk of the two.

1. **Story 1 — Scheduler heartbeat and exception containment.** Add `try`/`catch` + ERROR logging to both `run()` methods; register `cdk_scheduler_last_success_epoch_seconds` and `cdk_scheduler_runs_total`. Covers FR-007, FR-008, FR-009, AC-014 – AC-019.
2. **Story 2 — Scheduler enabled visibility.** Always-present component publishing `cdk_scheduler_enabled` and the startup INFO log, independent of the `@ConditionalOnProperty` gating; likely requires an `enabled` field on `SchedulerProperties` (OQ-007). Covers FR-010, AC-020 – AC-022.
3. **Story 3 — Stuck-work aggregate queries and repository methods.** New count methods on `CaseDocumentRepository` / `CaseQueryStatusRepository`; EXPLAIN-plan evidence; index migration `V1014` only if OQ-004 confirms it is needed. Covers FR-005, AC-006, AC-012, and the query half of AC-001 – AC-003, AC-007.
4. **Story 4 — Stuck-work gauge registration and ShedLock-guarded refresh.** Threshold property, ≥60 s cadence, own ShedLock lock, safe-degradation path. Covers FR-001, FR-002, FR-004, FR-006, AC-004, AC-005, AC-010 – AC-011, AC-013.
5. **Story 5 — Test coverage and quality gates.** Unit tests for both schedulers' success/failure paths and for the refresh component; `integrationTest` assertions that all new metric names appear on `/actuator/prometheus`; keep PMD/JaCoCo green. Covers NFR-006, AC-023 – AC-025.

Explicitly **not** a story here: alert rules, dashboards, stalled-work remediation, or any new API
endpoint.

---

## Open Questions

- **OQ-001 (source of truth):** Jira DD-43185 was not fetched in this session — no Jira/Atlassian MCP tool is available, so this document is grounded solely in the pasted text at `00-input-brief.md`, and **no summary comment has been posted to the epic**. Confirm the pasted brief is the complete and current ticket text (no later comments, no revised ACs) and post the Stage-1 summary manually. — Owner: requester · Due: before Stage 2.
- **OQ-002 (metric naming — Micrometer name vs Prometheus name):** the ticket names metrics in their **Prometheus-rendered** form (`cdk_documents_stalled`). Micrometer's convention is dot-separated meter names, which the Prometheus registry converts (`cdk.documents.stalled` → `cdk_documents_stalled`). Two consequences to settle explicitly: (a) which form is registered in code; (b) **`cdk_scheduler_runs_total`** — the Prometheus registry appends `_total` to counters automatically, so registering the meter as `cdk.scheduler.runs.total` would render as `cdk_scheduler_runs_total_total`; the meter must be registered as `cdk.scheduler.runs` to produce the name the ticket asks for. There are **no existing custom metrics in this codebase** to conflict with, so whatever is chosen here becomes the CDKS precedent and should be recorded in the ADR. Also confirm the `cdk_` prefix is what the platform's Prometheus scrape config and alert rules expect. — Owner: requester + platform/SRE · Due: Stage 2, ADR required.
- **OQ-003 (threshold property shape):** the ticket gives the property as `cdk.monitoring.stalled-threshold` with a default of 30 minutes, but not its **type** (ISO-8601 `Duration` e.g. `PT30M`, Spring `30m` shorthand, or a plain integer of minutes), nor its **env-var binding** — every other externally-tunable CDKS setting uses the `CP_CDK_*` convention in `application-cdk.yml` (e.g. `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_CRON`), and `cdk.monitoring.*` is a new property namespace for this repo. Also: does **one** threshold govern both FR-001 (documents) and FR-002 (queries), as the ticket's "the threshold" implies, or should they be independently tunable? — Owner: requester · Due: Stage 2.
- **OQ-004 (index support — may require migration `V1014`):** the ticket asserts each aggregate is "a single indexed aggregate" and that `case_query_status` is "indexed by `idx_cqs_status_at_desc`". Verified against `V1001`, that index is on **`(status_at DESC)` alone** — it does not include `status`, so a `status = 'ANSWER_NOT_AVAILABLE' AND status_at < ?` filter is not fully covered. Likewise `case_documents` has `idx_cd_phase (ingestion_phase)` and `idx_cd_case_phase (case_id, ingestion_phase)` but **nothing on `ingestion_phase_at`**. Meeting the 500 ms target at production scale may therefore need new composite or partial indexes — which would be a schema change the ticket does not authorise. Decide: accept the existing indexes, or add one append-only migration at `V1014` routed through `migration-reviewer`. — Owner: requester's design reviewers + DBA · Due: Stage 2, ADR required if a migration is added.
- **OQ-005 (phase set completeness):** the ticket lists `WAITING_FOR_UPLOAD`, `UPLOADING`, `INGESTING` as the stalled phases. `DocumentIngestionPhase` also contains **`UPLOADED`** (a real intermediate state — a document sitting at `UPLOADED` has been uploaded but not yet confirmed ingested, and can equally stall) and **`NOT_FOUND`**. Confirm `UPLOADED` is intentionally excluded rather than an oversight, and confirm `NOT_FOUND` is treated as terminal. — Owner: requester · Due: Stage 2.
- **OQ-007 (how to publish `cdk_scheduler_enabled` for a bean that does not exist):** both schedulers are `@ConditionalOnProperty(..., havingValue = "true", matchIfMissing = true)`, so when disabled **the bean is never created** — neither the gauge nor the startup INFO log can come from the scheduler class. Additionally, `SchedulerProperties.IntradayDiscovery` / `NightlyDiscovery` currently expose only `name`, `cron`, `lockAtLeastFor`, `lockAtMostFor` (plus `daysAhead`) — **there is no `enabled` field**, even though `application-cdk.yml` sets `scheduler.*.enabled`. Choose between adding `enabled` to `SchedulerProperties` (preferred — makes the flag a first-class bound property) versus reading `Environment` directly from a monitoring component. — Owner: requester's design reviewers · Due: Stage 2, ADR recommended.
- **OQ-008 (`scheduler` tag value convention):** "tagged by scheduler name" is ambiguous between the class name (`IntradayDiscoveryScheduler`), the configured ShedLock name (`intradayDiscoveryScheduler`, from `scheduler.intraday-discovery.name`), and a kebab-case key (`intraday-discovery`). The ShedLock name is configurable at runtime, which would make the tag value environment-dependent — probably undesirable for alert rules. Fix the convention and apply it identically to `cdk_scheduler_runs_total`, `cdk_scheduler_last_success_epoch_seconds` and `cdk_scheduler_enabled`. — Owner: requester + platform/SRE · Due: Stage 2.
- **OQ-009 (EXPLAIN-plan evidence — deliverable or manual step?):** the AC says "evidenced by an EXPLAIN plan attached to the ticket". Clarify whether this is (a) an artefact committed to this repo (e.g. a section of `02-design.md` or `deploy-notes.md`), or (b) a manual verification performed against a production-like environment and attached to Jira, outside the codebase. CDKS's compose-backed `integrationTest` stack holds only a handful of synthetic rows, so it **cannot** produce production-scale evidence. Also define "production-scale": expected row counts for `case_documents` and `case_query_status`, which environment the plan is captured against, and who runs it. Without a stated row count the 500 ms threshold is not independently verifiable. — Owner: requester + platform/DBA · Due: before Stage 4 (test specs must know whether this is an automatable assertion).
- **OQ-010 (heartbeat semantics across pods and restarts):** an in-memory `cdk_scheduler_last_success_epoch_seconds` has two behaviours the ticket does not address. (a) **Restart:** after a pod restart the gauge has no value until the next successful run — for `NightlyDiscoveryScheduler` that is up to 24 hours. Should it be absent, `0`, `NaN`, or seeded from persisted state? (b) **Multi-pod:** discovery runs are ShedLock-guarded, so on any given cron tick only **one** pod actually executes; the other pods' gauges stay stale indefinitely, and a naive `time() - cdk_scheduler_last_success_epoch_seconds > X` alert would fire continuously against them. Confirm the intended alert expression (e.g. `max()` across pods) or decide that the heartbeat must be persisted (e.g. to the DB) to be cluster-consistent — the latter would pull "persist scheduler heartbeat state" back into scope. The same question applies in weaker form to `cdk_scheduler_runs_total`, which resets to 0 on restart (normal and handled by `rate()`/`increase()`, but worth stating). — Owner: requester + platform/SRE · Due: Stage 2, ADR required.
- **OQ-011 (alerting ownership):** the ticket's stated goal is "find out about a stalled ingestion ... from an alert", but no alert thresholds, evaluation windows, severities or routing are specified, and Prometheus alert rules are not held in this repository. Confirm alert definition is out of scope for DD-43185 and identify the owning team and follow-up ticket — otherwise this ticket ships signals nobody is watching, which does not meet the story's stated intent. — Owner: requester + platform/SRE · Due: before Stage 3.
- **OQ-012 (metrics endpoint exposure):** `/actuator` is excluded from `cp-auth-rules-filter` (`application-other.yml`) and `/actuator/prometheus` is on the exposed endpoint list. The new gauges publish operational **volumes** (how many documents are stalled, how many queries lack answers) — counts only, no identifiers, so not PII, but still business-sensitive for an OFFICIAL-SENSITIVE service. Confirm with the security reviewer that the actuator port/path is not externally reachable and that publishing these counts there is acceptable. — Owner: security reviewer · Due: before merge.
- **OQ-013 (refresh enablement per environment):** should the stuck-work refresh have its own enable/disable flag (mirroring the discovery schedulers), and should it be **on** by default? Enabling it by default means it runs in local dev and in the `integrationTest` compose stack on every build — which is useful for AC-011 verification but adds a recurring query to every test run. Also confirm the default cadence: the ticket sets a floor ("no more often than every 60 seconds") but no target value. — Owner: requester · Due: Stage 2.
