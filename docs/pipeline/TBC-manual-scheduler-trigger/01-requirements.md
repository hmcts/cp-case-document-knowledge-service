# Requirements: Manual Discovery Scheduler Trigger Endpoint

> **Stage 1 — Requirements** · Service: `cp-case-document-knowledge-service` (CSDK)
> **Jira ticket: TBC** — no ticket raised yet (see OQ-011). Source brief: [`00-input-brief.md`](./00-input-brief.md).
> **Status: DRAFT — awaiting human gate approval.** Do not proceed to Architecture & Design
> until the open questions below are answered and this document is explicitly approved.

---

## Context

CSDK runs two ShedLock-guarded scheduled discovery operations — intraday (every 10 min, Mon–Fri
07:00–19:50) and nightly (daily 02:00) — that dispatch case-document ingestion work through the
Task Manager. Today the only way to make either run is to wait for its cron trigger. This
requirement adds a System-User-only REST endpoint that triggers one named discovery operation
(`INTRADAY` or `NIGHTLY`) on demand, reusing the existing operations unchanged.

Motivation stated in the brief is limited to "trigger scheduled jobs intraday / nightly" — the
underlying operational driver (recovery after a missed/failed scheduled run, environment
smoke-testing, support-led reprocessing) is **not** stated and is captured as OQ-001, because it
determines several design answers (concurrency policy, response contract, auditability).

---

## Actors

| Actor | Description |
|-------|-------------|
| System User | A CPP service/system account that is a member of the `"System Users"` group, identified by the `CJSCPPUID` request header. The **only** actor permitted to call this endpoint, per the brief. Mirrors the existing access model of `POST /discovery-scheduler/configurations`. |
| Support / Operations engineer (indirect) | A human who causes the trigger to be invoked, but only ever *via* a System User credential (e.g. a support script or internal tool). Has no direct identity at this endpoint. Whether a human-facing surface exists is OQ-009. |
| CSDK Discovery subsystem (internal, non-human) | `DiscoveryService` plus the Task Manager, Hearing API and RAG-ingestion flows it dispatches into. Consumes the trigger; unchanged by this requirement. |
| Existing cron schedulers (internal, non-human) | `IntradayDiscoveryScheduler` / `NightlyDiscoveryScheduler`. Continue to run on their own cron; they are the concurrency counterparty to a manual trigger (OQ-002). |

No end-user (caseworker, legal adviser, judge, defendant, legal rep) actor is in play — the brief
explicitly restricts access to System Users, and there is deliberately **no** `"AI search"`
permission fallback.

---

## Verified code baseline

Everything in this section was read from the working tree on branch `dev/dd-42958` and is stated as
fact. Requirements below are anchored to it; anything not listed here is an open question, not an
assumption.

