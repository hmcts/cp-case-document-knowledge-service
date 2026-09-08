# Test Specs: Operational Metrics Instrumentation (Micrometer)

> **Stage 4 — Test Specs** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43182** · Stories: [`03-stories.md`](./03-stories.md) · Design: [`02-design.md`](./02-design.md) ·
> Requirements: [`01-requirements.md`](./01-requirements.md) ·
> ADRs: [`adrs/DD-43182-operational-metrics-instrumentation.md`](../adrs/DD-43182-operational-metrics-instrumentation.md)
> (ADR-001 – ADR-010, all **Accepted** at the Stage-2 gate on 2026-09-03; GATE-1 – GATE-6 all
> accepted, including GATE-6's re-scoping of AC-024 — none of them reopened here).
>
> **Written prospectively — no implementation exists yet (A-TDD).** Nothing below is evidence of
> coverage. Every scenario states **"To be proven by:"**, which names a test *to write*, not a test
> that passed. None of the named tests can compile until Stage 5 lands the production classes
> described in design §2–§10: `metrics/MetricsSafety`, `metrics/IngestionMetrics`,
> `metrics/ExternalCallMetrics`, `metrics/OutcomeClassifier`, `metrics/AnswerGenerationMetrics`,
> `metrics/TaskRetryMetrics`, `metrics/TaskRetryDecision`, `metrics/TaskRetryMetricsAspect`,
> `metrics/HttpPoolMetricsConfig`, `metrics/CdkMetricsConfig`, `config/MetricsProperties`, and the
> `CdkMeters` extension.
>
> **This spec extends DD-43185's, it does not restate it.** DD-43185's Stage-4 spec established the
> two-tier split (unit vs compose-backed live), the `ListAppender` log-assertion idiom, the
> delta-not-equality rule for a shared compose database, and — most importantly for this ticket —
> the **negative-control rule** for metric naming (its Scenario 5.1, now implemented in
> `MonitoringMetricsHttpLiveTest`). DD-43182 introduces CDKS's **first two Timers**, whose rendered
> names go through a *different* branch of `PrometheusNamingConvention` (the `_seconds` insertion,
> ADR-001), so the same trap exists in a new form and the same control is applied to it. See
> §"The negative-control rule" below.
>
> **The one blocking finding this stage raised — now decided, 2026-09-04.** Writing Story 5's
> `timed_out` scenario and Story 6's exhaustion scenario required pinning down exactly when a task
> observes `retryAttemptsRemaining == 0`. It never does: `JobsRepository`'s assignment query
> excludes rows at `0`, and `performRetry` writes `remaining - 1`, so the final granted retry sets
> the row to `0` and the row is never selected again. CDKS's own production code already encodes
> this (`CheckIngestionStatusForAllDefendantsTask.LAST_RETRY_COUNT = 1`).
>
> **Decision on OQ-022: `cdk_task_retry_exhausted_total` is descoped entirely, and
> `cdk_answer_generation_total{outcome="timed_out"}` is descoped with it.** The follow-up trace
> confirmed `timed_out` has **no independent** detection mechanism —
> `CheckStatusOfAnswerGenerationTask`'s `PENDING` branch keeps no count of its own, and its
> `CTX_ANSWER_RETRY_COUNT` counter tracks `ANSWER_GENERATION_FAILED` re-dispatches rather than polls
> — so it shares the same unobservable state. The `catch`-path `failed` increment goes too, for the
> same reason. **`cdk_task_retry_total` is unaffected: only exhaustion *detection* is descoped, not
> retry counting.** The gap is a `task-manager-service` defect and is escalated there as a follow-up
> ticket. Reasoning, the rejected `remaining == 1` workaround and the concrete upstream ask are in
> **ADR-011**; `02-design.md` §7 and §8 carry the supersession banners and §15 indexes all fourteen
> Stage-4 decisions. **GATE-2 is withdrawn.**
>
> **All fourteen Stage-4 open questions (OQ-022 – OQ-037) now have decisions and are applied
> below.** OQ-033 alone is closed as an **implementer-time verification task** rather than an
> answer, because it is not resolvable from documents — see its entry and Scenario 2.10.
>
> **Jira linkage — resolved.** Real sub-tickets `DD-43267`–`DD-43273` were created and linked to
> the parent epic on 2026-09-04, satisfying CLAUDE.md's hard rule that every story needs a linked
> ticket before the test stage. See **OQ-037**.

---

## Scope boundaries this document inherits and does not attempt to work around

1. **No contract tests.** No API, OpenAPI model, consumer-visible schema, ACL or `version.cdk`
   change anywhere in this ticket (design §11). `src/pactVerificationTest/` is untouched and both
   consumed API artefact versions (`api-cp-crime-caseadmin-case-document-knowledge` 0.0.11,
   `api-cp-ai-rag` 0.0.15) are unchanged.
2. **No accessibility tests.** CDKS is backend-only; the WCAG 2.1 AA hard rule applies to
   downstream consumers of CDKS's API, not to a Prometheus scrape endpoint.
3. **No Flyway migration, so no migration test.** `V1014` remains the highest, `V1015` stays free;
   `migration-reviewer` has nothing to review.
4. **Production-scale scrape evidence is not deliverable from this repository** (GATE-6, ADR-005(6)).
   The compose stack's series count and scrape time are *not* production's — `http_server_requests_seconds_*`
   grows with distinct `uri` × `status` × `method` × `outcome` combinations and the live suite
   exercises fewer of them than production does, so the compose number is a **lower** bound. Every
   scenario that touches AC-024 states this in its own assertion message; none claims to close the
   original "under 1 second / under 2,000 series per pod" wording. The one-off production timing is
   a Stage-8 `deploy-notes.md` capture.
5. **Two test tiers, deliberately distinct** (DD-43185's precedent, unchanged). Meter registration,
   increment arithmetic, tag computation, exception classification, the retry predicate, the aspect's
   advice and failure containment are all **unit tier** (`src/test/`, JUnit 5 + Mockito + AssertJ over
   a `SimpleMeterRegistry`). Only three things are **integration tier** (`src/integrationTest/`,
   compose-backed, extending `AbstractHttpLiveTest`): the **rendered Prometheus name and tag set**,
   the **end-to-end value flow** from real work to a real scrape, and the **library-behaviour anchor**
   for the replicated retry predicate. No third pattern is invented.
6. **Alert rules, recording rules, dashboards and SLO definitions are out of scope** (OQ-019). The
   PromQL expressions in design §6/§8 are documentation handed to platform/SRE, not test subjects.

---

## The contract under test — names, tags and values (design §2, ADR-001)

Micrometer meter names are what production **registers** (via `CdkMeters` constants); Prometheus
names are what a **scrape renders** and what alert rules in another repository will be written
against. **Both forms must be asserted, and never from the same source** — see the next section.

| # | Micrometer name (`CdkMeters` constant) | Rendered Prometheus name(s) | Type | Ticket tags | Registered / worst case | Story |
|---|---|---|---|---|---|---|
| 1 | `cdk.document.ingestion.phase` | `cdk_document_ingestion_phase_total` | Counter | `phase`, `source` | 5 / 10 | 1 |
| 2 | `cdk.document.ingestion.duration` | `cdk_document_ingestion_duration_seconds_{bucket,count,sum}`, `…_max` | Timer + 8 SLOs | `phase` | 36 / 36 | 2 |
| 3 | `cdk.external.call.duration` | `cdk_external_call_duration_seconds_{count,sum}`, `…_max` | Timer, no buckets | `dependency`, `operation`, `outcome` | 33 / 165 | 3 |
| 4 | `httpcomponents.httpclient.pool.*` (framework binder — **not** a `CdkMeters` constant) | `httpcomponents_httpclient_pool_total_max`, `…_total_connections{state}`, `…_total_pending`, `…_route_max_default` | 4 Gauges | `httpclient="cdk"`, `state` | 5 / 5 | 4 |
| 5 | `cdk.http.pool.connections.leased` | `cdk_http_pool_connections_leased` | Gauge | — | 1 / 1 | 4 |
| 6 | `cdk.answer.generation` | `cdk_answer_generation_total` | Counter | `outcome`, `query_level` | 8 / 8 | 5 |
| 7 | `cdk.task.retry` | `cdk_task_retry_total` | Counter | `task_name`, `retry_policy` | 7 / 7 | 6 |
| | **Total added** | | | | **95 / 232** | 7 |

> **Amended 2026-09-04 (OQ-022 / ADR-011).** Row 8 was `cdk.task.retry.exhausted` /
> `cdk_task_retry_exhausted_total` (7 / 7) and is **withdrawn — not built**. Row 6 loses its
> `timed_out` `outcome` value (12 → 8). **Seven** rendered Prometheus names, not eight; **95
> registered / 232 worst case**, not 106 / 243. Both withdrawn signals are asserted **absent** —
> see the negative-control table below and Scenarios 5.1, 6.10, 6.15, 7.1, 7.2.

Plus DD-43185's **six meters / 14 series, unchanged** (NFR-005, Story 7 AC-008). Combined worst case
**246** (257 before ADR-011). The global `service` / `cluster` / `region` common tags from
`application-server-management.yml` apply on top of every row and add no series — this ticket
**asserts** them, it does not add them.

**Fixed, closed tag-value sets. No scenario below may introduce a value outside these:**

| Tag key | Permitted values | Count | Source of truth |
|---|---|---|---|
| `phase` (counter, meter 1) | `WAITING_FOR_UPLOAD`, `UPLOADED`, `INGESTED`, `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT` | 5 | the **five reachable** `DocumentIngestionPhase` constants, verbatim (ADR-009(4)) |
| `phase` (timer, meter 2) | `INGESTED`, `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT` | 3 | the three terminal phases (ADR-002(2)) |
| `source` | `IDPC`, `unknown` | 2 | allow-list membership check on `CaseDocument.source` (ADR-009(1)) |
| `dependency` | `rag`, `progression`, `hearing`, `azure_blob` | 4 | ticket literals |
| `operation` | `initiate-document-upload`, `document-status-by-reference`, `answer-user-query-async`, `answer-user-query-status`, `answer-user-query`, `get-court-documents`, `get-court-documents-all-defendants`, `get-material-download-url`, `get-hearings-and-cases`, `get-hearing-cases-for-day`, `copy-from-url` | 11 | CDKS-invented kebab-case `CdkMeters` constants, literal arguments (ADR-004) |
| `outcome` (meter 3) | `success`, `client_error`, `server_error`, `timeout`, `error` | 5 | ticket literals + `error` (GATE-1) |
| `outcome` (meter 6) | `succeeded`, `failed` | 2 | ticket literals minus the withdrawn `timed_out` (ADR-007 as amended, ADR-011). **`failed` from a new `CdkMeters.OUTCOME_FAILED`, never DD-43185's `OUTCOME_FAILURE` — OQ-031** |
| `query_level` | `CASE`, `DEFENDANT`, `CASE_ALL_DOCUMENTS`, `unknown` | 4 | `QueryLevel` constants verbatim + `unknown` (ADR-007(3)) |
| `task_name` | the 7 `TaskNames` constants | 7 | `TaskNames` verbatim, membership-checked (ADR-006(3)) |
| `retry_policy` | `default-retry`, `verify-document-status`, `questions-retry`, `none` | 4 | config keys, kebab-case; determined by `task_name` (GATE-3) |
| `httpclient`, `state` | `cdk`; `available`, `leased` | 1, 2 | framework binder (ADR-008) |

`(dependency, operation)` is **11 pairs, not 44** — `dependency` is functionally determined by
`operation`. `retry_policy` is functionally determined by `task_name`, so it adds a label to seven
series (one counter's worth after ADR-011, two before) and no new series. Both facts are asserted,
not assumed (Scenarios 3.1, 6.9).

**Three spelling collisions inside one tag key, which the tests must not paper over.** The `outcome`
tag key is now shared across three meters with three different vocabularies: DD-43185's
`cdk.scheduler.runs` uses `success` / **`failure`**; DD-43182's external-call timer uses `success` /
`client_error` / `server_error` / `timeout` / `error`; DD-43182's answer counter uses
**`succeeded`** / **`failed`** / `timed_out`. ADR-001(5) flagged `success` vs `succeeded` and
deliberately kept both. It did **not** flag `failure` vs `failed`, and `CdkMeters.OUTCOME_FAILURE`
(`"failure"`) already exists and will compile silently wherever `failed` is meant.
**OQ-031, decided 2026-09-04: all three spellings stay, and `failure` and `failed` are two distinct
values each behind its own clearly-named constant** — `OUTCOME_FAILURE` (`"failure"`, DD-43185's
scheduler counter) and a **new, distinctly named `OUTCOME_FAILED`** (`"failed"`, this ticket's
answer-generation counter), declared adjacently with a Javadoc sentence on each naming the meter it
belongs to so they cannot be confused or cross-used. Renaming either after alert rules exist is the
cross-repository one-way door, so both are frozen. **Every test therefore asserts the literal
string, never the constant** (Scenario 5.1).

---

## The negative-control rule (ADR-001, extended to Timers)

DD-43185's Scenario 5.1 established the rule and the implemented
`MonitoringMetricsHttpLiveTest` carries it as a field-level comment: **the rendered Prometheus names
are hard-coded string literals in the test, never derived from the `CdkMeters` constants production
registers with.** Deriving them (`meterName.replace('.', '_')`) makes the test self-fulfilling — a
typo'd meter name computes the same typo and still passes. The paired
`GET /actuator/metrics/{id}` check uses the `CdkMeters` constant for the *registered* id. **Neither
half proves ADR-001 alone; the pair does.** Using constants on both sides asserts only that the
constants equal themselves.

DD-43182 is subject to the identical risk in a **new** form, and this is the single most important
thing this spec adds to the precedent. `PrometheusNamingConvention.name(...)` has a
`Meter.Type.TIMER` branch that appends `_seconds` behind an `endsWith` guard (ADR-001 context). So:

- a Timer registered as `cdk.external.call.duration` renders `cdk_external_call_duration_seconds_count`;
- a Timer registered as `cdk.external.call.duration.seconds` renders **identically**, purely through
  the guard — which is the reliance ADR-001(1) forbids, by the same argument DD-43185 used against
  `cdk.scheduler.runs.total`.

A derived-name test cannot tell those two apart. **Every rendered-name assertion in this document
therefore uses literals, and every registered-id assertion uses `CdkMeters`.** Both directions are
also asserted negatively, because a passing positive assertion is compatible with the wrong name
also being present:

| Must be present (literal) | Must be absent (literal) | Catches |
|---|---|---|
| `cdk_document_ingestion_phase_total{` | `cdk_document_ingestion_phase{`, `…_total_total` | `.total` in the registered name; double suffix |
| `cdk_answer_generation_total{` | `cdk_answer_generation{`, `…_total_total` | as above |
| `cdk_task_retry_total{` | `cdk_task_retry{`, `…_total_total` | as above |
| — | **`cdk_task_retry_exhausted` and `cdk_task_retry_exhausted_total` — absent in *any* form** | **the descoping itself (OQ-022 / ADR-011).** A cheap guard that stops a withdrawn meter being re-introduced by a later merge without a decision |
| — | **`timed_out` — absent as an `outcome` value on `cdk_answer_generation_total`** | as above, for the withdrawn tag value |
| `cdk_document_ingestion_duration_seconds_bucket{`, `…_seconds_count`, `…_seconds_sum`, `…_seconds_max` | `cdk_document_ingestion_duration_bucket`, `cdk_document_ingestion_duration_count`, `…_seconds_seconds` | **the Timer `_seconds` rule, both directions** |
| `cdk_external_call_duration_seconds_count{`, `…_seconds_sum{`, `…_seconds_max{` | `cdk_external_call_duration_count`, `…_seconds_seconds`, any `…_bucket` | the Timer rule **and** ADR-005(4)'s "no buckets on this timer" |
| `cdk_http_pool_connections_leased{` | — | GATE-4's alias actually shipping |

The `…_bucket`-absent assertion on `cdk.external.call.duration` is worth calling out separately: it
is the only automated guard that ADR-005's series budget was actually implemented as designed rather
than `percentiles-histogram` being left at a default. It is cheap and it is the difference between
33 series and several thousand.

---

## Test inventory — files to create or extend

| Tier | File | New / extend | Story |
|---|---|---|---|
| Unit | `src/test/java/.../metrics/IngestionMetricsTest.java` | **new** (1) / extend (2) | 1, 2 |
| Unit | `src/test/java/.../services/IdpcAvailabilityServiceTest.java` | extend | 1 |
| Unit | `src/test/java/.../jobmanager/caseflow/RetrieveMaterialAndUploadTaskTest.java` | extend | 1 |
| Unit | `src/test/java/.../jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTaskTest.java` | extend | 1, 2 |
| Unit | `src/test/java/.../metrics/OutcomeClassifierTest.java` | **new** | 3 |
| Unit | `src/test/java/.../metrics/ExternalCallMetricsTest.java` | **new** | 3 |
| Unit | `src/test/java/.../clients/rag/ApimDocumentIngestionClientTest.java` | extend | 3 |
| Unit | `src/test/java/.../clients/rag/ApimDocumentIngestionStatusClientTest.java` | extend | 3 |
| Unit | `src/test/java/.../clients/rag/RagAnswerAsyncServiceImplTest.java` | extend | 3 |
| Unit | `src/test/java/.../clients/rag/RagAnswerServiceImplTest.java` | **new — no such class exists today** (OQ-036) | 3 |
| Unit | `src/test/java/.../clients/hearing/HearingClientImplTest.java` | extend | 3 |
| Unit | `src/test/java/.../clients/progression/ProgressionClientImplTest.java` | extend | 3 |
| Unit | `src/test/java/.../storage/AzureBlobStorageServiceTest.java` | extend | 3 |
| Unit | `src/test/java/.../metrics/HttpPoolMetricsConfigTest.java` | **new** | 4 |
| Unit | `src/test/java/.../metrics/AnswerGenerationMetricsTest.java` | **new** | 5 |
| Unit | `src/test/java/.../jobmanager/queryflow/CheckStatusOfAnswerGenerationTaskTest.java` | extend | 5 |
| Unit | `src/test/java/.../jobmanager/queryflow/GenerateAnswerForQueryTaskTest.java` | extend | 5 |
| Unit | `src/test/java/.../metrics/TaskRetryDecisionTest.java` | **new** | 6 |
| Unit | `src/test/java/.../metrics/TaskRetryMetricsTest.java` | **new** | 6 |
| Unit | `src/test/java/.../metrics/TaskRetryMetricsAspectTest.java` | **new** | 6 |
| Unit | `src/test/java/.../metrics/TaskRetryMetricsAspectConditionalTest.java` | **new** (mirrors the existing `StalledWorkMetricsRefreshJobConditionalTest`) | 6 |
| Unit | `src/test/java/.../jobmanager/support/JobManagerRetryPropertiesTest.java` | **new** | 6 |
| Unit | `src/test/java/.../metrics/MetricsSafetyTest.java` | **new** | 7 |
| Unit | `src/test/java/.../config/MetricsPropertiesTest.java` | **new** | 7 |
| Unit (context) | `src/test/java/.../metrics/IngestionDurationSloOverrideTest.java` | **new** (`@SpringBootTest` slice, property override — **OQ-033**) | 2 |
| Integration | `src/integrationTest/java/.../metrics/OperationalMetricsHttpLiveTest.java` | **new** in whichever of 1–6 lands first, **extended** by each later story, **owned** by 7 | 1–7 |
| Integration | `src/integrationTest/java/.../metrics/ExternalCallMetricsHttpLiveTest.java` | **new** | 3 |
| Integration | `src/integrationTest/java/.../metrics/HttpPoolMetricsHttpLiveTest.java` | **new** | 4 |
| Integration | `src/integrationTest/java/.../metrics/TaskRetryHttpLiveTest.java` | **new** — renamed from `TaskRetryExhaustionHttpLiveTest`, re-aimed at the surviving counter (ADR-011, OQ-028) | 6 |
| Integration | `src/integrationTest/java/.../metrics/PrometheusSeriesBudgetHttpLiveTest.java` | **new** | 7 |
| Integration | `ActuatorHttpLiveTest`, `MonitoringMetricsHttpLiveTest`, `SchedulerMetricsHttpLiveTest` | **unmodified — run as regression** | 7 |
| Integration | `IngestionProcessHttpLiveTest`, `IngestionProcessByCaseHttpLiveTest`, `IngestionStatusHttpLiveTest`, `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest`, `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` | **unmodified assertions — the real safety net for ADR-006's CGLIB proxying** | 6, 7 |
| ~~Config~~ | ~~`docker/docker-compose.integration.yml`~~ | **No change — settled 2026-09-04. OQ-026/OQ-027: no read-timeout override (unit-tier-only `outcome=timeout`). OQ-028: seed `jobs.retry_attempts_remaining` via JDBC instead of shortening a shared retry budget.** The compose file is untouched by this ticket. | — |
| Artefact | `docs/pipeline/DD-43182-.../baseline-series-count.md` | **new, captured when Story 7 is built, from a DD-43182-free commit (OQ-034)** | 7 |

**Class-ownership decision this spec is making (flag at the gate).** `OperationalMetricsHttpLiveTest`
is the single live-test class that scrapes and asserts the whole DD-43182 metric surface. Whichever
of Stories 1–6 lands first **creates** it with its own story's names; each later story **extends** it;
Story 7 **owns** the cross-cutting assertions (all **seven** names together — eight before ADR-011 —
common tags, tag-value membership, and the two withdrawn signals asserted absent). This mirrors the create-then-extend rule the stories already apply to `CdkMeters`,
`MetricsSafety` and `TaskRetryDecision`, and it avoids six live-test classes all scraping one
endpoint against one shared database. The alternative — strict per-story class ownership — is a
reviewer's call; if the gate prefers it, move the Story-1..6 scrape scenarios into Story 7 wholesale
rather than splitting them across seven classes.

**Naming convention** (house style, matching each class's existing style):
- Unit — `<method>_should<Outcome>_when<Condition>` or `should<Outcome>_when<Condition>`; one style
  per class.
- Live — `<subject>_<behaviour>` (e.g. `prometheusScrape_shouldExposeAllEightOperationalMetrics`).

**Log assertions use the established in-repo idiom**: a logback `ListAppender<ILoggingEvent>`
attached to the class logger, as `DiscoveryTriggerServiceTest` does. No new logging test library.

**Shared-registry idiom.** Unit tests use a real `SimpleMeterRegistry` where the assertion is a
meter *value*, and a `@Mock` collaborator where the assertion is "this method was called with these
arguments" — the same split DD-43185's spec drew, so a failure localises to one class.

---

## Story 1 — Document-ingestion phase counter (`DD-43267`)

Targets `metrics/IngestionMetrics` (phase-counter half), and the metric call added after
`saveAndFlush` at the three — and only three — phase-write sites (design §3, ADR-009).

**Shared Given for 1.1–1.9:** a real `SimpleMeterRegistry`, an `IngestionMetrics` constructed over
it, and synthetic `CaseDocument` fixtures (random UUIDs, obviously-synthetic `doc_name` / `blob_uri`,
no court reference, no real `CJSCPPUID`).

---

**Scenario 1.1 — All five phase series exist at zero the moment `IngestionMetrics` is constructed** *(AC-005, AC-002)*
- **Given** a fresh `SimpleMeterRegistry` and nothing yet ingested
- **When** `IngestionMetrics` is constructed
- **Then** five counters exist, one per `phase` ∈ {`WAITING_FOR_UPLOAD`, `UPLOADED`, `INGESTED`,
  `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT`} × `source="IDPC"`, each with `count() == 0`; and
  `source="unknown"` series are **not** pre-registered (ADR-009(1) — they materialise only if
  emitted).
- **To be proven by:** `IngestionMetricsTest.shouldPreRegisterAllFivePhaseSeriesAtZero_whenConstructed`.
- **Why zero and not absent:** DD-43185's rule, carried forward — `increase(...) == 0` over an
  absent series returns *no data* and silently defeats the alert.

**Scenario 1.2 — The `WAITING_FOR_UPLOAD` write site increments exactly once** *(AC-001)*
- **Given** `IdpcAvailabilityService.persistCaseDocument(...)` about to persist a new `CaseDocument`
- **When** the `saveAndFlush` returns
- **Then** `recordPhaseTransition(WAITING_FOR_UPLOAD, "IDPC")` is invoked exactly once and
  `cdk.document.ingestion.phase{phase="WAITING_FOR_UPLOAD",source="IDPC"}` increments by 1; no other
  phase series moves.
- **To be proven by:** `IdpcAvailabilityServiceTest.persistCaseDocument_shouldRecordExactlyOnePhaseTransition`
  (extend) with `verify(ingestionMetrics, times(1))` and `verifyNoMoreInteractions(...)`.

**Scenario 1.3 — A multi-field write in one flush still increments exactly once** *(AC-001)*
- **Given** `RetrieveMaterialAndUploadTask.saveDocumentUploaded(...)`, which sets `docName`,
  `blobUri`, `contentType`, `sizeBytes`, `uploadedAt` and `ragDocumentReference` **in the same
  `saveAndFlush`** as `ingestionPhase = UPLOADED`
- **When** the flush returns
- **Then** `cdk.document.ingestion.phase{phase="UPLOADED"}` increments by exactly **1**, not once per
  dirty property.
- **To be proven by:** `RetrieveMaterialAndUploadTaskTest.saveDocumentUploaded_shouldRecordExactlyOnePhaseTransition_whenSeveralFieldsChangeInOneFlush` (extend).
- **This is the explicit negative control for design §3's rejected `@PostUpdate` mechanism.** A JPA
  entity listener would fire on any update to the entity and cannot see the previous value, so it
  would over-count here. The scenario exists so that a future "improvement" to an entity listener
  fails a test rather than quietly inflating the metric.

**Scenario 1.4 — Each of the three terminal phases increments its own series once, and a poll that does not write increments nothing** *(AC-001)*
- **Given** `CheckIngestionStatusForAllDefendantsTask`, which polls RAG repeatedly and only calls
  `updateIngestionPhase(...)` on a terminal answer
- **When** the task runs N times returning `PENDING` and then once with a terminal status
- **Then** the phase counter is untouched for the N polls, and increments by exactly 1 on the
  terminal write — parameterised over `INGESTED`, `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT`, each landing
  on its own series.
- **To be proven by:** `CheckIngestionStatusForAllDefendantsTaskTest.updateIngestionPhase_shouldRecordOneTransitionPerTerminalWrite`
  and `…_shouldRecordNothing_whenStatusIsStillPending` (extend, parameterised).
- **This is AC-001's "not once per enclosing task invocation" clause**, and it is the clause most
  likely to be got wrong by an implementer who puts the call at the top of `execute`.

**Scenario 1.5 — A failed write records nothing** *(AC-001)*
- **Given** any of the three sites, with `saveAndFlush` throwing (e.g. a
  `DataIntegrityViolationException`)
- **When** the method runs
- **Then** the exception propagates unchanged **and** no counter increments — because the metric call
  sits *after* the flush, not before it.
- **To be proven by:** one test per site (`…_shouldNotRecordAnyTransition_whenSaveFails`).
- **Note the deliberate accepted gap** (design §3, reason 2): the call is post-flush but
  pre-commit, so a transaction that rolls back *after* a successful flush still increments. That is
  an accepted cost of declining the `TransactionSynchronizationManager` `afterCommit` callback, and
  no scenario here claims otherwise.

**Scenario 1.6 — `source` resolves through the allow-list and can never be read through as free text** *(AC-003, AC-004)*
- **Given** `recordPhaseTransition(phase, rawSource)` for each of: `"IDPC"`, `null`, `""`, `"   "`,
  `"idpc"` (wrong case), `"CROWN"`, a 200-character synthetic string, and a string containing a
  synthetic UUID
- **When** each is recorded
- **Then** only `"IDPC"` yields `source="IDPC"`; **every** other input — including the lowercase
  variant — yields `source="unknown"`; the registry never gains a third `source` value; and no tag
  value ever contains the raw input.
- **To be proven by:** `IngestionMetricsTest.shouldResolveSourceThroughTheAllowList` (parameterised).
- **Case sensitivity is an explicit assertion, not an incidental one.** ADR-009(1) says "membership
  check", which is exact-match; if the implementation lowercases or trims first, this test says so
  rather than a reviewer noticing.

**Scenario 1.7 — The three unreachable phases are neither registered nor emittable** *(AC-002)*
- **Given** `CdkMeters` already contains `PHASE_UPLOADING` and `PHASE_INGESTING` (from DD-43185's
  stall gauge, which legitimately uses them) and nothing structurally prevents the phase counter
  from using them
- **When** `IngestionMetrics` is constructed and every write path is exercised
- **Then** no series exists for `phase="UPLOADING"`, `phase="INGESTING"` or `phase="NOT_FOUND"`, and
  the set of `phase` values ever emitted is **exactly** the five reachable ones.
- **To be proven by:** `IngestionMetricsTest.shouldNotRegisterTheThreeUnreachablePhases` — asserting
  the registered `phase` tag-value set with `containsExactlyInAnyOrder(...)`, not merely that the
  five are present.
- **This is the guard for ADR-009(4)**, which is deliberately the *opposite* of DD-43185 ADR-004's
  ruling for the stall gauge. Two constants sitting in the same class under the same rule, with
  opposite correct answers, is exactly the situation a test has to pin.
- **OQ-032 — closed 2026-09-04: this scenario, as written, *is* the accepted guard.** The
  `containsExactlyInAnyOrder(...)` assertion on the registered `phase` tag-value set fails if
  `PHASE_UPLOADING` or `PHASE_INGESTING` is ever picked up by the phase counter, which is sufficient.
  **No structural restructuring of `CdkMeters` is required or wanted** — the alternative floated at
  Stage 4 (a nested `Phases.Reachable` / `Phases.Monitored` split so the mistake could not compile)
  would churn DD-43185's shipped constants to prevent a mistake a one-line assertion already
  catches. Write the assertion as a set equality, not as "the five are present"; that distinction is
  the whole guard.

**Scenario 1.8 — The tag-key set is exactly `{phase, source}`** *(AC-002, AC-004)*
- **Given** every registered phase counter
- **When** its `Meter.Id.getTags()` is read (unit tier, before common tags are applied)
- **Then** the ticket-specific keys are exactly `phase` and `source` — no `case_id`, `doc_id`,
  `defendant_id`, `material_id`, `courtdoc_id`, court centre/room id, `CJSCPPUID`, RAG transaction
  id, blob URI or document name, and no extra dimension of any kind.
- **To be proven by:** `IngestionMetricsTest.shouldCarryExactlyThePhaseAndSourceTags`.

**Scenario 1.9 — A throwing registry leaves the business write untouched** *(AC-006)*
- **Given** an `IngestionMetrics` over a `MeterRegistry` whose counter `increment()` throws, or an
  allow-list lookup stubbed to throw
- **When** each of the three write sites runs
- **Then** the write completes, the method returns exactly what it would without instrumentation,
  the persisted phase is unchanged, no exception escapes from the recording, and at most one WARN is
  logged (the throttle itself is Story 7's Scenario 7.9).
- **To be proven by:** `IngestionMetricsTest.shouldContainRecordingFailure_andNotDisturbTheCaller`
  plus one assertion per site.
- **`Error` is not contained** — see Scenario 7.9; consistent with DD-43185 §5 and PMD's
  `errorprone.AvoidCatchingThrowable`.

**Scenario 1.10 — The rendered name and tag set are correct on a real scrape** *(AC-002, AC-005)*
- **Given** the compose stack with Story 1's code merged
- **When** `GET /actuator/prometheus` is scraped
- **Then** `cdk_document_ingestion_phase_total{` is present as **sample lines** (not merely
  `# HELP` / `# TYPE` headers), with exactly five `phase` label values and `source="IDPC"`, each
  additionally carrying `service`, `cluster` and `region`;
  **and** `GET /actuator/metrics/cdk.document.ingestion.phase` (the `CdkMeters` constant) returns
  `200`;
  **and** the negative controls hold: no `cdk_document_ingestion_phase{`, no
  `…_total_total`, no `phase="UPLOADING"`, no `phase="INGESTING"`.
- **To be proven by:** `OperationalMetricsHttpLiveTest.ingestionPhaseCounter_shouldRenderWithItsDocumentedTagSet`.
- **Parsing note:** label order in the exposition is not the order anyone writes by hand — match with
  a regex or a parsed label set, never a whole-line literal. Follow
  `MonitoringMetricsHttpLiveTest`'s existing `Pattern`/`Matcher` idiom. Confirm at implementation
  whether a `…_created` companion series is emitted on this classpath and make the count assertion
  tolerant of it, or assert it explicitly once its presence is known.

**Scenario 1.11 — A real ingestion moves the counter** *(AC-001, end-to-end)*
- **Given** the compose stack and the existing `IngestionProcessByCaseHttpLiveTest` flow (WireMock
  Hearing/Progression/RAG stubs plus Azurite), with a baseline scrape taken first
- **When** one synthetic case is driven through `/ingestions/start-by-case` and Awaitility waits for
  the workflow to reach a terminal phase
- **Then** the `phase="WAITING_FOR_UPLOAD"`, `phase="UPLOADED"` and terminal-phase series have each
  increased by **at least** one relative to the baseline.
- **To be proven by:** `OperationalMetricsHttpLiveTest.ingestionPhaseCounter_shouldIncrementForARealIngestion`.
- **Delta, never equality** — DD-43185's OQ-015 rule, unchanged: the compose Postgres is shared by
  the whole live suite and this counter has no case dimension to isolate on. Assert
  `>= baseline + 1`.

---

## Story 2 — Ingestion duration timer, end to end (`DD-43268`)

Targets `metrics/IngestionMetrics` (timer half) and the second call added inside
`CheckIngestionStatusForAllDefendantsTask.updateIngestionPhase(...)` (design §4, ADR-002).

---

**Scenario 2.1 — Three terminal timers exist at construction, with the eight SLO boundaries** *(AC-003, AC-005)*
- **Given** a fresh `SimpleMeterRegistry`
- **When** `IngestionMetrics` is constructed
- **Then** exactly three `Timer`s exist, `phase` ∈ {`INGESTED`, `FAILED`,
  `EXCEEDED_FILE_SIZE_LIMIT`}, each with `count() == 0`; each timer's
  `takeSnapshot().histogramCounts()` reports the eight boundaries `15s, 30s, 1m, 2m, 5m, 10m, 30m,
  1h` (from `CdkMeters` `Duration` constants); `publishPercentileHistogram` is **false** and no
  client-side `percentiles` are configured.
- **To be proven by:** `IngestionMetricsTest.shouldRegisterThreeTerminalTimersWithTheEightSloBoundaries_whenConstructed`.
- **The percentiles half matters as much as the buckets half.** FR-004 requires *server-side*,
  aggregatable percentiles; pre-computed per-pod `percentiles` satisfy the words "p99 is available"
  while making the number wrong across pods. Assert their absence explicitly.

**Scenario 2.2 — A terminal transition records one observation equal to the computed persisted interval** *(AC-001)*
- **Given** a loaded `CaseDocument` with `created_at = T0` and, after the terminal write,
  `ingestion_phase_at = T2` (synthetic instants, `T2 - T0` a known non-trivial value such as
  `PT7M30S`)
- **When** `recordIngestionDuration(INGESTED, T0, T2)` is called after the `saveAndFlush`
- **Then** the `phase="INGESTED"` timer's `count()` is 1 and its `totalTime(SECONDS)` equals
  `Duration.between(T0, T2)` to within the registry's resolution; the other two timers are untouched.
- **To be proven by:** `IngestionMetricsTest.shouldRecordTheComputedPersistedInterval_whenTerminalPhaseIsWritten`.
- **The mechanism is asserted structurally, not just the value.** ADR-002's whole point is that this
  is **`Timer.record(Duration)` from two persisted timestamps and never a `Timer.Sample`** — the
  start and terminal writes are in different tasks, potentially on different pods, minutes to hours
  apart. The test pins that by (a) the method signature taking two `OffsetDateTime`s rather than
  returning a sample handle, and (b) asserting `IngestionMetrics` holds **no** per-document pending
  state — a second, interleaved document's terminal write records its own interval correctly with no
  cross-talk, which an in-process sample map would fail.

**Scenario 2.3 — All three terminal stops record, each on its own series** *(AC-001)*
- **Given** three synthetic documents reaching `INGESTED`, `FAILED` and `EXCEEDED_FILE_SIZE_LIMIT`
  respectively, with different intervals
- **When** each terminal write happens
- **Then** each records exactly one observation on its own `phase`-tagged timer.
- **To be proven by:** `IngestionMetricsTest.shouldRecordOnAllThreeTerminalPhases` (parameterised).
- **`EXCEEDED_FILE_SIZE_LIMIT` is a first-class case, not an afterthought** (ADR-002(2)) — the
  ticket's own scenario omits it, and excluding it would silently drop the observations for
  oversized documents, whose latency profile is genuinely different.

**Scenario 2.4 — A non-terminal phase records nothing** *(AC-002)*
- **Given** `recordIngestionDuration(WAITING_FOR_UPLOAD, …)` and
  `recordIngestionDuration(UPLOADED, …)`
- **When** each is called
- **Then** all three timers stay at `count() == 0` — the map lookup simply misses, and no branch is
  needed at either non-terminal write site.
- **To be proven by:** `IngestionMetricsTest.shouldRecordNoObservation_whenPhaseIsNotTerminal`
  (parameterised over the two non-terminal reachable phases).

**Scenario 2.5 — A negative computed duration is clamped to zero and warns once** *(AC-004)*
- **Given** `terminalAt` strictly **before** `createdAt` (cross-pod clock skew, the one place in this
  ticket that cannot use a monotonic clock)
- **When** `recordIngestionDuration(INGESTED, createdAt, terminalAt)` is called
- **Then** exactly one observation is recorded with value `Duration.ZERO`; `totalTime` does not
  decrease; and one throttled WARN is emitted whose message identifies the clamp and contains **no**
  case id, doc id or any other identifier.
- **To be proven by:** `IngestionMetricsTest.shouldClampNegativeDurationToZero_andWarnOnce` with a
  `ListAppender` on the `MetricsSafety` (or `IngestionMetrics`) logger.
- **A clamp firing is itself a signal** (ADR-002(4)) — hence a WARN rather than silence. Assert the
  WARN, not just the clamp.

**Scenario 2.6 — Null timestamps record nothing and throw nothing** *(AC-006)*
- **Given** `createdAt == null`, or `terminalAt == null`, or both
- **When** the method is called
- **Then** nothing is recorded, nothing is thrown, and the calling task's return value is unchanged.
- **To be proven by:** `IngestionMetricsTest.shouldRecordNothing_whenEitherTimestampIsNull`
  (parameterised).
- `created_at` is `NOT NULL` in `V1001` and is written once at `IdpcAvailabilityService:116`
  (verified in ADR-002), so this is defence in depth rather than an expected path — but it is a named
  failure mode in ADR-010's list and must not be able to fail a terminal phase write.

**Scenario 2.7 — The timer's success bias is stated where a reader will see it** *(AC-002)*
- **Given** a document that never reaches a terminal phase
- **When** the whole pipeline runs
- **Then** it contributes **no observation, ever** — so `_count` is *completed* ingestions, not
  *started* ones, and this timer structurally cannot detect a stall;
  **and** `CdkMeters`' Javadoc names DD-43185's `cdk_documents_stalled{phase="UPLOADED"}` as the
  complement, and states that the interval includes JobManager queue-and-retry latency and is
  therefore **not** RAG's latency and must not be read as an upstream SLO.
- **To be proven by:** the behavioural half by `IngestionMetricsTest` (Scenario 2.4 already proves
  the non-terminal case records nothing); the documentation half by a **diff-level check at Code
  Review**, not by a unit test. State both; do not claim a test proves a Javadoc sentence.

**Scenario 2.8 — A throwing timer leaves the terminal write untouched** *(AC-006)*
- **Given** a registry whose `Timer.record(...)` throws, or a `Duration.between` on an unexpected
  value
- **When** `CheckIngestionStatusForAllDefendantsTask.updateIngestionPhase(...)` runs
- **Then** the persisted phase, the returned `ExecutionInfo` and the task's own logging are all
  exactly as they would be without instrumentation.
- **To be proven by:** `CheckIngestionStatusForAllDefendantsTaskTest.updateIngestionPhase_shouldCompleteNormally_whenDurationRecordingThrows` (extend).

**Scenario 2.9 — The `_bucket` series are actually on the scrape, with the eight boundaries plus `+Inf`** *(AC-003)*
- **Given** the compose stack with Story 2's code merged
- **When** `GET /actuator/prometheus` is scraped
- **Then** `cdk_document_ingestion_duration_seconds_bucket{` is present with `le` values
  `15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0, +Inf` **per `phase`** — nine `_bucket`
  series × 3 phases — plus `…_seconds_count`, `…_seconds_sum` and `…_seconds_max` per phase (36
  series total);
  **and** `GET /actuator/metrics/cdk.document.ingestion.duration` returns `200`;
  **and** the negative controls hold: no `cdk_document_ingestion_duration_bucket`, no
  `cdk_document_ingestion_duration_count`, no `…_seconds_seconds`.
- **To be proven by:** `OperationalMetricsHttpLiveTest.ingestionDurationTimer_shouldPublishAllEightSloBucketsPerPhase`.
- **This is the single most load-bearing integration assertion in Story 2.** ADR-005(3) records that
  `management.metrics.distribution.*` is a Boot property path this repository has never exercised on
  Spring Boot 4.0.5; if it were inert, the buckets would silently not appear and `histogram_quantile`
  would return nothing. Only a real scrape catches that. The `_seconds` half of the same assertion is
  the Timer arm of the ADR-001 negative-control rule.
- **`le` label formatting must be confirmed at implementation**, not guessed: the Prometheus client
  renders boundaries as decimals (`60.0`) and `+Inf`, and the exact rendering of the `+Inf` bucket
  differs between client versions. Match on the `le=` label numerically where possible rather than on
  a formatted string.

**Scenario 2.10 — The SLO boundaries are overridable at runtime without a rebuild** *(AC-005)*
- **Given** a `@SpringBootTest` slice with
  `management.metrics.distribution.slo.cdk.document.ingestion.duration=1s,2s`
- **When** the context starts and the timer's snapshot is read
- **Then** the published bucket boundaries reflect the override rather than the eight code defaults.
- **To be proven by:** `IngestionDurationSloOverrideTest`.
- **OQ-033 — not resolvable from documents; a verification task for the Story 2 implementer
  (settled 2026-09-04).** Design §10 says Boot's `PropertiesMeterFilter` "applies a distribution
  setting only when the corresponding property is present and merges otherwise" — which does not say
  whether an explicit property **replaces** the code-declared `serviceLevelObjectives(...)` or
  **unions** with them, and no amount of document review can settle it. The expected bucket set
  differs accordingly (`{1s, 2s, +Inf}` versus `{1s, 2s, 15s, …, 1h, +Inf}`).
  **The Story 2 (`DD-43268`) implementer must verify Boot's actual `PropertiesMeterFilter` behaviour
  against the real running app on Spring Boot 4.0.5 before writing this scenario's assertion** — run
  the slice with the override in place, read the timer's `takeSnapshot().histogramCounts()`, and
  write the assertion against what it actually does. Do not write it against a guess, and do not
  write it against this document. **This is a verification task, not a blocking design question:**
  the code default is authoritative either way, this scenario's *shape* is fixed, AC-005's substance
  (the property is an effective runtime override) holds under both semantics, and Scenario 2.9
  catches an inert property path regardless. Record the observed behaviour in the test's Javadoc so
  the next reader does not re-derive it.

**Scenario 2.11 — A real terminal ingestion moves `_count` and `_sum`** *(AC-001, end-to-end)*
- **Given** the compose stack, a baseline scrape, and a synthetic `case_documents` row seeded via the
  established raw-JDBC live-test idiom with a backdated `created_at`
- **When** the document is driven to a terminal phase (WireMock RAG status stub returning a terminal
  status) and Awaitility waits for the phase write
- **Then** `cdk_document_ingestion_duration_seconds_count{phase=…}` has increased by at least 1 and
  `…_sum` has increased by at least the seeded interval; every seeded row is deleted in a `finally`
  block.
- **To be proven by:** `OperationalMetricsHttpLiveTest.ingestionDurationTimer_shouldRecordASeededTerminalIngestion`.
- Delta, never equality (shared compose database). Backdating `created_at` by a known amount is what
  makes the `_sum` assertion meaningful rather than trivially `>= 0`.

---

## Story 3 — Outbound dependency call timer (`DD-43269`)

Targets `metrics/ExternalCallMetrics`, `metrics/OutcomeClassifier`, and the eleven production call
sites across `clients/rag` ×4, `clients/hearing`, `clients/progression` and `storage/`
(design §5, ADR-003, ADR-004). The largest story in the ticket and the one with the highest NFR-006
exposure.

---

**Scenario 3.1 — Eleven `success` series exist at construction; the tag keys are exactly three; `(dependency, operation)` is eleven pairs, not forty-four** *(AC-004)*
- **Given** a fresh `SimpleMeterRegistry`
- **When** `ExternalCallMetrics` is constructed
- **Then** exactly 11 timers exist, one per `(dependency, operation)` pair, all with
  `outcome="success"` and `count() == 0`; the ticket-specific tag keys are exactly `dependency`,
  `operation`, `outcome`; the emitted `(dependency, operation)` set is exactly the eleven documented
  pairs; and **no** `(rag, get-court-documents)`-style cross-product combination exists.
- **To be proven by:** `ExternalCallMetricsTest.shouldPreRegisterElevenSuccessSeries_whenConstructed`
  and `…shouldRegisterElevenDependencyOperationPairsNotACrossProduct`.
- The cross-product assertion is what pins design §2's cardinality claim (33 registered, not 132).

**Scenario 3.2 — The success path records once and returns the response object untouched** *(AC-001, NFR-006)*
- **Given** a `ThrowingSupplier` returning a specific response instance
- **When** `record("rag", "answer-user-query", call)` is invoked
- **Then** one observation is recorded on `{dependency=rag, operation=answer-user-query,
  outcome=success}` with a positive duration; and the returned value is **reference-identical** to
  the supplier's (`assertThat(returned).isSameAs(stub)`).
- **To be proven by:** `ExternalCallMetricsTest.record_shouldObserveSuccess_andReturnTheSameInstance`.
- **Reference identity, not field-by-field equality.** It is the strongest available NFR-006
  guarantee at this level: an object that was never inspected, copied or mapped cannot have lost
  `doc_id`, `llm_input`, `llmResponse`, `documentChunks`, `transactionId` or a status. Field-level
  parity is Scenario 3.8's job, per-client.

**Scenario 3.3 — The throw path records once and rethrows the same instance** *(AC-002)*
- **Given** a supplier that throws a specific `RagClientException` instance whose cause is a specific
  `HttpServerErrorException`
- **When** `record(...)` is invoked
- **Then** exactly one observation is recorded with the classified `outcome`; and the exception
  caught by the caller is **the same instance** (`isSameAs`), with identical type, message, cause
  (also `isSameAs`) and stack trace.
- **To be proven by:** `ExternalCallMetricsTest.record_shouldObserveFailure_andRethrowTheSameInstance`.
- FR-006/AC-009 are satisfied *structurally* by never reconstructing the exception (ADR-003(2)); the
  test asserts the structure rather than inspecting fields, so a future refactor that wraps it fails
  here.

**Scenario 3.4 — `outcome` is derived by walking the cause chain: the full ADR-003 mapping table** *(AC-003)*
- **Given** real exception shapes, not mocks — one per row:

  | Input | Expected `outcome` |
  |---|---|
  | (no exception, value returned) | `success` |
  | `RagClientException(msg, HttpClientErrorException 404)` | `client_error` |
  | `RagClientException(msg, HttpServerErrorException 503)` | `server_error` |
  | `RagClientException(msg, ResourceAccessException(SocketTimeoutException))` | `timeout` |
  | `RagClientException(msg, ConnectTimeoutException)` | `timeout` |
  | `ConnectionRequestTimeoutException` | `timeout` |
  | `java.util.concurrent.TimeoutException` | `timeout` |
  | raw `HttpClientErrorException 404` (Hearing/Progression shape — those clients have no try/catch) | `client_error` |
  | raw `HttpServerErrorException 500` | `server_error` |
  | `com.azure.core.exception.HttpResponseException` with a 404 / 500 response | `client_error` / `server_error` |
  | `RagClientException(msg, JsonProcessingException)` | `error` |
  | `IllegalStateException` with **no** cause (Azure's aborted-copy shape) | `error` |
  | `NullPointerException` (a mapping bug) | `error` |
- **When** each is classified
- **Then** the expected value results.
- **To be proven by:** `OutcomeClassifierTest` as a parameterised test over the table.
- **Real exception shapes, not mocks** (design §Testing) — a mocked `HttpStatusCodeException` can be
  made to answer any status and would not catch a classifier reading the wrong accessor.
- **`error` is GATE-1's accepted fifth value.** Three of the rows above are only reachable because of
  it; folding them into `server_error` would make that value mean "5xx, or our own bug, or a failed
  blob copy".

**Scenario 3.5 — The classifier is never fooled by exception *type*** *(AC-003)*
- **Given** two `RagClientException` instances, identical in type and message, differing only in
  their cause (a 404 versus a 503)
- **When** both are classified
- **Then** they yield `client_error` and `server_error` respectively — **never** the same value, and
  never a blanket `server_error`.
- **To be proven by:** `OutcomeClassifierTest.shouldClassifyRagClientExceptionByItsCause_notByItsType`.
- **This is the assertion that encodes ADR-003's whole finding**, and the direct negative control for
  the ticket's own contradictory wording (AC-010 as originally written). It must not be omitted as
  redundant with 3.4 — 3.4 could pass with a type-based classifier that happened to order its rows
  favourably.

**Scenario 3.6 — The chain walk is depth-bounded and cycle-guarded, and terminates** *(AC-003)*
- **Given** (a) a cyclic chain — `a.cause = b`, `b.cause = a`; (b) a chain of seven wrappers whose
  only `HttpStatusCodeException` sits at depth 6, beyond the stated bound of 5
- **When** each is classified
- **Then** (a) the call terminates (no `StackOverflowError`, no infinite loop) and returns a value;
  (b) returns **`error`** — the generic value — even though a `client_error`-bearing cause exists at
  depth 6.
- **To be proven by:** `OutcomeClassifierTest.shouldTerminate_whenCauseChainIsCyclic` and
  `…shouldClassifyAsGenericError_whenTheStatusBearingCauseIsBeyondTheDepthBound`.
- **OQ-025 — decided 2026-09-04: beyond (and at) the 5-layer bound, classify as generic `error`.**
  Asserted here explicitly rather than left to be inferred, and stated in `OutcomeClassifier`'s
  Javadoc. The accepted consequence is named in both places: a status-bearing cause deeper than five
  layers is mis-tagged `error` rather than `client_error`/`server_error`. The bound is judged deep
  enough — CDKS's own deepest chain is depth 3
  (`RagClientException → ResourceAccessException → SocketTimeoutException`) — and `error` is the
  correct degradation for an unbounded walk: it means "we could not tell", never a wrong positive
  claim. If a real chain is ever found deeper than five, the fix is to raise the constant, not to
  remove the bound. **Assert the depth constant's value too**, so raising it is a deliberate edit.

**Scenario 3.7 — Each of the four RAG classes is instrumented, and none of them changes what it throws** *(AC-002)*
- **Given** each of `ApimDocumentIngestionClient.initiateDocumentUpload`,
  `ApimDocumentIngestionStatusClient.documentStatusByReference`,
  `RagAnswerAsyncServiceImpl.answerUserQueryAsync` / `.answerUserQueryStatus`, and
  `RagAnswerServiceImpl.answerUserQuery`, with its existing `RestClient` stubbed to return 200, then
  404, then 503, then to throw a `ResourceAccessException(SocketTimeoutException)`
- **When** each method is invoked
- **Then** one observation is recorded per call on the right `{dependency=rag, operation, outcome}`
  series; the existing `RagClientException` is thrown with the **same** type, message and cause as
  today; the existing log lines and the two `@ExceptionHandler(RagClientException.class)` handlers
  are unchanged.
- **To be proven by:** extending `ApimDocumentIngestionClientTest`,
  `ApimDocumentIngestionStatusClientTest`, `RagAnswerAsyncServiceImplTest`, and a **new**
  `RagAnswerServiceImplTest` (**OQ-036** — no such class exists today).
- **The two classes the ticket does not name are in scope** (`ApimDocumentIngestionStatusClient`,
  `RagAnswerServiceImpl`) — OQ-005's second question, answered yes at ADR-003(1).
- **OQ-036 — closed 2026-09-04, confirming what this scenario already specifies.** Both
  `RagAnswer*ServiceImpl` are `@RestController`s *and* outbound clients. The new
  `RagAnswerServiceImplTest` (and the parity cases in Scenario 3.8) **drive `RagAnswerServiceImpl` as
  a direct outbound client — calling its methods directly — never through simulated MVC/web
  requests**, because an MVC-driven assertion would measure CDKS's inbound surface instead of its
  outbound call. No `@WebMvcTest`, no `MockMvc`, no `standaloneSetup` for these two classes; a plain
  JUnit 5 + Mockito test with the `RestClient` stubbed. The class still does not exist today, so it
  is created new.

**Scenario 3.8 — RAG response-field parity, with and without instrumentation** *(NFR-006 — merge-blocking)*
- **Given** for each of the four RAG classes: a fixed, synthetic RAG response payload containing
  `doc_id`, `llm_input`, `llmResponse`, `documentChunks`, `transactionId` and a status
- **When** the method is invoked (a) against the uninstrumented shape — a directly-constructed
  instance without `ExternalCallMetrics`, or `ExternalCallMetrics` with recording disabled — and
  (b) instrumented
- **Then** every field of the returned object is identical between the two runs; no field is null in
  (b) that was populated in (a); no list is reordered or truncated.
- **To be proven by:** a `…ResponseParityTest` case in each of the four RAG client test classes.
- **Merge-blocking, not a nice-to-have** (design §5, §12; CLAUDE.md's RAG-preservation hard rule).
  This is the one test in the ticket whose absence would let a CLAUDE.md hard rule be broken
  silently, because the failure mode is a *missing* field in a response nobody reads in a test
  otherwise.
- Fixture values must be synthetic — no real `doc_id`, no real case content, no real `llm_input`
  text.

**Scenario 3.9 — Hearing and Progression need no exception handling, and the two same-URI Progression methods are distinguishable** *(AC-002, AC-004)*
- **Given** `HearingClientImpl` and `ProgressionClientImpl`, which have **no try/catch at all**, so
  `HttpStatusCodeException` and `ResourceAccessException` propagate raw
- **When** `getCourtDocuments(caseId)` and `getCourtDocumentsForAllDefendants(caseId)` are each
  invoked — the two methods that build the **identical URI** from the same `courtDocsPath` and the
  same `caseId` query parameter
- **Then** they record on **distinct** `operation` series (`get-court-documents` versus
  `get-court-documents-all-defendants`), and the raw exceptions propagate unchanged on the failure
  paths.
- **To be proven by:** `ProgressionClientImplTest.shouldRecordDistinctOperations_forTheTwoMethodsThatShareAUri`
  (extend) and equivalents in `HearingClientImplTest`.
- **This is the negative control for ADR-003's rejection of a `ClientHttpRequestInterceptor`.** The
  interceptor was rejected precisely because these two calls are indistinguishable at the HTTP layer.
  If a future change moves timing to an interceptor "for tidiness", this test fails — which is the
  point.

**Scenario 3.10 — `operation` is a literal at the call site and can never carry a path variable** *(AC-004, NFR-001)*
- **Given** every one of the eleven instrumented call sites driven at least once, including
  `documentStatusByReference` (whose `PATH_DOCUMENT_STATUS_BY_REFERENCE` contains a `{...}` segment
  that expands to a RAG document reference) and `answerUserQueryStatus` (whose path expands to a RAG
  transaction id)
- **When** the registry's emitted `operation` tag values are collected
- **Then** the set is **exactly** the eleven `CdkMeters` constants; and no emitted tag value on this
  meter contains `{`, `}`, `/`, a UUID-shaped substring, or a digit run of four or more — a
  structural guard that no URI, path template or expanded path variable can ever have become a label.
- **To be proven by:** `ExternalCallMetricsTest.shouldEmitOnlyTheElevenLiteralOperationConstants` plus
  the character-class negative assertion.
- The `{`/UUID negative assertion is the one that would catch the specific NFR-001 leak the
  requirements forbid, rather than merely confirming the happy path.

**Scenario 3.11 — Azure Blob's explicit-outcome entry point, all four paths, with its exception contract unchanged** *(AC-005)*
- **Given** `AzureBlobStorageService.copyFromUrl(...)` and, in turn: (a) a copy poller returning a
  successful status; (b) a poll timeout — the existing internal
  `runtimeException.getCause() instanceof TimeoutException` branch; (c) a copy reporting
  `ABORTED`/`FAILED`; (d) an `HttpResponseException` from the Azure SDK's own HTTP stack with a 4xx
  and a 5xx
- **When** each runs
- **Then** exactly one observation is recorded on `{dependency=azure_blob, operation=copy-from-url}`
  with `outcome` = `success` / `timeout` / `error` / `client_error`-or-`server_error` respectively;
  **and** what the method throws is byte-for-byte unchanged from today — in the timeout case, still
  `new IllegalStateException(message)` with **no cause** (the discarded cause is flagged, not fixed —
  ADR-003(5)); in the other failure cases, still the original `runtimeException`.
- **To be proven by:** `AzureBlobStorageServiceTest` extended with one case per path.
- `timeout` for this dependency means "the copy poll exceeded `cp.cdk.storage.copy-timeout-seconds`,
  default **120 s**" — not 180 s, not 3 minutes. Assert the semantic in the test's own name/comment so
  a dashboard author reading the test does not assume a uniform timeout.
- **OQ-027 — decided 2026-09-04: case (b) is `outcome=timeout`'s *only* coverage for
  `dependency=azure_blob`, and that is accepted.** There is no integration seam — Azurite cannot
  readily be made to stall a `copyFromUrl` poll past 120 s, and shortening
  `cp.cdk.storage.copy-timeout-seconds` in compose risks the existing upload live tests. **State the
  gap in the test's Javadoc rather than letting a reader assume end-to-end coverage exists:** what is
  unproven is only that a *real* Azure copy poll timing out reaches this branch; the branch itself,
  and the outcome it records, are fully covered here. If a seam is ever funded it is purely additive.

**Scenario 3.12 — `exists` and `getBlobSize` are not instrumented** *(AC-004)*
- **Given** `StorageService.exists(...)` and `getBlobSize(...)`, which have no production call site
- **When** they are invoked
- **Then** no observation is recorded, and `CdkMeters` contains **no** `operation` constant for
  either.
- **To be proven by:** `AzureBlobStorageServiceTest.shouldNotInstrumentTheTwoMethodsWithNoProductionCallSite`.
- ADR-004(3)'s reasoning made testable: a permanently-zero series would assert a call path that does
  not exist. This is the mirror image of Story 1's Scenario 1.7 and of DD-43185 ADR-004 — a
  reviewer will ask why the two tickets answer "register the unreachable?" differently, and these two
  tests are the answer.

**Scenario 3.13 — A recording failure never reaches the business call** *(AC-006)*
- **Given** a registry whose `Timer.record(...)` throws, and separately a classifier stubbed to throw
- **When** `record(...)` wraps (a) a supplier that returns and (b) a supplier that throws
- **Then** in (a) the business result is returned unchanged; in (b) the business exception propagates
  as the same instance; in neither case does the recording failure surface to the caller; and the
  business exception's propagation does **not** depend on the recording succeeding.
- **To be proven by:** `ExternalCallMetricsTest.shouldNotDisturbTheBusinessCall_whenRecordingThrows`
  (both directions).
- Design §9 restates this structure "because getting it wrong would be silent" — so the test asserts
  both directions, not just the return path.

**Scenario 3.14 — The right `{dependency, operation, outcome}` series move on a real scrape** *(AC-001 – AC-004)*
- **Given** the compose stack, a baseline scrape, and the existing WireMock RAG / Hearing /
  Progression stubs reconfigured per case to return 200, 404 and 503
- **When** each is driven through the real client path (via an existing live-test flow or a seeded
  `jobs` row, per the established idioms) and re-scraped
- **Then** `cdk_external_call_duration_seconds_count{dependency=…,operation=…,outcome=…}` has
  increased by at least 1 on exactly the expected series for each case, and not on the others;
  **and** `GET /actuator/metrics/cdk.external.call.duration` returns `200`;
  **and** the negative controls hold: no `cdk_external_call_duration_count`, no
  `…_seconds_seconds`, and **no `cdk_external_call_duration_seconds_bucket` at all** (ADR-005(4)).
- **To be proven by:** `ExternalCallMetricsHttpLiveTest.externalCallTimer_shouldRecordTheExpectedOutcomeSeries`
  (parameterised over the status cases).
- Delta, never equality. The bucket-absent assertion doubles as the automated guard on the series
  budget.

**Scenario 3.15 — `outcome=timeout` is covered at the unit tier only, and the gap is stated** *(AC-005)*

**Rewritten 2026-09-04 — OQ-026 decided: accept unit-tier-only coverage; do not manufacture a
compose seam. There is no integration-tier scenario here.**

- **Given** `OutcomeClassifier` and `ExternalCallMetrics`, driven at the **unit tier** with the real
  exception shapes a read or connect timeout actually produces —
  `ResourceAccessException(SocketTimeoutException)` for a read timeout,
  `ConnectTimeoutException` for a connect timeout, `ConnectionRequestTimeoutException` for a pool
  wait, `java.util.concurrent.TimeoutException`, each also wrapped in a `RagClientException` to
  reproduce the RAG client shape
- **When** each is classified and recorded through `record(...)`
- **Then** exactly one observation lands on `{dependency, operation, outcome=timeout}` for the
  dependency and operation under test, and it is **distinguishable from `server_error`** — asserted
  as a pair, i.e. the `server_error` series does **not** move.
- **To be proven by:** `OutcomeClassifierTest` (the timeout rows of Scenario 3.4's table) plus
  `ExternalCallMetricsTest.record_shouldObserveTimeout_forEachRealTimeoutExceptionShape`
  (parameterised), and `AzureBlobStorageServiceTest` for the `azure_blob` copy-poll case
  (Scenario 3.11(b), OQ-027).
- **The gap, stated plainly rather than downgraded silently.** No compose read-timeout override is
  added: `CP_CDK_RAG_READ_TIMEOUT_MS` is 180 000 ms, and a 180-second test is not shippable;
  `CP_CDK_CQRS_READ_TIMEOUT_MS` is 15 000 ms but is **shared by every Hearing and Progression live
  test in the same single-app stack**, so shortening it would change their effective timeouts too.
  **What is therefore not covered anywhere is the end-to-end wiring only** — that a genuine socket
  read timeout inside the shared Apache HttpClient surfaces as a
  `ResourceAccessException(SocketTimeoutException)` that reaches `OutcomeClassifier` through the real
  client stack. The classification and the recording are fully covered. This is recorded in the
  coverage summary as **unit-tier only**, and the `ExternalCallMetricsHttpLiveTest` class carries a
  Javadoc note saying why no timeout case appears in it.
- **If a seam is ever funded** — a dedicated WireMock path with a per-dependency read-timeout
  override, or a second app container with shortened timeouts — adding the integration case is purely
  additive and needs no change to anything above.

---

## Story 4 — HTTP connection-pool visibility (`DD-43270`)

Targets `metrics/HttpPoolMetricsConfig` (design §6, ADR-008). The smallest, lowest-risk story; no
dependency on any other story and no need for `MetricsSafety`.

---

**Scenario 4.1 — The framework binder registers four meters / five series over the shared connection manager** *(AC-001)*
- **Given** a real `PoolingHttpClientConnectionManager` configured as `RestClientFactoryConfig`
  configures it (`setMaxConnTotal(200)`, `setMaxConnPerRoute(50)`) and a `SimpleMeterRegistry`
- **When** `HttpPoolMetricsConfig`'s `PoolingHttpClientConnectionManagerMetricsBinder` bean is bound
- **Then** exactly five series exist —
  `httpcomponents.httpclient.pool.total.max`,
  `…pool.total.connections{state="available"}`, `…pool.total.connections{state="leased"}`,
  `…pool.total.pending`, `…pool.route.max.default` — each tagged `httpclient="cdk"`.
- **To be proven by:** `HttpPoolMetricsConfigTest.shouldRegisterFivePoolSeries_whenBound`.
- The binder's names are Micrometer-owned and are **not** `CdkMeters` constants — the one documented
  exception to "every meter name is a `CdkMeters` constant" (Story 4 NFR-008). Assert them as
  literals here.

**Scenario 4.2 — The `cdk_*` alias reads the same in-memory struct as the binder's leased series** *(AC-002)*
- **Given** both beans bound over the **same** connection-manager instance
- **When** the alias gauge and the binder's `state="leased"` gauge are both read
- **Then** they return the same value; and the alias is registered with `strongReference(true)` so it
  cannot be collected and silently stop reporting.
- **To be proven by:** `HttpPoolMetricsConfigTest.leasedAlias_shouldReadTheSameStatsAsTheBinder`.
- **The strong-reference assertion is not incidental.** A `Gauge` over a weakly-referenced object is
  the classic way a metric silently becomes `NaN` in production; the design specifies
  `strongReference(true)` and a test should hold it there.

**Scenario 4.3 — The configured maxima are reported, read from the bean and not hard-coded in the metrics code** *(AC-003)*
- **Given** the shared connection manager as configured
- **When** the gauges are read
- **Then** `httpcomponents.httpclient.pool.total.max` reports **200** and
  `…pool.route.max.default` reports **50**;
  **and** changing the manager's configured maxima in the test changes the reported values —
  proving the numbers come from the bean, so a consumer can express exhaustion as a **ratio** without
  hard-coding either limit into an alert rule (AC-013's explicit requirement).
- **To be proven by:** `HttpPoolMetricsConfigTest.shouldReportTheConfiguredMaxima_readFromTheBean`
  (parameterised over two configurations).

**Scenario 4.4 — One shared manager, so the gauges cover all Apache-HttpClient traffic — and Azure Blob is documented as not covered** *(AC-004)*
- **Given** `RestClientFactoryConfig.httpClientConnectionManager()` is a single `@Bean` with
  `setConnectionManagerShared(true)`
- **When** the application context is inspected
- **Then** exactly one `PoolingHttpClientConnectionManager` bean exists and the binder is bound over
  it, so the gauges reflect RAG, Progression and Hearing traffic regardless of which `RestClient`
  issued the call;
  **and** `HttpPoolMetricsConfig`'s Javadoc states plainly that `dependency=azure_blob` is **not**
  covered, because `AzureBlobStorageService` uses the Azure SDK's own HTTP stack.
- **To be proven by:** a context assertion (`assertThat(context.getBeansOfType(PoolingHttpClientConnectionManager.class)).hasSize(1)`)
  plus a **diff-level Javadoc check at Code Review**. State both; a test cannot prove a Javadoc
  sentence.

**Scenario 4.5 — Nothing is computed on scrape** *(AC-005)*
- **Given** `HttpPoolMetricsConfig`
- **When** its dependencies are inspected
- **Then** it references only the connection-manager bean — no repository, no `RestClient`, no
  `JdbcTemplate`, no lock — and every gauge is a lambda over `ConnPoolControl.getTotalStats()`, an
  in-memory struct.
- **To be proven by:** a constructor/field-level structural assertion plus a **diff-level check**.
  NFR-003 is structural here, not observable: there is no way to make a scrape fail for the right
  reason if the gauges were computing something remotely, short of stopping the database mid-suite.

**Scenario 4.6 — All six pool series are on a real scrape with the expected labels** *(AC-001, AC-003)*
- **Given** the compose stack with Story 4's code merged
- **When** `GET /actuator/prometheus` is scraped
- **Then** `httpcomponents_httpclient_pool_total_max`,
  `httpcomponents_httpclient_pool_total_connections{state="available"}`,
  `httpcomponents_httpclient_pool_total_connections{state="leased"}`,
  `httpcomponents_httpclient_pool_total_pending` and
  `httpcomponents_httpclient_pool_route_max_default` are all present with `httpclient="cdk"`, plus
  `cdk_http_pool_connections_leased`;
  `…_total_max` reports `200.0` and `…_route_max_default` reports `50.0`;
  and every one of the six carries `service`, `cluster` and `region`.
- **To be proven by:** `HttpPoolMetricsHttpLiveTest.poolGauges_shouldRenderAllFiveBinderSeriesPlusTheAlias`.
- **`httpcomponents_*` series do not exist on the current scrape at all** (design §Testing / ADR-008
  context) — so this is also the assertion that proves the binder bean was actually registered rather
  than silently missing.

**Scenario 4.7 — The alias and the binder's leased series agree: one snapshot at idle, plus a structural proof** *(AC-002)*

**Re-scoped 2026-09-04 — OQ-029 decided. AC-002's "agree at all times, asserted under concurrent
load" is replaced by the two-part proof below; no concurrent requests are raced.**

- **Part (a) — single-snapshot equality at idle (integration tier).**
  **Given** the compose stack at idle (no live test driving outbound traffic in parallel)
  **When** a **single** `/actuator/prometheus` body is fetched **once** and both values are parsed
  from *that one body*
  **Then** `cdk_http_pool_connections_leased` and
  `httpcomponents_httpclient_pool_total_connections{state="leased"}` report the same value.
  - **To be proven by:** `HttpPoolMetricsHttpLiveTest.leasedAlias_shouldAgreeWithTheBinderSeries_withinOneScrapeAtIdle`.
  - **Two scrapes must not be used**, and the test must not re-fetch to "confirm": the point of one
    body is that any disagreement is then a real defect rather than two samples taken at two
    instants.
- **Part (b) — the "cannot disagree by construction" proof (unit tier — this is the load-bearing
  half).**
  **Given** both beans bound over the **same** `PoolingHttpClientConnectionManager` instance
  **When** the alias gauge's value function and the binder's `state="leased"` gauge are inspected and
  read
  **Then** both resolve to the *same* `ConnPoolControl.getTotalStats()` struct on the *same* bean —
  so a difference is impossible other than by sampling instant — and the alias is registered with
  `strongReference(true)` so it cannot be collected and silently start reporting `NaN`. Reading both
  after mutating the pool's observable state yields equal values every time.
  - **To be proven by:** Scenario 4.2's `HttpPoolMetricsConfigTest.leasedAlias_shouldReadTheSameStatsAsTheBinder`,
    extended to assert the shared-source property explicitly rather than only equality at one moment.
- **Why not concurrent load.** Two gauges are sampled at two different instants even *within* one
  scrape, and under concurrent load the leased count changes between the samples — so a strict
  under-load equality assertion would be the flakiest test in the suite while proving strictly less
  than (b) does. (b) proves the property for *all* loads; (a) is the cheap end-to-end sanity check
  that both series are actually present and wired to the same pool.

---

## Story 5 — Answer-generation outcome counter (`DD-43271`)

Targets `metrics/AnswerGenerationMetrics` and the **four** surviving increment points across
`GenerateAnswerForQueryTask` and `CheckStatusOfAnswerGenerationTask` (design §8, ADR-007 as amended,
**ADR-011**).

> **Scope reduced 2026-09-04 (OQ-022 / ADR-011).** `outcome=timed_out` is **descoped** — its
> detection used the same `TaskRetryDecision.willBeRetried(...)` predicate over the same
> library-owned `getRetryAttemptsRemaining()` value that a task execution never observes at `0`, and
> the trace confirmed `CheckStatusOfAnswerGenerationTask` has **no independent** substitute (its
> `CTX_ANSWER_RETRY_COUNT` counts `ANSWER_GENERATION_FAILED` re-dispatches, not polls, and is never
> touched on the `PENDING` path). The `catch`-path `failed` increment is descoped for the same
> reason. **Scenario 5.5 and Scenario 5.6 are rewritten as negative controls; Scenario 5.10 is
> withdrawn** (the code move it tested is no longer made). `outcome` is `{succeeded, failed}` and the
> meter registers **8** series.
>
> **What this story must still assert, and must not soften:** that the two withdrawn terminations
> record **nothing**, so the resulting undercount is a *tested, documented* property rather than a
> surprise. `succeeded / (succeeded + failed)` is **not** a success rate.

---

**Scenario 5.1 — All eight series exist at construction, and the two enumerations are closed** *(AC-005)*
- **Given** a fresh `SimpleMeterRegistry`
- **When** `AnswerGenerationMetrics` is constructed
- **Then** exactly **8** counters exist — `outcome` ∈ {`succeeded`, `failed`} ×
  `query_level` ∈ {`CASE`, `DEFENDANT`, `CASE_ALL_DOCUMENTS`, `unknown`} — all at `0`; and the
  emitted tag-value sets are **exactly** those, asserted with `containsExactlyInAnyOrder`;
  **and no series exists for `outcome="timed_out"` in any combination** (ADR-011).
- **To be proven by:** `AnswerGenerationMetricsTest.shouldPreRegisterAllEightSeriesAtZero_whenConstructed`
  and `…shouldNotRegisterTheWithdrawnTimedOutValue`.
- **Amended 2026-09-04 (OQ-022 / ADR-011):** twelve series before, eight now. All eight are
  reachable, which is why the `timed_out` absence assertion belongs here — the previous note said
  three of the twelve "should stay at zero in a healthy service", and a value that can never be
  non-zero for *any* service state is exactly what ADR-009(4) says not to register.
  Pre-registering the eight is still DD-43185's rule: a zero series is a signal, a missing one is
  ambiguous.
- **Constant-name hazard — OQ-031, decided 2026-09-04.** `failed` must come from a **new**,
  distinctly-named constant (`CdkMeters.OUTCOME_FAILED`, `"failed"`), **not** from the existing
  `CdkMeters.OUTCOME_FAILURE` (`"failure"`, DD-43185's scheduler counter), which would compile
  silently in its place. Both constants stay and are declared adjacently, each with a Javadoc
  sentence naming the meter it belongs to; neither is renamed (tag values are a one-way door once
  alert rules exist). **This scenario's `containsExactlyInAnyOrder` must be written against the
  literal `"failed"`, never against the constant** — asserting the constant equals itself would not
  catch the substitution. Assert `"failure"` is *absent* from this meter's `outcome` values as the
  paired negative control.

**Scenario 5.2 — `ANSWER_GENERATED` increments `succeeded` exactly once, at the right query level** *(AC-001)*
- **Given** `CheckStatusOfAnswerGenerationTask` receiving `ANSWER_GENERATED` from the RAG status call,
  after the answer is upserted
- **When** the task completes
- **Then** `cdk.answer.generation{outcome="succeeded",query_level=<the transaction's level>}`
  increments by exactly 1; no other series moves — parameterised over the three real `QueryLevel`
  values.
- **To be proven by:** `CheckStatusOfAnswerGenerationTaskTest.shouldRecordOneSucceeded_whenAnswerGenerated`
  (extend, parameterised).

**Scenario 5.3 — `ANSWER_GENERATION_FAILED` with budget remaining records nothing** *(AC-002)*
- **Given** `ANSWER_GENERATION_FAILED` and `CTX_ANSWER_RETRY_COUNT < questions-retry.max-attempts`
- **When** the task runs
- **Then** **zero** increments occur on any series, and `GENERATE_ANSWER_FOR_QUERY` is re-dispatched
  as it is today.
- **To be proven by:** `CheckStatusOfAnswerGenerationTaskTest.shouldRecordNothing_whenAnswerGenerationFailedAndBudgetRemains`
  (extend).
- **This is the case that would over-count by up to 100×** (ADR-007(2)) — the single most valuable
  negative assertion in Story 5, and the reason the counter is not just placed next to the failure
  branch.

**Scenario 5.4 — `ANSWER_GENERATION_FAILED` with the re-dispatch budget spent increments `failed` once** *(AC-002)*
- **Given** `ANSWER_GENERATION_FAILED` and `CTX_ANSWER_RETRY_COUNT >= questions-retry.max-attempts` —
  the existing `log.warn("Max retries reached…")` branch
- **When** the task runs
- **Then** `{outcome="failed"}` increments by exactly 1 and nothing is re-dispatched.
- **To be proven by:** `…shouldRecordOneFailed_whenTheRedispatchBudgetIsSpent` (extend).
- **And, as a whole-transaction assertion:** a transaction that sees several
  `ANSWER_GENERATION_FAILED` cycles and then succeeds contributes **exactly one** increment in total
  (`succeeded`), not one per cycle — Story 5 AC-002's second clause, worth its own case.

**Scenario 5.5 — The `PENDING` path records nothing, whatever the polling budget, and `timed_out` never appears** *(AC-003)*

**Rewritten 2026-09-04 (OQ-022 / ADR-011). This was "`PENDING` with the polling budget spent
increments `timed_out` once"; that increment is withdrawn. The scenario is retained, inverted, as
the negative control that pins the descoping.**

- **Given** `CheckStatusOfAnswerGenerationTask` receiving `ANSWER_GENERATION_PENDING` — and, as
  separate cases, a `null` response and a non-2xx response — with `TaskRetryDecision.willBeRetried(...)`
  stubbed **both** `true` and `false` (both cases must behave identically, which is the point)
- **When** the task runs
- **Then** in **every** case: **zero** increments occur on **any** of the eight series; the task
  returns `INPROGRESS` + `shouldRetry=true` exactly as it does today; and no `outcome="timed_out"`
  series is created by the attempt.
- **To be proven by:** `CheckStatusOfAnswerGenerationTaskTest.shouldRecordNothing_whenStillPending_whateverTheRetryBudget`
  (extend, parameterised over the three response shapes × both predicate values).
- **Stubbing the predicate `false` and still asserting zero is the load-bearing half.** It is what
  fails if an implementer re-adds the withdrawn increment, and it is why the case is parameterised
  over the predicate rather than fixed at `true`.
- **This is a knowingly-uncounted termination, and the test says so.** A transaction abandoned while
  `PENDING` (the `questions-retry` budget spent, ~17 minutes of polling) ends with no increment
  anywhere, so `cdk_answer_generation_total` undercounts. The test's Javadoc must name ADR-011 and
  the `task-manager-service` follow-up, so the next reader finds the reason rather than treating the
  zero as a bug to "fix" by guessing at the library's counter.
- **There is no integration half.** The production event is undetectable by construction; there is
  nothing to observe end-to-end.

**Scenario 5.6 — The `catch` path records nothing, whatever the retry budget** *(AC-003)*

**Rewritten 2026-09-04 (OQ-022 / ADR-011). This was "the `catch` path with the budget spent
increments `failed`"; that increment (design §8 row 4) is withdrawn — it was gated on the identical
unobservable `!willBeRetried(...)` condition as row 3.**

- **Given** `CheckStatusOfAnswerGenerationTask`'s `catch (Exception)` branch reached (e.g. the RAG
  status call throwing), with `willBeRetried(...)` stubbed **both** `true` and `false`
- **When** the task runs
- **Then** in **both** cases: **zero** increments occur on any series; the task returns
  `INPROGRESS` + `shouldRetry=true` via its existing `retry(executionInfo)` at line 190, unchanged;
  and its existing `log.error("Failed to check answer generation status RAG …")` line is emitted as
  today.
- **To be proven by:** `CheckStatusOfAnswerGenerationTaskTest.shouldRecordNothing_whenTheCatchPathIsReached_whateverTheRetryBudget`
  (extend, parameterised over the predicate value).
- **The second knowingly-uncounted termination.** Same reasoning and same Javadoc obligation as
  Scenario 5.5. Note what is *not* lost: `outcome="failed"` itself remains fully reachable through
  Scenarios 5.4 and 5.7, whose conditions do not touch the library's counter — so the tag value is
  intact and only this increment *point* is gone.
- ~~**This is GATE-2's distinction made testable.**~~ GATE-2 is withdrawn; there is no `timed_out`
  to distinguish `failed` from. The distinction between a dependency error and a give-up-waiting
  event was right and is preserved *in the documentation*, not in the metric.

**Scenario 5.7 — `GenerateAnswerForQueryTask`'s three abandonment paths each increment `failed` once, and its success handoff increments nothing** *(AC-004, AC-006)*
- **Given**, in turn: (a) missing identifiers in the job data; (b) `QueryDefinitionLatest` not found;
  (c) the RAG async start throwing; (d) a successful start that dispatches
  `CHECK_STATUS_OF_ANSWER_GENERATION` and returns `COMPLETED`
- **When** the task runs
- **Then** (a), (b), (c) each increment `{outcome="failed"}` by exactly 1; (d) increments **nothing**.
- **To be proven by:** `GenerateAnswerForQueryTaskTest` extended with four cases.
- **(a)–(c) are GATE-5's accepted widening** — the ticket names only
  `CheckStatusOfAnswerGenerationTask`'s states. Without them the counter's total does not equal
  "transactions that ended", and `succeeded / total` is not a success rate. (d)'s zero assertion is
  what makes the exactly-once property hold across the handoff.
- (c) is terminal in practice because `GENERATE_ANSWER_FOR_QUERY` can never be retried
  (`getRetryDurationsInSecs()` not overridden). Assert that with a stubbed-false predicate rather
  than relying on the defect staying in place.

**Scenario 5.8 — A null `query_level` becomes `unknown`, and the increment is never omitted** *(AC-005)*
- **Given** job data whose `query_level` is missing, or present but invalid, so
  `TaskUtils.parseQueryLevel` returns `null`
- **When** any of the six increment points is reached
- **Then** the increment happens with `query_level="unknown"` — never skipped, never a null tag
  (which Micrometer rejects outright).
- **To be proven by:** `…shouldTagQueryLevelUnknown_whenParseQueryLevelReturnsNull` (extend) plus a
  `AnswerGenerationMetricsTest` case asserting a null level argument cannot reach the registry.
- The `case null, default:` branch is a real production path that persists an answer, so omitting the
  increment would silently drop a population (ADR-007(3)).

**Scenario 5.9 — Exactly one increment per transaction, traced through every path** *(AC-006)*
- **Given** a set of whole-transaction traces, **partitioned into the two groups ADR-011 creates**:
  - *observable terminations, expected sum `1`* — success-first-poll; success-after-N-pending-polls;
    success-after-M-failed-redispatches; failed-at-redispatch-budget; each of
    `GenerateAnswerForQueryTask`'s three abandonment paths;
  - *knowingly-unobservable terminations, expected sum `0`* — abandoned-while-pending (the former
    `timed_out`) and abandoned-from-the-`catch`-path.
- **When** each trace is simulated across the two tasks against one `SimpleMeterRegistry`
- **Then** the **sum** over all **eight** series is exactly `1` for every trace in the first group
  and exactly `0` for every trace in the second — no path double-counts, and every uncounted path is
  one of the two named, expected ones.
- **To be proven by:** a parameterised `CheckStatusOfAnswerGenerationTaskTest` /
  `GenerateAnswerForQueryTaskTest` pair, or a small dedicated
  `AnswerGenerationTransactionAccountingTest`.
- **Amended 2026-09-04 (ADR-011): the invariant is `sum ∈ {0, 1}` with the two groups enumerated —
  not `sum == 1` for every transaction.** Asserting `== 1` universally would encode a completeness
  claim that is now false, and would fail for the two withdrawn paths.
- **This scenario, not 5.2–5.8, is what makes the counter interpretable — and after ADR-011 it is
  also what makes the *undercount* explicit.** Writing the second group as expected-zero cases, in
  the same table as the expected-one cases, is what stops a future reader assuming
  `sum(cdk_answer_generation_total)` equals "transactions that ended". Add an assertion-message or
  Javadoc line on the second group stating that these two are the documented gap and naming
  ADR-011's follow-up.

**Scenario 5.10 — ~~The hoisted `query_level` parse is behaviour-neutral~~ WITHDRAWN** *(NFR-005)*

**Withdrawn 2026-09-04 (OQ-022 / ADR-011): there is no code move left to test.** The hoist of
`levelStr`/`level` parsing to the top of `CheckStatusOfAnswerGenerationTask.execute` existed **only**
so design §8's rows 3 and 4 would have a `query_level` to tag. Both rows are withdrawn, and the two
surviving increments in this class (rows 1 and 2, at lines ~145 and ~178) already sit **after** the
existing parse at lines 94–95. **Do not hoist** — NFR-005's strongest position for this story is now
"no existing line is moved at all".

- **What replaces it:** the existing `CheckStatusOfAnswerGenerationTaskTest` suite runs unmodified as
  regression (it always did), and NFR-005 is covered at Code Review by a **diff-level check that
  lines 94–95 are untouched**. No new test.
- **If an implementer hoists it anyway** for readability, this scenario is reinstated as written
  above and `02-design.md` §8 / ADR-007(6) must be updated in the same PR — but the reviewer should
  ask why the diff grew for no functional reason.

**Scenario 5.11 — A recording failure cannot affect either task's contract** *(AC-007)*
- **Given** a throwing registry or a throwing level-mapping
- **When** either task runs any of the six paths
- **Then** the returned `ExecutionInfo` is unchanged (including its status, `shouldRetry`, job data
  and `retryAttemptsRemaining`), the persisted answer/job data is unchanged, and nothing propagates.
- **To be proven by:** one case in each task's test class.

**Scenario 5.12 — The counter renders correctly and moves for a real answer** *(AC-001, AC-005)*
- **Given** the compose stack, a baseline scrape, and the existing
  `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` idiom — a `jobs` row seeded directly for
  `CHECK_STATUS_OF_ANSWER_GENERATION` with synthetic job data, plus the RAG WireMock status stub
  returning `ANSWER_GENERATED`
- **When** the live app's `JobExecutor` picks the row up and the task runs, then the endpoint is
  re-scraped
- **Then** `cdk_answer_generation_total{outcome="succeeded",query_level=…}` has increased by at
  least 1; all **8** series are present; `GET /actuator/metrics/cdk.answer.generation` returns `200`;
  and the negative controls hold (no `cdk_answer_generation{`, no `…_total_total`,
  **and no `outcome="timed_out"` label on any sample line of this meter** — ADR-011).
- **To be proven by:** `OperationalMetricsHttpLiveTest.answerGenerationCounter_shouldRenderAndIncrementForARealAnswer`.
- **Amended 2026-09-04 (OQ-022 / ADR-011).** The former closing note asked for a combined assertion
  that `cdk_answer_generation_total{outcome="timed_out"}` and
  `cdk_task_retry_exhausted_total{task_name="CHECK_STATUS_OF_ANSWER_GENERATION"}` fire on the same
  event, "so nobody later fixes it as a duplicate". **Both series are descoped, so that assertion is
  removed rather than unblocked** — there is no overlap to protect. The `timed_out`-absent negative
  control above replaces it, and it is the assertion that stops the withdrawn value reappearing.

---

## Story 6 — JobManager retry counter (`DD-43272`)

Targets `metrics/TaskRetryDecision`, `metrics/TaskRetryMetrics`, `metrics/TaskRetryMetricsAspect`
(design §7, ADR-006 as amended, **ADR-011**). **The seven `@Task` beans are not edited**, so their
existing unit and live tests are the regression net.

> ### OQ-022 — decided 2026-09-04: `cdk_task_retry_exhausted_total` is descoped
>
> The evidence this stage raised is confirmed and is the basis of the decision. Bytecode from
> `task-manager-service` 1.0.11:
>
> - `JobsRepository`'s assignment query is `… WHERE worker_id IS NULL AND (retry_attempts_remaining
>   IS NULL OR retry_attempts_remaining > 0) AND assigned_task_start_time <= :currentTime …` — a row
>   at `0` is never selected;
> - `TaskExecutor.performRetry(...)` calls `updateNextTaskRetryDetails(jobId, …, remaining - 1)`, so
>   the **last granted retry writes `0`** and that scheduled retry never runs;
> - `TaskExecutor.canRetry(task, info)` reads `info.isShouldRetry()` **and `this.job.getRetryAttemptsRemaining()`**
>   — so `remaining == 0` at execution time is unreachable for a job seeded from
>   `findRetryAttemptsRemainingFor(...)`;
> - CDKS's own production code already encodes this: `CheckIngestionStatusForAllDefendantsTask`
>   declares `LAST_RETRY_COUNT = 1` and treats `remaining == 1` as the final execution.
>
> **Exhaustion happens between executions, in the scheduler, with no task run at all**, so a counter
> incremented from inside a task execution cannot see it. `cdk_task_retry_exhausted_total` would
> have read permanently `0` for all six tasks with a real budget — a series an alert rule would be
> written against, reading `0` while work is abandoned, which is worse than no series.
>
> **Decision: the counter is not built.** Candidate (a) — redefining the predicate as
> `remaining <= 1` — was **rejected** (it replaces a replica of a documented predicate with a replica
> of the library's undocumented scheduling arithmetic, and would make `cdk_task_retry_total` report
> 49 for a 50-attempt budget: trading a missing signal for a wrong one; ADR-011(5)). Candidate (b)
> was rejected as a knowingly-misleading series. **Candidate (c) is adopted** — the
> `task-manager-service` exhaustion event, raised as its own follow-up ticket with a concrete ask
> (ADR-011(4)). FR-011, FR-012's exhaustion half and AC-020 are **descoped**.
>
> **`cdk_task_retry_total` is unaffected and every scenario about it stands.** Rewritten below:
> **6.1** (truth table — the two parked rows now have answers), **6.4** (was the "only reachable
> exhaustion path"), **6.5** (OQ-024), **6.10** (7 series, not 7+7), **6.14** (AC-007 documentation)
> and **6.15** (re-aimed at the surviving counter, new seam per OQ-028). **6.2, 6.3, 6.6, 6.7, 6.8,
> 6.9, 6.11, 6.12, 6.13, 6.16, 6.17 and 6.18 are unchanged in substance.**

---

**Scenario 6.1 — The replicated predicate's truth table** *(AC-001, AC-002)*
- **Given** `TaskRetryDecision.willBeRetried(info, task)` and a stub `ExecutableTask`
- **When** driven over every input combination:

  | `retryAttemptsRemaining` | `getRetryDurationsInSecs()` | Expected | Note |
  |---|---|---|---|
  | `null` | present | **false** | a job with no seeded budget |
  | `0` | present | **false** | **unreachable in production** (ADR-011) — kept as a boundary case, not as a production path |
  | `1` | present | **true** | **was the disputed row; settled 2026-09-04.** `canRetry` says true, so the predicate says true. The library then writes `0` and the row is never re-selected, so this *is* in fact the final execution — but the predicate replicates `canRetry`, not the scheduler's arithmetic, and `remaining <= 1` was explicitly rejected (ADR-011(5)) |
  | `5` | present | **true** | |
  | `5` | `Optional.empty()` | **false** | the `GENERATE_ANSWER_FOR_QUERY` shape |
- **And**, as a **signature-level** assertion: `willBeRetried(...)` takes exactly
  `(ExecutionInfo, ExecutableTask)` and **does not read `isShouldRetry()` at all** — driving the same
  inputs with `shouldRetry` `true` and `false` on the incoming `ExecutionInfo` yields the **same**
  result every time.
- **Then** the expected value results.
- **To be proven by:** `TaskRetryDecisionTest` as a parameterised truth table, plus
  `…shouldNotDependOnShouldRetry`.
- **OQ-023 — decided 2026-09-04 in favour of `02-design.md` §7's form; ADR-006(1)'s four-clause
  wording was the one corrected.** `shouldRetry` is **not** in the predicate; the caller tests it, so
  the full library decision a caller evaluates is `shouldRetry && willBeRetried(info, task)`. Two
  reasons from live call sites: `TaskRetryMetricsAspect.recordFromReturn` already gates on
  `INPROGRESS && isShouldRetry()` before consulting the predicate (a folded-in `shouldRetry` would be
  a redundant second test), and Story 5's `GenerateAnswerForQueryTask` site calls it where
  `shouldRetry` is about to be set true and is not yet on any `ExecutionInfo` the caller holds (a
  predicate reading `isShouldRetry()` would return false there for the wrong reason and look correct
  by accident). **The `shouldRetry=false` row is therefore removed from this table** — it is not an
  input — and the `shouldRetry`-independence assertion above replaces it. Story 6 AC-002's
  `INPROGRESS`-without-`shouldRetry` behaviour is asserted at the *aspect* level instead
  (Scenario 6.5).
- **This is the test that pins the replicated library predicate** — ADR-006's one acknowledged
  liability, **which ADR-011 does not remove**: the replica is still there, now for one counter. It
  must be written against the decompiled `canRetry` semantics, with the bytecode reference in the
  test's Javadoc, so a `task-manager-service` bump that changes `canRetry` fails here and not
  silently in production.

**Scenario 6.2 — `ExecutionInfo.Builder.from(...)` preserves `retryAttemptsRemaining`, which the predicate depends on** *(AC-001)*
- **Given** each of the seven `@Task` beans, all of which build their returned `ExecutionInfo` with
  `executionInfo().from(incoming)`
- **When** the returned info is inspected
- **Then** `getRetryAttemptsRemaining()` on the returned info equals the incoming value.
- **To be proven by:** one assertion per task's existing test class, or a single parameterised
  `TaskRetryMetricsAspectTest` case.
- **Why this is a scenario and not an implementation detail:** design §7's `recordFromReturn` reads
  the predicate from the **returned** info. If any task ever builds a fresh `ExecutionInfo` instead
  of `from(incoming)`, `retryAttemptsRemaining` becomes `null` and the aspect records a spurious
  *exhaustion*. All seven use `from(...)` today (verified); this test keeps it that way.

**Scenario 6.3 — `INPROGRESS` + `shouldRetry` with a live budget increments only `cdk.task.retry`** *(AC-001)*
- **Given** a stub `ExecutableTask` annotated `@Task(CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS)`
  returning `INPROGRESS` + `shouldRetry=true`, with a remaining budget and retry durations present
- **When** `TaskRetryMetricsAspect.aroundExecute(...)` advises it
- **Then** `cdk.task.retry{task_name=…,retry_policy="verify-document-status"}` increments by exactly
  1, and no other series in the registry moves. (**Amended 2026-09-04:** the original paired
  assertion "`cdk.task.retry.exhausted` is **unchanged**" is replaced — that meter no longer exists,
  so its absence is asserted once in Scenarios 6.4 and 6.10 rather than as an "unchanged" check
  here, which would silently pass against a non-existent meter and prove nothing.)
- **To be proven by:** `TaskRetryMetricsAspectTest.shouldRecordRetryGranted_whenBudgetRemains`.

**Scenario 6.4 — `INPROGRESS` + `shouldRetry` with no retry configuration records nothing at all** *(AC-002)*

**Rewritten 2026-09-04 (OQ-022 / ADR-011). This was "…increments only `cdk.task.retry.exhausted`";
that counter is withdrawn, so the expected behaviour inverts from "one increment" to "none".**

- **Given** a stub task annotated `@Task(GENERATE_ANSWER_FOR_QUERY)` whose
  `getRetryDurationsInSecs()` returns `Optional.empty()`, returning `INPROGRESS` + `shouldRetry=true`
- **When** the aspect advises it
- **Then** **neither** counter moves — `cdk.task.retry{task_name="GENERATE_ANSWER_FOR_QUERY",retry_policy="none"}`
  stays at `0`, and **no `cdk.task.retry.exhausted` meter exists in the registry at all**
  (`registry.find("cdk.task.retry.exhausted").meter()` is null).
- **To be proven by:** `TaskRetryMetricsAspectTest.shouldRecordNothing_whenTheTaskHasNoRetryConfiguration`
  and `…shouldNotRegisterAnExhaustionCounter`.
- **The registry-level absence assertion is the important half**, and it belongs here rather than
  only in Story 7's scrape test: it fails at the unit tier the moment someone re-adds the meter,
  before any integration run.
- **The signal is now weaker than ADR-006(6) claimed, and the test's Javadoc must say so.** The
  original design treated this task emitting `exhausted` on every failure as a *feature* that
  surfaced its missing `getRetryDurationsInSecs()` override. With no exhaustion counter, what
  surfaces the defect is a *permanently zero* `cdk_task_retry_total{task_name="GENERATE_ANSWER_FOR_QUERY"}`
  series — which reads identically to "this task never failed". That is why the series is still
  pre-registered at `0` (Scenario 6.10) and why Story 6 AC-007 requires the Javadoc to state both
  that the task can never be retried *and* that its zero is not evidence of health.

**Scenario 6.5 — `COMPLETED` and `STARTED` record nothing; `INPROGRESS` without `shouldRetry` records nothing** *(AC-005)*
- **Given** a stub task returning, in turn, `COMPLETED`, `STARTED`, and `INPROGRESS` with
  `shouldRetry=false`
- **When** the aspect advises each
- **Then** neither counter moves in any of the three cases.
- **To be proven by:** `TaskRetryMetricsAspectTest.shouldRecordNothing_whenNotAnInprogressRetry`
  (parameterised).
- **OQ-024 — decided 2026-09-04: an `INPROGRESS` result with `shouldRetry=false` counts as
  *neither* retried nor exhausted, and increments no counter.** The third case must therefore assert
  this **explicitly**, not incidentally: with `INPROGRESS` + `shouldRetry=false` and a **healthy
  remaining budget and retry durations present** — i.e. inputs on which `willBeRetried(...)` would
  return `true` — **still nothing is recorded**. Stubbing the budget healthy is what makes the case
  meaningful; asserting zero with an empty budget would pass for the wrong reason.
- **Write it even though it cannot happen today.** All seven CDKS tasks build `INPROGRESS` with
  `shouldRetry=true`, so this is unreachable with real tasks — but Story 6 AC-002 was worded
  absolutely ("every `INPROGRESS`-returning execution increments precisely one"), the correct scoping
  is "every `INPROGRESS` **and** `shouldRetry` execution", and pinning the boundary is exactly what
  stops a future implementer widening the aspect's condition and silently changing what the counter
  counts. Story 6 AC-002 has been reworded to match, so the AC and this scenario no longer conflict.
- Name the case for the boundary it pins, e.g.
  `shouldRecordNothing_whenInprogressWithoutShouldRetry_evenWithBudgetRemaining`.

**Scenario 6.6 — The throw path is covered, and the exception is rethrown unchanged** *(AC-001, AC-005)*
- **Given** a stub task that **throws** (the live shape: `CheckAllDocumentsIngestionStatusTask.execute`
  is unguarded, so `UUID.fromString(...)` on malformed job data propagates straight out)
- **When** the aspect advises it
- **Then** the predicate is computed from the **incoming** `ExecutionInfo` and the correct counter
  increments; and the caller receives **the same exception instance** (`isSameAs`), with identical
  type, message and cause.
- **To be proven by:** `TaskRetryMetricsAspectTest.shouldRecordFromTheIncomingInfo_andRethrowTheSameInstance_whenTheTaskThrows`.
- **This scenario is the whole justification for choosing an aspect over seven explicit call sites**
  (ADR-006(3), OQ-010(b)): `TaskExecutor.executeTask` catches `Exception` and synthesises
  `INPROGRESS` + `shouldRetry` **outside CDKS code**, so a task's own `retry(...)` helper never sees
  this path. If the gate ever revisits the aspect, this is the test that quantifies the cost.

**Scenario 6.7 — The aspect never alters the returned `ExecutionInfo`** *(AC-005)*
- **Given** any stub task returning a specific `ExecutionInfo` instance
- **When** the aspect advises it
- **Then** the object the caller receives is **reference-identical** to the task's return value.
- **To be proven by:** `TaskRetryMetricsAspectTest.shouldReturnTheTasksOwnExecutionInfoInstance`.
- NFR-004's strongest available assertion for an aspect wrapping every JobManager task execution.

**Scenario 6.8 — `task_name` comes from the `@Task` annotation, is proxy-aware, and is membership-checked** *(AC-003)*
- **Given** in turn: (a) a target class with `@Task(RETRIEVE_MATERIAL_AND_UPLOAD)`; (b) a
  **CGLIB-proxied** instance of the same class; (c) a target with no `@Task` annotation; (d) a target
  with `@Task("SOME_OTHER_TASK")`, a value outside `TaskNames`
- **When** the aspect advises each with an `INPROGRESS` + `shouldRetry` return
- **Then** (a) and (b) both record under `task_name="RETRIEVE_MATERIAL_AND_UPLOAD"` — proving
  `AopUtils.getTargetClass(...)` is used rather than `getClass()`; (c) and (d) record **nothing** on
  either counter.
- **To be proven by:** `TaskRetryMetricsAspectTest.shouldReadTaskNameThroughAopUtils_andRecordNothingForANonMemberValue`
  (parameterised).
- **(b) is not optional.** `getClass()` on a CGLIB proxy returns `Foo$$SpringCGLIB$$0`, which carries
  no `@Task` annotation, so a `getClass()`-based implementation would silently record nothing in
  production while passing (a). AC-003's "no other value can ever be emitted" is structural only if
  this case is tested.

**Scenario 6.9 — `retry_policy` is determined by `task_name`, for all seven, at zero series cost** *(AC-004)*
- **Given** each of the seven `TaskNames` values
- **When** the policy is resolved
- **Then** it is exactly: `GET_CASES_FOR_HEARING` → `default-retry`;
  `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS` → `default-retry`; `RETRIEVE_MATERIAL_AND_UPLOAD` →
  `default-retry`; `CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS` → `verify-document-status`;
  `CHECK_ALL_DOCUMENTS_INGESTION_STATUS` → `verify-document-status`;
  `CHECK_STATUS_OF_ANSWER_GENERATION` → `questions-retry`; `GENERATE_ANSWER_FOR_QUERY` → `none`;
  **and** the counter registers exactly 7 series, not 28 — `retry_policy` adds a label, not a
  dimension. (**Amended 2026-09-04:** "each counter" → "the counter"; there is one after ADR-011.)
- **To be proven by:** `TaskRetryMetricsTest.shouldMapEveryTaskNameToItsRetryPolicy` (parameterised)
  and `…shouldRegisterSevenSeries_notACrossProduct`.

**Scenario 6.10 — The retry counter exists at zero for all seven tasks, unconditionally — and no exhaustion counter exists** *(AC-001, AC-002)*

**Amended 2026-09-04 (OQ-022 / ADR-011): 7 series, not 7 + 7.**

- **Given** a fresh registry, `cdk.metrics.enabled` **false** (so `TaskRetryMetricsAspect` is absent)
- **When** `TaskRetryMetrics` is constructed — an unconditional bean (ADR-010(4))
- **Then** **7** series exist at `0` with their `retry_policy` labels, one per `TaskNames` value
  (including `GENERATE_ANSWER_FOR_QUERY`/`none`, which can never be non-zero — Scenario 6.4), even
  though nothing can record to them; **and `TaskRetryMetrics` exposes no exhaustion counter, no
  `recordRetryExhausted(...)` method, and registers no `cdk.task.retry.exhausted` meter.**
- **To be proven by:** `TaskRetryMetricsTest.shouldPreRegisterTheRetryCounterForAllSevenTasks_evenWhenRecordingIsDisabled`
  and `…shouldNotRegisterAnExhaustionCounter`.
- The method-level absence assertion (no `recordRetryExhausted`) is a compile-time fact rather than a
  runtime one; assert the **meter's** absence at runtime and leave the method to Code Review.

**Scenario 6.11 — An `Error` from a task propagates and is not recorded** *(design §7, ADR-010(1))*
- **Given** a stub task throwing an `OutOfMemoryError`
- **When** the aspect advises it
- **Then** the `Error` propagates unchanged and **neither** counter moves — the aspect catches
  `Exception`, not `Throwable` (PMD `errorprone.AvoidCatchingThrowable`, and DD-43185 §5's ruling).
- **To be proven by:** `TaskRetryMetricsAspectTest.shouldPropagateError_andRecordNothing`.

**Scenario 6.12 — A recording failure inside the advice cannot affect the task** *(AC-008)*
- **Given** a throwing registry, or `getRetryDurationsInSecs()` itself throwing, or
  `AopUtils.getTargetClass(...).getAnnotation(Task.class)` returning null
- **When** the aspect advises a task on both the return and the throw path
- **Then** the task's return value is reference-identical, its thrown exception is the same instance,
  and no recording failure surfaces.
- **To be proven by:** `TaskRetryMetricsAspectTest.shouldContainRecordingFailures_onBothPaths`
  (parameterised over the three failure injections).

**Scenario 6.13 — The *effective* retry budgets are pinned, including the key that does not bind** *(AC-007)*
- **Given** the shipped `application-cdk.yml`
- **When** `JobManagerRetryProperties` is bound
- **Then** the effective budgets are `default-retry` **3 × 20 s**, `verify-document-status`
  **50 × 5 s**, `questions-retry` **100 × 10 s**;
  **and** the YAML key `cdk.jobmanager.retry.default` does **not** bind to `setDefaultRetry(...)`, so
  `defaultRetry` holds its Java field defaults and
  `CDK_JOBMANAGER_RETRY_DEFAULT_MAX_ATTEMPTS` / `…_DELAY_SECONDS` are **inert** — asserted by setting
  those environment/property values to something else and observing no change.
- **To be proven by:** `JobManagerRetryPropertiesTest`.
- **Small test, disproportionate value.** Design §7's ⚠ finding is that the numbers coincide today,
  so nothing misbehaves — which means the defect is invisible and could be "fixed" by a rename that
  silently changes the effective budget from 3 to whatever the environment says. FR-012's
  documentation must state the *effective* numbers, and this test is what keeps the documentation
  honest. The defect itself is a separate ticket, not fixed here.

**Scenario 6.14 — FR-012's documentation exists and says the right things** *(AC-007)*
- **Given** `CdkMeters`' Javadoc after this story
- **When** it is reviewed
- **Then** — **rewritten 2026-09-04 (ADR-011)** — it lists all seven `task_name` values with their
  `retry_policy` and their **effective** budget (per Scenario 6.13, not the YAML's stated values);
  states plainly that `GENERATE_ANSWER_FOR_QUERY` cannot be retried at all **and that its
  permanently-zero `cdk_task_retry_total` series is not evidence of health**; **states that
  retry-budget exhaustion is not detected anywhere in CDKS** — `cdk.task.retry` counts *granted*
  retries only, a task whose budget runs out is abandoned by the library between executions and is
  counted nowhere, `cdk_answer_generation_total`'s total is therefore an undercount of ended
  transactions, `succeeded / (succeeded + failed)` must not be published as a success rate, and the
  `task-manager-service` exhaustion event (ADR-011(4)) is the follow-up that closes it; documents
  `OUTCOME_FAILURE` (`"failure"`) and `OUTCOME_FAILED` (`"failed"`) as two deliberately distinct
  values each naming its meter (OQ-031); and does **not** carry forward the uncorrected claim that
  `cdk.metrics.enabled=false` removes the `@Task` beans' proxying (true only until DD-43183 ships).
- **Two clauses are explicitly *removed* from the original review checklist, and a reviewer should
  check they are absent:** the identification of `cdk_task_retry_exhausted_total` as "the primary
  work-is-being-silently-abandoned signal" (there is no such signal in this ticket), and the note
  about a deliberate overlap with `cdk_answer_generation_total{outcome="timed_out"}` (neither series
  exists). Carrying either forward would leave the Javadoc claiming a capability that was descoped —
  the single most likely way this decision gets silently reversed in the codebase.
- **To be proven by:** a **diff-level check at Code Review**. A test cannot prove a Javadoc sentence;
  this is recorded as an explicit review item so it is not lost.

**Scenario 6.15 — Granted retries, counted against the library's actual behaviour, and no exhaustion counter anywhere** *(AC-006)*

**Re-aimed 2026-09-04, not deleted (OQ-022 / ADR-011 + OQ-028).** The exhaustion assertion is gone,
but the *reason this test exists* is not: `cdk.task.retry` still replicates a library predicate, and
this is still ADR-006(5)'s only real mitigation for that. Renamed
`TaskRetryExhaustionHttpLiveTest` → **`TaskRetryHttpLiveTest`**.

- **Given** the compose stack, a baseline scrape, and a `jobs` row **seeded directly via JDBC** with
  a **small** explicit `retry_attempts_remaining` (say `2`) using the established live-test idiom
  (`INSERT INTO jobs (job_id, assigned_task_name, assigned_task_start_time, job_data, priority,
  retry_attempts_remaining, worker_id, worker_lock_time)`, already used verbatim by
  `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` and
  `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest`, over the compose PostgreSQL reachable
  through `AbstractHttpLiveTest.openConnection()`), plus a WireMock RAG status stub that **never**
  returns a terminal status
- **When** the live app's `JobExecutor` picks the row up, runs it to the end of its budget, and the
  endpoint is re-scraped
- **Then**:
  - `cdk_task_retry_total{task_name=…,retry_policy=…}` has increased by **exactly the number of
    retries the library actually granted** for the seeded budget — the number is a direct function of
    the seeded value and must be asserted as an exact delta, not `>= 1`, because an off-by-one here is
    precisely the predicate drift the test exists to catch;
  - the `jobs` row ends with `retry_attempts_remaining = 0`, is **not** re-executed and is **not**
    deleted (the library abandons it in place);
  - **`cdk_task_retry_exhausted_total` is absent from the scrape body entirely, in any form** — the
    negative control that pins the descoping at the integration tier.
- **To be proven by:** `TaskRetryHttpLiveTest.taskRetryCounter_shouldMatchTheRetriesTheLibraryActuallyGranted`
  and `…shouldNotExposeAnExhaustionCounter`.
- **Still the most important integration test in the ticket, and no longer blocked.** It ties the
  CDKS-side prediction to the library's real behaviour, so a `task-manager-service` bump that changes
  `canRetry` fails CI instead of silently making the counter wrong. The exact-delta assertion is what
  makes that true: a predicate that became `remaining <= 1` (the rejected ADR-011(5) workaround) would
  report one fewer retry than the library granted, and this test would catch it.
- **Note what this test now also documents:** the `jobs` row ending at `0` with no counter having
  moved for it **is** the uncounted abandonment. Say so in the test's Javadoc, naming ADR-011 and the
  `task-manager-service` follow-up — the assertion on the row's end state is the closest thing the
  suite has to evidence of the gap.
- **Seed the `jobs` row; do not shorten the compose retry budget — OQ-028, decided 2026-09-04.**
  Design §10 originally proposed `CDK_JOBMANAGER_RETRY_VERIFY_DOC_MAX_ATTEMPTS: 2` in compose, but
  that key governs **two** tasks for the **whole** live suite, and `IngestionProcessHttpLiveTest`,
  `IngestionStatusHttpLiveTest` and `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest` all depend
  on polling to completion within the shipped budget. **Per-row seeding is already the house idiom in
  this codebase's live tests**, is scoped to one test, and needs no compose change — so
  `docker/docker-compose.integration.yml` is untouched by this ticket. Use the same
  `@BeforeAll`/`@AfterEach`-style seed-and-clean pattern the two existing job-seeding live tests use.
- Delete the seeded `jobs` row and any dependent rows in a `finally` block (or `@AfterEach`) — the
  compose database is shared.

**Scenario 6.16 — Aspect ordering with DD-43183's `JobCorrelationAspect`** *(Story 6 DoD, mandatory)*

**Reconciled 2026-09-03 — this scenario is now a reference, not an independent spec.**
`docs/pipeline/DD-43183-correlation-id-unification/04-test-specs.md` reached Stage 4 after this
document was first drafted; its **Scenario 3.5** is the canonical ordering test and this ticket
adopts it by reference rather than maintaining a second, differently-shaped definition of
"outermost" (exactly the risk this scenario originally flagged in its own OQ-035). Scenario 3.5's
mechanism is preferred over this scenario's original draft because its primary assertion — a
test-only probe aspect declared at `Ordered.LOWEST_PRECEDENCE` observing `MDC.get("correlationId")`
already populated at its own entry — is **true and provable with `TaskRetryMetricsAspect` entirely
absent**, satisfying "passes whether or not DD-43182 has landed" without depending on this ticket's
own aspect to exist first. This scenario's original draft (behavioural: force a recording failure
and check the resulting WARN's MDC) could not do that, since it needs `TaskRetryMetricsAspect`'s
own advice to run at all.

- **To be proven by:** `JobCorrelationProxyingTest.jobCorrelationAspect_shouldRunOutermostOnExecutableTaskExecute`
  (DD-43183, unconditional, the primary mechanism) and
  `…shouldOrderJobCorrelationAspectBeforeTaskRetryMetricsAspect_whenDd43182HasLanded` (DD-43183,
  conditional on `TaskRetryMetricsAspect` being present on the classpath — reads the proxy's advisor
  chain via `((Advised) bean).getAdvisors()` and asserts the correlation advisor precedes the metrics
  advisor when both exist).
- **This story's own obligations, unchanged:** `TaskRetryMetricsAspect` must declare **no** `@Order`
  annotation (Spring AOP's default `LOWEST_PRECEDENCE`) — adding one "for clarity" would invert the
  ordering DD-43183's conditional assertion depends on. Whichever ticket implements second is
  responsible for confirming the conditional half actually passes against the merged classpath — it
  cannot be exercised until both aspects coexist.
- **Also still this story's obligation:** assert that with both aspects present the seven `@Task`
  beans stay CGLIB-proxied **regardless of `cdk.metrics.enabled`**, because Spring merges same-bean
  aspects into one proxy — correcting this ticket's own design §7/§10 claim. This half has no DD-43183
  equivalent since it's specific to this ticket's kill-switch semantics; it remains a DD-43182 test.
- **OQ-035 — re-verified after the OQ-022 changes and still correct (2026-09-04).** ADR-011 withdraws
  a *counter*, not the aspect: `TaskRetryMetricsAspect` still exists and still records
  `cdk.task.retry`, so it is still a real advisor on
  `execution(* uk.gov.hmcts.cp.cdk.jobmanager..*.execute(uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo))`.
  The join point, the ordering requirement, the "no `@Order` on `TaskRetryMetricsAspect`" rule and
  DD-43183 Scenario 3.5's conditional advisor-chain assertion are therefore all **unaffected**, and
  the cross-reference stands as written. No change needed.

**Scenario 6.17 — All seven tasks still register, and the existing job-flow live tests still pass** *(NFR-005, ADR-006 Consequences)*
- **Given** the complete Story 6 merged — introducing the **first** `@Aspect` in this codebase and
  therefore CGLIB proxies over the seven `@Task` beans
- **When** `gradle integration` runs
- **Then** all seven tasks are registered at startup (`TaskRegistry.autoRegisterTasks()` already calls
  `AopUtils.getTargetClass(bean)` before reading `@Task`, so it is proxy-aware — verified in the
  bytecode, but that is the thing that would break); and
  `IngestionProcessHttpLiveTest`, `IngestionProcessByCaseHttpLiveTest`, `IngestionStatusHttpLiveTest`,
  `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest` and
  `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` all pass with their assertions
  **unmodified**.
- **To be proven by:** the existing suites as regression, plus one added startup assertion that all
  seven `TaskNames` values resolve to a registered task.
- These existing suites exercise all seven task beans, so they are the real safety net for the
  proxying risk. An added "all seven registered" assertion turns a diffuse failure into a specific
  one.

**Scenario 6.18 — The kill switch removes the aspect but not the series** *(NFR-009, AC-002)*
- **Given** `cdk.metrics.enabled=false`
- **When** the application context starts
- **Then** no `TaskRetryMetricsAspect` bean exists; the seven `@Task` beans are **not** proxied by it;
  `TaskRetryMetrics`' 14 series are still registered at `0`; and no recording occurs.
- **To be proven by:** `TaskRetryMetricsAspectConditionalTest`, mirroring the existing
  `StalledWorkMetricsRefreshJobConditionalTest`.
- **The "not proxied" half is true only in isolation** — once DD-43183's non-optional
  `JobCorrelationAspect` ships, the proxying persists regardless of this flag. Write the assertion so
  it tests what this story controls (the bean's absence and recording stopping), not the proxying,
  or it will break when DD-43183 lands. See Scenario 6.16.

---

## Story 7 — Cardinality budget, scrape-time bound and cross-cutting safety harness (`DD-43273`)

Owns the whole-endpoint assertions, the `MetricsSafety` direct coverage, and the final `CdkMeters`
Javadoc pass (design §9, §10, §12; ADR-001, ADR-005, ADR-010). Sequenced last by necessity — its
central test scrapes series registered by all six other stories.

---

**Scenario 7.1 — All seven rendered Prometheus names are present with their documented tag sets, the registered ids match the constants, and the withdrawn meter is absent** *(AC-001)*
- **Given** the compose stack with all six other stories merged
- **When** `GET /actuator/prometheus` is scraped
- **Then** all **seven** rendered names from §"The contract under test" are present as **sample
  lines** (not merely `# HELP` / `# TYPE` headers), with their documented tag sets — including every
  counter and timer series that has **not** yet been incremented (**95** series pre-registered at
  `0`);
- **And, in the same test:** `GET /actuator/metrics/{id}` returns `200` for every **Micrometer** id
  taken from its `CdkMeters` constant (`cdk.document.ingestion.phase`,
  `cdk.document.ingestion.duration`, `cdk.external.call.duration`, `cdk.answer.generation`,
  `cdk.task.retry`, `cdk.http.pool.connections.leased`) — **and returns `404` for
  `cdk.task.retry.exhausted`**, which is the registry-side half of the descoping assertion
  (ADR-011).
- **Amended 2026-09-04 (OQ-022 / ADR-011):** eight names and 106 pre-registered series before; seven
  and 95 now. `cdk.task.retry.exhausted` is removed from the id list and asserted absent instead.
- **To be proven by:** `OperationalMetricsHttpLiveTest.prometheusScrape_shouldExposeAllEightOperationalMetricsTogether`.
- **This pairing is what actually proves ADR-001, and neither half does it alone** — exactly as
  DD-43185's Scenario 5.1 established and `MonitoringMetricsHttpLiveTest` now implements. The
  `/actuator/metrics/{id}` half uses the constants production registers with; the scrape half uses
  **hard-coded Prometheus literals**. Using constants on both sides would assert only that the
  constants equal themselves.
- **The Timer rows are the new risk this ticket adds** (ADR-001(1)): `cdk.external.call.duration`
  registered *with* a `.seconds` segment would render identically through
  `PrometheusNamingConvention`'s `endsWith` guard, and a derived-name test could never tell. Hence
  Scenario 7.2.

**Scenario 7.2 — The naming negative controls, both directions, for all seven meters — plus the two descoping controls** *(AC-001)*
- **Given** the same scrape body
- **When** it is searched for names that must **not** appear
- **Then** none of the following is present: `cdk_document_ingestion_phase{`,
  `cdk_answer_generation{`, `cdk_task_retry{` (counters must never render bare, without the
  exposition-layer `_total`); any `…_total_total` (never doubled);
  `cdk_document_ingestion_duration_bucket`, `cdk_document_ingestion_duration_count`,
  `cdk_external_call_duration_count` (Timers must render **with** `_seconds`);
  any `…_seconds_seconds` (the `_seconds` segment must not be in the registered name);
  **no `cdk_external_call_duration_seconds_bucket` at all**;
  **and — added 2026-09-04 (ADR-011) — no `cdk_task_retry_exhausted` in *any* form
  (`cdk_task_retry_exhausted`, `cdk_task_retry_exhausted_total`, with or without labels), and no
  `timed_out` appearing as an `outcome` label value on any `cdk_answer_generation_total` sample
  line.** Note that `cdk_task_retry_exhausted{` was previously listed here as a *bare-name* negative
  control alongside its `_total` form being **required**; both forms are now required to be absent,
  which is a different assertion and must be written as such.
- **To be proven by:** the same test method as 7.1, as a block of `doesNotContain` assertions with
  an `as(...)` description on each explaining what it catches.
- The last one is the automated guard on ADR-005's series budget — the difference between 33 series
  and several thousand — and it is one line.

**Scenario 7.3 — Every `cdk_*` series carries `service`, `cluster` and `region`** *(AC-002)*
- **Given** the same scrape body
- **When** every sample line whose name starts `cdk_` (and every `httpcomponents_httpclient_pool_*`
  line) is parsed
- **Then** each carries `service="cp-case-document-knowledge-service"`, a `cluster` label and a
  `region` label — `local` in the compose stack.
- **To be proven by:** `OperationalMetricsHttpLiveTest.everyCdkSeries_shouldCarryTheCommonTags`.
- These tags come from `management.metrics.tags`, which is **already configured** — this ticket
  asserts them rather than adding them (FR-013). Match on a parsed label set, never on a whole-line
  literal with a hand-written label order.

**Scenario 7.4 — Every emitted tag value is a member of its enumerated set** *(AC-001, AC-010; requirements AC-003/AC-011/AC-019/AC-030)*
- **Given** the same scrape body, after the suite has exercised the ingestion, external-call,
  answer-generation and task-retry paths
- **When** every label value on every `cdk_*` series is extracted per tag key
- **Then** each is a member of the corresponding set in §"The contract under test" —
  `phase` ⊆ 5 (counter) / 3 (timer), `source` ⊆ {`IDPC`, `unknown`}, `dependency` ⊆ 4,
  `operation` ⊆ 11, `outcome` ⊆ 5 or 3 per meter, `query_level` ⊆ 4, `task_name` ⊆ 7,
  `retry_policy` ⊆ 4 — and **nothing else**, asserted as set containment plus an explicit assertion
  that no value matches a UUID shape, contains `/`, `{`, `}`, or a digit run of four or more.
- **To be proven by:** `OperationalMetricsHttpLiveTest.everyEmittedTagValue_shouldBeAMemberOfItsEnumeratedSet`.
- **This is the one test that would catch a case identifier reaching a label** — the NFR-001 failure
  the whole ticket is written to prevent. It is a *whole-endpoint* assertion, so it also catches a
  leak introduced by a future story that this spec does not know about.

**Scenario 7.5 — The pre-implementation baseline series count is captured** *(AC-003)*
- **Given** `origin/develop` **before** any DD-43182 change
- **When** `docker compose -f docker/docker-compose.integration.yml up -d --build` is run and
  `/actuator/prometheus` is scraped
- **Then** `docs/pipeline/DD-43182-operational-metrics-instrumentation/baseline-series-count.md`
  records the whole-endpoint series count (non-comment, non-blank sample lines), the method used, the
  commit, and the family count for cross-reference against DD-43185's
  `baseline-actuator-prometheus.md` (which records 76 families and **no** series count).
- **To be proven by:** the artefact itself, captured the same way DD-43185's families baseline was.
- **OQ-034 — decided 2026-09-04: capture this when Story 7 is built, not before Story 1 starts.**
  The earlier framing ("capture it before Story 1, it is a pre-implementation measurement by
  definition") is **withdrawn**. The concern it addressed is handled by *what* is measured rather
  than *when*: take the scrape from a commit that does **not** contain DD-43182's changes — a clean
  `origin/develop` checkout, or `git stash` the working tree, bring the compose stack up, scrape,
  record the commit SHA in the artefact — so it is a genuine pre-implementation number whatever the
  date. Keeping the capture with the **ceiling assertion** it feeds (7.6), in one story with one
  owner, is simpler and less error-prone than a hand-off from a story that has no other reason to
  touch it. **Record the measured commit SHA in the artefact**; that, not the date, is what makes the
  baseline auditable.

**Scenario 7.6 — The whole-endpoint series count stays under a stated ceiling, and DD-43182's own contribution matches its computed arithmetic** *(AC-003)*
- **Given** the compose stack with the complete ticket merged
- **When** `/actuator/prometheus` is scraped and its sample lines counted
- **Then** the **whole-endpoint** count is below a stated ceiling set from Scenario 7.5's measured
  baseline (design proposes **1,200**), with the compose-is-not-production reasoning in the
  assertion's own failure message;
  **and**, as the sharper drift-catching assertion, the count of `cdk_*` plus
  `httpcomponents_httpclient_pool_*` sample lines is **≤ 246** (DD-43182's **232** worst case plus
  DD-43185's unchanged 14) and **≥ 109** (the **95** + 14 pre-registered).
- **To be proven by:** `PrometheusSeriesBudgetHttpLiveTest.wholeEndpointSeriesCount_shouldStayUnderTheStatedCeiling`
  and `…cdkSeriesCount_shouldMatchTheComputedBudget`.
- **Merge-blocking** (ADR-005(6)).
- **Amended 2026-09-04 (OQ-022 / ADR-011):** the bounds were `≤ 257` / `≥ 120`; they are now
  `≤ 246` / `≥ 109`, because `cdk_task_retry_exhausted_total` (−7) and
  `cdk_answer_generation_total{outcome="timed_out"}` (−4) are not built. **The lower bound matters as
  much as the upper one here** — it is what fails if a withdrawn meter is quietly re-added, or if a
  pre-registered series stops being registered.
- **OQ-034 — decided 2026-09-04: the whole-endpoint ceiling number is set from Scenario 7.5's
  baseline, captured within this same story.** The *assertion shape* is fixed here; the number is
  7.5's output, and both land in the same PR, so the earlier "capture before Story 1" sequencing is
  no longer needed. ADR-005(6) records honestly that the number must be measured rather than
  guessed — design proposes 1,200 as a starting point, to be adjusted to the measured baseline plus
  documented headroom.
- The second assertion is the more valuable of the two, because the whole-endpoint ceiling is
  dominated by `http_server_requests_seconds_*` growth that has nothing to do with this ticket,
  whereas the `cdk_*` bound fails precisely when someone enables `percentiles-histogram` or widens a
  tag set.

**Scenario 7.7 — Scrape time, as a CI smoke bound** *(AC-004, GATE-6)*
- **Given** the compose stack with the complete ticket merged
- **When** `/actuator/prometheus` is scraped several times — the first few discarded as warm-up (JIT,
  first-scrape gauge binding) — and the slowest of the remaining N measured
- **Then** it is below **2 s**, with the assertion message stating explicitly that this is a **CI
  smoke bound on shared hardware, not a production guarantee**.
- **To be proven by:** `PrometheusSeriesBudgetHttpLiveTest.prometheusScrape_shouldCompleteWithinTheCiSmokeBound`.
- **AC-024 is re-scoped, per the accepted GATE-6** — the original "under 1 second" is not assertable
  on shared CI hardware without becoming a flaky test, the same argument DD-43185 §12 used for its
  500 ms `EXPLAIN` bound and the same outcome DD-43185's AC-012 reached. **This spec writes the test
  against the re-scoped form and makes no claim to close the original wording.**
- The one-off production scrape timing is a **Stage-8 `deploy-notes.md` capture**, not a test.
- Warm-up discarding is deliberate: NFR-003 is a structural property (nothing is computed on scrape —
  see 7.8), so a first-scrape outlier measures JVM warm-up, not the metric surface.

**Scenario 7.8 — Nothing is computed on scrape** *(NFR-003)*
- **Given** the complete ticket
- **When** the diff is reviewed and the pool gauges' suppliers inspected
- **Then** every counter and timer is written on the **business** path; the pool gauges read
  `ConnPoolControl.getTotalStats()`, an in-memory struct; and no scrape touches the database, a remote
  service or a lock — in contrast to DD-43185's stalled-work gauges, which are refreshed by a
  ShedLock-guarded job precisely so that they too are not computed on scrape.
- **To be proven by:** a diff-level check plus Scenario 4.5's structural assertion. Stated as
  structural, not claimed as test-proven.

**Scenario 7.9 — `MetricsSafety`: containment, the 60-second throttle, the suppressed count, and `Error` propagation** *(AC-005, AC-006)*
- **Given** `MetricsSafety.runSafely(...)`
- **When** driven with: (a) a `Runnable` throwing a `RuntimeException`; (b) many such failures in
  rapid succession; (c) failures spanning more than 60 s of the helper's time source; (d) a
  `Runnable` throwing an `Error`
- **Then** (a) nothing propagates; (b) **at most one** WARN is logged, globally — not once per site,
  not once per occurrence — and its message includes the count of suppressed failures; (c) a second
  WARN is emitted after the window; (d) the `Error` **propagates** and is not swallowed;
  and in every WARN the line contains the metric *area* and the exception object and **nothing
  else** — no case id, doc id, defendant id, material id, court reference, `CJSCPPUID`, RAG
  transaction id, blob URI, document name or answer text.
- **To be proven by:** `MetricsSafetyTest`, with a `ListAppender<ILoggingEvent>` on the
  `MetricsSafety` logger (the `DiscoveryTriggerServiceTest` idiom).
- **OQ-030 — decided 2026-09-04: `MetricsSafety` gains a time seam, so case (c) is written against
  it and never sleeps.** The helper carries a **package-private, settable time source** — a
  `LongSupplier` of epoch seconds, or an equivalent settable clock field — defaulting to the real
  clock. Case (c) is then: trigger a failure (one WARN), advance the seam past 60 s, trigger another,
  assert a **second** WARN whose suppressed count reflects the failures swallowed in between; and, as
  the paired negative control, advance it by **less** than 60 s and assert **no** second WARN.
  **Reset the seam in `@AfterEach`** — it is static state shared across tests, and leaving it
  advanced would make later tests order-dependent.
- **The seam is test-only by convention, and the test should not pretend otherwise:** package-private,
  no public setter, **no property binding, not a new production knob**, and production code never
  passes anything but the default. A Code Review diff check confirms it is not exposed publicly or
  bound from configuration. **Do not ship a 60-second unit test** under any circumstances.
- **The JSON-encoding half is not unit-observable.** AC-006's "emitted as structured JSON via the
  existing `logback-spring.xml`" is inherited from the unchanged appender configuration and is a
  **diff-level** check (no new appender, no Logback filter, no `System.out`). State both; do not claim
  the `ListAppender` proves the encoding — DD-43185's Scenario 1.3 drew the same line.

**Scenario 7.10 — Containment across all four business-path shapes, with an injected throwing registry** *(AC-005)*
- **Given** a `MeterRegistry` (or individual meters) that throws on every operation, injected into
  each area in turn
- **When** each of the four business-path shapes runs — (a) an ingestion phase write, (b) an outbound
  dependency call, (c) a JobManager task execution through the aspect, (d) an answer-generation state
  transition
- **Then** each completes exactly as it would without instrumentation: (a) same HTTP status and body
  and same persisted phase; (b) same returned instance or same thrown instance, and **no RAG response
  field dropped or altered**; (c) same `ExecutionInfo` instance and same thrown instance; (d) same
  persisted job data and same `ExecutionInfo`.
- **To be proven by:** one case per area (`IngestionMetricsTest`, `ExternalCallMetricsTest`,
  `TaskRetryMetricsAspectTest`, `CheckStatusOfAnswerGenerationTaskTest`) — Story 7's contribution is
  ensuring **all four** exist rather than one representative, per its AC-005's explicit "across all
  four business paths, not just one".
- Each area story also has its own containment AC (1.9, 2.8, 3.13, 5.11, 6.12), which exercises
  `MetricsSafety` indirectly. This scenario is the cross-cutting audit that none was skipped.

**Scenario 7.11 — `cdk.metrics.enabled` binds, defaults to `true`, and is not overridden in compose** *(NFR-009)*
- **Given** `config/MetricsProperties` and the shipped `application-cdk.yml`
- **When** the property is bound, and when it is absent entirely
- **Then** it binds from `cdk.metrics.enabled`, resolves from `CP_CDK_METRICS_ENABLED`, and defaults
  to `true` both in YAML and as the Java field default;
  **and** `docker/docker-compose.integration.yml` does **not** override it — the live suite
  exercises the shipped default, so the whole metric surface is exercised on every `gradle build`.
- **To be proven by:** `MetricsPropertiesTest` plus a diff-level check on the compose file.

**Scenario 7.12 — DD-43185's meters, and every existing operational parameter, are unchanged** *(AC-008)*
- **Given** the complete ticket merged
- **When** `gradle integration` runs and the PR diff is reviewed
- **Then** `ActuatorHttpLiveTest`, `MonitoringMetricsHttpLiveTest` and `SchedulerMetricsHttpLiveTest`
  all pass **with their files absent from the diff**; DD-43185's six meter names and 14 series are
  present and unchanged; and no existing timeout, retry budget, pool size, cron expression, ShedLock
  lock name or lock duration has changed in `src/main/resources`.
- **To be proven by:** the `gradle integration` run **plus a diff-level check**, which is the stronger
  evidence of the two: this ticket is purely additive, so a green run alone would not necessarily
  fail even if something existing had drifted.
- **One deliberate exception to state at the gate:** the compose file gains test-only overrides
  (whatever OQ-026 and OQ-028 settle on). Those are *test* configuration, not shipped configuration —
  and Scenario 6.13 asserts the shipped budgets separately, which is what keeps the distinction
  honest.

**Scenario 7.13 — Quality gates green at unmodified thresholds** *(AC-009)*
- **Given** the complete ticket — eleven new `metrics/` and `config/` classes, one aspect, edits to
  fifteen production classes, two YAML blocks and a compose change
- **When** `gradle clean build` (which includes `integration`) runs, followed by
  `gradle pmdMain pmdTest jacocoTestReport`, CodeQL and the secrets scanner
- **Then** all pass at existing, **unmodified** thresholds, with no new PMD suppression and no
  lowered JaCoCo limit.
- **To be proven by:** the CI workflows (`ci-build-publish`, `code-analysis`, `codeql`,
  `secrets-scanner`) plus a local `gradle clean build`.
- **Four things to confirm rather than assume:**
  (a) **`CdkMeters` is a known JaCoCo coverage sink** — a constants class with a throwing private
  constructor, now much larger. Handle it exactly as `util/TimeUtils` and DD-43185 already handle it;
  do **not** invent a new exclusion or lower a threshold.
  (b) **`OutcomeClassifier`'s default branch** and **`MetricsSafety`'s catch block** are the other two
  likely coverage dips; Scenarios 3.4/3.6 and 7.9 are what cover them, so write those first.
  (c) **`catch (Exception)`** in `MetricsSafety` and in the aspect passes PMD, because
  `.github/pmd-ruleset.xml` enables `errorprone.AvoidCatchingThrowable` but not the `design` category
  where `AvoidCatchingGenericException` lives (DD-43185 §5's finding, re-confirmed at ADR-006(3)). If
  it nonetheless fires, the fix is a narrower catch or a reviewed rule discussion — never a
  suppression.
  (d) the aspect declares `throws Throwable` (required by `ProceedingJoinPoint.proceed()`) while
  catching only `Exception` — confirm PMD accepts that pairing.

**Scenario 7.14 — No PII, case content or real identifier anywhere in the ticket's material** *(AC-010)*
- **Given** the new unit tests, the new live tests, the seeded `jobs` and `case_documents` rows, the
  WireMock stub bodies, the Azurite seed data, the compose changes, the RAG parity fixtures and the
  `CdkMeters` Javadoc
- **When** the diff is reviewed
- **Then** every value is synthetic — `UUID.randomUUID()` / `gen_random_uuid()` for ids,
  obviously-synthetic literals for `doc_name`, `blob_uri` and `blob_name`, no real court reference
  number, no real `CJSCPPUID`, no case content, no document body, no answer text, no real
  `llm_input`; every metric tag value comes from a fixed constant or enum set; and the only new log
  line in the whole ticket — `MetricsSafety`'s throttled WARN — carries nothing beyond a metric-area
  name, a suppressed count and an exception object.
- **To be proven by:** the secrets scanner, the `block-pii` / `block-secrets` plugin hooks (which run
  on every `Write`/`Edit`, so a violation is blocked at authoring time), and explicit reviewer
  sign-off at Code Review.
- **Specific to this ticket:** Scenario 3.8's RAG parity fixtures are the highest-risk material,
  because a realistic-looking `llm_input` is exactly what makes such a fixture tempting. Re-read them
  at review against NFR-001.

---

## Coverage summary — **planned**, not achieved

No row below is evidence of a passing test.

### Story 1 — Document-ingestion phase counter (`DD-43267`)

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 one increment per persisted transition | 1.2, 1.3, 1.4, 1.5, 1.11 | planned (4 classes) | planned (delta) | `IdpcAvailabilityServiceTest`, `RetrieveMaterialAndUploadTaskTest`, `CheckIngestionStatusForAllDefendantsTaskTest`, `OperationalMetricsHttpLiveTest` |
| AC-002 exactly `phase` + `source`; five reachable values | 1.1, 1.7, 1.8, 1.10 | planned | planned | `IngestionMetricsTest`, `OperationalMetricsHttpLiveTest` |
| AC-003 `source` allow-list, never read through | 1.6 | planned (parameterised) | — | `IngestionMetricsTest.shouldResolveSourceThroughTheAllowList` |
| AC-004 no case identifier in any tag | 1.8 | planned | planned (7.4) | `IngestionMetricsTest`, `OperationalMetricsHttpLiveTest` |
| AC-005 five series at `0` at construction | 1.1, 1.10 | planned | planned | `IngestionMetricsTest.shouldPreRegisterAllFivePhaseSeriesAtZero…` |
| AC-006 containment | 1.9 | planned | — | `IngestionMetricsTest` + one case per site |

### Story 2 — Ingestion duration timer (`DD-43268`)

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 `Timer.record(Duration)` from two persisted timestamps, three terminal stops | 2.2, 2.3, 2.11 | planned | planned (delta on `_count`/`_sum`) | `IngestionMetricsTest`, `OperationalMetricsHttpLiveTest` |
| AC-002 non-terminal records nothing; documented as complementary to the stall gauge | 2.4, 2.7 | planned (behaviour) | — | `IngestionMetricsTest` + **diff-level Javadoc check** |
| AC-003 eight `_bucket` boundaries + `+Inf`, per phase | 2.1, 2.9 | planned (snapshot) | planned (**the headline test**) | `IngestionMetricsTest`, `OperationalMetricsHttpLiveTest` |
| AC-004 clock-skew clamp + throttled WARN | 2.5 | planned | — | `…shouldClampNegativeDurationToZero_andWarnOnce` |
| AC-005 SLOs in code, overridable by property | 2.1, 2.10 | planned — **2.10 needs the OQ-033 implementer-time verification of `PropertiesMeterFilter` before its assertion is written** | — | `IngestionDurationSloOverrideTest` |
| AC-006 containment incl. null timestamps | 2.6, 2.8 | planned | — | `IngestionMetricsTest`, `CheckIngestionStatusForAllDefendantsTaskTest` |

### Story 3 — Outbound dependency call timer (`DD-43269`)

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 success observation; response returned untouched | 3.2, 3.14 | planned (identity) | planned (delta) | `ExternalCallMetricsTest`, `ExternalCallMetricsHttpLiveTest` |
| AC-002 same exception instance rethrown, all seven classes | 3.3, 3.7, 3.9, 3.11 | planned (7 classes) | — | client/storage test classes + **new `RagAnswerServiceImplTest`** (OQ-036) |
| AC-003 cause-chain classification, five outcomes | 3.4, 3.5, 3.6 | planned (parameterised, real exceptions) — **3.6(b) resolved: `error` at/beyond the depth bound (OQ-025)** | — | `OutcomeClassifierTest` |
| AC-004 `dependency`/`operation` closed sets, literal arguments | 3.1, 3.9, 3.10, 3.12 | planned | planned (7.4) | `ExternalCallMetricsTest`, `ProgressionClientImplTest`, `AzureBlobStorageServiceTest` |
| AC-005 `outcome=timeout` incl. the Azure copy poll | 3.11, 3.15 | planned (**the whole of this AC's coverage**) | **none — accepted gap (OQ-026 rag/hearing/progression, OQ-027 azure_blob), documented not downgraded** | `OutcomeClassifierTest`, `ExternalCallMetricsTest`, `AzureBlobStorageServiceTest` |
| AC-006 containment | 3.13 | planned (both directions) | — | `ExternalCallMetricsTest` |
| NFR-006 RAG field parity (**merge-blocking**) | 3.8 | planned (4 classes) | — | `…ResponseParityTest` per RAG client |

### Story 4 — HTTP connection-pool visibility (`DD-43270`)

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 five binder series, `httpclient="cdk"` | 4.1, 4.6 | planned | planned | `HttpPoolMetricsConfigTest`, `HttpPoolMetricsHttpLiveTest` |
| AC-002 alias agrees with the binder | 4.2, 4.7 | planned (same struct + strong reference) | planned — **re-scoped, OQ-029** | as above |
| AC-003 maxima 200 / 50, read from the bean | 4.3, 4.6 | planned (parameterised) | planned | as above |
| AC-004 single shared manager; azure_blob not covered | 4.4 | planned (context assertion) | — | context assertion + **diff-level Javadoc check** |
| AC-005 nothing computed on scrape | 4.5, 7.8 | planned (structural) | — | structural + diff-level |

### Story 5 — Answer-generation outcome counter (`DD-43271`)

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 `succeeded` once on `ANSWER_GENERATED` | 5.2, 5.12 | planned (parameterised) | planned (delta) | `CheckStatusOfAnswerGenerationTaskTest`, `OperationalMetricsHttpLiveTest` |
| AC-002 `failed` only when the re-dispatch budget is spent; zero while it remains | 5.3, 5.4 | planned (**the 100× over-count control**) | — | `…shouldRecordNothing_whenAnswerGenerationFailedAndBudgetRemains` |
| AC-003 *(rewritten)* `timed_out` descoped; `PENDING` and `catch` paths record **nothing** | 5.5, 5.6 | planned (negative controls, both predicate values) | **none needed — the event is undetectable by construction (OQ-022 / ADR-011)** | `CheckStatusOfAnswerGenerationTaskTest` |
| AC-004 `GenerateAnswerForQueryTask`'s three abandonment paths | 5.7 | planned (4 cases incl. the zero case) | — | `GenerateAnswerForQueryTaskTest` |
| AC-005 closed `outcome`(**2**) / `query_level`(4) sets; `unknown` never omitted; `OUTCOME_FAILED` not `OUTCOME_FAILURE` (OQ-031) | 5.1, 5.8 | planned (literals, not constants) | planned (7.4) | `AnswerGenerationMetricsTest` |
| AC-006 *(re-scoped)* **at most** once per transaction: `1` for observable terminations, `0` for the two documented unobservable ones | 5.9 | planned (**sum-over-series, two expectation groups**) | — | transaction-accounting test |
| AC-007 containment | 5.11 | planned | — | both task test classes |
| ~~NFR-005 hoisted parse is behaviour-neutral~~ | ~~5.10~~ | **withdrawn — no code move is made (ADR-011)**; existing suite runs as regression + diff-level check that lines 94–95 are untouched | — | existing suite only |

### Story 6 — JobManager retry and retry-exhaustion counters (`DD-43272`)

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 `cdk.task.retry` on a granted retry, incl. the throw path | 6.1, 6.2, 6.3, 6.6 | planned — **truth table resolved: `remaining==1`→true, `shouldRetry` not an input (OQ-022, OQ-023)** | planned (6.15, exact-delta) | `TaskRetryDecisionTest`, `TaskRetryMetricsAspectTest` |
| AC-002 *(rewritten)* no exhaustion counter exists; `willBeRetried==false` and `INPROGRESS`-without-`shouldRetry` record **nothing** | 6.4, 6.5, 6.10 | planned — **AC reworded to match (OQ-024); registry-level absence asserted** | planned (6.15's absence assertion) | `TaskRetryMetricsAspectTest`, `TaskRetryMetricsTest` |
| AC-003 `task_name` from `@Task`, proxy-aware, membership-checked | 6.8 | planned (incl. a CGLIB-proxied target) | — | `…shouldReadTaskNameThroughAopUtils…` |
| AC-004 `retry_policy` determined by `task_name`, zero extra series | 6.9 | planned (parameterised) | planned (7.4) | `TaskRetryMetricsTest` |
| AC-005 `COMPLETED` records nothing; `ExecutionInfo` and exception untouched | 6.5, 6.7, 6.11 | planned | — | `TaskRetryMetricsAspectTest` |
| AC-006 *(re-aimed)* granted-retry count tied to the `jobs` row; exhaustion counter absent | 6.15 | — | **planned, unblocked — JDBC row seeding, no compose change (OQ-028)** | `TaskRetryHttpLiveTest` |
| AC-007 *(rewritten)* FR-012 documentation with *effective* budgets **plus the explicit statement that exhaustion is undetected** | 6.13, 6.14 | planned (the budgets) | — | `JobManagerRetryPropertiesTest` + **diff-level Javadoc check** |
| AC-008 containment inside the advice | 6.12 | planned (3 injections) | — | `TaskRetryMetricsAspectTest` |
| DoD: aspect-ordering test | 6.16 | planned (structural, via DD-43183) | — | **defers to DD-43183 Scenario 3.5 as canonical — OQ-035 closed, re-verified unaffected by ADR-011** |
| NFR-005 seven tasks still register; existing live tests unmodified | 6.17, 6.18 | planned (conditional bean) | planned (existing suites as regression) | `TaskRetryMetricsAspectConditionalTest` + existing suites |

### Story 7 — Cardinality, scrape bound and safety harness (`DD-43273`)

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 all **seven** rendered names + registered-id cross-check + the two withdrawn signals asserted absent | 7.1, 7.2 | — | planned (**the headline test**) | `OperationalMetricsHttpLiveTest.prometheusScrape_shouldExposeAllSevenOperationalMetricsTogether` |
| AC-002 common tags on every `cdk_*` series | 7.3 | — | planned | `…everyCdkSeries_shouldCarryTheCommonTags` |
| AC-003 baseline artefact + merge-blocking ceiling (**≤ 246 / ≥ 109**) | 7.5, 7.6 | — | planned — **baseline captured within this story from a DD-43182-free commit (OQ-034)** | `baseline-series-count.md`, `PrometheusSeriesBudgetHttpLiveTest` |
| AC-004 scrape-time CI smoke bound (GATE-6) | 7.7 | — | planned (re-scoped) | `…shouldCompleteWithinTheCiSmokeBound` |
| AC-005 containment across all four business paths | 7.10 | planned (4 areas) | — | one case per area |
| AC-006 one WARN per 60 s, suppressed count, no PII, JSON | 7.9 | planned — **(c) unblocked via the package-private time seam (OQ-030)**; JSON half is diff-level | — | `MetricsSafetyTest` |
| AC-007 *(rewritten)* `CdkMeters` Javadoc pass — **six** meters, the `failure`/`failed` distinction, and the exhaustion-gap statement | 6.14, 7.13(a) | — | — | **diff-level check at Code Review** |
| AC-008 three live tests unmodified; DD-43185 unchanged | 7.12 | — | planned + **diff-level check** | existing suites as regression |
| AC-009 build / PMD / JaCoCo / CodeQL / secrets green | 7.13 | planned | planned | `gradle clean build` + CI workflows |
| AC-010 no PII anywhere | 7.14 | planned | planned | secrets scanner, `block-pii` hooks, reviewer sign-off |
| NFR-003 nothing computed on scrape | 7.8, 4.5 | planned (structural) | — | structural + diff-level |
| NFR-009 `cdk.metrics.enabled` bound, default `true`, not overridden in compose | 7.11 | planned | — | `MetricsPropertiesTest` + diff check |

### Requirements-level ACs not fully closable in this repository

| Requirements AC | Status |
|---|---|
| AC-020 (retry exhaustion after the configured attempts) | **DESCOPED — OQ-022 decided, ADR-011.** Not "not closable" but *not built*: a framework-managed job's execution never observes an exhausted budget, so the counter is withdrawn rather than shipped permanently-zero. Scenarios 6.4, 6.10 and 6.15 assert its **absence**. The fix is the `task-manager-service` exhaustion event (ADR-011(4)), a follow-up ticket against the library. **Do not treat this AC as met by this ticket.** |
| AC-017 (`outcome=timed_out` is recorded) | **DESCOPED — OQ-022 decided, ADR-011.** Traced: `timed_out`'s detection is the *same* unobservable mechanism, and `CheckStatusOfAnswerGenerationTask` has no independent substitute (`CTX_ANSWER_RETRY_COUNT` tracks re-dispatches, not polls). The tag value is not built; Scenarios 5.1, 5.5, 5.6, 5.12, 7.1 and 7.2 assert its absence. Consequence to carry forward: `cdk_answer_generation_total` **undercounts** ended transactions and `succeeded / (succeeded + failed)` is **not** a success rate. |
| AC-012 (`outcome=timeout` end to end) | **Unit-tier only — accepted at the Stage-4 gate (OQ-026, OQ-027), gap documented rather than downgraded silently.** Classification and recording are fully covered (3.4, 3.15, 3.11(b)); the end-to-end *wiring* is not, for rag/hearing/progression (no acceptable compose override — 180 s is unshippable, 15 s is shared by every Hearing/Progression live test) or azure_blob (Azurite cannot stall a copy poll). A seam is additive if ever funded. |
| AC-024 (scrape under 1 s, fewer than 2,000 series per pod) | **Re-scoped at the accepted GATE-6.** Series count asserted whole-endpoint against a compose ceiling **tighter** than 2,000 with the compose-is-not-production reason in the message (7.6); scrape time as a 2 s CI smoke bound (7.7) plus a Stage-8 production capture. Exactly the outcome DD-43185's AC-012 reached, for the same reason. |
| AC-013 (pool "approaching exhaustion" expressible without hard-coded limits) | **Closable for the signal, not the alert.** Scenarios 4.3 and 4.6 prove the maxima are published so a ratio can be written; the ratio *rule* lives outside this repository (OQ-019). |
| AC-021 / FR-012 (in-repo documentation) | **Review-level, not test-level.** Scenarios 6.14 and 7.13(a) record it as an explicit Code Review item; no test can assert a Javadoc sentence. |

### Tier notes

- **Nothing behavioural is integration-only.** Every behavioural AC has a unit-tier plan, so a
  failure localises to a class rather than to "the compose stack". The integration tier proves
  exactly three things the unit tier structurally cannot: the **rendered Prometheus name and tag set**
  (ADR-001, and now its Timer arm), the **end-to-end value flow** from real work to a real scrape,
  and the **library-behaviour anchor** for the replicated retry predicate (Scenario 6.15).
- **Story 4 is nearly all unit tier**, deliberately: gauge wiring, the alias's agreement and the
  configured maxima are all fully observable over a real `PoolingHttpClientConnectionManager` and a
  `SimpleMeterRegistry`, and the compose stack adds only "the names actually render".
- **`OutcomeClassifierTest` is the highest-value unit test in the ticket** relative to its cost: it is
  a pure function over exception shapes, it carries the finding that unblocked OQ-005, and it is where
  a regression would otherwise be invisible (a mis-tagged outcome looks like a working metric).
- **No contract tests, no accessibility tests, no migration tests** — see §"Scope boundaries".

---

## Risks and open points carried into implementation

Open questions raised by **Stage 4**, continuing the ticket's own numbering (OQ-001 – OQ-021 are
Stages 1–2's). **All sixteen (OQ-022 – OQ-037) are now closed** — decided at the Stage-4 gate on
2026-09-04 and applied to whichever documents each one actually changes, not only listed here.
**OQ-033 is closed as an implementer-time verification task**, not as an answer, because it is not
resolvable from documents. `02-design.md` §15 indexes the decisions against the sections they change.

- **OQ-022 — RESOLVED: descoped. `cdk_task_retry_exhausted_total` is not built, and
  `cdk_answer_generation_total{outcome="timed_out"}` is not built either. See ADR-011 for the full
  decision record and the supersession of ADR-006(2) / ADR-007(1).**
  The evidence stands: `JobsRepository`'s assignment query is
  `… WHERE worker_id IS NULL AND (retry_attempts_remaining IS NULL OR retry_attempts_remaining > 0)
  AND assigned_task_start_time <= :currentTime …`, so a row at `0` is never selected;
  `TaskExecutor.performRetry(...)` writes `remaining - 1`, so the **last granted retry writes `0`**
  and that scheduled retry never runs. Exhaustion therefore happens **between executions, in the
  scheduler**, with no task run to observe it — CDKS's own
  `CheckIngestionStatusForAllDefendantsTask.LAST_RETRY_COUNT = 1` already encodes this.
  **The follow-up trace this stage asked for, and its answer:** `outcome=timed_out` has **no
  independent** detection mechanism. `CheckStatusOfAnswerGenerationTask`'s `PENDING` branch does only
  `return retry(executionInfo)` (line 85 → helper at line 205) and keeps no count of its own; its one
  independent counter, `CTX_ANSWER_RETRY_COUNT` (lines 153/157/165), tracks
  `ANSWER_GENERATION_FAILED` **re-dispatch cycles** against `questionsRetry.getMaxAttempts()`, not
  polling attempts, and is never touched on the `PENDING` path; and its `getRetryDurationsInSecs()`
  (lines 195–203) always returns `Optional.of(...)`, so `!willBeRetried(...)` is never true for that
  task. `timed_out` therefore shares the same unobservable mechanism and is withdrawn, as is design
  §8's row 4 (`catch` + `!willBeRetried` → `failed`), gated on the identical condition.
  **`cdk_task_retry_total` is unaffected — only exhaustion detection is descoped, not retry
  counting.** Candidate (a) (`remaining <= 1`) was rejected: it swaps a replica of a documented
  predicate for a replica of the library's undocumented scheduling arithmetic and would make the
  retry counter report 49 for a 50-attempt budget (ADR-011(5)). Candidate (b) was rejected as a
  knowingly-misleading series. **Candidate (c) is adopted:** a `task-manager-service` exhaustion event,
  raised as its own follow-up ticket with the concrete ask in ADR-011(4).
  **Accepted, and not to be forgotten:** FR-011, FR-012's exhaustion half, AC-017 and AC-020 are
  descoped; `cdk_answer_generation_total` undercounts ended transactions; and a permanently-empty AI
  Search result caused by a spent polling budget still has no detector.
  *Applied in:* ADR-011 (new), ADR-006 and ADR-007 (partially superseded), `02-design.md` §2/§7/§8/
  §11/§12/§13/§14/§15/Testing/Deployment, `03-stories.md` Stories 5/6/7 and the follow-up list, and
  Scenarios 5.1, 5.5, 5.6, 5.9, 5.10, 5.12, 6.1, 6.4, 6.5, 6.10, 6.14, 6.15, 7.1, 7.2, 7.6 plus the
  coverage summary and the requirements-level AC table above.
- **OQ-023 — RESOLVED.** The predicate is
  `remaining != null && remaining > 0 && durations.isPresent()`, with the **caller** testing
  `shouldRetry` separately — i.e. `02-design.md` §7's code snippet was right and **ADR-006(1)'s
  four-clause wording was the document to correct**, which has been done. `willBeRetried(...)` takes
  no `shouldRetry` input, and Scenario 6.1 asserts that. *Applied in:* ADR-006(1), design §7,
  Scenario 6.1, Story 5/6 Notes.
- **OQ-024 — RESOLVED.** An `INPROGRESS` result with `shouldRetry=false` counts as **neither**
  retried nor exhausted and increments no counter. Story 6 AC-002 is reworded to
  "`INPROGRESS` **and** `shouldRetry`", and Scenario 6.5 asserts the boundary **explicitly** — with a
  healthy remaining budget, so it passes for the right reason — even though no CDKS task produces the
  shape today. *Applied in:* ADR-006(2), design §7, Story 6 AC-002, Scenario 6.5.
- **OQ-025 — RESOLVED.** At and beyond `OutcomeClassifier`'s 5-layer depth bound, classify as generic
  **`error`**, stated explicitly in the classifier's Javadoc along with the accepted consequence (a
  status-bearing cause deeper than five is mis-tagged). The bound stays at 5 — CDKS's deepest chain
  is depth 3 — and `error` is the right degradation: "we could not tell", never a wrong positive.
  *Applied in:* ADR-003(1), design (via the ADR), Story 3 Notes, Scenario 3.6.
- **OQ-026 — RESOLVED: accept unit-tier-only coverage for `outcome=timeout` on RAG / Hearing /
  Progression, and document the gap plainly.** No compose read-timeout override is added. The
  uncovered part is the end-to-end wiring only; classification and recording are fully covered.
  Scenario 3.15 is rewritten as a unit-tier scenario that says so, `ExternalCallMetricsHttpLiveTest`
  carries a Javadoc note explaining the absence, and the coverage summary and requirements-level AC
  table record it as unit-tier only. *Applied in:* design §12/Testing, Story 3 AC-005, Scenario 3.15.
- **OQ-027 — RESOLVED: same as OQ-026, for `dependency=azure_blob`** — unit-tier only via
  Scenario 3.11(b)'s explicit-outcome branch, gap stated in the test's Javadoc. *Applied in:*
  design §12, Story 3 AC-005, Scenario 3.11.
- **OQ-028 — RESOLVED: seed the `jobs` table's `retry_attempts_remaining` directly via JDBC** in a
  `@BeforeAll`/`@AfterEach`-style pattern — already the house idiom in this codebase's live tests —
  rather than changing the shared compose-level retry-budget config. **`docker/docker-compose.integration.yml`
  is therefore unchanged by this ticket.** *Applied in:* ADR-006(5), design §7/§10/§11/§12,
  Story 6 AC-006, Scenario 6.15, the test inventory.
- **OQ-029 — RESOLVED.** Story 4 AC-002 is proven by (a) a single-snapshot equality check at idle
  from one scrape body and (b) a structural/unit-level proof that the two gauges cannot disagree by
  construction (same `getTotalStats()` struct, same bean, `strongReference(true)`) — **not** by racing
  real concurrent requests. *Applied in:* design §12, Story 4 AC-002 and Notes, Scenarios 4.2/4.7.
- **OQ-030 — RESOLVED: add a time seam to `MetricsSafety`** — a package-private, settable
  `LongSupplier`/clock field defaulting to the real clock — so the 60-second WARN-throttle window is
  testable without a 60-second wait. Test-only by convention: package-private, no public setter, no
  property binding, not a production knob; reset in `@AfterEach`. *Applied in:* ADR-010(3), design
  §9/§11, Story 7 AC-006, Scenario 7.9(c).
- **OQ-031 — RESOLVED: keep `failure` and `failed` as two distinct tag values**, each behind its own
  clearly-named constant — DD-43185's existing `OUTCOME_FAILURE` (`"failure"`) and a new, distinctly
  named `OUTCOME_FAILED` (`"failed"`) for this ticket's answer-generation counter — declared
  adjacently with a Javadoc sentence on each naming its meter, so they cannot be confused or
  cross-used. Neither is renamed (tag values are a one-way door). Tests assert the **literal**
  strings. *Applied in:* ADR-001(5), design §2/§11, Story 5 AC-005, Story 7 AC-007, Scenario 5.1 and
  the tag-collision note above.
- **OQ-032 — RESOLVED: the test-only negative control is sufficient.** Scenario 1.7's
  `containsExactlyInAnyOrder` on the registered `phase` tag-value set is the accepted guard against
  the phase counter using `UPLOADING`/`INGESTING`; **no structural `CdkMeters` restructuring** (no
  nested `Phases.Reachable`/`Phases.Monitored` split), which would churn DD-43185's shipped constants
  to prevent a mistake one assertion already catches. *Applied in:* Scenario 1.7, Story 1 Notes.
- **OQ-033 — CLOSED AS AN IMPLEMENTER-TIME VERIFICATION TASK, not a documents answer.**
  **Not resolvable from documents: the Story 2 (`DD-43268`) implementer must verify Boot's
  `PropertiesMeterFilter` behaviour (replace vs. merge with code-declared histogram buckets) against
  the actual running app on Spring Boot 4.0.5 before writing Scenario 2.10's assertion.** The
  expected bucket set differs by semantics (`{1s, 2s, +Inf}` versus `{1s, 2s, 15s, …, 1h, +Inf}`), so
  the assertion must be written against observed behaviour, not against this document. **This is a
  verification task, not a blocking design question:** the code default is authoritative either way,
  the scenario's shape is fixed, AC-005's substance holds under both semantics, and Scenario 2.9
  catches an inert property path regardless. Record the observed behaviour in the test's Javadoc.
  — **Owner:** the Story 2 implementer · **Due:** before writing Scenario 2.10's assertion.
- **OQ-034 — RESOLVED: capture the real metric-series baseline when Story 7 is built** (the last
  story), **not before Story 1 starts.** The "must happen before Story 1" framing is withdrawn:
  "pre-implementation" is a property of the **commit measured** (a clean `origin/develop` checkout or
  a `git stash`, with the SHA recorded in the artefact), not of the calendar date, so keeping the
  capture with the ceiling assertion it feeds — one story, one owner, one PR — is simpler and avoids
  a hand-off from an unrelated story. *Applied in:* ADR-005(6), design §12, Story 7 header/AC-003/
  Notes, Scenarios 7.5 and 7.6.
- **OQ-035 — RESOLVED (in a prior session) and re-verified 2026-09-04.** Scenario 6.16 defers to
  DD-43183's Scenario 3.5 as the canonical aspect-ordering test. **Re-checked against the OQ-022
  changes:** ADR-011 withdraws a *counter*, not the aspect — `TaskRetryMetricsAspect` still exists and
  still records `cdk.task.retry`, so it is still a real advisor on the shared join point, and the
  ordering requirement, the "no `@Order`" rule and DD-43183's conditional advisor-chain assertion are
  all unaffected. **The cross-reference still makes sense as written; no further action.**
- **OQ-036 — RESOLVED, confirming what the scenarios already specify.** The new
  `RagAnswerServiceImplTest` drives `RagAnswerServiceImpl` as a **direct outbound client** (calling
  its methods directly), **not** through simulated MVC/web requests — no `@WebMvcTest`, no `MockMvc`,
  no `standaloneSetup` — because both `RagAnswer*ServiceImpl` are `@RestController`s as well as
  clients and an MVC-driven assertion would measure CDKS's inbound surface. The class still does not
  exist today and is created new. *Applied in:* Scenarios 3.7 and 3.8, Story 3 Notes.
- **OQ-037 — RESOLVED, 2026-09-04.** Real Jira sub-tickets `DD-43267`–`DD-43273` were created and
  linked to the parent epic DD-43182. No further action.

**Carried forward from earlier stages, unresolved and still relevant to testing:**
**OQ-001** (the pasted brief was never confirmed against the live ticket or its comments — still
unresolved through three stages); **OQ-018's second half** (platform/SRE confirmation that the scrape
config and alert rules expect the `cdk_` prefix, now for eight more names — settle before any name is
relied upon, because renaming after alert rules exist is a coordinated cross-repository change, and
note OQ-031 adds *tag values* to the same one-way door); **OQ-019** (alert rules and dashboards,
platform/SRE — without them this ships signals nobody watches, which does not meet the ticket's
stated intent); **OQ-020** (security sign-off that `/actuator/prometheus`, served on the public API
port and excluded from `cp-auth-rules-filter`, may publish CDKS's internal call topology via the
`dependency` / `operation` / `task_name` / `retry_policy` tags and its performance profile via the
ingestion histogram — required before merge).

---

## Stage-4 gate

Test Specs is a **human gate**. **Status: all sixteen Stage-4 open questions (OQ-022 – OQ-037) were
decided on 2026-09-04 and are applied above** — the remaining gate item is approval of the amended
scenarios themselves.

1. ~~The scenarios above are approved.~~ **Re-approval needed** for the scenarios the OQ-022 decision
   rewrote or withdrew: **5.5, 5.6, 5.9, 5.10 (withdrawn), 5.12, 6.1, 6.4, 6.5, 6.10, 6.14, 6.15,
   7.1, 7.2, 7.6**, plus the rewritten **3.15** and re-scoped **4.7**, and the amended coverage
   summary and requirements-level AC table. Scenarios not listed are unchanged in substance.
2. ~~**OQ-022 has a decision.**~~ **Decided: descoped** — `cdk_task_retry_exhausted_total` and
   `outcome=timed_out` are not built; `cdk_task_retry_total` is unaffected; the gap is escalated to
   `task-manager-service`. See ADR-011.
3. ~~OQ-023, OQ-024, OQ-025, OQ-031, OQ-032 and OQ-033 have decisions~~ — **all decided.** OQ-033 is
   closed as an **implementer-time verification task** for the Story 2 implementer rather than a
   documents answer; it does not block Stage 5.
4. ~~OQ-026, OQ-027, OQ-028 and OQ-029 have decisions on test seams~~ — **all decided.** OQ-026 and
   OQ-027 accept **unit-tier-only** coverage for `outcome=timeout` with the gap documented plainly
   (not silently downgraded); OQ-028 seeds `jobs.retry_attempts_remaining` via JDBC and leaves the
   compose file untouched; OQ-029 proves gauge agreement by one idle snapshot plus a structural proof.
5. ~~OQ-030 has a decision on the `MetricsSafety` time seam~~ — **decided:** a package-private,
   settable time source is added.
6. ~~OQ-034's baseline capture is scheduled before the first story starts~~ — **decided the other
   way:** the baseline is captured **when Story 7 is built**, from a DD-43182-free commit with the SHA
   recorded.
7. ~~OQ-035~~ (reconciled — DD-43182 defers to DD-43183 Scenario 3.5; re-verified unaffected by
   ADR-011) and ~~OQ-037~~ (resolved — real Jira sub-tickets exist and are linked) are both closed.

**Two things to carry into Stage 5 that are *not* test-spec items, and must not be lost in the
descoping:**

- **Raise the `task-manager-service` retry-exhaustion-event ticket when Story 6 is picked up**
  (ADR-011(4)) — until it lands, retry-budget exhaustion is undetected and
  `cdk_answer_generation_total` undercounts ended transactions.
- **Tell OQ-019's alert-rule owner explicitly** that `succeeded / (succeeded + failed)` is **not** an
  answer-generation success rate, and that there is no "work is being silently abandoned" signal in
  this ticket. The most likely way this decision gets silently reversed is a dashboard published as
  if it were.
