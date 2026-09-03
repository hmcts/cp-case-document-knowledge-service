# Test Specs: Stalled-Work Gauges and Scheduler Heartbeat Observability

> **Stage 4 — Test Specs** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43185** · Stories: [`03-stories.md`](./03-stories.md) · Design: [`02-design.md`](./02-design.md) ·
> Requirements: [`01-requirements.md`](./01-requirements.md) ·
> ADRs: [`adrs/DD-43185-stalled-work-scheduler-monitoring.md`](../adrs/DD-43185-stalled-work-scheduler-monitoring.md)
> (ADR-001 – ADR-008, all **Accepted** at the Stage-2 gate on 2026-08-25 — not reopened here).
>
> **Written prospectively — no implementation exists yet (A-TDD).** Nothing below is evidence of
> coverage. Every scenario states **"To be proven by:"**, which names a test *to write*, not a test
> that passed. None of the named tests can compile until Stage 5 lands the production classes
> described in design §2–§9: `metrics/CdkMeters`, `metrics/SchedulerMetrics`,
> `metrics/StalledWorkMetrics`, `metrics/StalledWorkMetricsRefreshJob`, `config/MonitoringProperties`,
> `config/MonitoringConfig`, `repo/PhaseCount`, the two new repository methods, and `V1014`.
>
> **Note — `document_verification_task` excluded.** This ticket covers two stuck-work gauges, not
> three, and six rendered metric names across 14 total series, not seven names / 18 series:
> `document_verification_task` is a dead table — a Spring Batch-era leftover, superseded by the
> JobManager framework, with no writer at all, confirmed both by static analysis (OQ-024 below) and
> by a live query against the running database. No test is written against a dead table; table
> cleanup itself is a separate future ticket.
>
> **Test-authoring order follows the story dependency chain** (`03-stories.md` §Summary):
> Area B is Story 1 → Story 2; Area A is Story 3 → Story 4; Story 5 last, across both.
> The two areas are independent and either may be authored first. Whichever of Story 1 or Story 4
> is written first **creates** `CdkMeters`; the other **extends** it — the same either-order rule the
> stories state for production code applies to the tests that assert against those constants.
>
> **Jira linkage — resolved.** Real sub-tickets `DD-43218`–`DD-43222` were created and linked to
> the parent epic on 2026-08-27, satisfying CLAUDE.md's hard rule *"Every story needs a linked
> Jira ticket before the test stage."* See OQ-023.

---

## Scope boundaries this document inherits and does not attempt to work around

1. **Production-scale `EXPLAIN` evidence is not deliverable from this repository** (OQ-009, design
   §12, ADR-003 Consequences). The compose stack and the Testcontainers unit tier both hold
   synthetic volumes only. Story 3's `StalledWorkQueryPlanTest` proves **index applicability at a
   documented synthetic volume**; it is *not* evidence for requirements AC-012's "under 500 ms at
   production scale", and no scenario below claims otherwise. The production-scale number and the
   `CREATE INDEX` write-lock sizing are DBA follow-ups outside this repo, and `V1014`'s merge is
   gated on them.
2. **Two test layers, deliberately distinct.** Story 3's repository and plan tests are
   **unit-tier Testcontainers** tests (`src/test/`, `@DataJpaTest` + `PostgreSQLContainer`, following
   `CaseDocumentRepositoryTest`), *not* compose-backed REST Assured tests. Only the gauge surface on
   `/actuator/prometheus`, the `shedlock` row and the end-to-end value flow are
   **integration-tier** (`src/integrationTest/`, compose-backed, extending `AbstractHttpLiveTest`,
   following `IntradayDiscoverySchedulerLiveTest` / `NightlyDiscoverySchedulerLiveTest` /
   `ActuatorHttpLiveTest`). Nothing new invents a third pattern.
3. **No contract tests.** No API, OpenAPI model, schema-visible-to-consumers, or ACL change anywhere
   in this ticket; `src/pactVerificationTest/` is untouched and both consumed API artefact versions
   are unchanged.
4. **No accessibility tests.** CDKS is backend-only. The WCAG 2.1 AA hard rule applies to downstream
   consumers of CDKS's API, not to a Prometheus scrape endpoint.

---

## The contract under test — names, tags and values (design §2, ADR-001)

Every scenario below asserts against exactly these. Micrometer meter names are what production
registers (via `CdkMeters` constants); Prometheus names are what a scrape renders and what alert
rules in another repository will be written against. **Both forms must be asserted** — a test that
only checks one cannot catch ADR-001's real trap (there is no snake-casing on this classpath, so a
camelCase meter name renders camelCase, silently).

| # | Micrometer meter name (constant) | Rendered Prometheus name | Type | Ticket tags | Series | Story |
|---|---|---|---|---|---|---|
| 1 | `cdk.documents.stalled` | `cdk_documents_stalled` | Gauge | `phase` | 4 | 4 |
| 2 | `cdk.queries.awaiting.answer` | `cdk_queries_awaiting_answer` | Gauge | — | 1 | 4 |
| 3 | `cdk.monitoring.last.refresh.epoch.seconds` | `cdk_monitoring_last_refresh_epoch_seconds` | Gauge | — | 1 | 4 |
| 4 | `cdk.scheduler.runs` | `cdk_scheduler_runs_total` | Counter | `scheduler`, `outcome` | 4 | 1 |
| 5 | `cdk.scheduler.last.success.epoch.seconds` | `cdk_scheduler_last_success_epoch_seconds` | Gauge | `scheduler` | 2 | 1 |
| 6 | `cdk.scheduler.enabled` | `cdk_scheduler_enabled` | Gauge | `scheduler` | 2 | 2 |

**14 series total, six rendered metric names.** Fixed, closed tag-value sets — no test may introduce a
tag value outside them:

| Tag key | Permitted values |
|---|---|
| `phase` | `WAITING_FOR_UPLOAD`, `UPLOADING`, `UPLOADED` (ADR-004, accepted), `INGESTING` |
| `scheduler` | `intraday-discovery`, `nightly-discovery` (ADR-006 fixed constants — **not** the ShedLock lock names `intradayDiscoveryScheduler` / `nightlyDiscoveryScheduler`, **not** class names) |
| `outcome` | `success`, `failure` |

**ShedLock lock name for the refresh job:** `stalledWorkMetricsRefresh` — a literal constant
(`StalledWorkMetricsRefreshJob.LOCK_NAME`), deliberately **not** a property placeholder, with
`lockAtMostFor = PT5M` explicitly overriding `ShedLockConfig`'s global
`defaultLockAtMostFor = "PT30S"` and `lockAtLeastFor = PT55S` (ADR-008). The two existing scheduler
lock names and durations are untouched.

**Every rendered series additionally carries the global common tags** `service`, `cluster`, `region`
from `application-server-management.yml` (`service="cp-case-document-knowledge-service"`,
`cluster`/`region` = `local` in the compose stack). Scrape assertions must therefore match on
substrings or a regex over label sets, never on a whole-line exact match with a hand-written label
order.

---

## Test inventory — files to create or extend

| Tier | File | New / extend | Story |
|---|---|---|---|
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/scheduler/IntradayDiscoverySchedulerTest.java` | extend | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/scheduler/NightlyDiscoverySchedulerTest.java` | extend | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/metrics/SchedulerMetricsTest.java` | **new** | 1, extended by 2 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/scheduler/SchedulerPropertiesBindingTest.java` | **new** | 2 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/repo/CaseDocumentRepositoryTest.java` | extend | 3 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/repo/CaseQueryStatusRepositoryTest.java` | **new** | 3 |
| Unit (Testcontainers) | `src/test/java/uk/gov/hmcts/cp/cdk/repo/StalledWorkQueryPlanTest.java` | **new** | 3 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/metrics/StalledWorkMetricsTest.java` | **new** | 4 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/config/MonitoringPropertiesTest.java` | **new** | 4 |
| Integration | `src/integrationTest/java/uk/gov/hmcts/cp/cdk/metrics/SchedulerMetricsHttpLiveTest.java` | **new** | 1, extended by 2 |
| Integration | `src/integrationTest/java/uk/gov/hmcts/cp/cdk/metrics/MonitoringMetricsHttpLiveTest.java` | **new** in 4, **extended** in 5 | 4, 5 |
| Integration | `ActuatorHttpLiveTest`, `IntradayDiscoverySchedulerLiveTest`, `NightlyDiscoverySchedulerLiveTest` | **unmodified — run as regression** | 1, 5 |
| Config | `docker/docker-compose.integration.yml` | extend (four `CP_CDK_MONITORING_*` overrides, design §3) | 4 |

**Why new integration classes rather than extra methods on the existing scheduler live tests.**
Story 1 AC-007 and requirements AC-024 say `IntradayDiscoverySchedulerLiveTest` and
`NightlyDiscoverySchedulerLiveTest` "need no change at all". Design §Testing notes they would be a
natural home for a heartbeat assertion; this spec deliberately declines that, because an untouched
file is a stronger regression signal than a diffed one, and the `queryShedlockRow(...)` /
`ShedlockRow` idiom is cheap to reproduce (it is already duplicated verbatim between those two
classes today — a third copy, or a shared `testsupport` helper, is a reviewer's call, not a
behavioural one).

**Naming convention** (matching house style):
- Unit — `<method>_should<Outcome>_when<Condition>` (e.g. `run_shouldTriggerIntradayDiscovery` today)
  or `should<Outcome>_when<Condition>`. Pick one per class and stay consistent with the class's
  existing style.
- Live — `<subject>_<behaviour>` (e.g. `scheduler_shouldAcquireShedLock_andPopulateShedlockTable`).

**Log assertions use the established in-repo idiom**: a logback `ListAppender<ILoggingEvent>`
attached to the class logger, exactly as `DiscoveryTriggerServiceTest` does (added by DD-43063).
No new logging test library.

---

## Story 1 — Scheduler run-outcome and heartbeat instrumentation (`DD-43218`)

Targets `scheduler/IntradayDiscoveryScheduler`, `scheduler/NightlyDiscoveryScheduler` and the new
`metrics/SchedulerMetrics` per design §5–§6.