| # | Verified fact | Evidence |
|---|---------------|----------|
| B-01 | The access-control model to copy is action `casedocumentknowledge-service.discovery-scheduler-configuration`, granted **only** by `"System Users"` group membership — there is no permission-based fallback clause, unlike the `queries` and `query-catalogue` rules which allow System Users **or** `PermissionConstants.accessToIntelligencePermissions()`. | `src/main/resources/acl/cdks-rules.drl:81-88` (contrast: `:16-24`, `:44-52`) |
| B-02 | Enforcement is **not** in Java code. It is a Drools rule evaluated by the external `cp-auth-rules-filter` (1.0.7). Action-name-to-URL resolution lives inside that library/contract, not in this repo. Adding an endpoint therefore needs a new `.drl` rule **and** a matching action-name mapping in the external filter contract. | `cdks-rules.drl` (whole file); `.claude/context/tech-stack.md` (Authorization); no in-repo URL mapping exists |
| B-03 | The endpoint to model the controller shape on is `POST /discovery-scheduler/configurations` → `DiscoverySchedulerController`, a `@Slf4j @RestController @RequiredArgsConstructor` that `implements DiscoverySchedulerApi` (generated interface from the external contract jar) and delegates straight to a service. | `src/main/java/uk/gov/hmcts/cp/cdk/controllers/DiscoverySchedulerController.java:15-31` |
| B-04 | `DiscoveryService.runIntradayDiscovery()` is `public void`, takes no arguments, and is currently invoked from exactly one place: `IntradayDiscoveryScheduler.run()`. It reads `scheduled_ingestion_request` rows for `LocalDate.now()` and dispatches `GET_CASES_FOR_HEARING` per row. | `services/DiscoveryService.java:73-87`; `scheduler/IntradayDiscoveryScheduler.java:36-41` |
| B-05 | `DiscoveryService.runNightlyDiscovery()` is `public void`, takes no arguments, and is currently invoked from exactly one place: `NightlyDiscoveryScheduler.run()`. It computes a hearing-date window (`days-ahead`, default 3), loads latest active `discovery_scheduler_configuration` rows, and — depending on the `cqrs.client.hearing.is-hearing-for-cases-enabled` flag — either calls the Hearing API **once per hearing date** and dispatches an IDPC-availability check per unique case, or dispatches `GET_CASES_FOR_HEARING` per (config × date). | `services/DiscoveryService.java:93-131`; `scheduler/NightlyDiscoveryScheduler.java:36-41` |
| B-06 | Both operations are **synchronous, unbounded-duration, `void`, and return no result object**. Both **swallow per-item dispatch exceptions** with `log.error(...)` and continue — neither method throws to its caller on a per-item failure. The only per-run signal today is the surrounding `log.info("… starting")` / `log.info("… finished")` pair in the scheduler. | `DiscoveryService.java:80-86`, `:122-128`, `:142-149`; both scheduler `run()` methods |
| B-07 | Both schedulers are ShedLock-guarded with distinct lock names and durations: `intradayDiscoveryScheduler` (`lockAtLeastFor PT8M`, `lockAtMostFor PT9M`) and `nightlyDiscoveryScheduler` (`lockAtLeastFor PT1H`, `lockAtMostFor PT2H`, all four values externalised via `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_*` env vars — commit 91cc310). The lock annotation sits on the **scheduler** method, not on the `DiscoveryService` method. | `IntradayDiscoveryScheduler.java:37-39`; `NightlyDiscoveryScheduler.java:37-39`; `src/main/resources/application-cdk.yml:56-69` |
| B-08 | ShedLock infrastructure already exists: `shedlock` table (`V1010`), `JdbcTemplateLockProvider` with `usingDbTime()`, `@EnableSchedulerLock(defaultLockAtMostFor = "PT30S", interceptMode = PROXY_METHOD)`, and a dedicated non-daemon `ThreadPoolTaskScheduler` (pool size 10) because virtual threads are enabled app-wide. | `config/ShedLockConfig.java`; `db/migration/V1010__create_shedlock_table.sql` |
| B-09 | Each scheduler is independently switchable off via `@ConditionalOnProperty` (`scheduler.intraday-discovery.enabled` / `scheduler.nightly-discovery.enabled`, `matchIfMissing = true`, wired to `CP_CDK_SCHEDULER_*_ENABLED`). `DiscoveryService` itself is an unconditional `@Service`, so it remains available even when a scheduler component is not registered. | both scheduler classes (class-level annotation); `application-cdk.yml:62,68`; `DiscoveryService.java:36` |
| B-10 | No `INTRADAY` / `NIGHTLY` enum, constant, or discriminator type exists anywhere in the codebase. This is a net-new type that must originate in the API contract. | Verified absent across `src/main/java` |
| B-11 | The OpenAPI contract is **not** generated in this repo. `api-cp-crime-caseadmin-case-document-knowledge` is consumed as a compiled external jar, currently `0.0.11`. A new operation must be added to that separate contract repo, released, and `version.cdk` bumped here **before** a controller can be written (contract-first, exactly how `DiscoverySchedulerApi` arrived). | `build.gradle:122`; `gradle.properties:2` (`version.cdk=0.0.11`) |
| B-12 | Error responses are centralised: `GlobalExceptionHandler` maps `MethodArgumentNotValidException` and `ConstraintViolationException` to **400** and `HttpMessageNotReadableException` to **400 "Malformed request body"**, each as an `ErrorResponse` carrying `error`, `message`, `timestamp` (UTC) and `traceId`. Any unhandled `Exception` maps to **500 "Unexpected error"**. An unparseable enum value in a request body surfaces as `HttpMessageNotReadableException` → 400. | `controllers/GlobalExceptionHandler.java:51-96` |
| B-13 | Audit events are published to Artemis by the external `cp-audit-filter-springboot` (1.0.5) filter; there are no `@JmsListener`s in service code. Structured JSON logging to stdout via `logback-spring.xml` + Logstash encoder is mandatory. | `.claude/context/csdk-context.md` (Stack, Hard rules); `.claude/context/tech-stack.md` (HMCTS internal libraries, Observability) |

---

