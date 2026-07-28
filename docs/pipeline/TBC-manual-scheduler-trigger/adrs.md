# Architecture Decision Records — Manual Discovery Scheduler Trigger Endpoint

> Service: `cp-case-document-knowledge-service` (CSDK)
> All decisions below were taken at the **Stage 1 → Stage 2 human gate** and are recorded here as the
> durable rationale. They are inputs to, not outputs of, the Stage 2 design.
> Requirements: [`01-requirements.md`](./01-requirements.md) · Design: [`02-design.md`](./02-design.md)

---

## ADR-001: Manual discovery trigger does not coordinate with the scheduler's distributed lock

- **Status:** Accepted
- **Date:** 2026-07-28
- **Jira:** TBC — no ticket raised yet (OQ-011). Must be linked before Stage 4 (Test Specs), per the CLAUDE.md hard rule.
- **Artefacts:** [`01-requirements.md`](./01-requirements.md) (OQ-002, NFR-004, AC-028, B-07, B-08) · [`02-design.md`](./02-design.md) (§3, §8.5, §13 R-1)
- **Decision drivers:** OQ-002 (concurrency policy), NFR-004 (concurrency/safety), FR-005 (reuse the existing operations unchanged), FR-010 (purely additive)

### Context

CSDK runs two ShedLock-guarded scheduled discovery operations. The `@SchedulerLock` annotation sits on
the **scheduler** method, not on the `DiscoveryService` method it calls:

- `IntradayDiscoveryScheduler.java:37-39` — lock `intradayDiscoveryScheduler`, `lockAtLeastFor PT8M`, `lockAtMostFor PT9M`
- `NightlyDiscoveryScheduler.java:30-32` — lock `nightlyDiscoveryScheduler`, `lockAtLeastFor PT1H`, `lockAtMostFor PT2H`

`DiscoveryService.runIntradayDiscovery()` and `runNightlyDiscovery()` are plain `public void` methods
with no locking of their own (`DiscoveryService.java:73,93`). The lock exists because CSDK runs on more
than one pod (`config/ShedLockConfig.java`, `db/migration/V1010__create_shedlock_table.sql`).

Consequently, **any** caller that invokes `DiscoveryService` directly bypasses the lock entirely. A new
manual trigger endpoint is exactly such a caller, so a policy decision was unavoidable. The
requirements deliberately refused to assume one and raised it as OQ-002 with four candidate options:
(a) reject with `409 Conflict` while the lock is held; (b) queue and serialise; (c) run concurrently,
unlocked; (d) share the existing lock so the manual run is silently skipped.

Two facts shaped the choice:

1. The nightly lock is held for **up to PT2H**. Any policy that defers to the lock makes manual
   triggering unavailable for up to two hours.
2. The operational driver for the endpoint is still unstated (**OQ-001**), but every candidate driver
   the requirements considered — recovery from a missed or failed scheduled run, environment
   smoke-testing, support-led reprocessing — is a case where an operator needs the run to happen
   **now** and would treat "nothing happened" as a failure of the tool.

### Decision

**The manual trigger runs concurrently and unlocked. It neither acquires nor checks
`intradayDiscoveryScheduler` or `nightlyDiscoveryScheduler`.** `DiscoveryTriggerService` calls
`DiscoveryService.runIntradayDiscovery()` / `runNightlyDiscovery()` directly, exactly as any other
caller of that bean would — option (c).

The possibility that a manual trigger overlapping a live cron run dispatches the same ingestion work
twice is **explicitly accepted as a known risk and is not mitigated by this change**. No ShedLock
semantics of any kind are added to the manual path, and no existing lock name, duration or annotation
placement is altered.

### Alternatives considered

- **(a) Reject with `409 Conflict` while the lock is held.** Rejected. With `lockAtMostFor PT2H` on
  nightly, a support engineer attempting recovery could be locked out for hours — the endpoint would be
  unavailable precisely when it is most needed. It also requires reading ShedLock's table or provider
  state from a request thread, which ShedLock does not expose as a first-class query, so it would mean
  new lock-inspection code and a new `409` in the API contract.
- **(b) Queue and serialise behind the lock.** Rejected. It converts a fire-and-forget accept into an
  unbounded wait of up to PT2H, needs new queue-management and expiry code, and is incompatible with
  ADR-002's `202` contract. Substantial new machinery for a capability whose driver is still unstated.
- **(d) Share the existing lock, so the manual run is skipped when the cron holds it.** Rejected, and
  the worst option for this use case: the endpoint would return success while doing **nothing**,
  silently. For a recovery tool that is actively dangerous — an operator would believe reprocessing had
  been triggered when it had not. It would also require moving `@SchedulerLock` off the scheduler
  methods onto a shared wrapper, changing the cron path and breaching FR-005 / FR-010's zero-blast-radius
  intent and AC-012.
