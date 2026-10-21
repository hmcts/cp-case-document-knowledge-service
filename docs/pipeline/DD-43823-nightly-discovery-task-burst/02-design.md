# Design: Deferred-Start Staggering of Nightly Discovery Entry Tasks (Option A1)

> **Stage 2 — Architecture & Design** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43823** · Evidence / brief: [`nightly-discovery-task-burst-analysis.md`](./nightly-discovery-task-burst-analysis.md) ·
> ADRs: [`adrs/DD-43823-nightly-discovery-task-burst.md`](../adrs/DD-43823-nightly-discovery-task-burst.md)
>
> Implements **Option A1** from the analysis: release the per-case
> `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS` entry tasks at a controlled rate instead of all at
> 02:00, using the Task Manager library's native `withAssignedTaskStartTime`. One new nested
> properties class, one new config block, one overloaded dispatch method, and one changed dispatch
> loop. **No API change, no new endpoint, no ACL change, no contract change, no migration, no new
> external integration.** Behaviour is **off by default** (`stagger.enabled=false`) — when disabled,
> every path behaves exactly as it does today.
>
> **Scope boundary.** This design covers A1 (smoothing) only. A2 (priority by hearing proximity)
> is noted where it will later attach to the same ordered list, but is **not** built here. B1
> (cap + carry-over) is explicitly out of scope — A1's tripwire is the trigger that would later
> justify B1.
>
> **Evidence base (from the analysis doc):** measured NFT throughput ≈ **24–28 answers/min
> (saturated), RAG/poll-bound, not pool-bound**; ≈ **10 answers/case** at d≈1; window capacity
> ≈ **1,000 cases/night**. These numbers set the default release rate and the tripwire anchor
> below. All were measured on **NFT**; the prod RAG rate is an open question (§8) and the rate
> must be re-tuned once it is known.
>
> **Decisions needing an explicit accept-or-reject at the gate** (argued in the ADR file):
> **ADR-003** (default rate `2 cases/min`, shipped **off**) and **ADR-004** (`max-window` is a
> tripwire, not a limiter — overflow bunches rather than carries over).

---

## Detailed Design

### 1. Shape of the change

Today, `DiscoveryService.runNightlyDiscovery()` (the `isHearingForCasesEnabled = true` branch)
dispatches one `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS` entry task per unique case, and
`JobManagerService.dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(...)` stamps each job
with `withAssignedTaskStartTime(ZonedDateTime.now())`. Every entry task therefore becomes eligible
to the `JobExecutor` poller at 02:00 simultaneously, and the whole fan-out cascade starts in one
burst.

A1 changes **only the start-time stamp**. The dispatch loop still runs once, still inserts N rows,
still completes in seconds. Each entry job is instead stamped with a **staggered future start
time** so the poller sees them become eligible at a bounded rate (≈ the sustainable RAG rate). The
per-case cascade then self-paces behind its entry task; the queue stays shallow; there is no 02:00
spike; a manual `HIGH` job can still jump the line.

```
02:00  dispatch loop (unchanged: one pass, inserts N rows)   ── completes in seconds ──┐
        row[0]  startTime = now                                                        │
        row[1]  startTime = now + 1 * (60s / casesPerMinute)                           │
        row[2]  startTime = now + 2 * (60s / casesPerMinute)                           │
        ...                                                                            │
        row[k]  startTime = now + min(k * 60s / casesPerMinute, maxWindow)             │
                                                                                       ▼
        JobExecutor poller (continuous, independent of the scheduler lock) claims each
        row only once its startTime is reached → entry tasks fire at ~casesPerMinute,
        each fanning out into RETRIEVE → CHECK_INGESTION → GENERATE_ANSWER → CHECK_STATUS.
```

Four files change (§9). The lever is entirely within the existing Task Manager contract
(`ExecutionInfo.withAssignedTaskStartTime(ZonedDateTime)`), already used on the `GET_CASES_FOR_HEARING`
and retry paths — no new library capability is introduced. (ADR-001.)

### 2. Start-time formula (ADR-002, ADR-004)

For the ordered list of unique case ids `[c0, c1, … cN-1]` (order preserved from the existing
`LinkedHashSet`; A2 will later sort this list), and a base instant `base = ZonedDateTime.now()`
captured once per run:

```
offset(i)  =  round( i / casesPerMinute * 60_000 )   milliseconds
start(i)   =  base + min( offset(i), maxWindow )
```