**Shared Given for 1.1–1.6** (the existing unit fixtures, plus one new mock): a
`@Mock DiscoveryService` and a **new** `SchedulerMetrics` collaborator — either `@Mock` (for the
scheduler-side scenarios, where the assertion is "`recordRun` was called with these arguments") or a
real instance over a `SimpleMeterRegistry` (for scenarios that assert meter values). Both are
legitimate; prefer the mock in the scheduler tests and the real registry in `SchedulerMetricsTest`,
so a failure localises to one class.

---

**Scenario 1.1 — A successful intraday run increments only the success counter and advances the heartbeat** *(AC-001, AC-003)*
- **Given** `DiscoveryService.runIntradayDiscovery()` returns normally
- **When** `IntradayDiscoveryScheduler.run()` executes
- **Then** `SchedulerMetrics.recordRun("intraday-discovery", true)` is invoked **exactly once**;
  `cdk.scheduler.runs{scheduler="intraday-discovery",outcome="success"}` increments by 1;
  `cdk.scheduler.runs{scheduler="intraday-discovery",outcome="failure"}` is unchanged;
  `cdk.scheduler.last.success.epoch.seconds{scheduler="intraday-discovery"}` equals the run's
  completion time in epoch seconds; and no `nightly-discovery` series is touched.
- **To be proven by:** `IntradayDiscoverySchedulerTest.run_shouldRecordSuccessExactlyOnce_whenDiscoveryCompletes`
  (new) with `verify(schedulerMetrics, times(1)).recordRun(INTRADAY_DISCOVERY, true)` and
  `verifyNoMoreInteractions(schedulerMetrics)`; the meter-value half is asserted in
  `SchedulerMetricsTest` (Scenario 1.7).
- **Clock note:** the heartbeat is taken from `TimeUtils.utcNow()` (design §6). Assert against a
  stubbed/controlled clock where the class allows it (`config/TestClockConfig` exists as a
  precedent), or assert a bounded window rather than an exact equality. Do **not** assert
  `Instant.now()` equality — it will flake.

**Scenario 1.2 — A successful nightly run does the same under its own distinct tag** *(AC-001)*
- **Given** `DiscoveryService.runNightlyDiscovery()` returns normally
- **When** `NightlyDiscoveryScheduler.run()` executes
- **Then** exactly the same holds with `scheduler="nightly-discovery"`, and no `intraday-discovery`
  series is touched. The two tag values are the `CdkMeters` constants, not derived from the ShedLock
  names `intradayDiscoveryScheduler` / `nightlyDiscoveryScheduler` and not from class names.
- **To be proven by:** `NightlyDiscoverySchedulerTest.run_shouldRecordSuccessExactlyOnce_whenDiscoveryCompletes` (new).

**Scenario 1.3 — An exception from intraday discovery is contained, logged at ERROR, and counted as a failure** *(AC-002, AC-003, AC-004, AC-006)*
- **Given** `DiscoveryService.runIntradayDiscovery()` throws a `RuntimeException`
- **When** `IntradayDiscoveryScheduler.run()` executes
- **Then** **no exception escapes** `run()` (the Spring `TaskScheduler` never sees it);
  `recordRun("intraday-discovery", false)` is invoked exactly once; the failure counter increments
  by 1; the success counter and
  `cdk.scheduler.last.success.epoch.seconds{scheduler="intraday-discovery"}` are **unchanged**; and
  exactly one `ERROR` event is logged carrying (a) the scheduler tag value in the message arguments,
  (b) the thrown exception object as the throwable (so the Logstash encoder renders the full stack
  trace), and (c) **no** case content, case id, document id, court reference or `CJSCPPUID`.
- **To be proven by:** `IntradayDiscoverySchedulerTest.run_shouldContainAndCountFailure_whenDiscoveryThrows`
  (new) — `assertThatCode(scheduler::run).doesNotThrowAnyException()`, plus a `ListAppender` on the
  `IntradayDiscoveryScheduler` logger asserting a single `Level.ERROR` event whose
  `getThrowableProxy()` is non-null and whose `getFormattedMessage()` contains
  `intraday-discovery`. Mirrors `DiscoveryTriggerServiceTest.trigger_emitsSingleCorrelatedStartFailedLogPair_onException`.
- **JSON-rendering note:** the `ListAppender` proves the *event* shape, not the JSON encoding.
  AC-004's "emitted as structured JSON through the existing `logback-spring.xml`" is inherited from
  the unchanged appender configuration and is a diff-level check (no new appender, no `System.out`),
  not something a unit test can observe. State both; do not claim the unit test proves the encoding.

**Scenario 1.4 — The equivalent holds for nightly discovery** *(AC-002)*
- **Given** `DiscoveryService.runNightlyDiscovery()` throws
- **When** `NightlyDiscoveryScheduler.run()` executes
- **Then** as 1.3, under `scheduler="nightly-discovery"`.
- **To be proven by:** `NightlyDiscoverySchedulerTest.run_shouldContainAndCountFailure_whenDiscoveryThrows` (new).

**Scenario 1.5 — Exactly one increment per invocation, across a mixed sequence** *(AC-003)*
- **Given** a scheduler whose `DiscoveryService` succeeds on the first call and throws on the second
- **When** `run()` is invoked twice
- **Then** the success counter reads 1, the failure counter reads 1, the sum of all
  `cdk.scheduler.runs` series for that scheduler is exactly 2, and the heartbeat holds the timestamp
  of the **first** (successful) run — it is neither advanced nor reset by the second.
- **To be proven by:** extending the existing `run_shouldBeCallableMultipleTimes` (which today only
  counts `DiscoveryService` invocations) into
  `run_shouldRecordExactlyOneOutcomePerInvocation_whenRunsSucceedThenFail`, against a real
  `SchedulerMetrics` over a `SimpleMeterRegistry`.
- **Why this scenario is separate:** AC-003 is a structural claim about the `finally` block. A
  per-scenario `times(1)` proves it for one path; only a mixed sequence proves the counters cannot
  drift relative to the number of invocations.

**Scenario 1.6 — An `Error` still propagates; the `finally` block still records one failure** *(AC-006)*
- **Given** `DiscoveryService.runIntradayDiscovery()` throws an `Error` (use a purpose-made
  `Error` subclass in the test — do **not** attempt to induce a real `OutOfMemoryError`)
- **When** `run()` executes
- **Then** the `Error` **propagates out of `run()`** (it is not caught by `catch (Exception e)`), no
  ERROR log line is written by the catch block, and — because the counter increment lives in
  `finally` (design §5) — `recordRun(tag, false)` **is** still invoked exactly once and the heartbeat
  is unchanged.
- **To be proven by:** `IntradayDiscoverySchedulerTest.run_shouldPropagateError_andStillRecordExactlyOneFailure_whenDiscoveryThrowsError` (new).
- **Confirm before writing (OQ-016).** The propagate-but-still-count behaviour is a *consequence* of
  design §5's `finally`-driven structure, not something design or the story states in words. It is
  self-consistent with AC-003 ("exactly one increment per invocation"), but it is an inference. Get
  it confirmed at the gate and then pin it with this test, rather than letting a later reader
  discover it by accident.

**Scenario 1.7 — All six Story-1 series exist at `0` immediately after `SchedulerMetrics` is constructed** *(AC-005)*
- **Given** a freshly constructed `SchedulerMetrics` over a `SimpleMeterRegistry`, with no run yet
  performed and both schedulers configured in any enabled state
- **When** the registry is inspected
- **Then** all four `cdk.scheduler.runs` series (2 schedulers × `success`/`failure`) exist with
  count `0`, and both `cdk.scheduler.last.success.epoch.seconds` series exist with value `0` — none
  is absent and none reads `NaN`.
- **To be proven by:** `SchedulerMetricsTest.shouldPreRegisterAllRunAndHeartbeatSeriesAtZero_whenConstructed` (new).
- **Why the `NaN` assertion matters:** design §6 registers the heartbeat gauges through an
  `AtomicLong` holder kept in a field map *and* `.strongReference(true)`, precisely because a
  weakly-referenced, collected holder reports `NaN` for ever. Assert the value is `0.0`, not merely
  that the meter exists.
- **Alert-shape rationale (do not delete this test as redundant):** an absent counter makes
  `increase(cdk_scheduler_runs_total[45m]) == 0` return *no data*, so ADR-007's liveness alert would
  never fire — the exact failure it exists to catch.

**Scenario 1.8 — `recordRun` mutates only the series it should** *(AC-001, AC-002)*
- **Given** a `SchedulerMetrics` over a `SimpleMeterRegistry`
- **When** `recordRun("intraday-discovery", true)` then `recordRun("nightly-discovery", false)` are
  called
- **Then** intraday: `outcome="success"` = 1, `outcome="failure"` = 0, heartbeat > 0. Nightly:
  `outcome="failure"` = 1, `outcome="success"` = 0, heartbeat still exactly `0` — a failed run must
  never advance the heartbeat (FR-009).
- **To be proven by:** `SchedulerMetricsTest.shouldIncrementOnlyTheMatchingCounter_andAdvanceHeartbeatOnSuccessOnly` (new).

**Scenario 1.9 — Existing scheduler tests keep their assertions; existing live tests keep their files** *(AC-007)*
- **Given** both scheduler classes gain a `SchedulerMetrics` constructor parameter (ADR-006
  Consequences)
- **When** `gradle test` and `gradle integration` run
- **Then** `IntradayDiscoverySchedulerTest.run_shouldTriggerIntradayDiscovery`,
  `run_shouldBeCallableMultipleTimes` and their nightly equivalents pass with **only** their
  `new …(discoveryService)` construction sites edited to `new …(discoveryService, schedulerMetrics)`
  — no assertion changed; and `IntradayDiscoverySchedulerLiveTest` /
  `NightlyDiscoverySchedulerLiveTest` pass **with no edit to the files at all**.
- **To be proven by:** the full `gradle test` / `gradle integration` runs, **plus a diff-level check**
  that the two live-test files are absent from the PR diff, and that cron expressions, ShedLock lock
  names, `lockAtLeastFor`/`lockAtMostFor`, `daysAhead` and both `@ConditionalOnProperty` annotations
  are untouched in `application-cdk.yml` and in the scheduler classes.
