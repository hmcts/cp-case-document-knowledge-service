# Architecture Decision Records — Stalled-Work Gauges and Scheduler Heartbeat Observability

> Service: `cp-case-document-knowledge-service` (CDKS) · Jira: DD-43185 · Taken at Stage 2
> (Architecture & Design), resolving Stage 1 open questions OQ-002 – OQ-008, OQ-010 and OQ-013.
> Requirement: [`../DD-43185-stalled-work-scheduler-monitoring/`](../DD-43185-stalled-work-scheduler-monitoring/) ·
> Requirements: [`01-requirements.md`](../DD-43185-stalled-work-scheduler-monitoring/01-requirements.md) ·
> Design: [`02-design.md`](../DD-43185-stalled-work-scheduler-monitoring/02-design.md)
>
> **Status of this file: Stage-2 human gate cleared on 2026-08-25.** ADR-001 – ADR-004 and
> ADR-006 – ADR-008 (seven ADRs; there is no ADR-005) are all `Accepted`. Gate decisions taken:
> - **ADR-004 — accepted.** `UPLOADED` is added to FR-001's stalled-phase set. Story 3/4 and the
>   threshold config reflect this; the 30-minute default threshold needs real-world calibration
>   before go-live, since `UPLOADED` has genuine normal-operation occupancy (OQ-011 follow-up).
> - **ADR-008 — accepted as designed.** ShedLock guards the stalled-work refresh per FR-004 as
>   ticketed; the `cdk_monitoring_last_refresh_epoch_seconds` freshness gauge ships. The
>   "drop ShedLock" alternative was considered and rejected.
> - **ADR-003 — accepted, DBA review required before merge.** `V1014` ships as designed; the
>   plain `CREATE INDEX` write-lock risk is routed to `migration-reviewer` and a DBA sizing call
>   as a pre-merge gate, not a blocker on starting Stage 3.
> - **Note — `document_verification_task` excluded.** This ticket covers two stuck-work gauges, not
>   three: `document_verification_task` is a dead table — a Spring Batch-era leftover, superseded by
>   the JobManager framework, with no writer at all, confirmed both by static analysis and by a live
>   query against the running database. No metric is built for a dead table; table cleanup itself is
>   a separate future ticket. Total added series across this ticket is **14**.
>
> This is the **first custom application metric in CDKS**. There is no existing precedent to
> conform to, so ADR-001 and ADR-006 set the house convention for every metric added after this
> ticket. Treat them as the load-bearing decisions in this file.

---

## ADR-001: Register Micrometer meters with lowercase dot-separated names; register the runs counter as `cdk.scheduler.runs`

- **Status:** Accepted at Stage-2 gate (2026-08-25) · **Date:** 2026-08-25 · **Jira:** DD-43185 · **Resolves:** OQ-002
- **Artefacts:** `01-requirements.md` (FR-001, FR-002, FR-007, FR-008, FR-010, OQ-002) · `02-design.md` (§2)

### Context

The ticket names every metric in its **Prometheus-rendered** form (`cdk_documents_stalled`,
`cdk_scheduler_runs_total`). Micrometer's own convention is dot-separated, registry-neutral meter
names, which each registry then renders in its native style. `src/main/java` contains **no
`MeterRegistry` injection at all** today, so whatever is chosen here is the CDKS precedent.

Stage 1 raised the `_total` double-suffix trap. Design verified the actual behaviour against the
resolved dependency rather than from memory. The classpath is **Micrometer 1.16.5** with the
**Prometheus Java client 1.x** (`io.prometheus:prometheus-metrics-*`), not the legacy simpleclient.
Decompiling the two classes that actually do the work:

1. `io.micrometer.prometheusmetrics.PrometheusNamingConvention.name(...)` calls
   `PrometheusNaming.prometheusName(name)`, conditionally appends `_<baseUnit>` (guarded by
   `endsWith`), conditionally appends `_seconds` for timers, then calls
   `PrometheusNaming.sanitizeMetricName(...)`. **It never appends `_total`.**
2. `PrometheusNaming.sanitizeMetricName(...)` *strips* any of eight
   `RESERVED_METRIC_NAME_SUFFIXES` — `_total`, `_created`, `_bucket`, `_info` and their
   dotted forms — repeatedly until none remain.
3. `_total` is appended at the **exposition layer**, by
   `io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter`, when a counter snapshot
   is written to the classic text format that `/actuator/prometheus` serves.

Two consequences, both of which correct Stage 1's premise:

- **The `_total_total` outcome cannot occur on this classpath.** Registering
  `cdk.scheduler.runs.total` would render as `cdk_scheduler_runs_total`, because the sanitizer
  strips the suffix before the writer re-adds it. OQ-002's stated risk is real for older
  Micrometer/simpleclient combinations but is not live here.
- **A different and unguarded trap replaces it: there is no snake-casing.**
  `PrometheusNaming.prometheusName` is `escapeName(name, UNDERSCORE_ESCAPING)` — it replaces
  invalid characters (including `.`) with `_` and **preserves case**. The legacy
  `NamingConvention.snakeCase` step is gone. A meter registered as `cdk.documentsStalled` would
  render as `cdk_documentsStalled`, silently, with no error.

### Decision

1. **Register every meter with a lowercase, dot-separated Micrometer name.** Not the Prometheus
   name, not camelCase, not underscores. The registry does the rendering.
2. **Register the runs counter as `cdk.scheduler.runs`** — with no `.total` segment.
3. **Do not set `baseUnit` on any meter in this ticket.** `cdk.scheduler.last.success.epoch.seconds`
   already carries its unit in the name; adding `.baseUnit("seconds")` is a no-op only because of
   an `endsWith` guard, and depending on that guard buys nothing.
4. **Hold every meter name and tag key as a `public static final String` constant** in a new
   `uk.gov.hmcts.cp.cdk.metrics.CdkMeters` final class (private constructor, per the `TimeUtils`
   precedent). No string literals at registration sites, so a rename is one edit and the
   integration test asserts against the same constants the production code registers.

| Prometheus name (ticket) | Micrometer meter name | Meter type | Ticket-specific tags |
|---|---|---|---|
| `cdk_documents_stalled` | `cdk.documents.stalled` | Gauge | `phase` |
| `cdk_queries_awaiting_answer` | `cdk.queries.awaiting.answer` | Gauge | — |
| `cdk_scheduler_runs_total` | `cdk.scheduler.runs` | Counter | `scheduler`, `outcome` |
| `cdk_scheduler_last_success_epoch_seconds` | `cdk.scheduler.last.success.epoch.seconds` | Gauge | `scheduler` |
| `cdk_scheduler_enabled` | `cdk.scheduler.enabled` | Gauge | `scheduler` |
| `cdk_monitoring_last_refresh_epoch_seconds` (ADR-008) | `cdk.monitoring.last.refresh.epoch.seconds` | Gauge | — |