- `i = 0` → `start = base` (immediate; identical to today for the first case).
- With `casesPerMinute = 2`, consecutive cases are released 30 s apart.
- The `min(…, maxWindow)` is a **backstop clamp only** — see §6. It does not implement a deadline
  or a carry-over.

When `stagger.enabled = false`, or `casesPerMinute <= 0`, `start(i) = base` for every `i` — i.e.
the current all-at-02:00 behaviour, bit-for-bit. (ADR-003: default-off.)

### 3. Configuration — `application-cdk.yml`

A new `stagger` block under the existing `scheduler.nightly-discovery`:

```yaml
  nightly-discovery:
    name: "nightlyDiscoveryScheduler"
    cron: "${CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_CRON:0 0 2 * * *}"  # daily at 02:00
    lock-at-least-for: "${CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_LOCK_AT_LEAST_FOR:PT1H}"
    lock-at-most-for: "${CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_LOCK_AT_MOST_FOR:PT2H}"
    enabled: ${CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED:false}
    days-ahead: ${CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_DAYS_AHEAD:3}
    stagger:
      enabled: ${CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_STAGGER_ENABLED:false}   # off until tuned per-env
      cases-per-minute: ${CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_STAGGER_CASES_PER_MINUTE:2}
      # Tripwire only (not a deadline): warns when the stagger window exceeds this, signalling that
      # volume has outgrown A1 smoothing -> consider option B1 (cap + carry-over). Generous so it
      # never reshapes a legitimate night; the clamp itself is only a backstop against misconfig.
      max-window: ${CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_STAGGER_MAX_WINDOW:PT8H}
```

All three keys are environment-overridable, consistent with every other value in this block.
Defaults: **off**, `2` cases/min (ADR-003), `PT8H` tripwire (ADR-004).

### 4. `SchedulerProperties` — bind the new block

`scheduler.nightly-discovery.stagger` binds to a new nested `Stagger` type on
`SchedulerProperties.NightlyDiscovery`. `NightlyDiscovery` is already `@Data`; the nested type
follows the same convention. `Duration` binds from the ISO-8601 string (`PT8H`) by Spring Boot's
standard relaxed binding.

```java
import java.time.Duration;

@Data
public static class NightlyDiscovery {
    private String name;
    private String cron;
    private String lockAtLeastFor;
    private String lockAtMostFor;
    private int daysAhead = 3;
    private boolean enabled = true;          // unchanged — see existing javadoc

    private final Stagger stagger = new Stagger();

    public Stagger getStagger() {
        return stagger;
    }

    /**
     * A1 (DD-43823): release per-case CHECK_IDPC entry tasks at a controlled rate instead of
     * all at 02:00, by stamping each job with a staggered assigned-start-time. Off by default.
     */
    @Data
    public static class Stagger {
        /** When false, entry tasks start immediately (current behaviour). */
        private boolean enabled;
        /** Release rate for per-case entry tasks; keep at or below the sustainable RAG rate. */
        private double casesPerMinute = 2.0;
        /**
         * Tripwire threshold, not a deadline: if the computed stagger window exceeds this, log a
         * warning (volume is outgrowing A1 smoothing — consider DD-43823 option B1). Set generous
         * so it never clamps a legitimate night; the clamp itself is only a backstop against
         * misconfiguration.
         */
        private Duration maxWindow = Duration.ofHours(8);
    }
}
```

### 5. `JobManagerService` — overloaded entry dispatch

The existing package-private `dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(JsonObject)`
keeps its signature (so any current caller/test compiles unchanged) and delegates to a new
2-arg overload that threads the start time through. `GET_CASES_FOR_HEARING` (intraday + the
API-triggered `startIngestionProcess`) is **untouched** (ADR-002).

```java
/* package */
void dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(final JsonObject jobData) {
    dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(jobData, ZonedDateTime.now());
}

/* package */
void dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(final JsonObject jobData,
                                                             final ZonedDateTime assignedTaskStartTime) {
    final ExecutionInfo executionInfo = executionInfo()
            .withAssignedTaskName(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
            .withAssignedTaskStartTime(assignedTaskStartTime)
            .withJobData(jobData)
            .withExecutionStatus(ExecutionStatus.STARTED)
            .build();

    executor.executeWith(executionInfo);
}
```

### 6. `DiscoveryService` — staggered dispatch loop + tripwire

The `uniqueCaseIds` set is already a `LinkedHashSet` (stable order), so an indexed loop is
deterministic. The `forEach` dispatch is replaced; the per-case helper is widened to carry the
start time.