## Functional requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| FR-001 | The service must expose a new state-changing REST endpoint, under the existing `/casedocumentknowledge-service` context path, whose sole purpose is to trigger one discovery operation on demand. `POST` is implied (state-changing, carries a request body). The exact path is **not specified by the brief** — see OQ-005. | Must | Brief line 1 |
| FR-002 | The request must carry a single caller-supplied discriminator naming the discovery operation to run, with exactly two permitted values: `INTRADAY` and `NIGHTLY`. This is a net-new enumerated type (B-10) and must be defined in the API contract, not hand-rolled in this repo (B-11). | Must | Brief line 3 |
| FR-003 | When the discriminator is `INTRADAY`, the service must perform the existing intraday discovery operation (`DiscoveryService.runIntradayDiscovery()`, B-04). | Must | Brief line 4 |
| FR-004 | When the discriminator is `NIGHTLY`, the service must perform the existing nightly discovery operation (`DiscoveryService.runNightlyDiscovery()`, B-05). | Must | Brief line 4 |
| FR-005 | The endpoint must reuse the existing discovery operations as-is. No discovery logic may be duplicated, forked, reimplemented, or behaviourally altered to serve the manual path; both operations remain single-sourced in `DiscoveryService`. | Must | Brief line 4 ("that specific discovery operation") + CSDK hard rules |
| FR-006 | Access must be restricted to members of the `"System Users"` group, enforced by a new Drools rule in `acl/cdks-rules.drl` that mirrors the `discovery-scheduler-configuration` rule (B-01): group membership only, **no** `PermissionConstants.accessToIntelligencePermissions()` fallback clause. | Must | Brief line 2 |
| FR-007 | A caller that is not a member of `"System Users"` — including a caller holding only the `"AI search"` permission — must be denied and no discovery operation may be started. The denial status code is owned by `cp-auth-rules-filter` (B-02) — see OQ-006. | Must | Brief line 2 (inverse) |
| FR-008 | A request whose discriminator is absent, empty, or not one of the two permitted values must be rejected with HTTP **400** and an `ErrorResponse` body (`error`, `message`, `timestamp`, `traceId`) produced by the existing `GlobalExceptionHandler` (B-12), and no discovery operation may be started. | Must | Implied by FR-002 + B-12 |
| FR-009 | The new operation must be added to the external `api-cp-crime-caseadmin-case-document-knowledge` contract repo and released, and `version.cdk` in `gradle.properties` bumped from `0.0.11`, before any controller is implemented here. The controller must `implement` the generated API interface, following the `DiscoverySchedulerController` pattern (B-03, B-11). | Must | B-11 (contract-first constraint) |
| FR-010 | The endpoint must be purely additive: the existing cron expressions, ShedLock lock names/durations, and `scheduler.*.enabled` switches (B-07, B-09) must remain unchanged, and both schedulers must continue to fire on their existing schedule after this change. | Must | "Add a new endpoint" (additive framing) |
| FR-011 | Each manual trigger must emit a structured JSON log record identifying which discovery operation was requested and the outcome of the attempt, correlated by trace/correlation id, containing **no** case data, document content, or `CJSCPPUID` value. | Must | CSDK hard rules (B-13) |

**Deliberately not stated as requirements** (no basis in the brief or the baseline — see Open questions):
whether the endpoint is synchronous or fire-and-forget (OQ-003); whether the response reports
per-item dispatch outcomes (OQ-004); concurrency behaviour against a live scheduled run (OQ-002);
any ability to scope, parameterise, cancel, or query the progress of a triggered run (Out of scope).

---

## Non-functional requirements