- **Watch item:** `verifyNoMoreInteractions(discoveryService)` in the existing intraday test remains
  valid (the new interactions are on `schedulerMetrics`, a different mock). If the implementer is
  tempted to broaden it to `verifyNoMoreInteractions(discoveryService, schedulerMetrics)`, that is an
  assertion change — allowed only as a deliberate, reviewed addition, not silently.

**Scenario 1.10 — The heartbeat and run-outcome series are observable on a live scrape** *(AC-001, story DoD)*
- **Given** the `gradle integration` compose stack, in which **both** discovery schedulers are
  enabled (`CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED: true`,
  `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED: true`) with their crons overridden to
  `0/30 * * * * *`
- **When** the test polls `GET /actuator/prometheus` with Awaitility (`atMost` 90 s, `pollInterval`
  5 s — the established window in the two scheduler live tests)
- **Then** the scrape contains a sample line for
  `cdk_scheduler_runs_total{…,outcome="success",scheduler="intraday-discovery",…}` with a value
  `>= 1`, the same for `nightly-discovery`, and
  `cdk_scheduler_last_success_epoch_seconds{…,scheduler="intraday-discovery",…}` with a value
  greater than `0` and within a plausible window of "now".
- **To be proven by:** `SchedulerMetricsHttpLiveTest.schedulerMetrics_shouldPublishRunOutcomeAndHeartbeat_afterAScheduledRun`
  (new, `src/integrationTest/.../metrics/`, extending `AbstractHttpLiveTest`, using the inherited
  `http` `RestTemplate` and `baseUrl`).
- **Parsing note that decides whether this test is real:** `# HELP` and `# TYPE` lines are emitted
  for every meter, so `body.contains("cdk_scheduler_runs_total")` passes even when **no series
  exists**. Assert on a *sample* line — match `^cdk_scheduler_runs_total\{[^}]*outcome="success"…`
  with a numeric value, or parse the body into (name, labels, value) triples in a small test helper.
  Confirm at implementation whether the exposition also emits `cdk_scheduler_runs_created` companion
  series on this classpath, and make the matcher tolerant of it either way.

---

## Story 2 — Scheduler enabled/disabled visibility (`DD-43219`)

Targets `scheduler/SchedulerProperties` (new bound `enabled` field) and the extension of
`metrics/SchedulerMetrics` per design §4 and §6.

---

**Scenario 2.1 — Both flags off: the gauge reports `0` for both schedulers, including the bean that was never created** *(AC-001)*
- **Given** `scheduler.intraday-discovery.enabled` and `scheduler.nightly-discovery.enabled` both
  resolve to `false` (the shipped default, since `CP_CDK_SCHEDULER_*_ENABLED` are unset), so
  `@ConditionalOnProperty` creates **neither** scheduler bean
- **When** `SchedulerMetrics` is constructed and the registry is inspected
- **Then** `cdk.scheduler.enabled{scheduler="intraday-discovery"}` = `0` and
  `cdk.scheduler.enabled{scheduler="nightly-discovery"}` = `0` — both series **present**, neither
  absent, despite neither bean existing. `SchedulerMetrics` must not hold a dependency on either
  scheduler class for this to be true.
- **To be proven by:** `SchedulerMetricsTest.enabledGauge_shouldReportZeroForBothSchedulers_whenBothFlagsAreFalse`
  (new; extends the class Story 1 created) — construct `SchedulerProperties` with both nested
  `enabled` fields `false`, no scheduler beans anywhere in the test.
- **Coverage boundary (see OQ-014):** this scenario is **unit-tier only**. The compose stack enables
  both schedulers, so the "gauge reads 0 for an absent bean" case cannot be observed in
  `integrationTest` without a second app configuration.

**Scenario 2.2 — One flag on, one off: `1` and `0`, not `1` and `1`** *(AC-002)*
- **Given** `scheduler.intraday-discovery.enabled = true`, `scheduler.nightly-discovery.enabled = false`
- **When** `SchedulerMetrics` is constructed
- **Then** `cdk.scheduler.enabled{scheduler="intraday-discovery"}` = `1` and
  `{scheduler="nightly-discovery"}` = `0`; and the mirrored case (nightly on, intraday off) yields
  the inverse.
- **To be proven by:** `SchedulerMetricsTest.enabledGauge_shouldReportPerSchedulerState_whenFlagsDiffer`
  (new), ideally a `@ParameterizedTest` over the four (intraday, nightly) boolean combinations so
  the "both true" and "both false" corners are covered by the same table.