```java
// imports: java.time.Duration, java.time.ZonedDateTime, java.util.ArrayList,
//          uk.gov.hmcts.cp.cdk.scheduler.SchedulerProperties.NightlyDiscovery.Stagger

// --- inside runNightlyDiscovery(), replacing:
//     uniqueCaseIds.forEach(caseId -> dispatchCaseEligibilityCheck(caseId, cpSystemUserId));

final List<UUID> orderedCaseIds = new ArrayList<>(uniqueCaseIds);
final Stagger stagger = schedulerProperties.getNightlyDiscovery().getStagger();
final ZonedDateTime base = ZonedDateTime.now();

if (stagger.isEnabled() && stagger.getCasesPerMinute() > 0 && orderedCaseIds.size() > 1) {
    final long fullWindowMs = Math.round((orderedCaseIds.size() - 1) / stagger.getCasesPerMinute() * 60_000d);
    final long capMs = stagger.getMaxWindow().toMillis();
    if (fullWindowMs > capMs) {
        log.warn("Nightly discovery stagger window {} min exceeds tripwire {} min for cases={} at {}/min; "
                        + "volume is outgrowing stagger-based smoothing — consider DD-43823 option B1 (cap + carry-over)",
                fullWindowMs / 60_000, capMs / 60_000, orderedCaseIds.size(), stagger.getCasesPerMinute());
    }
    log.info("Nightly discovery staggering cases={} at {}/min over ~{} min",
            orderedCaseIds.size(), stagger.getCasesPerMinute(), Math.min(fullWindowMs, capMs) / 60_000);
}

for (int i = 0; i < orderedCaseIds.size(); i++) {
    dispatchCaseEligibilityCheck(orderedCaseIds.get(i), cpSystemUserId, staggeredStartTime(base, i, stagger));
}
```

```java
private ZonedDateTime staggeredStartTime(final ZonedDateTime base, final int index, final Stagger stagger) {
    if (!stagger.isEnabled() || stagger.getCasesPerMinute() <= 0 || index == 0) {
        return base;   // immediate — preserves current behaviour when disabled
    }
    final long offsetMs = Math.round(index / stagger.getCasesPerMinute() * 60_000d);
    final long cappedMs = Math.min(offsetMs, stagger.getMaxWindow().toMillis());
    return base.plus(Duration.ofMillis(cappedMs));
}

private void dispatchCaseEligibilityCheck(final UUID caseId, final UUID cpSystemUserId,
                                          final ZonedDateTime startTime) {
    final JsonObject jobData = toJobDataForCaseEligibility(caseId, cpSystemUserId);
    try {
        jobManagerService.dispatchCaseDocumentIngestionTasksCheckIdpcAvailability(jobData, startTime);
    } catch (Exception e) {
        log.error("Nightly Discovery - Failed to dispatch case ingestion tasks for the jobData={}", jobData, e);
    }
}
```

**What the tripwire does and does not do (ADR-004).** When the uncapped window would exceed
`max-window`, two things happen and nothing else:

1. a single `WARN` is logged (alertable), and
2. every case beyond the cap has its start time **clamped to `base + maxWindow`** — so those
   overflow cases share one start time and become eligible **together** (a smaller, delayed echo
   of the original burst).

The run is **never stopped**, and **no case is ever dropped or deferred to the next run**. The
poller keeps draining the queue continuously — past `max-window`, and if necessary into the
following day — because execution is independent of the scheduler lock (§7). `max-window` is
therefore a *smoothing ceiling + alert signal*, not a deadline, a limiter, or a circuit-breaker.
True "hold the overflow for the next run" behaviour is option **B1** and is out of scope here; the
WARN is precisely the trigger that would justify building it.

### 7. Interactions and invariants

- **ShedLock.** `@SchedulerLock` guards the 02:00 dispatch *loop*, not execution. The loop still
  only inserts rows and finishes in seconds, so `lock-at-most-for: PT2H` is untouched even when the
  stagger window is `PT8H`. Deferred start times live in the Task Manager `jobs` table; the
  `JobExecutor` poller claims them independently of the lock. A stagger window may therefore safely
  exceed the lock window. (ADR-001.)
- **Priority / manual HIGH (A2 seam).** Staggering sets *start time*, orthogonal to *priority*.
  Manual `HIGH=1` jobs remain claimable ahead of the `DEFAULT=10` nightly band regardless of the
  stagger. When A2 lands, it sorts `orderedCaseIds` (nearer hearing dates first) before the loop —
  no change to the start-time mechanism.
