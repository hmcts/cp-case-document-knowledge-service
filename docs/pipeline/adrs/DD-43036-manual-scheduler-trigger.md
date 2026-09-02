# Architecture Decision Records — Manual Discovery Scheduler Trigger Endpoint

> Service: `cp-case-document-knowledge-service` (CDKS) · Jira: DD-43036 · Taken at the Stage 1 → Stage 2 human gate.
> Requirement: [`../DD-43036-manual-scheduler-trigger/`](../DD-43036-manual-scheduler-trigger/) ·
> Requirements: [`01-requirements.md`](../DD-43036-manual-scheduler-trigger/01-requirements.md) ·
> Design: [`02-design.md`](../DD-43036-manual-scheduler-trigger/02-design.md)

---

## ADR-001: Manual discovery trigger does not coordinate with the scheduler's distributed lock

- **Status:** Accepted · **Date:** 2026-07-28 · **Jira:** DD-43036
- **Artefacts:** `01-requirements.md` (NFR-004, FR-013) · `02-design.md` (§6)

### Context

`@SchedulerLock` sits on the **scheduler** classes (`intradayDiscoveryScheduler` PT8M/PT9M, `nightlyDiscoveryScheduler` PT1H/PT2H), not on `DiscoveryService`, which has no locking of its own. Any caller invoking `DiscoveryService` directly — including a new manual-trigger endpoint — bypasses the lock entirely, so a policy decision was unavoidable. The nightly lock can be held for up to 2 hours, and every plausible operational driver (missed-run recovery, smoke testing, support reprocessing) needs the run to happen *now*.

### Decision

**Run concurrently, unlocked.** `DiscoveryTriggerService` calls `runIntradayDiscovery()`/`runNightlyDiscovery()` directly, like any other caller. Duplicate dispatch if a manual trigger overlaps a live cron run is **accepted, not mitigated** — no lock semantics added, nothing on the cron path altered.

### Alternatives considered

- **Reject with 409 while locked** — rejected: could block a recovery tool for up to 2h; needs new lock-inspection code and a new contract response.
- **Queue behind the lock** — rejected: unbounded wait up to 2h, new queue/expiry machinery, incompatible with ADR-002's 202 contract.
- **Share the lock (silent skip)** — rejected as worst option: returns success while doing nothing, and requires moving `@SchedulerLock` off the schedulers.

### Consequences

- **Positive:** zero blast radius on the cron path (FR-005/FR-010 hold by construction); no new lock/queue/409 machinery; behaviour is unconditional and predictable.
- **Accepted risk:** duplicate ingestion dispatch is possible if a manual trigger overlaps a cron run, or two manual triggers land on different pods simultaneously (nightly is worst case). NFR-004/AC-021 are written to describe this, not to promise its absence. Idempotency is inherited from the Task Manager/RAG layers, unchanged either way. Must be stated in the runbook and contract description.
- **Containment (not mitigation):** a single-worker executor (`02-design.md` §6) caps the manual path at one concurrent run per pod — per-JVM only, not a distributed guarantee.
- **Reversible:** adding a ShedLock check (or distinct lock name) inside `DiscoveryTriggerService` later is additive — no contract change, no migration.

---

## ADR-002: Manual discovery trigger is fire-and-forget, returning 202 Accepted

- **Status:** Accepted · **Date:** 2026-07-28 · **Jira:** DD-43036
- **Artefacts:** `01-requirements.md` (FR-012, NFR-005) · `02-design.md` (§1, §6–§9)

### Context

`runIntradayDiscovery()`/`runNightlyDiscovery()` are synchronous, `void`, unbounded (nightly calls the Hearing API once per hearing date at a 15s read timeout, then dispatches N Task Manager jobs), and already swallow per-item failures internally. A synchronous response would hold the connection open against an unconfirmed gateway timeout. CDKS has both precedents already: `/ingestions/start` (202, fire-and-forget) and `/ingestions/start-by-case` (200, synchronous but single-case and bounded — not comparable here). Reporting per-item outcomes would also force `DiscoveryService`'s `void` signatures to change, touching the cron path.

### Decision

**Fire-and-forget, `202 Accepted`**, returned once the request is authorised, validated, and dispatched onto a separate thread — never blocking on completion. **Accept-and-log only**: the 202 body confirms the operation and returns a `correlationId`; no per-item counts or run status. `DiscoveryService` signatures stay unchanged. Progress/outcome are observable only via structured logs (`INFO` start/finish, `ERROR` on failure).

### Alternatives considered

- **Synchronous 200, blocking** — rejected: unbounded duration risks the gateway timeout; latency would grow silently with configuration count.
- **Synchronous 200 with per-item counts** — rejected: same timeout problem, plus forces a `DiscoveryService` signature change onto the cron path (breaches FR-005/AC-007).
- **202 + a run-status/history endpoint** — rejected as out of scope: needs new persistence; belongs to a fresh Stage 1 if ever needed.
- **204 No Content** — rejected: 202 is the correct semantics and allows a body carrying `correlationId`.

### Consequences

- **Positive:** response time independent of run duration (NFR-005 satisfied structurally); `DiscoveryService` untouched (FR-005/AC-006/AC-007 hold); consistent with the `/ingestions/start` precedent; continue-on-error behaviour inherited free.
- **Accepted:** the caller learns nothing beyond "dispatched" — a post-202 failure is only visible in logs (mitigated by the `correlationId` in the response + an explicit `ERROR` log). A new in-process async mechanism is introduced (CDKS had none); queue exhaustion surfaces as a bare 500 — a known, deliberately unaddressed rough edge.
- **Technical risk, mitigated in design:** `RequestContextFilter` clears MDC in `finally`, so a naive implementation would silently drop `correlationId` on every triggered run — closed by capturing MDC at submit time (`02-design.md` §7). Non-blocking behaviour needs an explicit IT (delayed-stub assertion), since nothing in the type system prevents a future change from making it synchronous again.
- **Reversibility — effectively one-way:** moving to synchronous/per-item-counts later would break the released contract and `DiscoveryService`'s signatures. Get the contract right first time (`02-design.md` §1).
