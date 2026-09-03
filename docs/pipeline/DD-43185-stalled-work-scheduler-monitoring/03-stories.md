# User Stories: Stalled-Work Gauges and Scheduler Heartbeat Observability

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43185.** Real sub-tickets `DD-43218`–`DD-43222` created and linked to the
> parent epic on 2026-08-27, satisfying CLAUDE.md's hard rule "every story needs a linked Jira
> ticket before the test stage."
>
> Acceptance criteria below are **derived from, not duplicated verbatim from**,
> [`01-requirements.md`](./01-requirements.md)'s acceptance criteria, rescoped to each story's slice and
> updated where the Stage-2 gate changed FR-001's scope (ADR-004: `UPLOADED` in) or added a series
> (ADR-008: `cdk_monitoring_last_refresh_epoch_seconds`). Full ADR text and rationale live in
> [`../adrs/DD-43185-stalled-work-scheduler-monitoring.md`](../adrs/DD-43185-stalled-work-scheduler-monitoring.md)
> and are not reopened here — no story below raises a new ADR.
>
> **Note — `document_verification_task` excluded.** This ticket covers two stuck-work gauges, not
> three: `document_verification_task` is a dead table — a Spring Batch-era leftover, superseded by
> the JobManager framework, with no writer at all, confirmed both by static analysis and by a live
> query against the running database. No metric is built for a dead table; cleaning up the table
> itself is a separate future ticket, not part of any story here. Total added metric series: **14**.
>
> **Five stories, matching `01-requirements.md`'s candidate breakdown**, reconciled against the
> accepted design in `02-design.md`. Areas A (stuck-work gauges) and B (scheduler heartbeat) remain
> independent of each other and can ship in either order, as `02-design.md` §1 states — but **within**
> each area the stories are not mutually independent, because both areas converge on one shared
> class, `metrics/CdkMeters` (the meter-name/tag constants from ADR-001), and Area B additionally
> converges on one shared component, `metrics/SchedulerMetrics` (ADR-006/ADR-007). Where a story
> extends rather than creates that shared infrastructure, it is called out explicitly below and in
> the Summary table.