- **Retries.** Per-task retry backoff already uses `withAssignedTaskStartTime`; A1 adds no retry
  semantics and does not alter the retry config.
- **Intraday & API trigger.** Both run through `GET_CASES_FOR_HEARING`, which is not modified.
  A1 affects the nightly `isHearingForCasesEnabled = true` path only.
- **Idempotency / RAG data.** No change to job payloads, answer persistence, or any RAG source
  field — A1 only alters *when* an unchanged entry job starts.

### 8. Open questions (status after this design)

| OQ | Item | Status |
|---|---|---|
| OQ-001 | Prod RAG sustainable rate (NFT ≠ prod) — sets `cases-per-minute` | **Open** — tune per-env before enabling; default is conservative |
| OQ-002 | Multi-defendant (d>1) behaviour — all NFT data is d=1 | **Open** — unvalidated; A1 is per-*case* so it is unaffected structurally, but capacity maths shifts with d |
| OQ-003 | Enable in which environments first, and at what rate | **Open** — operational rollout decision; ships off |

None of these block the design; all are rollout/tuning inputs, and the default-off posture means
the change is inert until they are answered.

### 9. Files touched

| File | Change |
|---|---|
| `src/main/resources/application-cdk.yml` | Add `scheduler.nightly-discovery.stagger` block (3 keys) |
| `scheduler/SchedulerProperties.java` | Add nested `Stagger` type + `getStagger()` on `NightlyDiscovery` |
| `services/JobManagerService.java` | Overload `dispatchCaseDocumentIngestionTasksCheckIdpcAvailability` with a `ZonedDateTime` start time; keep no-arg delegating to `now()` |
| `services/DiscoveryService.java` | Indexed staggered dispatch loop, `staggeredStartTime(...)` helper, widened `dispatchCaseEligibilityCheck(...)`, tripwire WARN |

No migration, no API/contract change, no ACL change, no new dependency.

---

## Testing

All new tests are unit tests (JUnit 5, Mockito); no integration-test or compose change is required
because nothing in the HTTP/DB/Artemis surface moves. Argument captors assert the start-time
stamping precisely.

### Unit — `DiscoveryServiceTest`

1. **Disabled (default) → no behaviour change.** `stagger.enabled=false`: every dispatched job's
   start time equals the captured `base` (within tolerance); dispatch count equals
   `uniqueCaseIds.size()`; order preserved.
2. **Enabled → linear stagger.** `enabled=true, casesPerMinute=2`: captured start times increase
   by `30 s` per index; `index 0 == base`.
3. **Rate maths.** `casesPerMinute=4` → `15 s` steps; `casesPerMinute=1` → `60 s` steps
   (parameterised).
4. **Tripwire clamp + WARN.** Volume whose window exceeds `max-window`: cases past the cap all
   carry `base + maxWindow`; a `WARN` is emitted (log captor); **no case is dropped** — dispatch
   count still equals input size.
5. **Degenerate config.** `casesPerMinute=0` (or negative) with `enabled=true` → treated as
   immediate (`base` for all), no divide-by-zero.
6. **Single case.** `size()==1` → one immediate dispatch, no stagger summary log.
7. **Flag ignored off the hearing path.** `isHearingForCasesEnabled=false` branch is unchanged by
   the stagger config (regression guard).

### Unit — `JobManagerServiceTest`

8. **2-arg overload stamps the given start time.** Captor on `executor.executeWith(...)` asserts
   `ExecutionInfo.getAssignedTaskStartTime()` equals the passed `ZonedDateTime`, task name is
   `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS`, status `STARTED`, payload unchanged.
9. **No-arg overload uses `now()`.** Backward-compatibility: start time ≈ now (tolerance), proving
   existing callers are unaffected.

### Unit — `SchedulerProperties` binding

10. **Full block binds.** `enabled`, `cases-per-minute`, `max-window: PT8H` bind to the expected
    values; `Duration` parses.
11. **Absent block → defaults.** No `stagger` present → `enabled=false`, `casesPerMinute=2.0`,
    `maxWindow=PT8H`; `getStagger()` is non-null.

### Coverage / quality

- JaCoCo and PMD thresholds must pass unchanged (no threshold lowering). The new branches
  (enabled/disabled, clamp/no-clamp, degenerate rate) are all covered by tests 1–6.
- No new PII surface: tests use synthetic UUIDs only; no real case ids, no court references.