**Tag-value casing rule, stated once so later tickets do not have to re-litigate it:** a tag value
mirrors its source-of-truth token. A value that comes from a database enum uses the enum constant
verbatim (`phase="WAITING_FOR_UPLOAD"`, `status="PENDING"`); a value the ticket specifies literally
is used as specified (`outcome="success"` / `"failure"`, per FR-008); a value CDKS invents is
lowercase kebab-case matching its configuration key (`scheduler="intraday-discovery"`, ADR-006).

### Alternatives considered

- **Register the Prometheus form directly (`cdk_documents_stalled`).** Rejected. It renders
  identically on `/actuator/prometheus`, so it is not *wrong* — but it hard-codes one registry's
  dialect into the application, breaks the meter id shown by `/actuator/metrics` and by any future
  OTLP exporter (`management.otlp.metrics` is already wired, currently disabled), and makes
  `cdk_scheduler_runs_total` the registered id — a counter whose Micrometer name asserts a suffix
  that only one exposition format uses.
- **Register `cdk.scheduler.runs.total`.** Rejected even though it happens to render correctly
  here, because it renders correctly only via the sanitizer's strip step. That is an implementation
  detail of `prometheus-metrics-model`, not a contract, and it is exactly the kind of thing a
  dependency bump changes quietly.
- **`cdk.scheduler.lastSuccessEpochSeconds` and friends (camelCase).** Rejected — see the
  no-snake-casing finding above; it would render as `cdk_scheduler_lastSuccessEpochSeconds`.
- **Free-floating string literals instead of a constants class.** Rejected: the metric name is a
  contract with alert rules held in another repository (OQ-011). It needs one definition site.

### Consequences

- **Positive:** the rendered names on `/actuator/prometheus` match the ticket character for
  character, which is what alert rules will be written against, while the registered ids stay
  registry-neutral.