- **(c) Run concurrently, unlocked — chosen.** The only option that adds no new machinery, changes
  nothing on the cron path, and always does what the caller asked.

### Consequences

**Positive**

- Zero blast radius on the scheduled path: no scheduler class, lock name, lock duration, cron
  expression or `@SchedulerLock` annotation changes. FR-005, FR-010, AC-011, AC-012 and AC-023 are
  satisfied by construction, verifiable from the PR's file list.
- No new lock-inspection, queueing or expiry code, and no `409` in the API contract.
- The endpoint's behaviour is unconditional and predictable: a valid, authorised request always
  dispatches the requested run.

**Negative / accepted**

- **Duplicate ingestion dispatch is possible.** A manual trigger overlapping a cron run of the same
  operation, or simultaneous manual triggers on different pods, can dispatch the same Task Manager work
  more than once. Nightly is the worst case (per-case IDPC availability checks across a 3-day window).
- **`NFR-004` / `AC-028` are materially weakened.** The requirement's "under no outcome is the same
  ingestion work dispatched twice" is **withdrawn as a guarantee**. `AC-028` must be reworded at Stage 4
  to describe the accepted behaviour, or the requirements and this ADR will contradict each other in
  the audit trail.
- Idempotency of repeated ingestion work is therefore inherited from whatever the Task Manager and RAG
  layers already provide. This change neither strengthens nor weakens it, and does not audit it.
- Operators must understand that triggering during a live cron window is not protected against. This
  belongs in the runbook (`deploy-notes.md`, Stage 8) and in the contract's operation description.

**Containment (not mitigation)**

The design bounds blast radius without acquiring the lock:

- A dedicated single-worker `ThreadPoolTaskExecutor` (`02-design.md` §8.5) serialises manual runs
  **per pod**, so the manual path adds at most one concurrent run per pod. This is explicitly **not** a
  distributed guarantee and is not a lock.
- The recommended default-off feature toggle (`02-design.md` §8.9, OQ-013) limits exposure to
  environments where OQ-001 justifies it.

**Reversibility — this is not a one-way door.** If duplicate dispatch proves unacceptable in practice,
the mitigation is additive and local: add a ShedLock check (or a shared lock wrapper) inside
`DiscoveryTriggerService`, or introduce a distinct manual lock name. No contract change, no data
migration, no change to the cron path. Revisit if OQ-001 confirms production use, or if OQ-004a
resolves in favour of throttling.

---

## ADR-002: Manual discovery trigger is fire-and-forget, returning 202 Accepted

- **Status:** Accepted
- **Date:** 2026-07-28
- **Jira:** TBC — no ticket raised yet (OQ-011). Must be linked before Stage 4 (Test Specs), per the CLAUDE.md hard rule.
- **Artefacts:** [`01-requirements.md`](./01-requirements.md) (OQ-003, OQ-004, OQ-007, NFR-005, AC-001, AC-029, B-05, B-06, B-12) · [`02-design.md`](./02-design.md) (§3, §6.1, §8.5, §8.6, §8.7, §8.8, §9.2)
- **Decision drivers:** OQ-003 (synchronous vs fire-and-forget), OQ-004 (response granularity), NFR-005 (timeout resilience), FR-005 (reuse the existing operations unchanged)

### Context

The triggered operations are synchronous, `void`, and of unbounded duration
(`DiscoveryService.java:73-87` and `:93-131`, B-05/B-06):

- `runNightlyDiscovery()` calls the Hearing API **once per hearing date** (default 3 dates, 15 s read
  timeout each), then dispatches one Task Manager job per unique case or per (configuration × date).
- `runIntradayDiscovery()` dispatches one job per `scheduled_ingestion_request` row for today.
- Both **swallow per-item dispatch failures** with `log.error(...)` and continue. Neither returns a
  result object, and neither throws to its caller on a per-item failure.
- The only per-run signal today is the `log.info("… starting")` / `log.info("… finished")` pair in the
  surrounding scheduler.

A synchronous response would therefore hold the HTTP connection open for an unbounded period, against
a gateway timeout whose value is still unknown (**OQ-007**). CSDK already contains both precedents:
`/ingestions/start` is fire-and-forget `202` (`IngestionController.java:62`), while
`/ingestions/start-by-case` is synchronous `200` (`:74`) — but the latter is a single-case, bounded
operation driven by a UI button, which is not comparable to a nightly window sweep.