| ID | Category | Requirement | Threshold |
|----|----------|-------------|-----------|
| NFR-001 | Security — authorisation | System Users group membership only; no permission-based fallback path may exist for this action. Verified by an integration test asserting an `"AI search"`-only caller is denied. | Zero non-System-User access |
| NFR-002 | Security — logging | No PII, case content, document body, court reference number, or `CJSCPPUID` value in any log line, error message, or artefact emitted by this endpoint. | Zero tolerance |
| NFR-003 | Security — cloud identity | All downstream calls made by the triggered operation (Hearing API, Task Manager, RAG, Azure Blob) continue to authenticate via Managed Identity / APIM through `ApimAuthHeaderService`. No connection string, SAS token, or account key introduced anywhere. | Zero tolerance |
| NFR-004 | Concurrency / safety | A manual trigger must not corrupt state, double-dispatch ingestion work, or starve the scheduled run when it overlaps a concurrently executing cron run of the same operation, in a multi-pod deployment. Existing locks: `intradayDiscoveryScheduler` (PT8M/PT9M), `nightlyDiscoveryScheduler` (PT1H/PT2H) (B-07). **Required behaviour is undecided — OQ-002.** | Defined by OQ-002; no silent duplicate dispatch |
| NFR-005 | Performance / timeout resilience | The HTTP response must not depend on completion of an unbounded-duration operation. Nightly discovery calls the Hearing API once per hearing date (3 dates by default, 15 s read timeout each) and then dispatches N Task Manager jobs (B-05); a synchronous response risks exceeding APIM/ingress/client timeouts. | Response within the platform gateway timeout — exact value **TBD, OQ-007** |
| NFR-006 | Reliability | A per-item dispatch failure inside a triggered run must not abort the remainder of the run, matching today's continue-on-error behaviour (B-06). | Remaining items still dispatched |
| NFR-007 | Observability | Start and completion of a manually triggered run must be observable in structured JSON logs to stdout, distinguishable from a cron-initiated run of the same operation, and correlated via `CorrelationIdInterceptor` / OpenTelemetry trace id. | 100% of triggers traceable |
| NFR-008 | Auditability | The trigger must produce an audit trail sufficient to answer "who triggered which operation, when" via `cp-audit-filter-springboot` → Artemis (B-13). Whether the new action needs explicit registration in that filter's configuration is **OQ-008**. | Every trigger auditable |
| NFR-009 | Data protection | No new personal data is introduced. If any run-record is persisted (not currently proposed — OQ-010), it must be non-personal and covered by a retention position. | No new personal data |
| NFR-010 | Testability | Unit tests (`src/test/`) plus integration tests in `src/integrationTest/` covering authorised trigger, unauthorised caller, and invalid discriminator. `gradle integration` must pass against the Docker Compose stack — `build`/`check` depend on it. | `gradle clean build` green |
| NFR-011 | Code quality | PMD (`pmdMain`, `pmdTest`) and JaCoCo thresholds must pass unmodified; CodeQL and the secrets scanner must be clean. | No threshold lowered |
| NFR-012 | Platform conformance | Java 25 / Spring Boot 4.0.5 / Gradle 9; controller follows the `@Slf4j @RestController implements <Generated>Api` pattern (B-03); no hand-scaffolded build files, Dockerfile, or logback config. | No deviation without an ADR |
| NFR-013 | Accessibility | CSDK is backend-only with no UI, so WCAG 2.1 AA does not apply to this endpoint directly. It applies in full to any downstream user-facing surface built on it (OQ-009), which must then be assessed separately. | WCAG 2.1 AA for any consuming UI |

---

## Acceptance criteria

Format per `skills/write-acceptance-criteria` (Given/When/Then, one observable outcome per AC).
Where the exact observable value is genuinely undecided, the AC names the blocking open question
rather than inventing a value — those ACs cannot be finalised until the gate is cleared.

### FR-001 — New on-demand trigger endpoint
- **AC-001**: Given the service is running, when a System User sends a well-formed trigger request to the new endpoint, then the request is accepted and a discovery operation is initiated.
- **AC-002**: Given the service is running, when any caller sends a request to the new endpoint using an HTTP method other than the one defined in the contract, then the response is **405 Method Not Allowed** and no discovery operation is initiated.
- **AC-003**: Given the new endpoint exists, when the full API surface is inspected, then the new endpoint is reachable under the `/casedocumentknowledge-service` context path and every pre-existing endpoint listed in `csdk-context.md` still responds exactly as before.

### FR-002 — `INTRADAY` / `NIGHTLY` discriminator
- **AC-004**: Given the released API contract, when the request model for the new operation is inspected, then it defines a single enumerated discriminator field whose permitted values are exactly `INTRADAY` and `NIGHTLY` — no third value, and no free-text alternative.
- **AC-005**: Given a request body with the discriminator set to `INTRADAY`, when it is deserialised by the controller, then it binds to the contract-generated enum type (not a `String`) with no custom parsing code added in this repo.

### FR-003 — `INTRADAY` runs intraday discovery
- **AC-006**: Given a System User caller and `scheduled_ingestion_request` rows exist for today's date, when the caller triggers with `INTRADAY`, then a `GET_CASES_FOR_HEARING` Task Manager dispatch occurs for each of those rows, and no nightly-discovery behaviour (hearing-date-window calculation, active-configuration lookup, Hearing API call) occurs.
- **AC-007**: Given a System User caller and **no** `scheduled_ingestion_request` rows for today's date, when the caller triggers with `INTRADAY`, then zero dispatches occur, the request still succeeds, and no error is logged.

### FR-004 — `NIGHTLY` runs nightly discovery
- **AC-008**: Given a System User caller, at least one latest-active `discovery_scheduler_configuration` row, and `cqrs.client.hearing.is-hearing-for-cases-enabled=false`, when the caller triggers with `NIGHTLY`, then a `GET_CASES_FOR_HEARING` dispatch occurs for each (active configuration × hearing date in the `days-ahead` window), and no intraday-discovery behaviour occurs.
- **AC-009**: Given a System User caller and `cqrs.client.hearing.is-hearing-for-cases-enabled=true`, when the caller triggers with `NIGHTLY`, then the Hearing API is called once per hearing date in the window and exactly one IDPC-availability dispatch occurs per **unique** case id across all matched hearings (no duplicate dispatch for a case appearing on two dates).
- **AC-010**: Given a System User caller and **no** latest-active `discovery_scheduler_configuration` rows, when the caller triggers with `NIGHTLY`, then zero dispatches occur and the request still succeeds.