- **Positive:** a single, checkable rule ("lowercase, dots, no unit suffix unless it is part of the
  name, no `_total`") that the next metric-adding ticket can follow without re-deriving any of this.
- **Accepted:** the meter id and the scraped name differ, so an engineer grepping the codebase for
  `cdk_documents_stalled` finds only the integration test. Mitigated by `CdkMeters` carrying a
  Javadoc table of both forms.
- **Accepted (must be confirmed at the gate, OQ-002 second half):** that the platform's Prometheus
  scrape configuration and the SRE team's alert rules expect the `cdk_` prefix. This design cannot
  verify that from inside the repository.
- **Reversibility:** poor once alert rules exist elsewhere. Renaming a metric is a coordinated
  cross-repository change with a dual-publish window. Settle the names at this gate.

---

## ADR-002: `cdk.monitoring.*` is a `Duration`-typed property namespace bound to `CP_CDK_MONITORING_*`, with one shared threshold and the refresh enabled by default

- **Status:** Accepted at Stage-2 gate (2026-08-25) · **Date:** 2026-08-25 · **Jira:** DD-43185 · **Resolves:** OQ-003, OQ-013
- **Artefacts:** `01-requirements.md` (FR-001, FR-002, FR-004, NFR-007, AC-005, AC-010, OQ-003, OQ-013) · `02-design.md` (§3)

### Context

The ticket gives `cdk.monitoring.stalled-threshold` with a default of "30 minutes" and says the
refresh must run "no more often than every 60 seconds" — but fixes neither the property type, nor
the environment-variable binding, nor whether one threshold governs both FR-001 and FR-002, nor
whether the refresh has its own enable flag or a target cadence.

Repository facts that constrain the answer:

- Every externally-tunable CDKS setting in `application-cdk.yml` is a `${CP_CDK_*:default}`
  placeholder. There is one legacy family using a bare `CDK_*` prefix (`CDK_UPLOAD_*`,
  `CDK_JOBMANAGER_*`), which is the exception, not the pattern to copy.
- Every existing duration in that file is an **ISO-8601 string**: `lock-at-least-for: "PT8M"`,
  `lock-at-most-for: "PT9M"`, `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_LOCK_AT_LEAST_FOR:PT1H`. They are
  bound as `String` only because ShedLock's `@SchedulerLock` takes strings.
- Spring's `DurationStyle.detectAndParse`, which backs both `@ConfigurationProperties` `Duration`
  binding and `@Scheduled(fixedDelayString = ...)`, parses `PT30M`, `30m` and `1800s` — and parses
  a **bare `30` as 30 milliseconds** unless a `@DurationUnit` is declared. A bare integer is
  therefore a live foot-gun for a property whose documented default is "30 minutes".
- `cdk.discovery-trigger.*` (`DiscoveryTriggerProperties`, enabled from `DiscoveryTriggerConfig`)
  is the in-repo precedent for a `cdk.*`-prefixed `@ConfigurationProperties` class living in
  `config/`.

### Decision

A new `uk.gov.hmcts.cp.cdk.config.MonitoringProperties` (`@ConfigurationProperties(prefix =
"cdk.monitoring")`), registered by a new `config/MonitoringConfig` carrying
`@EnableConfigurationProperties(MonitoringProperties.class)` — mirroring `DiscoveryTriggerConfig`
exactly.

```yaml
cdk:
  monitoring:
    enabled:           ${CP_CDK_MONITORING_ENABLED:true}
    stalled-threshold: ${CP_CDK_MONITORING_STALLED_THRESHOLD:PT30M}
    refresh-interval:  ${CP_CDK_MONITORING_REFRESH_INTERVAL:PT1M}
    initial-delay:     ${CP_CDK_MONITORING_INITIAL_DELAY:PT30S}
    lock-at-least-for: ${CP_CDK_MONITORING_LOCK_AT_LEAST_FOR:PT55S}
    lock-at-most-for:  ${CP_CDK_MONITORING_LOCK_AT_MOST_FOR:PT5M}
```

1. **Type is `java.time.Duration`**, with `@DurationUnit(ChronoUnit.MINUTES)` on
   `stalledThreshold` and `@DurationUnit(ChronoUnit.SECONDS)` on the four scheduling/lock
   durations, so a unit-less integer means the unit an operator would assume rather than
   milliseconds. ISO-8601 remains the shipped and documented form.
2. **One threshold governs both FR-001 and FR-002**, as the ticket's definite article implies.
3. **`CP_CDK_MONITORING_*` env-var bindings**, matching the dominant convention.
4. **The refresh has its own `cdk.monitoring.enabled` flag, defaulting to `true`** — deliberately
   the opposite default to `CP_CDK_SCHEDULER_{INTRADAY,NIGHTLY}_DISCOVERY_ENABLED:false`.
5. **Default cadence `PT1M`**, sitting exactly on FR-004's floor.
6. The flag gates only the **scheduled refresh job**, never meter registration. All series exist
   from context refresh at value `0` whatever the flag says (AC-009, AC-020).

The asymmetry in (4) is deliberate and worth stating plainly: the discovery schedulers default off
because they *dispatch work* — they call the Hearing API and enqueue JobManager tasks, so an
accidentally-enabled scheduler has real-world side effects. The monitoring refresh issues three
read-only aggregate counts and writes to an in-process meter registry. It has no side effect that
could harm an environment, and defaulting it off would reproduce the exact failure mode this ticket
exists to eliminate — a pod that looks healthy and silently publishes nothing.

`refresh-interval` and `lock-at-least-for` are coupled: ADR-008's "one refresh per cadence,
cluster-wide" property holds only while `lock-at-least-for` is close to `refresh-interval`.
`MonitoringConfig` logs a single startup WARN if `refresh-interval < PT1M` (FR-004's floor) or if
`lock-at-least-for < 0.9 × refresh-interval`. It **logs and continues** — it does not fail startup
(NFR-004). A unit test asserts the shipped `application-cdk.yml` defaults satisfy both (AC-010).

### Alternatives considered

- **Plain integer of minutes (`cdk.monitoring.stalled-threshold: 30`).** Rejected: no unit in the
  property value, no relationship to the four other durations this ticket adds, and it cannot
  express a sub-minute threshold for a test.
- **Spring shorthand (`30m`) as the shipped default.** Rejected as the *shipped* form only — every
  existing duration in `application-cdk.yml` is ISO-8601, and mixing dialects in one file is worse
  than either dialect. `30m` still parses, so nobody is blocked.
- **Separate `documents-stalled-threshold` and `queries-stalled-threshold`.** Rejected now, cheap
  later. There is no evidence the two pipelines have different SLOs, two knobs double the
  alert-tuning surface, and splitting one property into two defaulted properties is a purely
  additive change if evidence appears.
- **No `enabled` flag at all.** Rejected: an always-on scheduled DB query with no kill switch is
  the kind of thing that gets a service rolled back rather than reconfigured during an incident.
- **`enabled` defaulting to `false`, matching the discovery schedulers.** Rejected — see (4). It
  would also mean AC-011 (a `shedlock` row must exist for the refresh lock) could not be verified
  by the integration suite without an override, and the flag would inevitably be left off in some
  environment, which is precisely the bug class in Stage 1's Context section.
- **Hot-reloadable threshold via `@RefreshScope`.** Rejected: Spring Cloud Config is not on the
  classpath. AC-005 is satisfied because the component reads `stalledThreshold` on **every**
  refresh rather than caching a cutoff — so any mechanism that mutates the bound property takes
  effect on the next tick with no code change.

### Consequences

- **Positive:** one namespace, one prefix, one duration dialect; the whole feature is tunable and
  killable from environment variables with no rebuild.
- **Positive:** defaulting on means the compose integration stack exercises the refresh path,
  the ShedLock row, and the metric surface on every `gradle build` with no extra wiring.
- **Accepted:** the compose stack will override `refresh-interval` to `PT10S` and
  `lock-at-least-for` to `PT0S` so integration tests finish quickly. This mirrors the existing
  `SCHEDULER_INTRADAY_DISCOVERY_CRON: "0/30 * * * * *"` override (shipped cron is 10-minutely) and
  means FR-004's ≥60 s floor is asserted against `application-cdk.yml`, not against the running
  test container.
- **Accepted:** three extra scheduled DB round trips per minute per pod in every environment
  including dev. At three indexed aggregates this is immaterial next to the existing 10-minutely
  discovery run.
- **Reversibility:** excellent. `CP_CDK_MONITORING_ENABLED=false` disables the refresh without a
  deployment; the gauges then freeze at their last values with the freshness gauge (ADR-008)
  showing them as stale.

---

## ADR-003: Add Flyway `V1014` with two indexes — a composite on `case_documents`, a partial on `case_query_status`

- **Status:** Accepted at Stage-2 gate (2026-08-25) · **Date:** 2026-08-25 · **Jira:** DD-43185 · **Resolves:** OQ-004
- **Artefacts:** `01-requirements.md` (FR-005, NFR-005, AC-006, AC-012, OQ-004, OQ-009) · `02-design.md` (§7, §9)

### Context

Stage 1 correctly found that the ticket's index claim does not hold. Verified again at Design
against `V1001`:

| Aggregate | Predicate | Existing index cover |
|---|---|---|
| Documents stalled | `ingestion_phase IN (…) AND ingestion_phase_at < ?` | `idx_cd_phase (ingestion_phase)` and `idx_cd_case_phase (case_id, ingestion_phase)`. **Nothing on `ingestion_phase_at`.** |
| Queries awaiting answer | `status = 'ANSWER_NOT_AVAILABLE' AND status_at < ?` | `idx_cqs_case_status (case_id, status)` — leading column is `case_id`, unusable here. `idx_cqs_status_at_desc (status_at DESC)` — **`status_at` alone; does not include `status`.** |
| Verification tasks | `GROUP BY status` | `idx_dvt_status_next_attempt (status, next_attempt_at)` — **already sufficient**, serves an index-only scan. |

Why the existing indexes are not merely imperfect but structurally wrong for these two queries:

- On `case_documents`, `idx_cd_phase` gives a bitmap scan over *every* row in the three monitored
  phases, then a heap recheck for the age predicate. Nothing in CDKS ever cleans up a
  `WAITING_FOR_UPLOAD` row that never progressed, so that population grows without bound — and it
  grows in exact proportion to the problem the gauge exists to measure. The query gets slower
  precisely when it matters most.
- On `case_query_status`, `ANSWER_NOT_AVAILABLE` is the column **default**; rows are created in it
  and flip to `ANSWER_AVAILABLE` on answer arrival. A range scan of `idx_cqs_status_at_desc` for
  `status_at < now() - 30m` therefore visits nearly the entire historical table and rechecks
  `status` on the heap for each row. This is a full-history scan wearing an index's clothes.

A second, subtler constraint drove the *shape* of the two indexes. A **partial** index is only used
if PostgreSQL's `predicate_implied_by` can prove the query predicate implies the index predicate.
Equality against a literal (`status = 'ANSWER_NOT_AVAILABLE'`) is trivially provable. Proving that
one `IN`-list implies a *different, wider* `IN`-list is not reliably supported — and the
`case_documents` phase list is exactly what ADR-004 may change. Coupling an index predicate to an
unresolved requirements question is a bad trade.

### Decision

Add one append-only migration, `V1014__add_stalled_work_monitoring_indexes.sql` (`V1013` is
consumed by DD-43083, confirmed present in `src/main/resources/db/migration/`), routed through
`migration-reviewer` per the CLAUDE.md hard rule.

```sql
-- Supports the DD-43185 stalled-document aggregate:
--   ingestion_phase IN (...) AND ingestion_phase_at < :cutoff, GROUP BY ingestion_phase.
-- Deliberately NOT partial: the monitored phase set is a requirements decision (ADR-004) and a
-- partial predicate would have to be re-migrated if it changes. A plain composite serves any
-- subset of phases, with literal or bound values, and needs no predicate-implication proof.
CREATE INDEX IF NOT EXISTS idx_cd_phase_phase_at
    ON case_documents (ingestion_phase, ingestion_phase_at);

-- Supports the DD-43185 queries-awaiting-answer aggregate:
--   status = 'ANSWER_NOT_AVAILABLE' AND status_at < :cutoff.
-- Partial: ANSWER_NOT_AVAILABLE is the initial status and rows leave it permanently, so this
-- index covers only the outstanding population and shrinks as answers land. The equality
-- predicate is trivially provable, so the planner will use it whenever the query spells the
-- status as a literal (which the native query does).
CREATE INDEX IF NOT EXISTS idx_cqs_awaiting_answer_at
    ON case_query_status (status_at)
    WHERE status = 'ANSWER_NOT_AVAILABLE';
```

- **Plain `CREATE INDEX`, not `CREATE INDEX CONCURRENTLY`.** Flyway wraps each migration in a
  transaction and `CONCURRENTLY` cannot run inside one; every index in `V1000`–`V1013` is created
  the plain way. The cost is that `case_documents` and `case_query_status` are write-blocked (a
  `SHARE` lock) for the duration of each build. See Consequences — this needs a DBA sizing call,
  and it is the single highest-risk line in this ticket.
- **The literal-status requirement on the partial index is a hard coupling and must be enforced by
  test, not by comment.** The `case_query_status` aggregate is written as a native query with
  `'ANSWER_NOT_AVAILABLE'` inline (matching the existing `existsAnswerAvailableForLatestDoc`
  precedent), never as a bound enum parameter, and a Testcontainers test asserts the plan uses
  `idx_cqs_awaiting_answer_at`.

### Alternatives considered

- **Accept the existing indexes and add no migration** (the option Stage 1 held open). Rejected on
  the two structural arguments above: neither existing index bounds the work by the *selective*
  part of the predicate, and on `case_query_status` the "index" scan degenerates to a full-history
  scan. Shipping FR-005's "single indexed aggregate" without them would be shipping the words, not
  the property.
- **A partial index on `case_documents` restricted to the monitored phases** — genuinely
  attractive: much smaller, and self-pruning as documents reach terminal phases. Rejected as
  primary because its predicate would have to be re-migrated (append-only: a new index plus a drop)
  if ADR-004's phase set is amended, and because `IN`-list-implies-`IN`-list proofs are not
  something to bet a performance requirement on. Recorded here as the documented optimisation if a
  DBA later finds the composite too large; by then the phase set will be settled.
- **A plain composite `(status, status_at)` on `case_query_status`** instead of the partial.
  Rejected: functionally equivalent for this query but indexes every row rather than only
  outstanding ones, on a table sized cases × queries. The proof concern that ruled out a partial on
  `case_documents` does not apply, because the predicate is a single equality against a literal.
- **A materialised view or a summary table refreshed by the job.** Rejected: strictly more moving
  parts (a table, a migration, a refresh transaction, a staleness question) than two indexes, for
  three counts that are already sub-second once indexed.
- **Dropping the now-redundant `idx_cd_phase`,** which becomes a strict prefix of
  `idx_cd_phase_phase_at`. Deliberately **not** done: dropping a shipped index is a behaviour
  change outside this ticket's "purely additive" NFR-005, and it needs its own before/after
  evidence. Recorded as a follow-up in `02-design.md`.

### Consequences

- **Positive:** both aggregates become bounded by the selective part of their predicate. The
  `case_query_status` aggregate becomes an index-only range scan whose cost tracks the outstanding
  backlog rather than the table's history.
- **Positive:** the `case_documents` index is decoupled from ADR-004, so the phase-set decision can
  be taken or reversed at the Stage-2 gate without touching the migration.
- **Accepted:** `idx_cd_phase_phase_at` indexes every `case_documents` row, including the
  `INGESTED` majority. It is the price of not depending on a predicate-implication proof.
- **Accepted / needs DBA input:** plain `CREATE INDEX` takes a `SHARE` lock that blocks writes to
  each table for the duration of the build. On a small table this is sub-second; on a large one it
  is an ingestion outage. **Neither this repository nor its compose stack can tell you which.** The
  row counts must come from the DBA (OQ-009), and if either table is large the migration should be
  scheduled into a window, or `CONCURRENTLY` applied out-of-band with a no-op
  `CREATE INDEX IF NOT EXISTS` left in `V1014` to keep Flyway consistent.
- **Accepted:** `AC-006`/`AC-012` ("EXPLAIN shows an index scan", "under 500 ms at production
  scale") remain **unverifiable inside this repository**. What can be delivered is a
  Testcontainers-backed plan assertion at documented synthetic volumes; the production-scale number
  is a manual DBA follow-up. See `02-design.md` §12 (OQ-009).
- **Reversibility:** good. Both indexes are additive and droppable in a later migration with no
  data implication; the only irreversible cost is the build-time lock.

---

## ADR-004: Include `UPLOADED` in the stalled-phase set — and treat `UPLOADING`, `INGESTING` and `NOT_FOUND` as currently unreachable or terminal

- **Status:** Accepted at Stage-2 gate (2026-08-25) — FR-001's scope now includes `UPLOADED` ·
  **Date:** 2026-08-25 · **Jira:** DD-43185 · **Resolves:** OQ-005
- **Artefacts:** `01-requirements.md` (FR-001, AC-001, AC-003, NFR-002, OQ-005) · `02-design.md` (§7)

### Context

FR-001 lists the stalled phases as `WAITING_FOR_UPLOAD`, `UPLOADING`, `INGESTING`. Design traced
every write of `CaseDocument.ingestionPhase` in `src/main/java`:

| Phase | Written by | Reachable in production today? |
|---|---|---|
| `WAITING_FOR_UPLOAD` | `IdpcAvailabilityService.persistCaseDocument(...)` (~line 117) | **Yes** — the row's initial persisted state. |
| `UPLOADING` | Only the Java field initialiser `CaseDocument.ingestionPhase = UPLOADING` (~line 73) and the `V1001` column `DEFAULT 'UPLOADING'`. `IdpcAvailabilityService` overwrites it with `WAITING_FOR_UPLOAD` before the insert. | **No.** No production path persists a row in this phase. |
| `UPLOADED` | `RetrieveMaterialAndUploadTask.saveDocumentUploaded(...)` (~line 235) | **Yes** — set when the blob copy completes. |
| `INGESTING` | Nothing in `src/main/java`. The only occurrences of the literal are two test fixtures (`IngestionStatusHttpLiveTest`, `IngestionStatusViewRepositoryTest`). | **No.** |
| `INGESTED` / `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT` | `CheckIngestionStatusForAllDefendantsTask.updateIngestionPhase(...)` | Yes — terminal. |
| `NOT_FOUND` | Only `IngestionService` (~line 38), as a **response** value on a DTO. Never persisted. | **No** — not a persisted phase at all. |

So the ticket's three-phase set contains two phases that no production code ever writes, and omits
the one intermediate phase that is both reachable and genuinely at risk of stalling. A document at
`UPLOADED` has had its blob copied and is waiting for `CheckIngestionStatusForAllDefendantsTask` to
poll RAG's `GET /document-upload/{documentReference}` until it returns a terminal status. That poll
is retry-bounded (`CDK_JOBMANAGER_RETRY_VERIFY_DOC_MAX_ATTEMPTS:50`,
`CDK_JOBMANAGER_RETRY_VERIFY_DOC_DELAY_SECONDS:5`). If RAG never confirms, if the JobManager task
is lost, or if the retry budget is exhausted, the row **stays at `UPLOADED` for ever** — invisible,
with no terminal phase, and with the case's AI Search results permanently empty.

That is the exact user-visible failure the ticket's own problem statement describes ("a legal
adviser in a live hearing finds an empty AI Search result"), and as written FR-001 does not detect
it. As written, `cdk_documents_stalled` is a `WAITING_FOR_UPLOAD` counter with two permanently-zero
companion series.

### Decision

**Recommend to the requirements owner that FR-001's stalled-phase set becomes
`WAITING_FOR_UPLOAD`, `UPLOADING`, `UPLOADED`, `INGESTING`** — the ticket's three plus `UPLOADED`.

Design is not taking this unilaterally. It is a change to a functional requirement's scope and it
adds one series to NFR-002's inventory. The Stage-2 gate must accept or reject it, and Stage 3 must
reflect the outcome in Story 3 and Story 4.

Supporting positions, which hold either way:

- **Keep `UPLOADING` and `INGESTING` in the set** even though nothing writes them today. They are
  legal enum values, `UPLOADING` is the database column default so any future insert path that
  forgets to set the phase lands there, and a permanently-zero series costs one row on a scrape
  while a missing series costs an undetected stall. AC-009's "publish 0 rather than disappear"
  reasoning applies here too.
- **`NOT_FOUND` is confirmed terminal — in fact confirmed non-persisted.** Excluded, and
  AC-003's parenthetical about it can be closed.
- The `V1014` index (ADR-003) is deliberately non-partial, so this decision changes exactly one
  thing in the code: the literal `IN`-list inside one native query, plus the set the component
  iterates when seeding gauges. No migration impact either way.

### Alternatives considered

- **Ship FR-001 exactly as written.** Rejected as the recommendation, but it is a legitimate
  outcome of the gate — it is cheap, it is what the ticket says, and `UPLOADED` can be added later
  by a one-line change with no migration. The cost of choosing it is that the ticket's headline
  scenario stays undetected until that follow-up ships.
- **Add `UPLOADED` silently as an implementation detail.** Rejected outright. CLAUDE.md's hard rule
  is "never invent requirements"; widening a gauge's population changes what an alert fires on and
  is precisely the kind of change that must be visible to whoever writes the alert threshold.
- **Also fix the dead `UPLOADING`/`INGESTING` phases** (e.g. have `RetrieveMaterialAndUploadTask`
  set `UPLOADING` before the copy, and `CheckIngestionStatus…` set `INGESTING` while polling).
  Rejected as scope creep — that is a change to ingestion behaviour, explicitly out of scope
  ("Any change to discovery behaviour … Only observability is added"). Recorded as a follow-up: the
  phase model currently has two unused states, which is a latent modelling defect worth its own
  ticket, and would make these gauges considerably more informative.

### Consequences

- **Positive:** the gauge actually detects the failure mode the ticket was raised for.
- **Positive:** the two dead phases are now documented rather than folklore, and the follow-up
  ticket to either populate or remove them has evidence attached.
- **Accepted:** one extra series (`phase="UPLOADED"`), and an alert threshold that must be tuned
  for a phase with genuine normal-operation occupancy — unlike `WAITING_FOR_UPLOAD`, a document
  legitimately sits at `UPLOADED` for as long as RAG takes to ingest it. The 30-minute default
  threshold has to be larger than normal RAG ingestion latency or this series will alert on healthy
  traffic. **This is the one number in the ticket that needs real-world calibration**, and it is a
  direct input to OQ-011's alert-rule ticket.
- **Reversibility:** total. One literal in one native query and one entry in one set.

---

## ADR-006: Publish scheduler identity and enabled-state from an always-present `SchedulerMetrics` component reading a new bound `enabled` flag, tagged with fixed kebab-case constants

- **Status:** Accepted at Stage-2 gate (2026-08-25) · **Date:** 2026-08-25 · **Jira:** DD-43185 · **Resolves:** OQ-007, OQ-008
- **Artefacts:** `01-requirements.md` (FR-007, FR-008, FR-010, AC-016, AC-020 – AC-022, OQ-007, OQ-008) · `02-design.md` (§4, §5, §6)

### Context

**OQ-007.** Both schedulers carry
`@ConditionalOnProperty(name = "scheduler.<x>-discovery.enabled", havingValue = "true",
matchIfMissing = true)`, and `application-cdk.yml` binds each to a `CP_CDK_*` placeholder
defaulting to `false`. A disabled scheduler therefore has **no bean at all**, so neither
`cdk_scheduler_enabled` nor the startup INFO log can originate from the scheduler classes — yet
AC-020 requires the gauge to report `0` for exactly those absent beans. Separately,
`SchedulerProperties.IntradayDiscovery` / `NightlyDiscovery` expose `name`, `cron`,
`lockAtLeastFor`, `lockAtMostFor` (and `daysAhead`) but **no `enabled` field**, even though the YAML
sets `scheduler.*.enabled`. The property is written and read by the conditional, but never bound.

**OQ-008.** "Tagged by scheduler name" has three candidate readings: the class name
(`IntradayDiscoveryScheduler`), the configured ShedLock name (`scheduler.intraday-discovery.name`,
default `intradayDiscoveryScheduler`), or a kebab-case key matching the config namespace. The
ShedLock name is a runtime-overridable property whose job is distributed locking, not identity — if
an environment overrode it, that environment's metric series would silently rename and every alert
rule keyed on the old value would go quiet. Silent-alert-loss is the worst available failure mode
for an observability ticket.

### Decision

**1 — Bind the flag.** Add `private boolean enabled = true;` to both nested classes in
`SchedulerProperties`. The Java default is `true`, **not** `false`, so that it mirrors
`matchIfMissing = true` and the gauge agrees with bean presence in the "property genuinely absent"
case. In every shipped configuration `application-cdk.yml` supplies the value, so the effective
default remains `false` — Stage 1's reading is unchanged. A code comment on the field states the
mirroring relationship, because the two defaults must be changed together or not at all.

**2 — One always-present component.** A new `uk.gov.hmcts.cp.cdk.metrics.SchedulerMetrics`
(`@Component`, no `@ConditionalOnProperty`, depends only on `MeterRegistry` and
`SchedulerProperties` — never on the scheduler beans) owns all three scheduler meters. In its
constructor it eagerly registers, for **both** schedulers regardless of enabled state:

- `cdk.scheduler.enabled` — value `1`/`0` from `SchedulerProperties`;
- `cdk.scheduler.last.success.epoch.seconds` — seeded to `0`;
- `cdk.scheduler.runs` — all four `scheduler` × `outcome` counters created at `0`, so that
  `increase(cdk_scheduler_runs_total[…]) == 0` has a series to evaluate against instead of no data.

It exposes one method, `recordRun(String schedulerTag, boolean success)`, which increments the
right counter and — on success only — sets the heartbeat gauge (FR-009: unchanged on failure).

**3 — Log at `ApplicationReadyEvent`, not in the constructor**, so AC-022's "exactly once at
startup" is anchored to a single well-defined lifecycle event rather than to bean-instantiation
order. The same listener performs a **cross-check**: it injects
`ObjectProvider<IntradayDiscoveryScheduler>` / `ObjectProvider<NightlyDiscoveryScheduler>` and logs
a WARN if bean presence disagrees with the configured flag. This is six lines and it guards the one
way this design can go wrong — the gauge reporting configured *intent* while the pod's actual
behaviour differs. `ObjectProvider` is lazy, so it creates no bean-ordering dependency, and
resolving it at `ApplicationReadyEvent` means every conditional has already been evaluated.

**4 — Fixed tag values.** `scheduler` takes one of exactly two compile-time constants in
`CdkMeters`: `"intraday-discovery"` and `"nightly-discovery"`. They match the configuration
namespace (`scheduler.intraday-discovery.*`), are lowercase kebab-case per ADR-001's tag-value
rule, and are **not** derived from any property, class name, or ShedLock lock name. The identical
constant is used for `cdk_scheduler_runs_total`, `cdk_scheduler_last_success_epoch_seconds` and
`cdk_scheduler_enabled` (AC-016), and each scheduler class references it via a `static final`
import rather than a literal.

### Alternatives considered

- **Read `Environment.getProperty("scheduler.intraday-discovery.enabled")` from the monitoring
  component.** Rejected: it re-implements relaxed binding and default handling by hand, sidesteps
  `@ConfigurationProperties` validation and IDE/metadata support, and leaves the YAML setting a
  property that no `@ConfigurationProperties` class acknowledges — the very gap OQ-007 identified.
- **Derive the gauge purely from bean presence via `ObjectProvider`.** Genuinely tempting: it
  cannot disagree with reality, which is what AC-020 is really asking about. Rejected as the
  *primary* source because it forces meter registration to `ApplicationReadyEvent`, leaving a
  window in which a scrape sees no series, and because it couples a metrics component to two
  scheduler classes it otherwise has no reason to know about. Kept as the cross-check in (3), which
  gets the safety without the coupling cost.
- **Emit the gauge from each scheduler bean, plus a separate "absent scheduler" component.**
  Rejected: two registration sites for one metric, and the absent-scheduler component still needs
  to know which schedulers *should* exist — i.e. it needs exactly the bound flag from (1) anyway.
- **`scheduler="IntradayDiscoveryScheduler"` (class name).** Rejected: mixed case in a label value
  is awkward for alert authors, and a class rename — a routine refactor that no reviewer would
  flag as breaking — would silently rename a production metric series.
- **`scheduler="${scheduler.intraday-discovery.name}"` (the ShedLock name).** Rejected on the
  environment-drift argument above. The ShedLock name exists to identify a *lock row*; overloading
  it as a metric identity couples two things that should be free to differ.
- **`scheduler="intraday"` / `"nightly"`.** Considered and rejected only for being less
  greppable; `intraday-discovery` maps one-to-one onto the config key an operator will be reading.

### Consequences

- **Positive:** AC-020 and AC-021 are satisfiable for a bean that does not exist, without any
  reflection, `Environment` poking, or conditional gymnastics.
- **Positive:** `scheduler.*.enabled` becomes a first-class bound property, so it appears in
  configuration metadata and can be asserted in a `@ConfigurationProperties` unit test.
- **Positive:** metric identity is a compile-time constant that no environment can change, which is
  the property alert rules in another repository need most.
- **Accepted:** two sources describe the same fact (the `@ConditionalOnProperty` string and the
  bound field). They read the same property key, so they cannot resolve differently for the same
  input — and the (3) cross-check catches any future divergence loudly. A shared constant for the
  property key is not possible: `@ConditionalOnProperty` requires a compile-time constant string
  and `SchedulerProperties`' prefix is declared separately.
- **Accepted:** `cdk_scheduler_enabled` is fixed at startup and will not reflect a property changed
  at runtime. Correct — the *bean* is fixed at startup too, so the gauge tracks reality.
- **Accepted:** `IntradayDiscoveryScheduler` and `NightlyDiscoveryScheduler` gain a constructor
  parameter, so `IntradayDiscoverySchedulerTest` and `NightlyDiscoverySchedulerTest` must change
  their `new …(discoveryService)` call. AC-024 names the two **live** tests as needing no
  modification; the unit tests need a construction-site edit (not an assertion change), which
  Story 5 covers.
- **Reversibility:** good for (1)–(3); poor for (4) once external alert rules exist, same as
  ADR-001.

---

## ADR-007: Keep the scheduler heartbeat in memory, per pod — the counter is the liveness signal, the gauge is a diagnostic

- **Status:** Accepted at Stage-2 gate (2026-08-25) · **Date:** 2026-08-25 · **Jira:** DD-43185 · **Resolves:** OQ-010
- **Artefacts:** `01-requirements.md` (FR-007, FR-008, Out-of-scope, AC-014 – AC-016, OQ-010, OQ-011) · `02-design.md` (§6, §11)

### Context

`cdk_scheduler_last_success_epoch_seconds` as a plain in-memory gauge has two behaviours the ticket
does not address, both identified in OQ-010:

- **Multi-pod.** Discovery runs are ShedLock-guarded, so on any cron tick exactly one pod executes.
  Every other pod's gauge stays at whatever it last set — frequently `0`. The obvious alert
  `time() - cdk_scheduler_last_success_epoch_seconds > X` would fire permanently against the
  non-executing pods.
- **Restart.** After a rolling restart the gauge has no observed success until the next run. For
  `NightlyDiscoveryScheduler` (`0 0 2 * * *`) that is up to 24 hours of "no recent success", which
  is indistinguishable from a genuinely broken scheduler.

Stage 1 listed persistence to the database as a possible answer, while also listing "persisting
scheduler heartbeat state" as explicitly out of scope. This ADR settles which way that tension
resolves.

The decisive observation is that **`cdk_scheduler_runs_total` already solves both problems, and
the gauge does not need to.** A counter is restart-safe (`increase()` and `rate()` handle resets by
definition) and pod-safe (`sum by (scheduler)` aggregates naturally, because each pod contributes
only the runs it actually performed).

### Decision

**Keep both meters in memory. Add no table, no migration, no write on the scheduler path.**

Assign the two meters distinct roles and document them so the OQ-011 alert-rule ticket inherits the
reasoning rather than rediscovering it:

- **`cdk_scheduler_runs_total` is the primary liveness signal.** Recommended alert shape
  (documentation only — alert rules are out of scope and live in another repository):

  ```promql
  sum by (service, cluster, scheduler) (increase(cdk_scheduler_runs_total{outcome="success"}[45m])) == 0
    and on (service, cluster, scheduler)
        max by (service, cluster, scheduler) (cdk_scheduler_enabled) == 1
  ```

  The `cdk_scheduler_enabled == 1` join is what stops the alert firing for the deliberately
  disabled schedulers that both flags default to — and it is the reason FR-010's gauge is worth
  having beyond a startup log line. Window per scheduler: intraday ≈ 45 min (cron is 10-minutely,
  weekday-daytime only, so the rule needs a time-of-day guard); nightly ≈ 26 h.

- **`cdk_scheduler_last_success_epoch_seconds` is a dashboard and triage gauge.** Seeded to `0` at
  registration so the series always exists (never absent, never `NaN`), and always aggregated with
  `max by (service, cluster, scheduler)` so the pod that actually ran dominates the pods that did
  not. A `0` from a never-run pod loses to any real timestamp under `max`. If it is ever used in an
  alert, the post-restart window must be suppressed with the framework-supplied
  `process_start_time_seconds`.

- **A failure rate alert comes free from the same counter:**
  `sum by (scheduler) (increase(cdk_scheduler_runs_total{outcome="failure"}[1h])) > 0`.

**`cdk_scheduler_runs_total` counts scheduled runs only.** `DiscoveryTriggerService` (the manual
`/discovery-scheduler` trigger, DD-43062) calls the same `DiscoveryService` methods but is **not**
instrumented by this ticket: it is not ShedLock-guarded, it is operator-initiated, and counting it
would let a manual run mask a dead scheduler — inverting the metric's purpose. If manual runs need
counting later, the additive change is a third tag (`trigger="scheduled"|"manual"`), which is
recorded as a follow-up, not done here.

### Alternatives considered

- **Persist the heartbeat to the database** (a `scheduler_heartbeat` table, or a column on
  `shedlock`), so every pod reports the same cluster-wide value. Rejected on five counts, in
  descending weight: (1) it duplicates a signal the counter already provides correctly; (2) it
  makes an observability signal depend on the database whose health is one of the things you want
  the signal to survive; (3) every pod would need to *read* it on a schedule — i.e. a second
  ShedLock-free refresh job, re-importing the whole staleness problem of ADR-008 on the read side;
  (4) it adds a write to the scheduler's critical path, contradicting FR-009's containment intent
  and NFR-004's "no new failure mode"; (5) it needs a table, a migration, and a retention answer.
  Stage 1's own out-of-scope list already leans this way; this ADR confirms it with reasons.
- **Mutate `shedlock.locked_at` as a de-facto heartbeat.** Rejected: ShedLock owns that table, the
  row means "lock acquired", not "run succeeded", and reading another library's internal table is
  a coupling that breaks on any ShedLock upgrade.
- **Seed the gauge to process start time instead of `0`,** so `time() - gauge` behaves sensibly
  after a restart. Rejected: it makes the gauge report a *lie* about when the scheduler last
  succeeded, and the same suppression is achievable honestly with `process_start_time_seconds`,
  which Micrometer already publishes.
- **Do not register the gauge until the first success** (series absent, alerts use `absent()`).
  Rejected: it contradicts AC-009's principle that a series must not vanish, and `absent()` rules
  are notoriously fragile across pod churn.

### Consequences

- **Positive:** zero new persistent state, zero new failure modes, zero schema change, and both
  restart and multi-pod semantics are handled by standard PromQL that any SRE will recognise.
- **Positive:** the design hands OQ-011's owner concrete, justified expressions rather than a bag
  of metrics.
- **Accepted:** alert rules **must** aggregate. A naive per-pod rule on either meter will produce
  false positives. This is a documentation obligation onto the OQ-011 ticket, and it must be
  written into the handover, not assumed.
- **Accepted:** `cdk_scheduler_last_success_epoch_seconds` reads `0` on every pod that has not run
  since its own start. Harmless under `max`, confusing on a naive per-pod dashboard panel.
- **Accepted:** manual discovery triggers are invisible to these metrics. Deliberate; recorded as
  a follow-up.
- **Reversibility:** excellent. Persisting the heartbeat later is purely additive — a new table, a
  new migration, and the existing gauge repointed at it. Nothing decided here forecloses it.

---

## ADR-008: ShedLock makes the stalled-work gauges per-pod stale; publish a refresh-freshness gauge and require `max by (…)` aggregation

- **Status:** Accepted at Stage-2 gate (2026-08-25) — NFR-002's series inventory is **14** ·
  **Date:** 2026-08-25 · **Jira:** DD-43185 ·
  **Arises from:** FR-004, FR-006; informs OQ-011
- **Artefacts:** `01-requirements.md` (FR-004, FR-006, NFR-002, NFR-003, AC-011, AC-013) · `02-design.md` (§8, §11)

### Context

FR-004 requires the stalled-work refresh to be ShedLock-guarded "so that only one pod in the
cluster performs the refresh", and AC-011 tests for a `shedlock` row. That requirement is sound as
a load-control measure but it has a consequence the ticket does not state.

Unlike the scheduler heartbeat (which is genuinely a per-pod fact — *this* pod ran the job), the
two stalled-work gauges describe a property of the **shared database**, identical from every pod's
viewpoint. ShedLock means only one pod's copy is ever refreshed on a given tick. So at any moment,
in an N-pod deployment:

- one pod holds a fresh value;
- the others hold whatever they last computed, on whichever tick they last won the lock — possibly
  hours ago, possibly `0` from registration if they have never won.

The consequences for alerting are sharp and easy to get wrong:

- **`sum by (phase) (cdk_documents_stalled)` is wrong** — the intuitive aggregation for a count
  metric. It adds one fresh reading to N−1 stale ones and over-reports, badly.
- **`max by (phase) (cdk_documents_stalled)` is *nearly* right**, and is the best simple option —
  but it also latches onto a stale high reading. A pod that observed 400 stalled documents an hour
  ago keeps reporting 400 long after the backlog cleared, and `max` will prefer it over the current
  winner's `0`.

There is no way for a pod to tell that it did not run: with `interceptMode = PROXY_METHOD`, an
unacquired lock means the method body simply does not execute.

### Decision

**Implement ShedLock as FR-004 mandates** — lock name `stalledWorkMetricsRefresh` (a literal
constant, deliberately *not* a property placeholder, unlike the two existing schedulers; see
ADR-006 on environment-drift), `lockAtMostFor = PT5M` explicitly overriding `ShedLockConfig`'s
global `defaultLockAtMostFor = "PT30S"` (FR-004 requires this; 30 s is shorter than the 60 s
cadence), `lockAtLeastFor = PT55S` so one refresh per cadence holds cluster-wide.

**And publish one companion gauge to make staleness detectable:**

`cdk.monitoring.last.refresh.epoch.seconds` → `cdk_monitoring_last_refresh_epoch_seconds`, no
ticket-specific tags, set to `utcNow()` epoch seconds at the end of **every** refresh in which at
least one aggregate succeeded. Seeded to `0`.

Recommended consumption (documentation only; alert rules are out of scope, OQ-011):

```promql
max by (service, cluster, phase) (
  cdk_documents_stalled
    and on (instance) (time() - cdk_monitoring_last_refresh_epoch_seconds < 300)
)
```

— i.e. take the maximum across pods, but only across pods whose reading is younger than five
minutes. Without the freshness join, a `max` alert cannot distinguish a real backlog from a
stale one.

The gauge doubles as the health signal for the refresh job itself: if
`time() - max(cdk_monitoring_last_refresh_epoch_seconds) > 300` on **every** pod, the refresh has
stopped everywhere (ShedLock deadlock, DB unavailable, flag turned off) and every stalled-work
gauge should be treated as unknown rather than as `0`. That is a failure mode with no other
detector, and it is the reason this is worth one extra series.

### Alternatives considered

- **Drop ShedLock; let every pod compute its own copy.** Technically the *correct* design for a
  metric that describes shared state: every pod would then be fresh, `max`/`avg` would be exact,
  no freshness gauge would be needed, and the whole class of staleness reasoning disappears. The
  cost is (N−1) × 3 indexed aggregate queries per minute — with three pods, six extra sub-second
  reads per minute, against a HikariCP pool of 20 that is already absorbing a 10-minutely discovery
  run. **Rejected only because FR-004 mandates the lock and AC-011 tests for it.** The Stage-2 gate
  should know that relaxing FR-004 would delete this ADR, the extra series, and a paragraph of
  alerting complexity — Design's recommendation is to consider it.
- **Accept staleness and document `max by (…)` alone.** Rejected: it is one line cheaper and
  produces an alert that cannot tell a current backlog from a historical one. For a ticket whose
  entire purpose is trustworthy signals, that is the wrong saving.
- **Publish `NaN` from pods that did not refresh.** Rejected: with `interceptMode = PROXY_METHOD`
  a pod cannot detect that it did not run, and `NaN` propagates awkwardly through PromQL
  aggregation.
- **Give the refresh a very short `lockAtLeastFor` so pods rotate.** Rejected: it reduces the
  *average* staleness without bounding the *worst case*, and taken far enough it is just
  "no ShedLock" with extra steps and a weaker guarantee.

### Consequences

- **Positive:** the stalled-work gauges become safely interpretable, and "the refresh job has
  stopped" becomes detectable — which it otherwise is not.
- **Positive:** one gauge covers both the per-pod-staleness problem and the job-liveness problem.
- **Accepted:** NFR-002's series inventory rises by one, to a ticket total of **14**. Still fixed,
  still bounded, still no user-supplied tag value. NFR-002 should be amended at the gate.
- **Accepted:** an alerting obligation is transferred to OQ-011's owner: never `sum`, always `max`,
  always with the freshness join. It must be written into the handover explicitly.
- **Accepted:** `lockAtLeastFor = PT55S` is implicitly coupled to `refresh-interval = PT1M`.
  ADR-002's startup WARN covers the case where one is changed without the other.
- **Reversibility:** excellent. Removing the gauge, or removing ShedLock, are both single-commit
  changes with no schema or contract implication.