Separately (**OQ-004**), reporting per-item dispatch outcomes in the response would require changing the
`void` signatures of both `DiscoveryService` methods to return a result object. Those methods are
invoked by the cron schedulers too, so that change would land on the scheduled path and on every
existing `DiscoveryServiceTest` — directly against FR-005 ("reuse as-is") and AC-012 ("existing tests
pass without modification").

### Decision

**The endpoint is fire-and-forget and returns `202 Accepted`.** The response is returned as soon as the
request has been authorised, validated, and the run dispatched onto a separate thread. It does **not**
block on `runIntradayDiscovery()` / `runNightlyDiscovery()` completing.

**Response granularity is accept-and-log only.** The `202` body confirms which operation was dispatched
and carries the `correlationId` under which the run logs its progress; it reports **no** per-item
outcome, no counts, and no run status. Consequently
`DiscoveryService.runIntradayDiscovery()` and `runNightlyDiscovery()` **keep their existing `public void`
signatures unchanged**.

Progress and outcome are observable only via structured JSON logs, correlated by `correlationId`, with
one `INFO` record at the start of the run and one at its completion, plus an `ERROR` record if the run
fails outright.

### Alternatives considered

- **Synchronous `200 OK`, blocking until the run completes.** Rejected. Nightly is unbounded — 3+
  Hearing API calls at a 15 s read timeout followed by N Task Manager dispatches — so the response time
  is bounded only by the data volume. With OQ-007 unanswered, there is no evidence any gateway timeout
  accommodates it, and NFR-005 explicitly requires that the response not depend on completion of an
  unbounded operation. It would also make the endpoint's latency grow silently with court-centre
  configuration count.
- **Synchronous `200 OK` with per-item counts (items found / dispatched / failed).** Rejected on two
  independent grounds: it inherits the unbounded-duration problem above, and it requires changing both
  `DiscoveryService` methods' signatures, which touches the cron path and breaches FR-005 / AC-012. The
  extra `blast radius` buys reporting that the logs already provide.
- **`202` plus a run-status / run-history query endpoint** so callers can poll for the outcome.
  Rejected as out of scope — the requirements explicitly exclude a run-status, run-history or progress
  endpoint, and it would require new persistence (OQ-010, a `V1012` migration, a retention position
  under NFR-009). The correct front door for that capability is a fresh Stage 1, not this change.
- **`204 No Content`.** Rejected. `202 Accepted` is the semantically correct code for "accepted for
  processing, not yet complete", it matches the existing `/ingestions/start` precedent, and it allows a
  body carrying the `correlationId`, which `204` does not.

### Consequences

**Positive**

- The HTTP response time is independent of run duration, so NFR-005 is satisfied regardless of what
  OQ-007's gateway timeout turns out to be. AC-029 is satisfied structurally rather than by tuning.
- `DiscoveryService` is left byte-for-byte unchanged, so FR-005, AC-011 and AC-012 hold and the cron
  path carries zero risk from this change.
- Matches the existing `/ingestions/start` precedent, so the API surface stays internally consistent.
- Per-item continue-on-error behaviour (NFR-006, AC-030) is inherited unchanged from `DiscoveryService`,
  with no new code.

**Negative / accepted**

- **The caller learns nothing about the outcome.** A `202` means "dispatched", not "succeeded". A run
  that fails immediately after the response is indistinguishable, to the caller, from one that succeeds.
  Mitigations: the contract description states this explicitly; the `202` body returns the
  `correlationId`; and the worker logs an explicit `ERROR` on failure.
- **A failure after the `202` cannot be surfaced as an HTTP error.** `GlobalExceptionHandler` (B-12) is
  irrelevant once the response is written, so the worker needs its own `try/catch` that logs and does
  not rethrow (`02-design.md` §8.7). Alerting on that log record — not the HTTP response — is the real
  operational safety net, and belongs to whoever owns CSDK's alerting.
- **A new in-process async mechanism is introduced.** CSDK currently has no `@Async`,
  `ExecutorService`, `TaskExecutor` or `CompletableFuture` in `src/main/java`, so the executor bean,
  its bounded queue, and its shutdown behaviour are net-new operational surface
  (`02-design.md` §8.5). Queue exhaustion currently surfaces as a `500` via the catch-all handler — a
  known rough edge, tied to OQ-004a.
- **MDC does not cross the thread boundary for free, and the failure is silent.**
  `RequestContextFilter` calls `MDC.clear()` in a `finally` block
  (`config/RequestContextFilter.java:40-41`), so with a `202` the filter chain unwinds before the worker
  runs. The MDC map must be captured **at submit time on the request thread**
  (`02-design.md` §8.6). A naive implementation compiles, passes a happy-path test, and quietly emits
  log records with no `correlationId` — failing AC-025 invisibly.
- **Non-blocking behaviour needs an explicit test.** Nothing in the type system prevents a future change
  from making the dispatch synchronous again (a `@Async` self-invocation would do it silently). An
  integration test asserting the `202` returns well before a deliberately delayed Hearing API stub
  could have responded is the only real guard (`02-design.md` §12.5).

**Reversibility — effectively a one-way door.** Moving to a synchronous `200` with per-item counts would
be a breaking change to a released API contract **and** would change `DiscoveryService`'s signatures on
the cron path. Get the contract right first time (`02-design.md` §6). Adding a *separate* status-query
endpoint later remains possible and additive, but requires OQ-010 and a fresh Stage 1.