**Scenario 2.3 — Each scheduler's enabled state is logged at INFO exactly once at startup** *(AC-003)*
- **Given** a `SchedulerMetrics` with a known `SchedulerProperties`
- **When** the `ApplicationReadyEvent` listener fires (invoke the listener method directly in the
  unit test — the assertion is about the listener, not about Spring's event plumbing)
- **Then** exactly two INFO events are logged, one naming `intraday-discovery` with its boolean
  state and one naming `nightly-discovery` with its boolean state; **no** log line is emitted from
  the constructor (design §6 anchors "exactly once at startup" to the lifecycle event, not to bean
  instantiation order); and neither line contains any identifier beyond the fixed scheduler tag.
- **To be proven by:** `SchedulerMetricsTest.shouldLogEnabledStateOncePerScheduler_onApplicationReady`
  (new) with a `ListAppender`, asserting `filteredOn(Level.INFO)` has exactly two events and that
  constructing the component alone produces none.
- **Deliberate limit:** "exactly once *per context*" under a real Spring lifecycle (e.g. no double
  fire on a refreshed context) is not asserted at the unit tier. If the gate wants that guarantee
  proven, it needs a `@SpringBootTest` — call that out rather than implying the unit test covers it.

**Scenario 2.4 — A configured/actual mismatch logs a WARN and does not block startup** *(AC-004)*
- **Given** the bound `enabled` flag says `true` for a scheduler while the corresponding
  `ObjectProvider<…Scheduler>.getIfAvailable()` returns `null` (and the converse case: flag `false`
  but the bean present)
- **When** the `ApplicationReadyEvent` listener runs
- **Then** a single WARN is logged naming the mismatched scheduler and both observed states; the
  listener returns normally; **no exception is thrown**; startup is not blocked; and the
  `cdk.scheduler.enabled` gauge still reports the **configured** value (the gauge tracks
  configuration; the WARN is the drift alarm, not an override).
- **To be proven by:** `SchedulerMetricsTest.shouldWarn_whenBoundEnabledFlagDisagreesWithBeanPresence`
  (new) — stub `ObjectProvider` with Mockito; assert one `Level.WARN` event and
  `assertThatCode(...).doesNotThrowAnyException()`.
- **Why it is worth a test:** ADR-006 identifies this cross-check as the guard against the single
  way that ADR's design can be wrong — the gauge reporting configured *intent* while the pod's
  actual behaviour differs. An untested guard is not a guard.

**Scenario 2.5 — `enabled` binds as a first-class property, and the shipped effective default is still `false`** *(AC-005)*
- **Given** `SchedulerProperties.IntradayDiscovery` / `NightlyDiscovery` gain
  `private boolean enabled = true;` (Java default `true`, mirroring `matchIfMissing = true`)
- **When** properties are bound (a) from a source that omits `scheduler.*.enabled` entirely, and (b)
  from the shipped `application-cdk.yml` with `CP_CDK_SCHEDULER_*_ENABLED` unset
- **Then** case (a) yields `true` for both — matching what `@ConditionalOnProperty(matchIfMissing =
  true)` would decide for the same absent property; case (b) yields `false` for both, because
  `application-cdk.yml` always supplies an explicit value defaulting to `false`. Neither
  `@ConditionalOnProperty` annotation, nor its `matchIfMissing`, nor either flag's shipped default
  is changed.
- **To be proven by:** `SchedulerPropertiesBindingTest` (new) — an `ApplicationContextRunner` (or
  `Binder` over a `MapConfigurationPropertySource`) for case (a), and a runner with
  `ConfigDataApplicationContextInitializer` (so the real `application-cdk.yml` is loaded) for case (b).
- **Trap to avoid:** a test that hand-feeds `scheduler.intraday-discovery.enabled=false` and asserts
  `false` proves only that Spring binds booleans. Case (b) — asserting against the **shipped YAML** —
  is the one that would catch someone changing the placeholder's default. Write both; the second is
  the load-bearing one.

**Scenario 2.6 — All three scheduler meter families agree on the tag value for the same scheduler** *(AC-006)*
- **Given** a constructed `SchedulerMetrics` and one recorded run per scheduler
- **When** the registry's meters are grouped by name
- **Then** the set of `scheduler` tag values on `cdk.scheduler.enabled`,
  `cdk.scheduler.last.success.epoch.seconds` and `cdk.scheduler.runs` is identical and is exactly
  `{"intraday-discovery", "nightly-discovery"}` — no `intradayDiscoveryScheduler`, no
  `IntradayDiscoveryScheduler`, no `intraday`.
- **To be proven by:** `SchedulerMetricsTest.shouldUseTheSameSchedulerTagValuesAcrossAllThreeMeterFamilies` (new).
- **Assert the literal strings here, not only the constants.** Everywhere else, tests should use the
  `CdkMeters` constants so a rename is one edit. This one scenario should *also* compare against the
  hard-coded `"intraday-discovery"` / `"nightly-discovery"` literals, because the constants'
  **values** are the cross-repository contract with alert rules (ADR-006, "reversibility: poor once
  external alert rules exist"). A test written purely in terms of the constants would happily pass
  after someone renamed both.

**Scenario 2.7 — `cdk_scheduler_enabled` is present and correct on a live scrape** *(AC-002, story DoD)*
- **Given** the compose stack, where both flags are set to `true`
- **When** `GET /actuator/prometheus` is scraped
- **Then** both `cdk_scheduler_enabled{…,scheduler="intraday-discovery",…}` and
  `{…,scheduler="nightly-discovery",…}` are present with value `1.0`.
- **To be proven by:** extending `SchedulerMetricsHttpLiveTest` (created in Story 1) with
  `schedulerMetrics_shouldPublishEnabledGaugeForBothSchedulers`.
- **Explicit gap, not an omission (OQ-014):** the story's DoD asks for integration coverage of
  "**both** the default-disabled and an enabled-override compose configuration". `gradle integration`
  starts **one** compose stack with both flags `true`; there is no second app container and no
  restart hook in the `integration` task. The `0` case is therefore **not** covered at the
  integration tier by anything in this spec. Decide at the gate: (a) accept unit-tier-only coverage
  for the disabled case (cheapest, and the behaviour is fully determined by one boolean), or (b) add
  a second app service to the compose file on a different port with the flags off — which also gives
  the "absent bean" case a live proof, at the cost of a second JVM in every build.

---

## Story 3 — Stuck-work aggregate queries, projections and supporting indexes (`DD-43220`)

**All scenarios in this story are unit-tier Testcontainers tests** (`src/test/`), following
`CaseDocumentRepositoryTest`'s `@DataJpaTest` + `@Testcontainers` + `@ServiceConnection`
`postgres:16-alpine` shape. None is compose-backed.

**Flyway note that makes these tests possible.** `src/test/resources/application.yml` does not
import `application-datasource.yml`, so the unit tier runs Flyway with its **default** location
`classpath:db/migration` — which contains `V1000`–`V1013` today and will contain `V1014` once it
lands. So a `@DataJpaTest` gets the real, fully-migrated schema **including the new indexes** with
no extra configuration. This is why these tests must **not** follow
`IngestionStatusViewRepositoryTest`'s hand-rolled `CREATE TABLE IF NOT EXISTS` pattern: a
hand-created table would silently have no `V1014` indexes and Scenario 3.7 would be meaningless.

**Seeding constraints discovered in the schema — get these right or the tests will not insert:**
- `case_documents` requires non-null `case_id`, `material_id`, `doc_name`, `blob_uri` (with
  `cd_blob_uri_not_blank`), `source` (`cd_source_not_blank`, defaults `'IDPC'`), `uploaded_at`,
  `ingestion_phase`, `ingestion_phase_at`.
- `case_query_status` has `PRIMARY KEY (case_id, query_id)` and **`fk_cqs_query` → `queries(query_id)`**.
  Bulk seeding must therefore insert at least one synthetic `queries` row first (with a unique
  `label` — `ux_queries_label_ci` is a unique index on `lower(btrim(label))`) and vary `case_id`.
  Design §12's `INSERT … SELECT … FROM generate_series(…)` sketch does not mention this FK.
- All identifiers synthetic: `gen_random_uuid()` (built in on PostgreSQL 16 — no `pgcrypto`
  extension needed) or `UUID.randomUUID()`. No real case, defendant, material or court identifiers.

---

**Scenario 3.1 — `V1014` is additive, creates exactly two indexes, and migrates cleanly both fresh and from `V1013`** *(AC-005)*
- **Given** (a) an empty PostgreSQL 16 database, and (b) a database already migrated to `V1013`
- **When** the Flyway chain runs
- **Then** `idx_cd_phase_phase_at` exists on `case_documents (ingestion_phase, ingestion_phase_at)`
  and `idx_cqs_awaiting_answer_at` exists on `case_query_status (status_at)` with predicate
  `WHERE status = 'ANSWER_NOT_AVAILABLE'`; **no** table, column, constraint, view
  (`v_case_ingestion_status`, `v_query_definitions_latest`), enum type, or pre-existing index
  (`idx_cd_phase`, `idx_cd_case_phase`, `idx_cd_case_uploaded_desc`, `idx_cqs_case_status`,
  `idx_cqs_status_at_desc`, `idx_dvt_*`) is altered or dropped; and because both statements are
  `CREATE INDEX IF NOT EXISTS`, re-running is a no-op rather than an error.
- **To be proven by:** the whole Testcontainers unit tier boots the migration chain on every
  `gradle test` (`CaseDocumentRepositoryTest`, `QueryVersionRepositoryTest`,
  `QueriesAsOfRepositoryTest`, `DiscoverySchedulerConfigurationRepositoryTest`, `JobManagerConfigTest`),
  so a malformed `V1014` fails them all — a broad but implicit guard. **Make it explicit** with
  `StalledWorkQueryPlanTest.shouldHaveCreatedBothMonitoringIndexes_afterFlywayMigration` (new),
  querying `pg_indexes` for the two index names and asserting `indexdef` contains the expected
  column list and, for the partial index, the `WHERE` predicate.
- **Not automatable here, and must not be claimed:** the `SHARE`-lock/write-blocking window of a
  plain `CREATE INDEX` on production-sized tables. That is ADR-003's pre-merge DBA gate. A green
  migration against an empty container says nothing about it.

**Scenario 3.2 — `countStalledByPhase` counts one row per monitored phase, older than the cutoff only** *(AC-001)*
- **Given** synthetic `case_documents` rows across the ADR-004 phase set — `WAITING_FOR_UPLOAD`,
  `UPLOADING`, `UPLOADED`, `INGESTING` — with `ingestion_phase_at` set to a known time **before** a
  chosen cutoff, in distinct per-phase counts (e.g. 3 / 1 / 5 / 2, so a wrong-phase attribution
  cannot pass)
- **When** `CaseDocumentRepository.countStalledByPhase(cutoff)` runs
- **Then** it returns one `PhaseCount` per phase present, each `getPhase()` being the enum constant
  **verbatim** (`"UPLOADED"`, not `"uploaded"`, not a `PGobject` toString) and each `getTotal()`
  matching the seeded count for that phase.
- **To be proven by:** `CaseDocumentRepositoryTest.countStalledByPhase_shouldReturnOneCountPerMonitoredPhase_whenRowsAreOlderThanCutoff`
  (extend the existing class — it already has a `persist(...)` JDBC helper and a live Postgres container).
- **The `::text` cast is the thing being proven here as much as the counting.**
  `case_documents.ingestion_phase` is the PostgreSQL enum `document_ingestion_phase_enum`; pgJDBC
  returns a user-defined enum as a `PGobject`, which a `String` projection getter cannot bind
  (design §7.4). If the cast is dropped, this test fails at binding — which is the intended alarm.

**Scenario 3.3 — Terminal phases and rows newer than the cutoff are excluded entirely** *(AC-002)*
- **Given** rows in `INGESTED`, `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT` and `NOT_FOUND` at **any** age,
  plus rows in all four monitored phases whose `ingestion_phase_at` is **after** the cutoff
- **When** `countStalledByPhase(cutoff)` runs
- **Then** no `PhaseCount` is returned for any terminal phase (not even with total `0` — a
  `GROUP BY` legitimately omits them; zero-filling is Story 4's job, at the gauge, not the query's),
  and the monitored-phase counts exclude every newer-than-cutoff row.
- **To be proven by:** `CaseDocumentRepositoryTest.countStalledByPhase_shouldExcludeTerminalPhasesAndRowsNewerThanCutoff` (new).
- **Boundary to pick deliberately:** the predicate is `ingestion_phase_at < :cutoff` (strict). Seed
  one row **exactly at** the cutoff and assert it is excluded, so the strictness is pinned rather
  than incidental.

**Scenario 3.4 — `countAwaitingAnswerOlderThan` counts only outstanding, aged rows, via a literal status** *(AC-003)*
- **Given** synthetic `case_query_status` rows (all referencing one synthetic `queries` row, varying
  `case_id`): N with `status = 'ANSWER_NOT_AVAILABLE'` and `status_at` before the cutoff, some with
  `ANSWER_NOT_AVAILABLE` but `status_at` after it, and some with `ANSWER_AVAILABLE` at any age
- **When** `CaseQueryStatusRepository.countAwaitingAnswerOlderThan(cutoff)` runs
- **Then** it returns exactly N.
- **To be proven by:** `CaseQueryStatusRepositoryTest.countAwaitingAnswerOlderThan_shouldCountOnlyAgedAnswerNotAvailableRows` (new).
- **Note:** the correctness of the count does not depend on the status being spelled as a literal —
  a bound parameter would return the same number. The literal is required for *index usage*, and
  only Scenario 3.6 can detect its loss. Keep the two concerns in separate tests and say so in both.

**Scenario 3.6 — The two aggregate plans use the intended indexes at synthetic volume, with no `Seq Scan`** *(AC-007)*
- **Given** a Flyway-migrated `postgres:16-alpine` Testcontainer seeded with a **documented**
  synthetic volume — order 100 000 rows in `case_documents` and `case_query_status` (via
  `INSERT … SELECT … FROM generate_series(…)`, with a realistic phase/status mix and a plausible
  spread of `ingestion_phase_at` / `status_at`), all identifiers `gen_random_uuid()` — followed by
  `ANALYZE` on both tables
- **When** `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` is run against each of the two production
  statements
- **Then** the `case_documents` plan references `idx_cd_phase_phase_at`; the `case_query_status`
  plan references `idx_cqs_awaiting_answer_at`; and **no** plan contains a `Seq Scan` on its target
  table. Additionally a **loose** `Actual Total Time` bound (e.g. 500 ms) is asserted purely as a
  CI-hardware smoke check.
- **To be proven by:** `StalledWorkQueryPlanTest.shouldUseTheMonitoringIndexes_atSyntheticVolume` (new).
- **This is explicitly NOT evidence for requirements AC-012.** Say so in the test's Javadoc, in the
  assertion message on the timing bound, and in `deploy-notes.md` at Stage 8 (design §12): the
  captured plan output must be labelled *"synthetic volume, not production scale — see the DBA
  follow-up"*, together with the exact seeded row counts and the container's PostgreSQL version. A
  future reader must not be able to mistake a green CI run for a production sign-off.
- **`ANALYZE` inside a transaction is fine** (unlike `VACUUM`), so `@DataJpaTest`'s rolled-back
  transaction is not an obstacle. Seeding is likewise fine against uncommitted data within the same
  transaction.
- **Source-of-truth hazard (OQ-018).** If this test hard-codes its own copy of the two SQL
  statements, it asserts the plan of a *copy* and
  will silently drift from the `@Query` values it is supposed to protect — defeating its own
  purpose. Decide before writing: extract each statement to
  a shared `public static final String` used by both the repository annotation and the test, or read
  the `@Query` annotation's `value()` reflectively from the repository interface method. The second
  is more faithful and needs no production change beyond the annotation already being there.

**Scenario 3.7 — A bound-parameter status silently disables the partial index (the regression this test exists for)** *(AC-003, AC-007, ADR-003)*
- **Given** the same seeded container as 3.6
- **When** the `case_query_status` aggregate is executed **twice**: once with the status as an inline
  literal (`status = 'ANSWER_NOT_AVAILABLE'`, as production spells it) and once with the status as a
  bound parameter
- **Then** the literal form's plan uses `idx_cqs_awaiting_answer_at`; the bound-parameter form's does
  **not** (PostgreSQL cannot prove the predicate implication for a parameter, so the partial index is
  unavailable to it). The test asserts both halves.
- **To be proven by:** `StalledWorkQueryPlanTest.shouldLosePartialIndex_whenStatusIsBoundInsteadOfLiteral` (new).
- **Why this is its own scenario.** ADR-003 states the literal coupling "must be enforced by test,
  not by comment". Asserting only the positive case (3.6) would still pass if someone later
  parameterised the status *and* the planner happened to pick the index for an unrelated reason. The
  negative control is what makes the coupling visible — and it is the single most likely silent
  performance regression in this ticket.
- **Robustness caveat to handle at implementation:** the negative half asserts planner behaviour, not
  application behaviour, so it is the most brittle assertion in the suite (a PostgreSQL minor-version
  change could alter it). If it proves unstable, downgrade it to asserting only that the *literal*
  form uses the index and raise the coupling to a PMD/architecture-test check instead — but record
  that decision, do not just delete the test.

**Scenario 3.8 — Each aggregate carries a 5 s statement timeout, and the two do not share a transaction** *(AC-006)*
- **Given** the two new repository methods
- **When** the interfaces are inspected
- **Then** each carries
  `@QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))`, and no
  `@Transactional` groups the two calls (design §7: two independent auto-commit statements, so one
  failure cannot mark a shared transaction rollback-only and take the other aggregate down — the
  property Story 4's per-aggregate degradation depends on).
- **To be proven by:** a reflective assertion on the annotation in
  `CaseDocumentRepositoryTest` / `CaseQueryStatusRepositoryTest`
  (`Method.getAnnotation(QueryHints.class)`), **plus** the behavioural proof at the Story 4 unit tier
  (Scenario 4.7: one repository throwing leaves the other aggregate updating), **plus** a diff-level
  check for the absence of `@Transactional`.
- **Honest limitation (OQ-019).** The timeout's *effect* is not exercised: none of these two queries
  can be made to run for five seconds at test volume, and there is no fault-injection seam for the
  database in this repository. The annotation-presence check proves configuration, not behaviour. Do
  not write an assertion that implies otherwise, and do not add a `pg_sleep`-based fake query to
  manufacture one — it would test a statement production never runs.

---

## Story 4 — Stuck-work gauges: registration, ShedLock-guarded refresh and freshness gauge (`DD-43221`)

Unit scenarios target `metrics/StalledWorkMetrics` with **mocked** repositories (design §8) and
`config/MonitoringProperties`. Integration scenarios target the compose stack.

**Compose prerequisite for the integration scenarios** (design §3): add to
`docker/docker-compose.integration.yml`, alongside the existing
`SCHEDULER_INTRADAY_DISCOVERY_CRON: "0/30 * * * * *"` overrides —
`CP_CDK_MONITORING_REFRESH_INTERVAL: PT10S`, `CP_CDK_MONITORING_INITIAL_DELAY: PT5S`,
`CP_CDK_MONITORING_LOCK_AT_LEAST_FOR: PT0S`, `CP_CDK_MONITORING_STALLED_THRESHOLD: PT1M`.
`cdk.monitoring.enabled` defaults to `true` (ADR-002) so no override is needed to turn the refresh
on. **See OQ-017 on `PT0S`.**

---

**Scenario 4.1 — `cdk.documents.stalled` publishes one series per monitored phase, tagged by `phase` only** *(AC-001)*
- **Given** a mocked `CaseDocumentRepository.countStalledByPhase(cutoff)` returning counts for a
  **subset** of the phase set (e.g. `WAITING_FOR_UPLOAD` = 7, `UPLOADED` = 2, and nothing for
  `UPLOADING` / `INGESTING`)
- **When** `StalledWorkMetrics.refresh()` runs
- **Then** the registry holds exactly four `cdk.documents.stalled` series — `WAITING_FOR_UPLOAD` = 7,
  `UPLOADED` = 2, `UPLOADING` = 0, `INGESTING` = 0 — each carrying a `phase` tag and **no other
  ticket-specific tag**; and no series carries anything resembling a case, document, defendant,
  material or court identifier.
- **To be proven by:** `StalledWorkMetricsTest.documentsStalled_shouldPublishAllFourPhases_zeroFillingAbsentGroups` (new).
- **The `UPLOADED` series is the ADR-004 gate decision made concrete.** If the requirements owner
  ever reverses ADR-004, this test's expected series count drops from four to three — which is
  exactly the visible, deliberate change ADR-004 asked for instead of a silent widening.

**Scenario 4.2 — `cdk.queries.awaiting.answer` publishes a single untagged series** *(AC-002)*
- **Given** a mocked `CaseQueryStatusRepository.countAwaitingAnswerOlderThan(cutoff)` returning a
  known count
- **When** `refresh()` runs
- **Then** exactly one `cdk.queries.awaiting.answer` series exists, carrying **no ticket-specific
  tag** (only the global common tags, which a `SimpleMeterRegistry` will not add), with that value.
- **To be proven by:** `StalledWorkMetricsTest.queriesAwaitingAnswer_shouldPublishOneUntaggedSeries` (new).

**Scenario 4.4 — The threshold defaults to 30 minutes and the cutoff is recomputed every refresh** *(AC-004)*
- **Given** a `MonitoringProperties` with `stalledThreshold` unset (so `PT30M`), then mutated to a
  different value between two refreshes
- **When** `refresh()` runs twice
- **Then** the `cutoff` passed to both repositories on the first refresh is
  `utcNow() − PT30M`, and on the second is `utcNow() − <new value>` — proving the cutoff is derived
  from the bound property **on every call**, never cached at construction, so a changed value takes
  effect on the next tick with no restart.
- **To be proven by:** `StalledWorkMetricsTest.shouldRecomputeCutoffFromThePropertyOnEveryRefresh` (new),
  with `ArgumentCaptor<OffsetDateTime>` on both repository calls and a controlled clock
  (`TimeUtils.utcNow()` — the `config/TestClockConfig` precedent) so the assertion is exact rather
  than windowed.

**Scenario 4.5 — The shipped cadence is ≥ 60 s and the lock durations are internally consistent** *(AC-005, config half)*
- **Given** the **shipped** `application-cdk.yml` defaults (not the compose overrides)
- **When** `MonitoringProperties` is bound
- **Then** `stalledThreshold` = `PT30M`, `refreshInterval` = `PT1M` and is **not shorter than 60 s**,
  `lockAtMostFor` = `PT5M` and is strictly greater than both `refreshInterval` and `ShedLockConfig`'s
  global `PT30S`, `lockAtLeastFor` = `PT55S` and is ≥ 0.9 × `refreshInterval`, `initialDelay` =
  `PT30S`, and `enabled` = `true`. Separately: a **unit-less** `stalled-threshold: 30` binds as
  **30 minutes**, not 30 milliseconds (the `@DurationUnit(ChronoUnit.MINUTES)` foot-gun from ADR-002).
- **To be proven by:** `MonitoringPropertiesTest` (new) — `ApplicationContextRunner` with
  `ConfigDataApplicationContextInitializer` for the shipped-YAML half, and a plain `Binder` over a
  map for the unit-less-integer half.
- **Assert against the YAML, not against the Java field initialisers.** A test that constructs
  `new MonitoringProperties()` and asserts `Duration.ofMinutes(30)` proves only that a field
  initialiser exists; it would pass even if someone set
  `CP_CDK_MONITORING_REFRESH_INTERVAL:PT5S` in the shipped file. AC-005 is about the shipped file.
- **This is also where FR-004's ≥60 s floor is proven,** deliberately *not* against the running
  compose container (which is overridden to `PT10S` for test speed) — exactly as the shipped
  10-minutely intraday cron is not what the compose stack runs.

**Scenario 4.6 — A `shedlock` row named `stalledWorkMetricsRefresh` exists after the first refresh** *(AC-005, integration half)*
- **Given** the compose stack with the `CP_CDK_MONITORING_*` overrides above
- **When** the test polls the `shedlock` table with Awaitility (`atMost` 90 s, `pollInterval` 5 s)
- **Then** a row with `name = 'stalledWorkMetricsRefresh'` exists, with non-null `locked_at`,
  non-null `lock_until`, a non-blank `locked_by`, and `lock_until` after `locked_at`.
- **To be proven by:** `MonitoringMetricsHttpLiveTest.stalledWorkRefresh_shouldAcquireItsOwnShedLock`
  (new class, created by this story) — reusing the `queryShedlockRow(...)` + `ShedlockRow` record
  idiom from `IntradayDiscoverySchedulerLiveTest` verbatim, and `openConnection()` from
  `AbstractHttpLiveTest`.
- **Assert the literal lock name**, not only the `StalledWorkMetricsRefreshJob.LOCK_NAME` constant.
  ADR-008 chose a literal constant over a property placeholder precisely so no environment can
  rename the row an operator would look for; a test written only in terms of the constant would not
  notice a rename.
- **Flake risk to settle first (OQ-017).** With the design's proposed compose override
  `CP_CDK_MONITORING_LOCK_AT_LEAST_FOR: PT0S`, ShedLock releases the lock by setting
  `lock_until` to the unlock instant. `lock_until > locked_at` then holds only by the elapsed
  duration of the refresh itself, which at two sub-millisecond queries against an empty test DB is a
  very small margin. Either use a small non-zero `lockAtLeastFor` in compose (e.g. `PT1S`) or
  relax the assertion to `isAfterOrEqualTo`. Decide before the test is written — the existing
  scheduler live tests get their comfortable margin from `PT15S`.

**Scenario 4.7 — One failing aggregate degrades alone: its gauges hold, one WARN, the other still updates, nothing thrown** *(AC-006)*
- **Given** mocked repositories where `countStalledByPhase(...)` throws (simulating a failure or the
  5 s statement timeout) while the other (`countAwaitingAnswerOlderThan`) returns normally — and a
  first, fully successful refresh has already populated all series with known values
- **When** `refresh()` runs a second time, with a **different** value available from the healthy
  repository
- **Then** all four `cdk.documents.stalled` series still read their **first-refresh** values (not
  zero, not stale-then-zeroed); `cdk.queries.awaiting.answer` updates to the new value; **exactly
  one** `WARN` is logged, naming the failing aggregate and carrying the exception object, containing
  no SQL text, no row data and no identifier; and `refresh()` **throws nothing**.
- **To be proven by:** `StalledWorkMetricsTest.shouldDegradePerAggregate_whenOneRepositoryThrows`
  (new), with a `ListAppender` for the WARN count and
  `assertThatCode(metrics::refresh).doesNotThrowAnyException()`.
- **Also assert the both-fail case**: **two** WARNs (one per aggregate), every
  series holding its previous value, still nothing thrown, and — per Scenario 4.9 — the freshness
  gauge **not** advanced.
- **The "query-then-apply, never pre-zero" rule is what this test protects** (design §8, rule 1). An
  implementation that zeroed all holders and then applied results would pass a naive
  "gauges are correct after a successful refresh" test and fail this one.

**Scenario 4.8 — A concurrent API request is unaffected by a failing refresh** *(AC-006, second half)*
- **Given** the compose stack under normal operation
- **When** any existing API live test runs while the 10-second refresh cadence is ticking
- **Then** the request completes normally.
- **To be proven by:** the existing `*HttpLiveTest` suites passing unchanged with the refresh enabled
  — which, since `cdk.monitoring.enabled` defaults to `true`, is automatic for every
  `gradle integration` run once Story 4 lands.
- **Honest limitation (OQ-020).** The *failing*-refresh half of AC-006 cannot be exercised at the
  integration tier: there is no fault-injection seam for PostgreSQL in this repository (WireMock
  covers HTTP dependencies only), so a refresh query cannot be made to fail on demand in the compose
  stack. What is actually proven is: (a) containment, at the unit tier, by 4.7 — `refresh()` cannot
  throw, so nothing reaches Spring's `TaskScheduler`; (b) thread isolation, by design and diff review
  — the refresh runs on the `scheduler-*` pool (`ShedLockConfig.taskScheduler`), never on a request
  thread; (c) the happy-path non-interference above. Do not write an integration test that pretends
  to cover the failure path. If the gate wants a live proof, the cheapest honest option is a
  temporary, explicitly-flagged experiment (e.g. revoke `SELECT` on one table in the compose DB) run
  once by hand and recorded on the ticket — not committed.

**Scenario 4.9 — The freshness gauge advances only when at least one aggregate succeeded** *(AC-007)*
- **Given** a constructed `StalledWorkMetrics`, before any refresh
- **When** (a) the registry is inspected; (b) a refresh in which both aggregates succeed runs;
  (c) a refresh in which exactly one succeeds runs; (d) a refresh in which both fail runs
- **Then** (a) `cdk.monitoring.last.refresh.epoch.seconds` exists with value `0`; (b) it advances to
  the completion time in epoch seconds; (c) it advances again — one success is enough; (d) it is
  **unchanged**, still holding (c)'s value.
- **To be proven by:** `StalledWorkMetricsTest.freshnessGauge_shouldAdvanceOnlyWhenAtLeastOneAggregateSucceeds` (new).
- **Case (d) is the point of the gauge.** Per ADR-008 it is the only detector for "the refresh has
  stopped everywhere"; a gauge that advanced on a totally failed refresh would report stale
  stalled-work values as fresh, which is worse than having no gauge.

**Scenario 4.10 — Every series exists at `0` from construction, before any refresh** *(AC-008)*
- **Given** a `StalledWorkMetrics` constructed with repositories that have never been called (or a
  context where `cdk.monitoring.enabled=false`, so the refresh job bean does not exist at all)
- **When** the registry is inspected
- **Then** all six Area-A series exist at `0`: four `cdk.documents.stalled`, one
  `cdk.queries.awaiting.answer`, one `cdk.monitoring.last.refresh.epoch.seconds` — none absent, none
  `NaN`. Meter registration is **not** gated by `cdk.monitoring.enabled`; only the scheduled job is
  (ADR-002 (6)).
- **To be proven by:** `StalledWorkMetricsTest.shouldRegisterAllSeriesAtZero_beforeAnyRefresh` (new),
  plus an `ApplicationContextRunner` assertion that with `cdk.monitoring.enabled=false` the
  `StalledWorkMetricsRefreshJob` bean is **absent** while `StalledWorkMetrics` is **present**.

**Scenario 4.11 — The Area-A metric names are present on a live scrape** *(AC-001–AC-003, AC-007, story DoD)*
- **Given** the compose stack with the monitoring overrides
- **When** `GET /actuator/prometheus` is polled with Awaitility past the 10-second refresh interval
  and the 5-second initial delay
- **Then** sample lines exist for `cdk_documents_stalled` (four `phase` values),
  `cdk_queries_awaiting_answer` and `cdk_monitoring_last_refresh_epoch_seconds` —
  the last with a value greater than `0` and within a plausible window of "now".
- **To be proven by:** `MonitoringMetricsHttpLiveTest.stalledWorkMetrics_shouldPublishAllAreaASeries` (new).
- **The seeded-value assertion is Story 5's** (Scenario 5.2), not this one — this scenario proves
  presence, shape and freshness only. Keeping value-correctness in one place avoids two tests racing
  each other over the same shared compose database.

---

## Story 5 — Cross-cutting integration coverage and quality-gate regression proof (`DD-43222`)

**Class ownership decision this spec is making (flag at the gate).** Story 4 **creates**
`MonitoringMetricsHttpLiveTest` (Scenarios 4.6, 4.11) and Story 5 **extends** it (Scenarios 5.1–5.3).
Story 5's AC-001 says "a **new** `MonitoringMetricsHttpLiveTest`" — read here as "new to the ticket",
mirroring the same create-then-extend rule the stories already apply to `CdkMeters` and
`SchedulerMetrics`. The alternative — two live-test classes both asserting the `shedlock` row and
both scraping Area-A metrics — duplicates assertions over one shared database for no gain. If the
gate prefers strict per-story class ownership, move Scenarios 4.6 and 4.11 into Story 5 wholesale;
do not split them across two classes.

---

**Scenario 5.1 — All six rendered Prometheus names are present, correctly tagged, with one series per enum value — and the meter ids match the constants** *(AC-001)*
- **Given** the compose stack with every story's production code merged
- **When** `GET /actuator/prometheus` is scraped
- **Then** all six rendered names from §"The contract under test" are present as **sample lines**
  (not merely as `# HELP` / `# TYPE` headers) — with:
  `cdk_documents_stalled` × 4 `phase` values; `cdk_queries_awaiting_answer` × 1;
  `cdk_monitoring_last_refresh_epoch_seconds` × 1; `cdk_scheduler_runs_total` × 4
  (`scheduler` × `outcome`); `cdk_scheduler_last_success_epoch_seconds` × 2;
  `cdk_scheduler_enabled` × 2 — **14 series**, each additionally carrying `service`, `cluster` and
  `region`.
- **And, in the same test:** for every meter, `GET /actuator/metrics/{id}` returns `200` when `{id}`
  is the **Micrometer** name taken from the `CdkMeters` constant (`cdk.documents.stalled`,
  `cdk.scheduler.runs`, …). `management.endpoints.web.exposure.include` already lists `metrics`, so
  this endpoint is available with no config change.
- **To be proven by:** `MonitoringMetricsHttpLiveTest.prometheusScrape_shouldExposeAllSixMonitoringMetrics_withTheirDocumentedTagSets`
  (extend the class created in Story 4).
- **This pairing is what actually proves ADR-001, and neither half does it alone.** The
  `/actuator/metrics/{CdkMeters.X}` call asserts the *registered id* using the constant production
  registers; the scrape assertion uses **hard-coded Prometheus literals**. A divergence between the
  two — a camelCased meter name rendering as `cdk_documentsStalled`, or a counter registered as
  `cdk.scheduler.runs.total` — then fails a test rather than passing silently. Using the constants on
  both sides would assert only that the constants equal themselves.
- **Also assert `cdk_scheduler_runs_total` is *not* `cdk_scheduler_runs`** (and that no
  `cdk_scheduler_runs_total_total` appears) — ADR-001's specific finding, cheap to pin.
- **Parsing notes:** label order in the exposition is not the order anyone writes by hand, so match
  with a regex or a parsed triple, never a whole-line literal. Confirm at implementation whether a
  `cdk_scheduler_runs_created` companion series is emitted on this classpath, and make the series
  count assertion tolerant of it (or assert it explicitly, once its presence is known).

**Scenario 5.2 — Seeded, backdated synthetic rows are reflected in the stuck-work gauge values** *(AC-002)*
- **Given** the compose stack (`CP_CDK_MONITORING_STALLED_THRESHOLD: PT1M`,
  `CP_CDK_MONITORING_REFRESH_INTERVAL: PT10S`) and, seeded via raw JDBC in the
  `IntradayDiscoverySchedulerLiveTest` / `IngestionProcessByCaseHttpLiveTest` idiom: N
  `case_documents` rows per monitored phase with `ingestion_phase_at` backdated well beyond the
  threshold; M `case_query_status` rows at `ANSWER_NOT_AVAILABLE` with backdated `status_at`
  (plus the one synthetic `queries` parent row `fk_cqs_query` requires)
- **When** Awaitility waits past one refresh interval and re-scrapes
- **Then** the corresponding gauge series have increased by at least the seeded counts, and the
  freshness gauge has advanced past the scrape taken before seeding
- **And** every seeded row is deleted in a `finally` block — the compose database is shared by the
  whole suite.
- **To be proven by:** `MonitoringMetricsHttpLiveTest.stalledWorkGauges_shouldReflectSeededBackdatedRows` (extend).
- **Do not assert exact equality (OQ-015).** These gauges are global counts over a database shared
  by every live test in the run, and the tags are `phase` / `status` only — there is no case or
  correlation dimension to isolate on. Other suites (`IngestionProcess*HttpLiveTest`,
  `NightlyDiscoverySchedulerLiveTest`) create `case_documents` rows in `WAITING_FOR_UPLOAD` /
  `UPLOADED`, and with a one-minute threshold any such row older than a minute joins the count.
  Integration tests run sequentially (no JUnit parallel configuration in this repo), so there is no
  *concurrent* mutation — but leftovers and same-run ordering are enough to break exact equality.
  **Use a before/after delta**: scrape → seed → wait one refresh → scrape → assert the delta is at
  least the seeded count for each affected series. Settle this at the gate; a strict-equality test
  here would be the flakiest test in the suite.
- **Threshold-boundary bonus worth including:** seed one row per monitored phase with a
  `ingestion_phase_at` of *now* (inside the threshold) and assert it does **not** contribute — the
  live-tier counterpart of Scenario 3.3.

**Scenario 5.3 — The refresh lock row is present at the full-stack level** *(AC-003)*
- Identical to Scenario 4.6. If Story 4 lands first, this AC is satisfied by that scenario and
  nothing new is written; record the cross-reference on the ticket rather than duplicating the
  assertion. If the gate moves 4.6 into Story 5, it is written here instead.

**Scenario 5.4 — The three named live tests pass with existing assertions untouched, and nothing existing was renamed** *(AC-004)*
- **Given** the complete ticket merged
- **When** `gradle integration` runs
- **Then** `ActuatorHttpLiveTest` (including `prometheus_is_exposed`, which asserts on
  `application_started_time_seconds`), `IntradayDiscoverySchedulerLiveTest` and
  `NightlyDiscoverySchedulerLiveTest` all pass **with their files absent from the PR diff**; and no
  existing metric name, actuator endpoint, exposure list, cron expression, ShedLock lock name or
  lock duration has changed anywhere.
- **To be proven by:** the `gradle integration` run **plus a diff-level check**, which is the
  stronger evidence of the two: confirm `application-server-management.yml`,
  `application-other.yml`, `config/ShedLockConfig.java`, the `scheduler.*` block of
  `application-cdk.yml` (other than the two new `enabled`-binding fields being *read*, not changed),
  and the three live-test files are all untouched. A green run is a weak negative signal — the new
  metrics are additive, so nothing existing would necessarily fail even if something had drifted.

**Scenario 5.5 — Quality gates green at unmodified thresholds** *(AC-005)*
- **Given** the complete ticket (four new metrics classes, two new config classes, two projections,
  three repository methods, `V1014`, YAML and compose changes)
- **When** `gradle clean build` runs (which includes `integration`), followed by
  `gradle pmdMain pmdTest jacocoTestReport`, CodeQL and the secrets scanner
- **Then** all pass at existing, **unmodified** thresholds, with no new PMD suppression and no
  lowered JaCoCo limit.
- **To be proven by:** the CI workflows (`ci-build-publish`, `code-analysis`, `codeql`,
  `secrets-scanner`) plus a local `gradle clean build`.
- **Two specific things to confirm rather than assume:** (a) **`CdkMeters` is a known JaCoCo
  coverage sink** — a constants class with a private constructor that throws. Handle it the way
  `util/TimeUtils` is already handled in this repo; do **not** invent a new exclusion or lower a
  threshold. (b) `catch (Exception e)` in both schedulers passes PMD, because
  `.github/pmd-ruleset.xml` enables `errorprone.AvoidCatchingThrowable` but not the `design`
  category where `AvoidCatchingGenericException` lives (design §5). If it nonetheless fires, the fix
  is a narrower catch or a reviewed rule discussion — never a suppression.

**Scenario 5.6 — No PII, case data, or real identifiers anywhere in the ticket's test material** *(AC-006)*
- **Given** `V1014`, the new unit tests, `StalledWorkQueryPlanTest`'s ~100 k seeded rows, the new
  live tests and the compose additions
- **When** the diff is reviewed
- **Then** every seeded value is synthetic — `gen_random_uuid()` / `UUID.randomUUID()` for ids,
  obviously-synthetic literals for `doc_name`, `blob_uri` and `blob_name`, no real court reference
  number, no real `CJSCPPUID`, no case content, no document body, no answer text; every metric tag
  value comes from the fixed enum/constant sets; and no new log line added by this ticket carries
  anything beyond a scheduler tag, an aggregate name and a boolean/count.
- **To be proven by:** the secrets scanner, the `block-pii` / `block-secrets` plugin hooks (which run
  on every `Write`/`Edit`, so a violation is blocked at authoring time), and explicit reviewer
  sign-off at Code Review.
- **Specific to this ticket:** the `SELECT` lists of both aggregates must be re-read at review
  against NFR-001 — no `doc_name`, no `blob_uri`, no `llm_input`, no answer text, no join to
  `answers`. The queries as designed (§7) satisfy this; the check is that they still do after
  implementation.

---

## Coverage summary — **planned**, not achieved

No row below is evidence of a passing test.

### Story 1 — `DD-43218`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 success → heartbeat + `outcome="success"` +1 | 1.1, 1.2, 1.7, 1.8, 1.10 | planned | planned | `Intraday/NightlyDiscoverySchedulerTest.run_shouldRecordSuccessExactlyOnce_…`; `SchedulerMetricsTest`; `SchedulerMetricsHttpLiveTest` |
| AC-002 exception caught, ERROR-logged, `outcome="failure"` +1, no rethrow, heartbeat unchanged | 1.3, 1.4, 1.8 | planned (2 classes) | — | `…run_shouldContainAndCountFailure_whenDiscoveryThrows` |
| AC-003 exactly one increment per invocation (`finally` + `boolean`) | 1.1, 1.3, 1.5 | planned | — | `…run_shouldRecordExactlyOneOutcomePerInvocation_whenRunsSucceedThenFail` |
| AC-004 ERROR log has no PII, structured JSON | 1.3 | planned (event shape) | — | `ListAppender` assertions + diff check for no new appender / no `System.out` |
| AC-005 all series pre-registered at `0` | 1.7 | planned | — | `SchedulerMetricsTest.shouldPreRegisterAllRunAndHeartbeatSeriesAtZero_whenConstructed` |
| AC-006 `catch (Exception)`, `Error` propagates | 1.6 | planned | — | `…run_shouldPropagateError_andStillRecordExactlyOneFailure_…` (**confirm OQ-016 first**) |
| AC-007 existing tests unchanged | 1.9 | planned (`gradle test`) | planned (`gradle integration` + diff check) | full runs + PR-diff review |

### Story 2 — `DD-43219`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 both flags off → `0`/`0`, incl. absent bean | 2.1 | planned | **not covered — OQ-014** | `SchedulerMetricsTest.enabledGauge_shouldReportZeroForBothSchedulers_…` |
| AC-002 one on, one off → `1`/`0` | 2.2, 2.7 | planned (parameterised) | partial (both `1` only) | `…enabledGauge_shouldReportPerSchedulerState_whenFlagsDiffer`; `SchedulerMetricsHttpLiveTest` |
| AC-003 INFO once per scheduler at `ApplicationReadyEvent` | 2.3 | planned | — | `…shouldLogEnabledStateOncePerScheduler_onApplicationReady` |
| AC-004 drift WARN, startup not blocked | 2.4 | planned | — | `…shouldWarn_whenBoundEnabledFlagDisagreesWithBeanPresence` |
| AC-005 `enabled` bound; Java default `true`; shipped default `false` | 2.5 | planned (2 halves) | — | `SchedulerPropertiesBindingTest` |
| AC-006 same `scheduler` tag values across all three families | 2.6 | planned | — | `…shouldUseTheSameSchedulerTagValuesAcrossAllThreeMeterFamilies` |

### Story 3 — `DD-43220`

| Story AC | Scenario(s) | Unit (Testcontainers) | Integration | Planned test |
|---|---|---|---|---|
| AC-001 `countStalledByPhase` over the ADR-004 phase set | 3.2 | planned | — | `CaseDocumentRepositoryTest.countStalledByPhase_shouldReturnOneCountPerMonitoredPhase_…` |
| AC-002 terminal phases + newer-than-cutoff excluded | 3.3 | planned | — | `…countStalledByPhase_shouldExcludeTerminalPhasesAndRowsNewerThanCutoff` |
| AC-003 `countAwaitingAnswerOlderThan`, literal status | 3.4 (count), 3.7 (literal coupling) | planned | — | `CaseQueryStatusRepositoryTest…`; `StalledWorkQueryPlanTest…` |
| AC-005 `V1014` shape, clean migrate fresh and from `V1013` | 3.1 | planned (explicit `pg_indexes` assertion + implicit chain) | — | `StalledWorkQueryPlanTest.shouldHaveCreatedBothMonitoringIndexes_afterFlywayMigration` |
| AC-006 5 s timeout; no shared transaction | 3.8 | planned (annotation-level only) | — | reflective annotation assertions + Scenario 4.7 + diff check — **see OQ-019** |
| AC-007 plan uses the intended indexes, no `Seq Scan` | 3.6, 3.7 | planned | — | `StalledWorkQueryPlanTest…` — **synthetic volume only, not AC-012 evidence** |

### Story 4 — `DD-43221`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 `cdk_documents_stalled`, one series per phase, `phase` tag only | 4.1, 4.11 | planned | planned (presence) | `StalledWorkMetricsTest…`; `MonitoringMetricsHttpLiveTest…` |
| AC-002 `cdk_queries_awaiting_answer` untagged | 4.2, 4.11 | planned | planned | as above |
| AC-004 default `PT30M`; cutoff recomputed each refresh | 4.4 | planned | — | `…shouldRecomputeCutoffFromThePropertyOnEveryRefresh` |
| AC-005 cadence ≥ 60 s (shipped YAML); own lock; `lockAtMostFor` override; `shedlock` row | 4.5, 4.6 | planned (config) | planned (lock row) | `MonitoringPropertiesTest`; `MonitoringMetricsHttpLiveTest.stalledWorkRefresh_shouldAcquireItsOwnShedLock` — **see OQ-017** |
| AC-006 per-aggregate degradation, one WARN, nothing thrown, request unaffected | 4.7, 4.8 | planned | partial (happy path only) — **OQ-020** | `…shouldDegradePerAggregate_whenOneRepositoryThrows` |
| AC-007 freshness gauge seeded `0`, advances on ≥1 success | 4.9, 4.11 | planned | planned | `…freshnessGauge_shouldAdvanceOnlyWhenAtLeastOneAggregateSucceeds` |
| AC-008 eager registration, query-then-apply | 4.10, 4.7 | planned | — | `…shouldRegisterAllSeriesAtZero_beforeAnyRefresh` |

### Story 5 — `DD-43222`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 all six rendered names, documented tag sets, constants-vs-literals cross-check | 5.1 | — | planned (**the headline test**) | `MonitoringMetricsHttpLiveTest.prometheusScrape_shouldExposeAllSixMonitoringMetrics_…` |
| AC-002 seeded backdated rows reflected in gauge values | 5.2 | — | planned (**delta-based — OQ-015**) | `…stalledWorkGauges_shouldReflectSeededBackdatedRows` |
| AC-003 `shedlock` row `stalledWorkMetricsRefresh` | 5.3 (= 4.6) | — | planned | `…stalledWorkRefresh_shouldAcquireItsOwnShedLock` |
| AC-004 three existing live tests unmodified; nothing renamed | 5.4 | — | planned + **diff-level check** | existing suites as regression |
| AC-005 build / PMD / JaCoCo / CodeQL / secrets green | 5.5 | planned | planned | `gradle clean build` + CI workflows |
| AC-006 no PII anywhere in the diff | 5.6 | planned | planned | secrets scanner, `block-pii` / `block-secrets` hooks, reviewer sign-off |

### Requirements-level ACs not fully closable in this repository

| Requirements AC | Status |
|---|---|
| AC-006 (EXPLAIN shows an index scan) | **Partially closable.** Scenarios 3.6/3.7 prove index usage at ~100 k synthetic rows. The production-scale plan is a DBA follow-up (OQ-009). |
| AC-012 (< 500 ms at production scale) | **Not closable here.** Scenario 3.6's timing bound is a CI-hardware smoke check only, explicitly labelled as such. Requires the DBA's row counts and a production-like `EXPLAIN (ANALYZE, BUFFERS)`. Design §12 recommends re-scoping this AC; Stage 4 concurs. |
| AC-020 / AC-021 (enabled gauge for a disabled/absent scheduler, at the live tier) | **Unit-tier only** unless the gate funds a second compose app configuration (OQ-014). |

### Tier notes

- **Nothing is integration-only.** Every behavioural AC has a unit-tier plan, so a failure localises
  to a class rather than to "the compose stack". The integration tier proves three things that the
  unit tier structurally cannot: the **rendered Prometheus name** (ADR-001), the **`shedlock` row**
  (AC-011/Story 4 AC-005), and the **end-to-end value flow** from real rows to a real scrape.
- **Story 3 is the only story with no integration-tier test at all,** deliberately: repository SQL,
  projection binding and query plans are all fully observable against a Testcontainers PostgreSQL,
  and the compose stack cannot say anything about plans at its data volume.
- **No contract tests, no accessibility tests** — see §"Scope boundaries".

---

## Risks and open points carried into implementation

New open questions raised by Stage 4. These are **questions, not assumptions** — none has been
resolved here, and each needs an owner's answer before the affected test is written.

- **OQ-014 (the disabled-scheduler case has no live coverage).** `docker/docker-compose.integration.yml`
  sets both `CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED` and
  `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED` to `true`, and `gradle integration` starts exactly one
  app container. Story 2's DoD asks for integration coverage of "both the default-disabled and an
  enabled-override compose configuration", which no single-stack run can provide. Decide: accept
  unit-tier-only coverage for the `0`/absent-bean case, or add a second app service with the flags
  off. — Owner: requester + the Story 2 implementer · Due: before Story 2's tests are written.
- **OQ-015 (Story 5 AC-002 cannot assert exact gauge equality).** The gauges are global counts over a
  compose database shared by the whole live suite, and their only tags are `phase` / `status` — there
  is no dimension to isolate a test's own rows on. With `CP_CDK_MONITORING_STALLED_THRESHOLD: PT1M`,
  any row another suite left behind more than a minute ago joins the count. Recommend a before/after
  **delta** assertion (`>= seeded count`) rather than equality. Confirm that satisfies AC-002's
  "gauge values match the seeded counts". — Owner: requester · Due: before Story 5's test is written.
- **OQ-016 (`Error` semantics through the `finally` block).** Design §5 puts `recordRun(...)` in
  `finally`, so an `Error` propagates (correct, AC-006) **and** still increments the failure counter
  (consistent with AC-003's exactly-once rule). Neither the design nor the story states this in
  words. Confirm it is intended, then pin it with Scenario 1.6. — Owner: design reviewer · Due:
  before Story 1's tests are written.
- **OQ-017 (`lockAtLeastFor: PT0S` vs the `lock_until > locked_at` assertion).** Design §3's compose
  override sets `CP_CDK_MONITORING_LOCK_AT_LEAST_FOR: PT0S`; ShedLock then releases the lock by
  setting `lock_until` to the unlock instant, leaving only the refresh's own (sub-millisecond)
  duration between `locked_at` and `lock_until`. Story 4 AC-005 and Story 5 AC-003 both assert a
  strict `>`. Either raise the compose value slightly (e.g. `PT1S`) or relax the assertion to
  `isAfterOrEqualTo`. — Owner: Story 4 implementer + design reviewer · Due: before Scenario 4.6 is
  written.
- **OQ-018 (the plan test must assert production's SQL, not a copy).** `StalledWorkQueryPlanTest`
  runs `EXPLAIN` over an SQL string. If that string is a hand-copied duplicate of the `@Query` value,
  the test can drift from production and stop protecting the very literal-status coupling ADR-003
  requires it to protect. Decide: a shared SQL constant referenced by both, or reflective read of the
  `@Query` annotation. — Owner: Story 3 implementer · Due: before Scenario 3.6 is written.
- **OQ-019 (Story 3 AC-006's two halves are only indirectly provable).** The 5 s statement timeout is
  asserted as annotation presence, not behaviour (no query can be made to take 5 s at test volume,
  and inventing one would test a statement production never runs). "No shared transaction" is a
  structural property proved indirectly by Scenario 4.7 plus a diff check for the absence of
  `@Transactional`. Confirm this is acceptable evidence for the AC. — Owner: requester · Due:
  Stage 4 gate.
- **OQ-020 (Story 4 AC-006's failure path has no integration seam).** There is no PostgreSQL
  fault-injection mechanism in this repository (WireMock covers HTTP only), so "an aggregate query
  fails **and** a concurrent API request is unaffected" cannot be exercised in the compose stack.
  Containment is proved at the unit tier; thread isolation is proved by design and diff review.
  Confirm that split, or fund a one-off manual experiment recorded on the ticket. — Owner: requester ·
  Due: Stage 4 gate.
- **OQ-021 (CI cost of the 100 k-row plan test).** `StalledWorkQueryPlanTest` seeds ~100 k rows into
  two tables plus `ANALYZE` on every `gradle test`, in a class with its own PostgreSQL container.
  No runtime budget is stated anywhere in Stages 1–3. Confirm the added `gradle test` time is
  acceptable, or agree a smaller documented volume / a tagged opt-in. Note the volume cannot be cut
  far: below the planner's crossover point it will legitimately choose a `Seq Scan` and the test's
  central assertion becomes false for the right reason. — Owner: Story 3 implementer + CI owner ·
  Due: before Scenario 3.6 is written.
- **OQ-022 (`MonitoringMetricsHttpLiveTest` ownership across Stories 4 and 5).** This spec assigns
  creation to Story 4 and extension to Story 5, mirroring the `CdkMeters` create-then-extend rule.
  Story 5's AC-001 says "a new `MonitoringMetricsHttpLiveTest`". Confirm the reading, or move
  Scenarios 4.6 and 4.11 into Story 5 entirely. — Owner: sprint planning · Due: before Story 4 starts.
- **OQ-023 — Resolved, 2026-08-27.** Real Jira sub-tickets `DD-43218`–`DD-43222` were created and
  linked to the parent epic DD-43185, satisfying CLAUDE.md's hard rule requiring a linked ticket
  per story before the test stage.
- **OQ-024 (`document_verification_task` has no writer) — Resolved, 2026-08-26.** Verified in this
  session: the table was referenced only by its `@Entity` class and its repository interface —
  nothing in `src/main`, `src/test` or `src/integrationTest` inserts, updates or reads a row. Design
  authority independently confirmed this via a live query
  (`select * from document_verification_task dvt order by created_at desc`) — the table is a
  Spring Batch-era leftover, superseded by the JobManager framework, with no writer at all — and
  directed that `document_verification_task` be removed from DD-43185's scope entirely rather than
  shipping a gauge over permanently-zero series. See the note at the top of this
  document. Table cleanup itself remains a separate future ticket, not resolved by this OQ.

Carried forward from earlier stages: **OQ-001** (the Jira brief was never confirmed against the
live ticket, still unresolved), **OQ-009** (**resolved 2026-09-01** — production row counts
confirmed under 100k for both tables, `V1014`'s merge gate is cleared; a formal production
`EXPLAIN` capture remains a non-blocking post-deploy follow-up), **OQ-011** (alert rules and
dashboards —
platform/SRE; without it this ticket ships signals nobody watches), **OQ-012** (security sign-off
that `/actuator/prometheus`, served on the public API port and excluded from
`cp-auth-rules-filter`, may carry these operational-volume series — required before merge).

---

## Stage-4 gate

Test Specs is a **human gate**. Do not proceed to Stage 5 (Code) until:

1. The scenarios above are approved.
2. OQ-014 – OQ-022 have decisions (OQ-023 and OQ-024 are both resolved).
