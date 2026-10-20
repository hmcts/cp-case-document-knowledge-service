# Architecture Decision Records — Nightly Discovery Task Burst: Deferred-Start Staggering (Option A1)

> Service: `cp-case-document-knowledge-service` (CDKS) · Jira: DD-43823 · Taken at the Stage 2 (Architecture & Design) human gate.
> Requirement / evidence: [`../DD-43823-nightly-discovery-task-burst/`](../DD-43823-nightly-discovery-task-burst/) ·
> Analysis: [`nightly-discovery-task-burst-analysis.md`](../DD-43823-nightly-discovery-task-burst/nightly-discovery-task-burst-analysis.md) ·
> Design: [`02-design.md`](../DD-43823-nightly-discovery-task-burst/02-design.md)
>
> **Status of this file:** all decisions `Proposed` pending the Stage-2 human gate — nothing below
> is confirmed yet. ADR-003 and ADR-004 need an explicit accept-or-reject at the gate, not silent
> approval.
>
> **Scope:** these ADRs cover **Option A1** (deferred-start staggering) only. A2 (priority) and
> B1 (cap + carry-over) from the analysis are referenced but not decided here.

---

## ADR-001: Stagger using the Task Manager library's native `withAssignedTaskStartTime`, not pool tuning or an external throttle

- **Status:** Proposed · **Date:** 2026-10-19 · **Jira:** DD-43823
- **Artefacts:** `02-design.md` (§1, §2, §7) · analysis (§3 execution model, §4 throughput, §6 option A1)

### Context

The nightly run dispatches every per-case `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS` entry task at
02:00 with `withAssignedTaskStartTime(ZonedDateTime.now())`, so all N become eligible to the
`JobExecutor` poller at once and the whole fan-out cascade starts in one burst. The analysis
established that throughput is **RAG/poll-bound at ≈ 24–28 answers/min (saturated), not
pool-bound** — so enlarging the thread pool does not raise the ceiling, it only deepens the queue
and increases retry churn. The constraint is *rate of work admitted*, and the Task Manager library
already persists a per-job assigned start time that the poller honours (it is used on the
`GET_CASES_FOR_HEARING` and retry paths today).

### Decision

**Spread the entry tasks' start times** across a window at a configured rate, using the existing
`ExecutionInfo.withAssignedTaskStartTime(ZonedDateTime)`. No new library capability, no external
scheduler/queue, and **no thread-pool change**.

### Alternatives considered

- **Increase `core/max pool size`** — rejected: the measured bottleneck is RAG/poll latency, not
  pool capacity; more threads deepen the queue without raising throughput (analysis §4, §5).
- **External rate limiter / message-broker throttle** — rejected: new infrastructure and failure
  modes for a problem the library already solves with a persisted start time.
- **Shorten poll interval / enlarge `batchSize`** — this is a *different* lever (option A3, a
  throughput experiment), orthogonal to A1 and not a substitute for rate control; deferred.

### Consequences

- **Positive:** smallest possible change surface; reuses a battle-tested mechanism; protects RAG;
  reduces retry churn; no new infra. Start time is orthogonal to priority, so manual `HIGH` jobs
  still pre-empt.
- **Accepted limit:** staggers *entry* tasks only; within-case fan-out is still bounded by the
  pool. This is sufficient because the entry task is the root of each case's cascade — pacing the
  roots paces the cascades.
- **Reversible:** setting `stagger.enabled=false` restores today's behaviour exactly.

---

## ADR-002: Stagger the nightly per-case `CHECK_IDPC` entry path only; leave `GET_CASES_FOR_HEARING`, intraday, and the API trigger unchanged

- **Status:** Proposed · **Date:** 2026-10-19 · **Jira:** DD-43823
- **Artefacts:** `02-design.md` (§5, §7)

### Context

Three code paths dispatch entry tasks: the nightly `isHearingForCasesEnabled = true` branch
(per-case `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS`), the nightly/intraday `else` and intraday paths
(`GET_CASES_FOR_HEARING`), and the synchronous API trigger `startIngestionProcess`
(`GET_CASES_FOR_HEARING`). The burst problem is specific to the first: it is the path that fans out
one case into ~`6d+4` answers at a known high volume at a fixed 02:00 instant. The `GET_CASES`
paths are either low-volume, user-initiated, or already naturally spread.

### Decision

Thread the staggered start time through **only** the per-case
`dispatchCaseDocumentIngestionTasksCheckIdpcAvailability` path. Keep the existing no-arg method
(delegating to `now()`) so `GET_CASES_FOR_HEARING`, the intraday loop, and `startIngestionProcess`
compile and behave unchanged.

### Alternatives considered

- **Stagger every dispatch path uniformly** — rejected: the API trigger is synchronous and
  user-facing (staggering it would delay an interactive request for no burst-protection benefit),
  and intraday volume does not exhibit the burst.

### Consequences

- **Positive:** blast radius confined to the one path that has the problem; the interactive API
  trigger keeps its immediate-start contract; existing tests for the other paths are untouched.
- **Accepted:** a future burst discovered on the intraday path would need its own decision; this
  ADR does not pre-solve it.

---

