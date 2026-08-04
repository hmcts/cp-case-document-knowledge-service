# Requirements: Manual Discovery Scheduler Trigger Endpoint

> **Stage 1 — Requirements** · Service: `cp-case-document-knowledge-service` (CSDK)
> **Jira: DD-43036**
> Gate decisions (concurrency, response contract, granularity, path) are folded in below — see `02-design.md` and [`adrs/DD-43036-manual-scheduler-trigger.md`](../adrs/DD-43036-manual-scheduler-trigger.md) (ADR-001/002).

---

## Context

CSDK runs two ShedLock-guarded scheduled discovery jobs — intraday (every 10 min, Mon–Fri 07:00–19:50) and nightly (daily 02:00) — via `DiscoveryService.runIntradayDiscovery()` / `runNightlyDiscovery()`. Today the only way to run either is to wait for its cron. This adds a System-User-only endpoint, `POST /discovery-scheduler/trigger`, to run one named operation (`INTRADAY`/`NIGHTLY`) on demand, reusing both operations unchanged.

Operational driver (missed-run recovery, smoke-testing, support reprocessing) is unstated and should be confirmed before go-live/production rollout.

---

## Functional Requirements

| ID | Requirement |
|----|-------------|
| FR-001 | `POST /discovery-scheduler/trigger`, alongside `/discovery-scheduler/configurations`. |
| FR-002 | Body carries one enum discriminator `discoveryOperation`: `INTRADAY` \| `NIGHTLY`, contract-defined. |
| FR-003 / FR-004 | `INTRADAY` → `runIntradayDiscovery()`; `NIGHTLY` → `runNightlyDiscovery()`. |
| FR-005 | Reuse existing operations as-is — no duplicated/forked/altered logic; `DiscoveryService` signatures unchanged. |
| FR-006 / FR-007 | `"System Users"` group only (new Drools rule, no `"AI search"` fallback); anyone else denied, nothing dispatched. |
| FR-008 | Missing/invalid discriminator → 400 via existing `GlobalExceptionHandler`, nothing dispatched. |
| FR-009 | Contract-first: operation added & released in `api-cp-crime-caseadmin-case-document-knowledge`, `version.cdk` bumped, before implementation. |
| FR-010 | Purely additive — cron expressions, lock names/durations, `scheduler.*.enabled` all unchanged. |
| FR-011 | Structured JSON start/completion log per trigger, correlated, no case data/`CJSCPPUID`. |
| FR-012 | `202 Accepted`, fire-and-forget — no blocking, no per-item outcome (accept-and-log, like today's schedulers). |
| FR-013 | No coordination with the `intraday`/`nightlyDiscoveryScheduler` ShedLock — overlap with a live cron run is an accepted risk (ADR-001). |

**Out of scope:** parameterising the run, a third operation, cancel/pause/resume, run-status/history, new persistence, rate limiting, any RAG/ingestion-internals change.

---

## Non-Functional Requirements

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-001 | AuthZ | `"System Users"` only, no permission fallback. |
| NFR-002 | Logging | No PII/case content/`CJSCPPUID` in logs, errors, or artefacts. |
| NFR-003 | Cloud identity | Downstream calls stay on Managed Identity/APIM; no static credentials introduced. |
| NFR-004 | Concurrency | Accepted limitation (FR-013): possible duplicate dispatch if overlapping a live cron run — stated in contract + runbook, not hidden. |
| NFR-005 | Performance | Response independent of the (unbounded) run's completion (FR-012). |
| NFR-006 | Reliability | Per-item dispatch failure doesn't abort the run (existing `DiscoveryService` behaviour). |
| NFR-007 | Observability | Manual run distinguishable from cron run in logs, correlated by correlation id. |
| NFR-008 | Auditability | Trigger produces an audit trail via existing `cp-audit-filter-springboot` → Artemis. |
| NFR-009 | Data protection | No new personal data, no new persistence. |
| NFR-010 | Testability | Unit + `integrationTest` coverage for authorised/unauthorised/invalid cases; `gradle integration` passes. |
| NFR-011 | Code quality | PMD/JaCoCo unmodified; CodeQL/secrets-scanner clean. |
| NFR-012 | Platform | Java 25 / Spring Boot 4.0.5 / Gradle 9; `@Slf4j @RestController implements <Generated>Api` pattern. |
| NFR-013 | Accessibility | N/A directly (backend-only); applies to any future UI consumer. |

---

## Acceptance Criteria

**Endpoint & discriminator**
- AC-001: Valid System User request → `202 Accepted`, operation dispatched.
- AC-002: Wrong HTTP method → `405`, nothing dispatched.
- AC-003: Request model exposes exactly `INTRADAY`/`NIGHTLY` — no third value, no free text.

**Operation execution**
- AC-004 / AC-005: `INTRADAY`/`NIGHTLY` runs only its own operation, not the other's.
- AC-006: `runIntradayDiscovery()`/`runNightlyDiscovery()` stay the single implementation, used by both cron and this endpoint.
- AC-007: Existing intraday/nightly test suites pass unmodified.

**Access control**
- AC-008: `"System Users"` member → authorised.
- AC-009: `"AI search"`-only caller → denied (403), nothing dispatched.
- AC-010: No `CJSCPPUID` → denied (401), nothing dispatched.
- AC-011: New Drools rule has exactly one group-membership condition — no `hasPermission`, no `or` — matching `discovery-scheduler-configuration`.

**Invalid input**
- AC-012–014: Missing field / unrecognised value / malformed JSON → `400` (`ErrorResponse`; malformed JSON message is `"Malformed request body"`), nothing dispatched.
- AC-015: None of the above error bodies contain a case identifier, court reference, document content, or `CJSCPPUID`.

**Contract-first delivery**
- AC-016: `version.cdk` references a released contract version with the new operation; controller implements the generated interface, no hand-scaffolded mapping.

**Fire-and-forget & logging**
- AC-017: `202` returns well before a `NIGHTLY` run could complete.
- AC-018: Exactly one start + one completion log record per run, both carrying the request's correlation id.
- AC-019: Manual run distinguishable from a cron run by an explicit field, not timestamp inference.
- AC-020: No log record from a manual run contains `CJSCPPUID`, case identifier, court reference, document content, or answer text.

**Non-functional**
- AC-021: Manual trigger overlapping a locked cron run behaves per ADR-001's accepted-risk position (documented, not prevented).
- AC-022: A per-item dispatch failure doesn't stop remaining dispatches; failure is logged.
- AC-023: An audit event exists identifying the action and its time.
- AC-024: `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green at existing thresholds.
- AC-025: Diff introduces no connection string, SAS token, account key, or other static credential.