### FR-005 — Reuse of existing operations
- **AC-011**: Given the implemented change, when the call graph is inspected, then `DiscoveryService.runIntradayDiscovery()` and `runNightlyDiscovery()` each remain the single implementation of their operation, invoked by both the cron scheduler and the new endpoint, with no copied or parallel discovery logic anywhere in the codebase.
- **AC-012**: Given the pre-existing unit and integration test suites for intraday and nightly discovery, when they are run after this change, then all of them pass without modification to their expected behaviour.

### FR-006 — System Users only
- **AC-013**: Given a caller whose `CJSCPPUID` resolves to a member of the `"System Users"` group, when they call the new endpoint with a valid body, then the request is authorised and the operation runs.
- **AC-014**: Given the updated `acl/cdks-rules.drl`, when the new rule is inspected, then its `when` clause contains exactly one `eval(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups($a, "System Users"))` condition and **no** `hasPermission(...)` clause and **no** `or` operator — structurally identical to the `discovery-scheduler-configuration` rule at `cdks-rules.drl:81-88`.

### FR-007 — Unauthorised callers denied
- **AC-015**: Given a caller who holds the `"AI search"` permission but is **not** a member of `"System Users"`, when they call the new endpoint with an otherwise valid body, then the request is denied with the framework's denial status code (**exact code pending OQ-006**) and zero Task Manager dispatches occur.
- **AC-016**: Given a request with no `CJSCPPUID` header, when it is sent to the new endpoint, then it is denied and zero Task Manager dispatches occur.

### FR-008 — Invalid input rejected
- **AC-017**: Given a System User caller, when the request body omits the discriminator field, then the response is **400** with an `ErrorResponse` body containing non-null `error`, `message`, and `timestamp`, and zero Task Manager dispatches occur.
- **AC-018**: Given a System User caller, when the request body sets the discriminator to an unrecognised value such as `"WEEKLY"`, then the response is **400** and zero Task Manager dispatches occur.
- **AC-019**: Given a System User caller, when the request body is not valid JSON, then the response is **400** with message `"Malformed request body"`, and zero Task Manager dispatches occur.
- **AC-020**: Given any of AC-017 to AC-019, when the returned `ErrorResponse` is inspected, then its `message` contains no case identifier, court reference, document content, or `CJSCPPUID` value.

### FR-009 — Contract-first delivery
- **AC-021**: Given the implementation branch, when `gradle.properties` is inspected, then `version.cdk` references a released contract version **greater than `0.0.11`** that contains the new operation, and the new controller `implements` the generated API interface from that jar.
- **AC-022**: Given the new controller, when it is inspected, then it declares no Spring `@RequestMapping`/`@PostMapping` path of its own — the path comes solely from the generated interface, as with `DiscoverySchedulerController`.

### FR-010 — Purely additive
- **AC-023**: Given the change is deployed with default configuration, when a full cron cycle elapses without any manual trigger, then `IntradayDiscoveryScheduler` and `NightlyDiscoveryScheduler` fire on their existing cron expressions with their existing ShedLock lock names and durations, unchanged.
- **AC-024**: Given `scheduler.nightly-discovery.enabled=false`, when the service starts, then it starts successfully and the new endpoint's behaviour in that state is as decided by **OQ-004b** (endpoint availability when the corresponding scheduler is disabled — currently undefined).

### FR-011 — Logging and traceability
- **AC-025**: Given a System User triggers `NIGHTLY`, when the stdout logs are inspected, then exactly one structured JSON record marks the start of that manually triggered run and one marks its completion, both carrying the trace/correlation id of the HTTP request and identifying the operation as `NIGHTLY`.
- **AC-026**: Given a manually triggered run and a cron-initiated run of the same operation, when their log records are compared, then the manual run is distinguishable from the cron run by an explicit field or message, not by timestamp inference alone.
- **AC-027**: Given any manually triggered run, when every log record it produces is scanned, then none contains a `CJSCPPUID` value, case identifier, court reference number, document content, or answer text.