**Standard DoD (every story, per `hmcts-standards.md` and this repo's CLAUDE.md hard rules)**: code
reviewed & approved · all ACs covered by automated tests (unit + integration, Given/When/Then) ·
`gradle clean build` (incl. `integration`) passes · PMD/JaCoCo green at existing thresholds ·
CodeQL and secrets-scanner clean · no PII/case content/court reference/`CJSCPPUID` in code, config,
tests or fixtures · deployed to and verified on sandbox · Jira ticket updated with test evidence ·
`claude-generated` + `needs-review` labels applied, linked to parent epic DD-43185.

---

## Story 1 — Scheduler run-outcome and heartbeat instrumentation
**Jira: `DD-43218`**

As a **production support engineer**,
I want **`IntradayDiscoveryScheduler` and `NightlyDiscoveryScheduler` to record whether each run
succeeded or failed, and the timestamp of the last successful run, without relying on the presence
or absence of a log line**,
so that **I can tell from `/actuator/prometheus` whether a scheduler is running and succeeding, and
build an alert on it, instead of discovering a silent failure only when a hearing has an empty AI
Search result**.

### Background
Today both `run()` methods are three lines with no `try`/`catch`; an exception from
`DiscoveryService` propagates into Spring's `TaskScheduler`, which logs it via `ErrorHandler` and
swallows it, leaving no in-service record. This story closes that gap and creates the shared
`metrics/CdkMeters` constants class and the always-present `metrics/SchedulerMetrics` component that
Story 2 extends.

### Acceptance criteria
- [ ] AC-001: Given `IntradayDiscoveryScheduler.run()` (or `NightlyDiscoveryScheduler.run()`) completes without throwing, when the run finishes, then `cdk_scheduler_last_success_epoch_seconds` for that scheduler's `scheduler` tag (`intraday-discovery` / `nightly-discovery`, per ADR-006 — fixed constants, not the ShedLock lock name or the class name) updates to the completion time in epoch seconds, and `cdk_scheduler_runs_total{outcome="success"}` for that scheduler increments by exactly 1.
- [ ] AC-002: Given `DiscoveryService.runIntradayDiscovery()` (or `runNightlyDiscovery()`) throws, when `run()` executes, then the exception is caught, logged at **ERROR** with the exception object (full stack trace) and the scheduler name, `cdk_scheduler_runs_total{outcome="failure"}` increments by exactly 1, the exception is **not** rethrown into the Spring `TaskScheduler`, and `cdk_scheduler_last_success_epoch_seconds` for that scheduler is left unchanged.
- [ ] AC-003: The increment happens exactly once per invocation of `run()` regardless of outcome — implemented via a single `finally` block driven by a `boolean success` flag, not split across `try`/`catch` (design §5), so there is no code path that double-counts or under-counts.
- [ ] AC-004: The ERROR log line from AC-002 contains no case content, case id, document id or `CJSCPPUID`, and is emitted as structured JSON through the existing `logback-spring.xml` (`LogstashEncoder` → `ASYNC_JSON` → stdout) — no `System.out`, no new appender.
- [ ] AC-005: Given the pod has just started and neither scheduler has run yet, when `SchedulerMetrics` is constructed, then all four `cdk_scheduler_runs_total` series (2 schedulers × 2 outcomes) and both `cdk_scheduler_last_success_epoch_seconds` series already exist at value `0` — so `increase(cdk_scheduler_runs_total[...]) == 0` has a series to evaluate against, not "no data" (design §6, the pre-registration rule).
- [ ] AC-006: `catch (Exception e)` is used, not `catch (Throwable e)` — an `Error` (e.g. `OutOfMemoryError`) still propagates rather than being swallowed behind a metric.
- [ ] AC-007: Existing `IntradayDiscoverySchedulerTest`, `NightlyDiscoverySchedulerTest`, `IntradayDiscoverySchedulerLiveTest` and `NightlyDiscoverySchedulerLiveTest` continue to pass; the two unit tests' construction sites gain the new `SchedulerMetrics` dependency (a compile-level edit) with no change to their existing assertions, and the two live tests need no change at all. Cron expressions, ShedLock lock names, `lockAtLeastFor`/`lockAtMostFor`, `daysAhead` and the `@ConditionalOnProperty` defaults are untouched.

### NFR links
- NFR-001 (Data protection): the `scheduler` and `outcome` tags are fixed, closed sets — no case, document, defendant or court identifier in any tag or log line.
- NFR-002 (Cardinality): this story adds 6 of the ticket's **14** total series (4 `cdk_scheduler_runs_total` + 2 `cdk_scheduler_last_success_epoch_seconds`), all fixed and bounded.
- NFR-004 (Availability): `recordRun(...)` is a map lookup only — no registration on the hot path — so it cannot realistically throw; a failing metric write must not fail a discovery run.
- NFR-006 (Testability): unit coverage for both schedulers' success/failure paths, including the exception-containment path, plus a `SchedulerMetricsTest` against a `SimpleMeterRegistry`.

### Out of scope for this story
- `cdk_scheduler_enabled` and the startup INFO/WARN visibility log — Story 2.
- Instrumenting `DiscoveryTriggerService`'s manual `/discovery-scheduler` trigger path — explicitly not done, per ADR-007 (a manual run must not mask a dead scheduler).
- Any change to cron expressions, ShedLock lock names/durations, `daysAhead`, or `DiscoveryService` logic.
- Alert rules, dashboards, on-call routing (OQ-011 — owned outside this repository).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: both schedulers' success/failure paths + `SchedulerMetricsTest`; integration: heartbeat and run-outcome series visible on `/actuator/prometheus` in the compose stack).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **Creates `metrics/CdkMeters`** (ADR-001 — lowercase dot-separated meter names, tag-key/tag-value constants, private-constructor final class) and **`metrics/SchedulerMetrics`** (ADR-006/ADR-007 — always-present, no `@ConditionalOnProperty`, depends only on `MeterRegistry` and `SchedulerProperties`). This is shared infrastructure: **Story 2 extends both classes rather than creating them**, and **Story 4 (Area A) either extends `CdkMeters` if this story ships first, or creates it if Area A ships first** — see the Summary table for the explicit either-order rule.
- Requires **no** binding of `SchedulerProperties.*.enabled` — that field is added in Story 2, which is the first story that needs it.
- Jira sub-ticket: `DD-43218`.

---

## Story 2 — Scheduler enabled/disabled visibility
**Jira: `DD-43219`**
**Depends on Story 1.**

As a **production support engineer**,
I want **`cdk_scheduler_enabled` to report `1` or `0` per scheduler — including for a scheduler
whose bean was never created because its flag is off — plus a one-time startup log of each
scheduler's configured state**,
so that **I can immediately see, without SSH-ing into a pod or reading startup logs, whether a
scheduler flag was accidentally left off after a release**.

### Background
Both scheduler beans carry `@ConditionalOnProperty(havingValue = "true", matchIfMissing = true)`;
when disabled, the bean does not exist at all, so neither the gauge nor the startup log can be
emitted from the scheduler class itself (OQ-007 → ADR-006). `SchedulerProperties.IntradayDiscovery`
/ `NightlyDiscovery` currently expose `name`, `cron`, `lockAtLeastFor`, `lockAtMostFor` and
`daysAhead` but **not** `enabled`, even though `application-cdk.yml` sets
`scheduler.*.enabled` — the property is read by the conditional but never bound to a
`@ConfigurationProperties` class. This story fixes that gap and extends the `SchedulerMetrics`
component Story 1 created.

### Acceptance criteria
- [ ] AC-001: Given `CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED` and `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED` are both unset (both therefore `false`), when the service starts, then `cdk_scheduler_enabled` reports `0` for both scheduler tags — **including** the scheduler whose bean `@ConditionalOnProperty` did not create.
- [ ] AC-002: Given either flag is `true`, when the service starts, then `cdk_scheduler_enabled` reports `1` for that scheduler and `0` for the other.
- [ ] AC-003: The enabled state of each scheduler is logged at **INFO** exactly once at startup (via `@EventListener(ApplicationReadyEvent.class)`, not the constructor), naming the scheduler and its state.
- [ ] AC-004: Given the bound `enabled` flag disagrees with whether the corresponding scheduler bean actually exists (checked via a lazy `ObjectProvider`, resolved after every `@ConditionalOnProperty` has been evaluated), when the startup listener runs, then a WARN is logged naming the mismatch; startup is not blocked and no exception is thrown.
- [ ] AC-005: `scheduler.intraday-discovery.enabled` and `scheduler.nightly-discovery.enabled` are bound as first-class fields on `SchedulerProperties` (Java default `true`, mirroring `matchIfMissing = true`); `application-cdk.yml` continues to always supply an explicit value, so the effective shipped default (`false`) is unchanged.
- [ ] AC-006: The `scheduler` tag value on `cdk_scheduler_enabled` uses the same fixed constants (`intraday-discovery` / `nightly-discovery`) already used by `cdk_scheduler_runs_total` and `cdk_scheduler_last_success_epoch_seconds` from Story 1 — verified by a test that all three meter families agree on tag value for the same scheduler.

### NFR links
- NFR-001 (Data protection): tag values are the same fixed two-value set as Story 1; the startup log names only the scheduler and its boolean state.
- NFR-002 (Cardinality): adds 2 of the ticket's **14** total series (`cdk_scheduler_enabled` × 2 schedulers).
- NFR-004 (Availability): the gauge is fixed at startup from configuration already validated by Spring Boot property binding; the drift-check WARN never fails startup.

### Out of scope for this story
- Run-outcome and heartbeat metrics — already shipped by Story 1.
- Changing the `@ConditionalOnProperty` gating, the conditional's `matchIfMissing` value, or either flag's shipped default.
- Alert rules — the `cdk_scheduler_enabled == 1` join that suppresses false alerts on the deliberately-disabled schedulers is documented for the OQ-011 owner in `02-design.md` §11, not built here.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `SchedulerMetricsTest` extended for the enabled gauge and the drift cross-check; a `SchedulerProperties` binding test for the new `enabled` field; integration: `cdk_scheduler_enabled` visible and correct on `/actuator/prometheus` for both the default-disabled and an enabled-override compose configuration).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **Extends** `metrics/CdkMeters` (adds the `SCHEDULER_ENABLED` meter-name constant) and `metrics/SchedulerMetrics` (adds the third meter family plus the `ApplicationReadyEvent` listener) — both created by Story 1. This story cannot start meaningfully before Story 1 lands, because it edits the same constructor loop and the same constants class.
- Jira sub-ticket: `DD-43219`.

