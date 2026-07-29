# User Stories: Manual Discovery Scheduler Trigger Endpoint

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CSDK)
> **Jira: TBC** — no ticket exists yet. Must be raised and linked to every story below before
> Stage 4 (Test Specs) begins — CLAUDE.md hard rule. **Do not proceed to test-engineer until
> the stories below are confirmed.**
> AC-IDs reference [`01-requirements.md`](./01-requirements.md); ADR-001/ADR-002
> ([`adrs.md`](./adrs.md)) are locked decisions, not reopened here.

**Standard DoD (applies to every story unless noted)**: code reviewed & approved · all ACs
covered by automated tests (unit + integration) · no critical/high Snyk findings introduced ·
deployed to and verified on sandbox · Jira ticket updated with test evidence. Deltas only, below.

---

## Story 1 — Release trigger operation in the API contract
**Jira: TBC**
As a **CSDK developer**, I want **`POST /discovery-scheduler/trigger` and its schemas — including
the dedicated vendor media type — added to and released from
`api-cp-crime-caseadmin-case-document-knowledge`**, so that **the endpoint can be implemented
against a generated interface, not hand-scaffolded**.

- AC: AC-016
- NFR: none — schema-only change, no runtime behaviour in this repo
- Dependency: blocks Stories 2–3; vendor media type drives the Drools action name (design §1)
- DoD delta: contract PR reviewed & released; no breaking change to existing `Discovery Scheduler` tag

## Story 2 — System-Users-only ACL rule for the trigger endpoint
**Jira: TBC**
As a **security engineer**, I want **a new Drools rule restricting the trigger action to "System
Users" only, no "AI search" fallback**, so that **only trusted operational callers can force an
on-demand run**.

- AC: AC-008, AC-009, AC-010, AC-011
- NFR: NFR-001 (AuthZ)
- Dependency: needs Story 1's released vendor media type/action name; route through `rbac-auditor`
  (CLAUDE.md — any `acl/` change)
- DoD delta: `rbac-auditor` review completed for `acl/cdks-rules.drl`

## Story 3 — On-demand discovery trigger endpoint (fire-and-forget dispatch)
**Jira: TBC**
As a **support/operations user (System User)**, I want **to POST a named operation
(`INTRADAY`/`NIGHTLY`) and get an immediate `202 Accepted`**, so that **I can force a missed or
extra discovery run without waiting for its cron, or blocking on completion**.

Reuses `DiscoveryService` unchanged via new `DiscoveryTriggerService` + dedicated bounded executor
(design §5–6, §9–10); no scheduler-lock coordination (ADR-001), fire-and-forget 202 (ADR-002).

- AC: AC-001, AC-002, AC-004–AC-007, AC-012–AC-015, AC-017, AC-021–AC-025
- Out of scope: run-status/history, cancel/pause/resume, parameterising the run, a third operation,
  rate limiting, 409/429 on queue-full/lock-overlap (design §6, ADR-001)
- NFR: NFR-004, NFR-005, NFR-006, NFR-008, NFR-009, NFR-012
- Dependency: needs Story 1 released/consumed (`version.cdk` bump lands here); compiles independent
  of Story 2 but fail-closed without it — ship together
- DoD delta: `DiscoveryServiceTest`/existing scheduler tests pass unmodified (AC-007); `gradle
  clean build` incl. `integration` green, PMD/JaCoCo at existing thresholds (AC-024)

## Story 4 — Structured logging and correlation for manual discovery runs
**Jira: TBC**
As an **on-call engineer**, I want **each manual trigger to emit one correlated start/completion
log pair, explicitly distinguishable from a cron run**, so that **I can trace and audit manual
interventions without confusing them with scheduled ones**.

`RequestContextFilter` clears MDC before the worker runs under a 202 — needs
`MdcCopyingTaskDecorator` to capture MDC at submit time (design §7–8).

- AC: AC-018, AC-019, AC-020
- NFR: NFR-002 (Logging), NFR-007 (Observability)
- Dependency: implemented alongside Story 3's classes — split out for FR-011 traceability only,
  not independently deployable
- DoD delta: covers `MdcCopyingTaskDecoratorTest` and the non-blocking delayed-stub assertion
  (design Testing item 5); manual + automated check confirms no PII/case data in any log record

---

## Open item
No Jira ticket exists yet (TBC throughout) — must be raised and linked to all four stories above
before Stage 4 begins.