### NFR acceptance criteria
- **AC-028** (NFR-004): Given a scheduled run of the same operation is currently executing and holding its ShedLock lock, when a System User triggers the same operation manually, then the observable behaviour matches the policy agreed in **OQ-002**, and under no outcome is the same ingestion work dispatched twice.
- **AC-029** (NFR-005): Given a `NIGHTLY` trigger where each Hearing API call consumes its full 15 s read timeout, when the caller observes the HTTP exchange, then the response is returned within the platform gateway timeout (**value pending OQ-007**) and the caller never sees a gateway timeout.
- **AC-030** (NFR-006): Given a `NIGHTLY` trigger where the Task Manager dispatch for one court-centre configuration throws, when the run completes, then dispatches for all remaining configurations still occurred and the failure was recorded via `log.error` without aborting the run.
- **AC-031** (NFR-008): Given a System User triggers either operation, when the audit trail is inspected, then an audit event exists that identifies the action and its invocation time.
- **AC-032** (NFR-010/011): Given the completed implementation, when `gradle clean build` is run, then it passes including `integration`, with PMD and JaCoCo green at their existing unmodified thresholds.
- **AC-033** (NFR-003): Given the completed implementation, when the diff is scanned, then it introduces no connection string, SAS token, account key, or other static credential in code, config, env vars, or compose files.

---

## Constraints

**Legislative / policy**
- Data Protection Act 2018 and UK GDPR — data minimisation and purpose limitation apply to anything logged or persisted by this endpoint.
- Data classified **OFFICIAL-SENSITIVE**; MOJ security policy applies. No PII, case data, or court reference numbers in artefacts, prompts, logs, or test fixtures — synthetic data only.
- GDS Service Manual and HMCTS engineering standards apply to the delivery process; WCAG 2.1 AA applies to any user-facing consumer of this endpoint (NFR-013).

**Platform / architectural**
- **Contract-first, external contract repo**: the OpenAPI spec is not in this repo. Adding an endpoint is a cross-repo change — new operation in `api-cp-crime-caseadmin-case-document-knowledge`, release, then `version.cdk` bump here (B-11). This gates the earliest possible implementation date.
- **Split access-control surface**: the Drools rule lives here (`acl/cdks-rules.drl`) but action-name-to-URL resolution lives inside `cp-auth-rules-filter` 1.0.7 (B-02). Both sides must be aligned or the endpoint will be either unreachable or unprotected. Route the ACL change through `rbac-auditor`.
- **Multi-instance deployment**: ShedLock exists precisely because more than one pod runs (B-08); no single-instance assumption may be made about locking or in-memory state.
- **Flyway is append-only**: next available version is `V1012`. Any persistence added for this feature is a new migration, never an edit to a shipped `V*.sql`, and must go through `migration-reviewer`.
- **Azure via Managed Identity only**; all APIM calls keep the `RestClientFactoryConfig` → `CorrelationIdInterceptor` → `ApimAuthHeaderService` chain.
- **JSON logging to stdout only** via `logback-spring.xml`; no `System.out`.
- **Integration tests are part of `build`/`check`** — not optional.
- **Existing external timeouts are fixed inputs**: Hearing API 15 s read, RAG 180 s read (`tech-stack.md`). These bound how slow a synchronous nightly trigger can be.
- Fixed stack: Java 25, Spring Boot 4.0.5, Gradle 9, PostgreSQL 16, base package `uk.gov.hmcts.cp.cdk`, context path `/casedocumentknowledge-service`, port 8082.
- **RAG response fidelity**: this change touches the discovery/dispatch entry point, not the RAG response path, so no RAG field mapping changes are expected. The hard rule stands regardless — no change may drop or transform RAG response fields such as `doc_id` or `llm_input`.

**Process**
- Human gate after this stage. No progression to Architecture & Design without explicit approval.
- Every story needs a linked Jira ticket before the Test Specs stage (OQ-011).

---

## Out of scope

Explicitly excluded — none of these are stated or implied by the brief. Any of them becoming in
scope invalidates this document and requires a re-run of this stage.

- **Changing what intraday or nightly discovery does.** Both operations are reused verbatim (FR-005).
- **Parameterising the triggered run** — no court-centre id, court-room id, case id, hearing date, date window, or `days-ahead` override. The only input is the operation name.
- **A third operation type** beyond `INTRADAY` and `NIGHTLY`.
- **Cancelling, pausing, resuming, or aborting** a run — cron-initiated or manually triggered.
- **Enabling or disabling the cron schedulers at runtime**, or editing cron expressions via API. Those remain env-var/config-driven (B-09).
- **A run-status / run-history / progress query endpoint**, and any UI to drive the trigger (OQ-009).
- **New persistence** — no new table or Flyway migration is proposed (OQ-010).
- **Rate limiting, quota, or throttling** of manual triggers (raised as OQ-004a, not assumed).
- **Retry / dead-letter handling** for failed dispatches beyond today's log-and-continue (B-06).
- **Changes to the RAG contract, ingestion flow internals, answer serving, or `api-cp-ai-rag`.**
- **Changes to any existing endpoint**, its ACL rule, or its response contract.
- **Notification/alerting** on trigger success or failure (email, Slack, PagerDuty).

---

## Open questions

All must be resolved before the Architecture & Design stage. Nothing here has been silently
assumed — where a design choice was needed to write an AC, the AC defers to the question.

