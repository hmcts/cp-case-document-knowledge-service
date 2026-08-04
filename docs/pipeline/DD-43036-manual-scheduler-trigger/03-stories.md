# User Stories: Manual Discovery Scheduler Trigger Endpoint

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43036** — each story below gets its own sub-ticket (placeholder `DD-#####` until raised).
> Acceptance Criteria text below is embedded verbatim from [`01-requirements.md`](./01-requirements.md)
> so each story is ready to paste into its Jira ticket; ADR-001/ADR-002
> ([`adrs/DD-43036-manual-scheduler-trigger.md`](../adrs/DD-43036-manual-scheduler-trigger.md)) are locked decisions, not reopened here.

**Standard DoD (applies to every story unless noted)**: code reviewed & approved · all ACs
covered by automated tests (unit + integration) · no critical/high Snyk findings introduced ·
deployed to and verified on sandbox · Jira ticket updated with test evidence. Deltas only, below.

---

## Story 1 — Release trigger operation in the API contract
**Jira: DD-#####**
As a **CDKS developer**, I want **`POST /discovery-scheduler/trigger` and its schemas — including
the dedicated vendor media type — added to and released from
`api-cp-crime-caseadmin-case-document-knowledge`**, so that **the endpoint can be implemented
against a generated interface, not hand-scaffolded**.

**Acceptance Criteria**
- AC-016: `version.cdk` references a released contract version with the new operation; controller implements the generated interface, no hand-scaffolded mapping.

- NFR: none — schema-only change, no runtime behaviour in this repo
- Dependency: blocks Stories 2–3; vendor media type drives the Drools action name (design §1)
- DoD delta: contract PR reviewed & released; no breaking change to existing `Discovery Scheduler` tag

## Story 2 — System-Users-only ACL rule for the trigger endpoint
**Jira: DD-#####**
As a **security engineer**, I want **a new Drools rule restricting the trigger action to "System
Users" only, no "AI search" fallback**, so that **only trusted operational callers can force an
on-demand run**.

**Acceptance Criteria**
- AC-008: `"System Users"` member → authorised.
- AC-009: `"AI search"`-only caller → denied (403), nothing dispatched.
- AC-010: No `CJSCPPUID` → denied (401), nothing dispatched.
- AC-011: New Drools rule has exactly one group-membership condition — no `hasPermission`, no `or` — matching `discovery-scheduler-configuration`.

- NFR: NFR-001 (AuthZ)
- Dependency: needs Story 1's released vendor media type/action name; route through `rbac-auditor`
  (CLAUDE.md — any `acl/` change)
- DoD delta: `rbac-auditor` review completed for `acl/cdks-rules.drl`

## Story 3 — On-demand discovery trigger endpoint (fire-and-forget dispatch)
**Jira: DD-#####**
As a **support/operations user (System User)**, I want **to POST a named operation
(`INTRADAY`/`NIGHTLY`) and get an immediate `202 Accepted`**, so that **I can force a missed or
extra discovery run without waiting for its cron, or blocking on completion**.

Reuses `DiscoveryService` unchanged via new `DiscoveryTriggerService` + dedicated bounded executor
(design §5–6, §9–10); no scheduler-lock coordination (ADR-001), fire-and-forget 202 (ADR-002).

**Acceptance Criteria**
- AC-001: Valid System User request → `202 Accepted`, operation dispatched.
- AC-002: Wrong HTTP method → `405`, nothing dispatched.
- AC-004 / AC-005: `INTRADAY`/`NIGHTLY` runs only its own operation, not the other's.
- AC-006: `runIntradayDiscovery()`/`runNightlyDiscovery()` stay the single implementation, used by both cron and this endpoint.
- AC-007: Existing intraday/nightly test suites pass unmodified.
- AC-012–014: Missing field / unrecognised value / malformed JSON → `400` (`ErrorResponse`; malformed JSON message is `"Malformed request body"`), nothing dispatched.
- AC-015: None of the above error bodies contain a case identifier, court reference, document content, or `CJSCPPUID`.
- AC-017: `202` returns well before a `NIGHTLY` run could complete.
- AC-021: Manual trigger overlapping a locked cron run behaves per ADR-001's accepted-risk position (documented, not prevented).
- AC-022: A per-item dispatch failure doesn't stop remaining dispatches; failure is logged.
- AC-023: An audit event exists identifying the action and its time.
- AC-024: `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green at existing thresholds.
- AC-025: Diff introduces no connection string, SAS token, account key, or other static credential.

- Out of scope: run-status/history, cancel/pause/resume, parameterising the run, a third operation,
  rate limiting, 409/429 on queue-full/lock-overlap (design §6, ADR-001)
- NFR: NFR-004, NFR-005, NFR-006, NFR-008, NFR-009, NFR-012
- Dependency: needs Story 1 released/consumed (`version.cdk` bump lands here); compiles independent
  of Story 2 but fail-closed without it — ship together
- DoD delta: `DiscoveryServiceTest`/existing scheduler tests pass unmodified (AC-007); `gradle
  clean build` incl. `integration` green, PMD/JaCoCo at existing thresholds (AC-024)

## Story 4 — Structured logging and correlation for manual discovery runs
**Jira: DD-#####**
As an **on-call engineer**, I want **each manual trigger to emit one correlated start/completion
log pair, explicitly distinguishable from a cron run**, so that **I can trace and audit manual
interventions without confusing them with scheduled ones**.

`RequestContextFilter` clears MDC before the worker runs under a 202 — needs
`MdcCopyingTaskDecorator` to capture MDC at submit time (design §7–8).

**Acceptance Criteria**
- AC-018: Exactly one start + one completion log record per run, both carrying the request's correlation id.
- AC-019: Manual run distinguishable from a cron run by an explicit field, not timestamp inference.
- AC-020: No log record from a manual run contains `CJSCPPUID`, case identifier, court reference, document content, or answer text.

- NFR: NFR-002 (Logging), NFR-007 (Observability)
- Dependency: implemented alongside Story 3's classes — split out for FR-011 traceability only,
  not independently deployable
- DoD delta: covers `MdcCopyingTaskDecoratorTest` and the non-blocking delayed-stub assertion
  (design Testing item 5); manual + automated check confirms no PII/case data in any log record