## ADR-003: Default release rate `2 cases/min`, shipped **disabled**, tuned per environment against the prod RAG rate

- **Status:** Proposed · **Date:** 2026-10-19 · **Jira:** DD-43823 · **Needs explicit gate decision**
- **Artefacts:** `02-design.md` (§3, §8 OQ-001) · analysis (§4 throughput, §5 capacity)

### Context

The release rate must sit **at or below the sustainable RAG answer rate**, or staggering achieves
nothing (the queue refills as fast as it drains). The only measurement available is **NFT**:
≈ 24–28 answers/min saturated, ≈ 10 answers/case at d≈1 — i.e. ≈ 2.4–2.8 cases/min is the NFT
ceiling. The **prod** RAG rate is unknown (OQ-001). Shipping an aggressive default, or shipping
enabled, risks either under-protecting (rate too high) or silently slowing a healthy environment
(rate too low, enabled unexpectedly).

### Decision

Default `cases-per-minute = 2` (just under the NFT ceiling, leaving headroom) and default
`stagger.enabled = false`. Enablement and the production rate are a **per-environment operational
decision**, taken after the prod RAG rate is measured. All keys are environment-overridable.

### Alternatives considered

- **Ship enabled with `2/min`** — rejected: would change behaviour in every environment on deploy
  before the prod rate is known; violates "inert until chosen".
- **Express the rate as answers/min or IDPCs/min** — rejected for the config surface: the dispatch
  loop iterates *cases*, so `cases/min` is the directly actionable unit; the analysis documents the
  `answers = 6d+4` conversion for operators who need it.
- **Derive the rate automatically from a live RAG measurement** — rejected as premature: adds a
  feedback-control mechanism before we have even a static prod baseline; revisit only if manual
  tuning proves insufficient.

### Consequences

- **Positive:** the change is inert on deploy (default-off); conservative when first enabled;
  tunable without a code change.
- **Accepted risk:** `2/min` is derived from **NFT at d=1**. If prod RAG is slower, `2/min`
  under-protects until re-tuned; if faster, it leaves throughput on the table. Both are config
  fixes, not code changes. **The gate must confirm "ship off, tune later" is acceptable.**
- **Open:** OQ-001 (prod rate) and OQ-002 (d>1 behaviour) remain open and are rollout inputs, not
  blockers.

---

## ADR-004: `max-window` is a tripwire (warn + backstop clamp), not a deadline or a carry-over limiter

- **Status:** Proposed · **Date:** 2026-10-19 · **Jira:** DD-43823 · **Needs explicit gate decision**
- **Artefacts:** `02-design.md` (§2, §6) · analysis (§6 option B1)

### Context

With a fixed release rate, a large night produces a long stagger window (`(N-1)/rate` minutes).
Something must bound how far the *last* case is deferred, and there was a choice of what that bound
means. Two fundamentally different behaviours were on the table: (a) a **smoothing ceiling** that
caps the deferral and keeps processing, versus (b) a **hard per-run limit** that stops admitting
work and holds the remainder for a later run (option B1 in the analysis). `days-ahead = 3` gives
days of runway before a case's hearing, so deferral is cheap; the real operational anchor is the
02:00-to-morning budget, not the case count.

### Decision

`max-window` is a **tripwire**: when the computed window exceeds it, log one alertable `WARN` and
**clamp** overflow cases' start times to `base + maxWindow` as a *backstop only*. The run is never
stopped and **no case is ever dropped or carried to the next run**. Default `PT8H`, chosen generous
(02:00 + 8h = 10:00; at `2/min` it only trips past ~960 cases ≈ the full ~1,000-case/night NFT
ceiling) so it never reshapes a legitimate night — it fires only when volume has outgrown A1
entirely. True carry-over is **option B1**, explicitly out of scope; this WARN is its trigger.

### Alternatives considered

- **Treat `max-window` as a hard cap-per-run with carry-over (B1)** — rejected *for A1*: requires
  new backlog state and defers those cases' answers by a day; a bigger change than A1's smoothing
  remit. Deferred to B1, to be built only if the tripwire shows volume consistently exceeds the
  window.
- **No clamp at all (warn only)** — rejected: a config typo (e.g. `cases-per-minute: 0.01`) could
  defer a case absurdly far with nothing to stop it. The clamp is a cheap backstop that only ever
  engages in pathological cases.
- **Small/tight `max-window` (e.g. PT3H) sized to a specific N** — rejected: couples the cap to the
  rate and to an assumed case count, so lowering the rate would silently clamp normal nights and
  defeat the stagger.

### Consequences

- **Positive:** A1 stays a pure smoothing lever; the cap never interferes on a real night; the
  WARN is an alertable, unambiguous "time to consider B1" signal that costs nothing until it fires.
- **Accepted:** when the tripwire *does* fire, clamped overflow cases share one start time and
  therefore bunch — a smaller, delayed echo of the original burst. This is acceptable because by
  construction it only happens past the full measured nightly capacity, where B1 (not more
  smoothing) is the correct response.
- **Reversible / extensible:** the WARN is the natural hook for B1; adding carry-over later is
  additive and does not change A1's mechanism.