| ID | Question | Why it blocks | Owner | Due |
|----|----------|---------------|-------|-----|
| **OQ-001** | What is the operational driver for manual triggering — recovery from a missed/failed scheduled run, non-prod smoke testing, support-led reprocessing, or something else? Which environments must it be enabled in (prod included)? | Determines the concurrency policy (OQ-002), the response contract (OQ-003), and whether this is a production or lower-environment capability at all. | TBC | TBC |
| **OQ-002** | **Concurrency / locking:** what must happen when a manual trigger arrives while a *scheduled* run of the same operation is executing and holding its ShedLock lock (`intradayDiscoveryScheduler` PT8M/PT9M; `nightlyDiscoveryScheduler` PT1H/PT2H)? Options: (a) reject with `409 Conflict`; (b) queue/serialise; (c) run concurrently, unlocked; (d) share the existing lock so the manual run is skipped silently. Note the `@SchedulerLock` annotation currently sits on the *scheduler* method, not on `DiscoveryService` (B-07), so a manual path calling `DiscoveryService` directly would bypass the lock entirely today. Also: what happens when two manual triggers for the same operation arrive simultaneously on different pods? A nightly lock held for up to PT2H means option (a) could block manual triggering for hours. | Duplicate dispatch of ingestion work is a real risk; drives the entire design and NFR-004/AC-028. **Do not resolve here — Architecture & Design decides, with an ADR.** | TBC | TBC |
| **OQ-003** | **Synchronous vs fire-and-forget:** must the endpoint block until the discovery operation completes (returning e.g. `200 OK`), or accept and return immediately (e.g. `202 Accepted`) while the run proceeds on a separate thread? Nightly is unbounded (Hearing API per date at 15 s read timeout + N Task Manager dispatches, B-05/B-06), so a synchronous contract risks gateway timeouts (NFR-005). Note the precedent split already in CSDK: `/ingestions/start` is fire-and-forget `202`, `/ingestions/start-by-case` is synchronous `200`. Which precedent applies here? | Fixes the success status code and response body; AC-001 and AC-029 cannot be finalised without it. | TBC | TBC |
| **OQ-004** | **Response granularity:** should the response report per-item dispatch outcomes (e.g. counts of items found / dispatched / failed, or a per-item list), or accept-and-log only, exactly as the schedulers do today? Both `DiscoveryService` methods are `void`, swallow per-item exceptions with `log.error`, and return no result object (B-06) — per-item reporting would require changing those method signatures, which touches the cron path too and conflicts with the "reuse unchanged" intent of FR-005. | Determines whether `DiscoveryService` must change at all, and therefore the blast radius of this change onto the existing scheduled flows. | TBC | TBC |
| **OQ-004a** | Is any rate limiting, throttling, or minimum interval between manual triggers required (e.g. to stop a script hammering `NIGHTLY` and flooding the Task Manager)? Currently listed out of scope. | Affects design and possibly a new NFR. | TBC | TBC |
| **OQ-004b** | Should the endpoint still trigger an operation whose cron scheduler is disabled via `scheduler.*.enabled=false` (B-09)? `DiscoveryService` remains available regardless, so the manual path would work by default — is that intended, or must the endpoint respect the same switch? | AC-024 is unfinalised; also a safety question (a scheduler may be disabled deliberately in an environment). | TBC | TBC |
| **OQ-005** | What is the endpoint path and request shape in the contract? (Not specified by the brief; must be agreed with the owner of the `api-cp-crime-caseadmin-case-document-knowledge` repo.) Should it sit under the existing `/discovery-scheduler/...` namespace for consistency with the endpoint whose ACL it copies? | FR-001/FR-009 cannot be implemented until the contract change is agreed, released, and `version.cdk` bumped. This is a cross-repo lead-time item — raise early. | TBC | TBC |
| **OQ-006** | What HTTP status does `cp-auth-rules-filter` 1.0.7 return for a denied action — `401`, `403`, or something else — and what body? Enforcement is external to this repo (B-02). | AC-015/AC-016 need a concrete expected status code to be automatable. Confirm against the filter's behaviour or an existing integration test for `/discovery-scheduler/configurations`. | TBC | TBC |
| **OQ-007** | What is the actual request timeout on the ingress/APIM path in front of CSDK for this endpoint? | Sets the concrete threshold for NFR-005/AC-029 and, combined with OQ-003, decides whether a synchronous contract is viable at all. | TBC | TBC |
| **OQ-008** | Does the new action need explicit registration or configuration in `cp-audit-filter-springboot` (1.0.5) for an audit event to be published, or is it picked up automatically from the request path? | NFR-008/AC-031 depend on this; a silent gap would mean no audit trail for a privileged operational action. | TBC | TBC |
| **OQ-009** | Is there any user-facing surface (support console, internal admin UI) intended to call this endpoint, now or later? If so, WCAG 2.1 AA applies to that surface and it needs its own assessment (NFR-013). | Determines whether an accessibility workstream and a human-facing actor exist. Currently assumed system-to-system only. | TBC | TBC |
| **OQ-010** | Must a record of each manual trigger be persisted (who, what, when, outcome) for operational or audit purposes, beyond logs and Artemis audit events? If yes, that is a new Flyway migration (next version `V1012`) and needs a data-retention position (NFR-009). | Adds scope: migration + `migration-reviewer` review + retention decision. Currently out of scope. | TBC | TBC |
| **OQ-011** | **No Jira ticket exists for this work.** One must be raised and linked before the Test Specs stage (CLAUDE.md hard rule), and the `docs/pipeline/TBC-manual-scheduler-trigger/` folder renamed to `<JIRA-TICKET>-manual-scheduler-trigger`. Is there a parent epic to attach it to? | Hard-rule blocker for stage 4; also blocks the artefact-folder naming convention. No summary comment could be posted to a Jira epic for this stage because no epic is linked. | TBC | TBC |
| **OQ-012** | Should the two operations be independently permissioned (e.g. `NIGHTLY` restricted further than `INTRADAY`, given nightly is far heavier), or is one `"System Users"` rule covering both correct as the brief states? | Affects the ACL design; the brief implies a single rule, so that is what FR-006 states — confirming avoids a rework of the `.drl`. | TBC | TBC |