---

## Story 3 — Stuck-work aggregate queries, projections and supporting indexes
**Jira: `DD-43220`**
**No dependency on Stories 1–2 (Area B). Independent starting point for Area A.**

As a **CDKS developer**,
I want **indexed, single-round-trip aggregate queries over `case_documents` and `case_query_status`,
backed by a supporting Flyway migration**,
so that **Story 4's stuck-work gauges can be computed cheaply and safely on a schedule, without a
sequential scan and without ever selecting document content, blob URIs or answer text**.

### Background
`FR-005` requires each aggregate to be a single indexed query. Verified against `V1001`/`V1003`:
`case_documents` has no index on `ingestion_phase_at`; `case_query_status`'s only relevant index
(`idx_cqs_status_at_desc`) omits `status`. ADR-003 (accepted) adds one migration, `V1014`, with two
indexes. ADR-004 (accepted) widens the stalled-document phase set to include `UPLOADED` — the one
reachable intermediate phase that can strand a document indefinitely — alongside
`WAITING_FOR_UPLOAD`, `UPLOADING` and `INGESTING`. `DocumentVerificationTaskRepository.countByStatus(...)`
remains untouched, exactly as before — it already had no call sites and no metric is built on it
(see the note above).

### Acceptance criteria
- [ ] AC-001: Given `case_documents` rows across `WAITING_FOR_UPLOAD`, `UPLOADING`, `UPLOADED` and `INGESTING` (the ADR-004 phase set — **not** the ticket's original three), when `CaseDocumentRepository.countStalledByPhase(cutoff)` runs, then it returns one row per phase present, counting only rows whose `ingestion_phase_at` is older than the given cutoff, selecting only the `ingestion_phase` and `ingestion_phase_at` columns — no document content, name, blob URI or answer text.
- [ ] AC-002: Given `case_documents` rows in `INGESTED`, `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT` or `NOT_FOUND`, or rows in the monitored phases newer than the cutoff, when the same query runs, then those rows are excluded from every returned count.
- [ ] AC-003: Given `case_query_status` rows with `status = ANSWER_NOT_AVAILABLE` and `status_at` older than a cutoff, when `CaseQueryStatusRepository.countAwaitingAnswerOlderThan(cutoff)` runs, then it returns exactly that count; the status is spelled as a **literal** in the native query (never a bound parameter), because the `V1014` partial index only applies when PostgreSQL can trivially prove the predicate.
- [ ] AC-005: `V1014__add_stalled_work_monitoring_indexes.sql` adds exactly two indexes — `idx_cd_phase_phase_at (ingestion_phase, ingestion_phase_at)` composite on `case_documents`, and `idx_cqs_awaiting_answer_at (status_at) WHERE status = 'ANSWER_NOT_AVAILABLE'` partial on `case_query_status` — with no table, column, constraint, view, enum or existing index touched; Flyway migrates cleanly on a fresh DB and on a DB already at `V1013`.
- [ ] AC-006: Each of the two new query methods carries a 5-second JDBC statement timeout (`jakarta.persistence.query.timeout`); the two aggregates share no transaction with one another (so one failing aggregate cannot roll back the other — this is the property Story 4's per-aggregate degradation depends on).
- [ ] AC-007: A Testcontainers-backed `StalledWorkQueryPlanTest` (Flyway-migrated, so `V1014`'s indexes genuinely exist), seeded with a documented synthetic volume (order 100k rows, all values synthetic — `gen_random_uuid()`, no real identifiers), asserts: the `case_documents` aggregate's `EXPLAIN` plan uses `idx_cd_phase_phase_at`; the `case_query_status` aggregate's plan uses `idx_cqs_awaiting_answer_at`; and no plan contains a `Seq Scan` on the target table. A loose `Actual Total Time` bound (e.g. 500 ms) is asserted as a CI-hardware smoke check only — **not** as production-scale evidence (see the pre-merge gate note below).

### NFR links
- NFR-001 (Data protection): no query in this story selects, joins to, or scans document content, answer text, `llm_input` or blob payloads.
- NFR-005 (Backward compatibility): `V1014` is purely additive and append-only; no shipped migration (`V1000`–`V1013`) is edited; `countByStatus` and every existing repository signature are unchanged.
- NFR-006 (Testability): Testcontainers-backed repository correctness tests (cutoff inclusion/exclusion, terminal-phase exclusion) plus the `StalledWorkQueryPlanTest` above.

### Out of scope for this story
- Gauge registration, Micrometer meter/tag wiring, the scheduled refresh, ShedLock guarding, or the threshold/cadence configuration — all Story 4.
- Populating the currently-dead `UPLOADING` and `INGESTING` phases in production write paths — a separate ingestion-behaviour change, explicitly out of scope for this observability-only ticket (recorded as a follow-up in `02-design.md` §13).
- Dropping the now-redundant `idx_cd_phase` (a strict prefix of the new composite) — recorded as a follow-up, needs its own before/after evidence.

### Pre-merge gate note (does not block starting this story)
**Resolved — 2026-09-01.** `V1014` uses plain `CREATE INDEX` (not `CONCURRENTLY` — Flyway wraps
migrations in a transaction, and `CONCURRENTLY` cannot run inside one), which takes a `SHARE` lock
blocking writes to `case_documents` and `case_query_status` for the build's duration. The requester
has confirmed both tables hold fewer than ~100,000 rows in production — at that volume the lock
window is sub-second to low-single-digit seconds, not a meaningful ingestion-outage risk. `V1014`
is cleared to merge and deploy as a normal migration, still routed through `migration-reviewer` per
CLAUDE.md's standard hard rule (not as a sizing gate, just the usual review). See ADR-003's
Consequences for the full resolution. A formal production `EXPLAIN` capture (AC-012, OQ-009) remains
good practice post-deploy but no longer blocks merge — see `02-design.md` §12.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (Testcontainers repository correctness tests + `StalledWorkQueryPlanTest`).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] `V1014` reviewed via `migration-reviewer`; **DBA sizing sign-off obtained before merge** (see gate note above).
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; all seeded test data synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence, including the DBA sign-off reference.

### Notes / open questions
- No dependency on Stories 1–2. This is the natural first story to start if Area A ships before Area B.
- **Story 4 has a hard dependency on this story** — it cannot register meaningful gauge values without these repository methods, and cannot satisfy the index-usage half of its own testing without `V1014`.
- ADR-004's phase-set change (`UPLOADED` included) is reflected only in this story's `IN` list and in the phase set Story 4 seeds gauges for — no migration impact either way, since `idx_cd_phase_phase_at` is not partial.
- Jira sub-ticket: `DD-43220`.

---

## Story 4 — Stuck-work gauges: registration, ShedLock-guarded refresh and freshness gauge
**Jira: `DD-43221`**
**Depends on Story 3.**

As a **production support engineer**,
I want **`cdk_documents_stalled` and `cdk_queries_awaiting_answer` published on
`/actuator/prometheus`, refreshed automatically on a cluster-guarded schedule, with a freshness
gauge so a stale per-pod reading is never mistaken for a current one**,
so that **I can alert on a document or query that has stopped progressing, instead of finding out
only when a legal adviser hits an empty AI Search result in a live hearing**.

### Background
FR-001, FR-002, FR-004 and FR-006 require the two gauges to be refreshed
together, no more often than every 60 seconds, guarded by ShedLock under its own lock name (the
repo's global `defaultLockAtMostFor = PT30S` is shorter than the refresh cadence and must be
overridden explicitly). Because the refresh is ShedLock-guarded, only one pod's copy is fresh at any
moment (ADR-008, accepted) — so this story also ships a companion freshness gauge,
`cdk_monitoring_last_refresh_epoch_seconds`, without which a `max()`-aggregated alert cannot
distinguish a current backlog from a stale one hours old.

### Acceptance criteria
- [ ] AC-001: Given the configured threshold `cdk.monitoring.stalled-threshold` (default `PT30M`), when the refresh runs, then `cdk_documents_stalled` publishes one series per phase tag (`WAITING_FOR_UPLOAD`, `UPLOADING`, `UPLOADED`, `INGESTING`, per Story 3's phase set) matching `countStalledByPhase`'s result, with no tag other than `phase` and no case/document/defendant/court identifier of any kind.
- [ ] AC-002: `cdk_queries_awaiting_answer` publishes Story 3's `countAwaitingAnswerOlderThan` result as a single untagged series (beyond the global common tags).
- [ ] AC-004: With `cdk.monitoring.stalled-threshold` unset, the effective threshold is 30 minutes; the cutoff is recomputed from the bound property on **every** refresh (never cached at construction), so a changed value takes effect on the next tick with no restart.
- [ ] AC-005: The refresh runs on a fixed schedule of **at least 60 seconds** (asserted against the shipped `application-cdk.yml` default — a `MonitoringPropertiesTest`, not the compose override, which runs faster for test speed), guarded by ShedLock under its own literal lock name `stalledWorkMetricsRefresh`, with `lockAtMostFor` explicitly overriding `ShedLockConfig`'s global `PT30S` default; an integration test asserts a `shedlock` row exists for that lock name after the first refresh, with `lock_until > locked_at`.
- [ ] AC-006: Given one of the two aggregate queries fails or times out, when the refresh runs, then that aggregate's gauges retain their last successfully-computed value, exactly one WARN is logged for that failure (naming the aggregate, carrying the exception object, no SQL or row data), no exception escapes into the Spring `TaskScheduler`, and a concurrently-running API request completes normally and unaffected; the other aggregate still updates if it independently succeeds.
- [ ] AC-007: `cdk_monitoring_last_refresh_epoch_seconds` is seeded to `0` at startup and is set to the completion time (epoch seconds) at the end of any refresh in which **at least one** of the two aggregates succeeded (ADR-008).
- [ ] AC-008: All series in AC-001–AC-003 and AC-007 are registered eagerly at construction (query-then-apply, never pre-zeroed mid-refresh), so a Prometheus scrape landing mid-refresh never observes a spurious zero.

### NFR links
- NFR-001 (Data protection): counts only, fixed `phase`/`status` tag sets, no case identifier anywhere.
- NFR-002 (Cardinality): this story adds 6 of the ticket's **14** total added series (4 `cdk_documents_stalled` + 1 `cdk_queries_awaiting_answer` + 1 `cdk_monitoring_last_refresh_epoch_seconds`).
- NFR-003 (Performance/isolation): the refresh runs only on the `scheduler-*` pool, never a request thread; gauges serve last-refreshed values — there is no query on scrape.
- NFR-004 (Availability): a failing aggregate query, a failing meter write, or ShedLock contention must not fail a request, a discovery run, startup, or `/actuator/health`.
- NFR-007 (Configurability): threshold and cadence externally configurable via `cdk.monitoring.*` / `CP_CDK_MONITORING_*`, with the ticket's stated defaults shipped.

### Out of scope for this story
- The repository methods, projections and `V1014` migration themselves — Story 3, a hard prerequisite.
- The scheduler heartbeat/enabled meter family — Stories 1–2, an unrelated meter family sharing only `MeterRegistry` and `CdkMeters`.
- Alert thresholds, recording rules, dashboards (OQ-011) — this story ships the freshness *signal*, not the alert that consumes it; the recommended `max by (...) and on (instance) (time() - cdk_monitoring_last_refresh_epoch_seconds < 300)` expression is documentation for the OQ-011 owner, not built here.
- Real-world calibration of the 30-minute default threshold now that `UPLOADED` is in scope (it has genuine normal-operation occupancy, unlike `WAITING_FOR_UPLOAD`) — flagged to the OQ-011 follow-up ticket owner, not a blocker on this story.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `StalledWorkMetricsTest` with mocked repositories covering the per-aggregate degradation path; `MonitoringPropertiesTest` for the ≥60s/lock-duration constraints; integration: seeded backdated rows, `Awaitility`-driven wait past one refresh, gauge values asserted on `/actuator/prometheus`; a `shedlock` row assertion for AC-005).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **Hard dependency on Story 3** — cannot register meaningful gauge values without its repository methods, and cannot exercise the ShedLock/index behaviour meaningfully without `V1014`.
- **Shares `metrics/CdkMeters` with Stories 1–2, not a hard dependency either way.** Areas A and B are independent (`02-design.md` §1) and can ship in either order. Whichever of Story 1 or Story 4 is implemented first **creates** `CdkMeters`; the other **extends** it with its own meter-name/tag constants. This must be stated explicitly at sprint planning so the second-delivered story's PR is scoped as "extend `CdkMeters`" rather than "create `CdkMeters`" and does not collide with the first.
- Adds new classes `metrics/StalledWorkMetrics`, `metrics/StalledWorkMetricsRefreshJob`, `config/MonitoringProperties`, `config/MonitoringConfig`.
- Jira sub-ticket: `DD-43221`.

---

## Story 5 — Cross-cutting integration coverage and quality-gate regression proof
**Jira: `DD-43222`**
**Depends on Stories 1, 2, 3 and 4.**

As a **CDKS developer / release engineer**,
I want **one integration test that scrapes `/actuator/prometheus` and asserts every new metric from
both Area A and Area B is present and correctly tagged, plus confirmation that every existing test
suite and quality gate is unaffected**,
so that **the whole ticket can be merged and deployed with confidence, not just each story in
isolation, and the "no regression" acceptance criteria that only make sense once every series
exists (NFR-006, AC-023–AC-025) are actually exercised**.

### Background
Each of Stories 1–4 already carries its own unit and integration tests as part of its own DoD. This
story is the one place where a test can assert on **all six** rendered Prometheus names together
(`cdk_documents_stalled`, `cdk_queries_awaiting_answer`,
`cdk_monitoring_last_refresh_epoch_seconds`, `cdk_scheduler_runs_total`,
`cdk_scheduler_last_success_epoch_seconds`, `cdk_scheduler_enabled`)
and where the whole-ticket regression and quality-gate ACs are proven, not merely inherited from
each story's own DoD.

### Acceptance criteria
- [ ] AC-001: A new `MonitoringMetricsHttpLiveTest` scrapes `/actuator/prometheus` and asserts all **six** rendered Prometheus names from `02-design.md` §2 are present, each with its documented tag set and one series per enum value, asserted using the same `CdkMeters` constants production code registers (so a name divergence between test and production fails the test, not just a manual read of the scrape output).
- [ ] AC-002: The same test class seeds synthetic, backdated rows across `case_documents` and `case_query_status` (raw-JDBC idiom, matching `IngestionProcessByCaseHttpLiveTest` / `IntradayDiscoverySchedulerLiveTest`), waits past one compose-configured refresh interval via Awaitility, and asserts the stuck-work gauge values match the seeded counts; cleans up in `finally` since the compose DB is shared across the suite.
- [ ] AC-003: The same test class asserts a `shedlock` row named `stalledWorkMetricsRefresh` exists with `lock_until > locked_at` after the first refresh (covering Story 4's AC-005 at the full-stack level).
- [ ] AC-004: `ActuatorHttpLiveTest`, `IntradayDiscoverySchedulerLiveTest` and `NightlyDiscoverySchedulerLiveTest` pass with their **existing** assertions completely unmodified; no existing metric name, actuator endpoint, cron expression, ShedLock lock name or lock duration changes anywhere in the ticket.
- [ ] AC-005: `gradle clean build` (including `integration`) passes end-to-end for the whole ticket; PMD and JaCoCo are green at existing, unmodified thresholds; CodeQL and the secrets scanner are clean.
- [ ] AC-006: The full diff across Stories 1–5 introduces no PII, case content, court reference number, or `CJSCPPUID` into code, config, tests or fixtures; every value used to seed stall counts is synthetic (`gen_random_uuid()` or equivalent, no real identifiers).

### NFR links
- NFR-006 (Testability): this story is the direct deliverable for NFR-006's "integrationTest coverage asserting the new metric names appear on `/actuator/prometheus`."
- NFR-005 (Backward compatibility): verified end-to-end here, not just asserted per-story.

### Out of scope for this story
- Writing the production code for any of the six meters or the two repository queries — Stories 1–4.
- Contract tests — no API, schema, or contract change anywhere in this ticket; `pactVerificationTest` is unaffected.
- A formal production `EXPLAIN` capture — good practice post-deploy, no longer a merge blocker now that OQ-009's row-count question is resolved (Story 3's gate note above).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by the new `MonitoringMetricsHttpLiveTest` plus confirmation runs of the three existing unmodified live tests.
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence covering the full ticket, not just this story's own diff.

### Notes / open questions
- Intentionally sequenced last — its central integration test scrapes metrics registered by all four other stories, so it cannot be meaningfully completed (only partially stubbed) before Stories 1–4 land.
- Jira sub-ticket: `DD-43222`.

---

## Summary

| Story | Title | Jira | Depends on | Area |
|---|---|---|---|---|
| 1 | Scheduler run-outcome and heartbeat instrumentation | `DD-43218` | none | B |
| 2 | Scheduler enabled/disabled visibility | `DD-43219` | Story 1 | B |
| 3 | Stuck-work aggregate queries, projections and supporting indexes | `DD-43220` | none | A |
| 4 | Stuck-work gauges: registration, ShedLock-guarded refresh and freshness gauge | `DD-43221` | Story 3 | A |
| 5 | Cross-cutting integration coverage and quality-gate regression proof | `DD-43222` | Stories 1, 2, 3, 4 | A + B |

**Shared-infrastructure sequencing (ADR-001/ADR-006), stated once so sprint planning does not have
to re-derive it:**
- `metrics/CdkMeters` (meter-name and tag constants) is created by **whichever of Story 1 (Area B)
  or Story 4 (Area A) is implemented first**; the other story extends the same class with its own
  constants. Areas A and B remain independent and can be picked up in either order.
- `metrics/SchedulerMetrics` (the always-present component owning all three scheduler meter
  families) is created by **Story 1** and **extended by Story 2** — this is a hard, in-area
  dependency, not an either-order one, because Story 2 adds a third meter family to the same
  constructor loop Story 1 writes.
- `repo/PhaseCount` and the two new repository methods (Story 3) are a hard prerequisite for
  `metrics/StalledWorkMetrics` (Story 4) — Story 4 has nothing to register gauge values from until
  Story 3 lands.

**Pre-merge gate carried forward from Stage 2, not a start blocker:** Story 3's `V1014` migration
needs a DBA sizing sign-off on the `CREATE INDEX` write-lock window (ADR-003, accepted) before that
story's PR merges — work can proceed in parallel with the DBA review.

**Not a story here** (per `01-requirements.md`'s Out of scope, unchanged at Stage 3): Prometheus
alert rules, recording rules, Grafana dashboards, or on-call routing (OQ-011 — a follow-up ticket
owned by platform/SRE, which `01-requirements.md` and `02-design.md` both flag as required before
this ticket delivers any real value, since it ships signals with nobody yet watching them); any
remediation of stalled work (no auto-retry, no requeue, no cleanup job); any new or changed REST
endpoint; metrics for any other pipeline stage; backfill of stall counts prior to first deployment;
persisting scheduler heartbeat state to the database (ADR-007, accepted — the counter is the
liveness signal); populating the currently-dead `UPLOADING`/`INGESTING` phases in ingestion write
paths (a separate ingestion-behaviour ticket); **any monitoring of `document_verification_task`, and
any cleanup of that table** (confirmed dead — a Spring Batch-era
leftover superseded by the JobManager framework, with no writer; table cleanup is a separate future
ticket, not part of this set).

**Carried-forward follow-ups needing action before or shortly after this ticket ships**, for
visibility at sprint planning (none of these are stories in this set):
- OQ-009 — **Resolved 2026-09-01** (row-count/lock-sizing question closed; Story 3's gate note above). A formal production `EXPLAIN` capture for the permanent record remains a good post-deploy follow-up, non-blocking.
- OQ-011 — the alert-rule/dashboard follow-up ticket itself, owned by platform/SRE.
- OQ-012 — security-reviewer sign-off that `/actuator/prometheus`'s exposure (same port as the public API, excluded from `cp-auth-rules-filter`, protected only by ingress/network policy) is acceptable for these new operational-volume series. Required before merge, not a story.
- OQ-001 — Jira DD-43185's pasted brief was never confirmed against the live ticket/epic comments in this session (no Jira/Atlassian MCP tool available). Sub-tickets `DD-43218`–`DD-43222` are now cut and linked; this OQ still asks the requester to confirm the original pasted brief was complete and current before Stage 5 starts.