---

## Traceability

| FR | ACs | Related NFRs |
|----|-----|--------------|
| FR-001 | AC-001, AC-002, AC-003 | NFR-012 |
| FR-002 | AC-004, AC-005 | NFR-012 |
| FR-003 | AC-006, AC-007 | NFR-006 |
| FR-004 | AC-008, AC-009, AC-010 | NFR-005, NFR-006 |
| FR-005 | AC-011, AC-012 | NFR-010 |
| FR-006 | AC-013, AC-014 | NFR-001 |
| FR-007 | AC-015, AC-016 | NFR-001 |
| FR-008 | AC-017, AC-018, AC-019, AC-020 | NFR-002 |
| FR-009 | AC-021, AC-022 | NFR-012 |
| FR-010 | AC-023, AC-024 | NFR-004 |
| FR-011 | AC-025, AC-026, AC-027 | NFR-002, NFR-007 |
| — | AC-028 – AC-033 | NFR-003 – NFR-011 |

---

## Gate

**Human gate — partially cleared.** The four design-blocking questions have been answered by the
requester and are recorded below. Stage 2 (Architecture & Design) may proceed on this basis. All
other open questions (OQ-001, OQ-004a, OQ-004b, OQ-006–OQ-012) remain **unresolved** and are
carried forward into `02-design.md` as explicit open items / working assumptions — they are not
silently closed.

### Decisions recorded (this gate)

| Question | Decision | Recorded as |
|----------|----------|-------------|
| OQ-002 — concurrency vs. a live scheduled run | **Run concurrently, unlocked.** The manual trigger does **not** acquire or check the existing `intradayDiscoveryScheduler` / `nightlyDiscoveryScheduler` ShedLock. It calls `DiscoveryService` directly, same as today's bypass risk described in B-07/OQ-002. Explicitly accepted as a known risk (possible duplicate dispatch if a manual trigger overlaps a live cron run) rather than mitigated. | ADR-001 in `adrs.md` |
| OQ-003 — synchronous vs fire-and-forget | **Fire-and-forget, `202 Accepted`.** Matches the `/ingestions/start` precedent (B- reference to existing split). No blocking on discovery completion. | ADR-002 in `adrs.md` |
| OQ-004 — response granularity | **Accept-and-log only.** No per-item outcome in the response body. `DiscoveryService.runIntradayDiscovery()` / `runNightlyDiscovery()` signatures are **not** changed — FR-005 (reuse as-is) is preserved in full, cron path blast radius is zero. | Folded into design, no separate ADR needed |
| OQ-005 — endpoint path/namespace | **`/discovery-scheduler/trigger`**, alongside `/discovery-scheduler/configurations`, same controller package and ACL namespace. | Folded into design, no separate ADR needed |

NFR-004/AC-028 are updated by this decision: "no silent duplicate dispatch" is **no longer
guaranteed** by design — it is a documented, accepted risk. AC-024/OQ-004b (endpoint behaviour when
the corresponding scheduler is disabled) remains open and defaults, in the absence of a decision,
to "endpoint still works" (consistent with B-09: `DiscoveryService` is unconditionally available) —
flagged in the design doc as a default, not a confirmed requirement.
