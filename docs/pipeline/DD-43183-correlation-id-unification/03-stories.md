# User Stories: Unified Correlation-ID Handling and Trace Propagation

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43183.** Real sub-tickets `DD-43260`–`DD-43266` were created and linked to the
> parent epic on 2026-09-04, satisfying CLAUDE.md's hard rule that every story needs a linked
> ticket before the test stage.
>
> Acceptance criteria below are **derived from, not duplicated verbatim from**, `01-requirements.md`'s
> AC-001–AC-038, rescoped to each story's slice and rewritten to reflect the six accepted Stage-2
> gate decisions (`02-design.md` §15 / `adrs/DD-43183-correlation-id-unification.md`, all `Accepted`
> at the Stage-2 gate on 2026-09-03). Full ADR text and rationale live in
> [`../adrs/DD-43183-correlation-id-unification.md`](../adrs/DD-43183-correlation-id-unification.md)
> and are not reopened here — no story below raises a new ADR.
>
> **Seven stories**, following `01-requirements.md`'s candidate breakdown, reconciled against the
> accepted design. The reconciliation moved one thing from the requirements-stage preview: the
> `CorrelationIdInterceptor` rewrite is **not** split across Story 1 (inbound) — it sits entirely in
> **Story 2 (outbound propagation)**, because the accepted design (ADR-007) makes the interceptor
> fully MDC-**read-only**. That single change fixes both defects at once — the "doesn't propagate
> outbound" defect *and* the `MDC.remove()` destruction defect — so they can no longer be split
> across two stories without one of them being untestable in isolation.
>
> **A live security finding (GATE-6) has already been fixed and merged, ahead of this story —
> see Story 2's Background.** `DebugLoggingInterceptor` logged the full outbound header map at
> DEBUG, including the APIM bearer token and subscription key injected by `ApimAuthHeaderService`.
> This was pre-existing and technically outside DD-43183's stated scope; per this pipeline's own
> recommendation to expedite it, it shipped independently as **PR #225** on 2026-09-03 (deny-list
> redaction of the two credential header names, not the allow-list originally sketched — reasoned
> in Story 2). Story 2's AC-007 is retained as a regression check only.
>
> **Cross-ticket coordination with DD-43182** (in flight in parallel, also at Stage 3): DD-43182 adds
> an optional (`cdk.metrics.enabled`-gated) `@Aspect` (`TaskRetryMetricsAspect`, retry-count metrics)
> on the **exact same join point** (`ExecutableTask.execute`) that this ticket's Story 3
> (`JobCorrelationAspect`) uses. Both tickets' Stage-2 designs accept the same resolution —
> `JobCorrelationAspect` ordered outermost of `TaskRetryMetricsAspect` — see Story 3 below for the
> full detail and the required ordering test.

> **Stage-4 gate decisions applied to this document (2026-09-04).** Seven open questions raised at
> Stage 4 were decided by the requester; four of them change wording here:
> **OQ-102** — the integration tier will **not** read the app's log output, so any AC whose oracle
> was "read a JSON log line from the container" is split into automated unit-tier event-shape
> coverage plus **MV-1**, a named manual-verification step (`02-design.md` §13) that gates
> **release** of the ticket. Affects Story 2 AC-004, Story 4 AC-002, Story 5 AC-004/AC-005 and
> Story 7 AC-002. **OQ-107** — Story 5's (Area E's) scope is **confirmed** as the design's list, no
> wider. **OQ-108** — Story 6 AC-004 no longer asks the metadata audit to catch DD-43182's `cdk.*`
> binding gap; it structurally cannot. **OQ-109** — Story 1 AC-003 now states the **first-non-blank-wins**
> precedence rule explicitly. **OQ-110** — Story 3 AC-005 pins **two representative multi-hop chains**,
> not nine dispatch sites. **OQ-105** — the stale OTLP paths in `docker/docker-compose.integration.yml`
> are an explicit Story 6 to-do (new AC-008), deliberately **not** edited at the documentation stages.
> **OQ-103** — the ERROR-dispatch behaviour stays an unasserted probe; no story gains work for it.

**Standard DoD (every story, per `hmcts-standards.md` and this repo's CLAUDE.md hard rules)**: code
reviewed & approved · all ACs covered by automated tests (unit + integration, Given/When/Then) ·
`gradle clean build` (incl. `integration`) passes · PMD/JaCoCo green at existing thresholds ·
CodeQL and secrets-scanner clean · no PII/case content/court reference/`CJSCPPUID` in code, config,
tests or fixtures · deployed to and verified on sandbox · Jira ticket updated with test evidence ·
`claude-generated` + `needs-review` labels applied, linked to parent epic DD-43183 · story has its
own linked Jira sub-ticket (`DD-43260`–`DD-43266`, below).

---

## Story 1 — One documented inbound correlation convention; `TracingFilter` deleted
**Jira: `DD-43260`**
**No dependency on other stories in this set. Must land first — Stories 2–7 all build on the
`correlation/CorrelationIds` and `correlation/CorrelationScope` classes this story creates.**

As a **production support engineer**,
I want **a single documented inbound correlation header, with one deprecated-but-honoured alias,
resolved once per request into one MDC key and echoed on every response**,
so that **I have exactly one value to search for across an entire request, instead of guessing which
of several competing headers or MDC keys was actually used**.

### Background
Six correlation mechanisms exist today in five identifier namespaces (`01-requirements.md`'s Context
table); none of them agree, and one of them — `filters/tracing/TracingFilter` — actively corrupts the
two MDC keys (`traceId`, `spanId`) that Micrometer Tracing's `Slf4JEventListener` already populates
correctly on every span-scope transition (`02-design.md` §2.4, ADR-001). This story makes
`CPPCLIENTCORRELATIONID` — already the CPP platform convention consumed by
`cp-audit-filter-springboot` — the canonical inbound header, keeps `X-Correlation-Id` as the one
accepted alias, deletes `TracingFilter` and its package outright, and rewrites
`config/RequestContextFilter` to restore rather than destroy the prior MDC map. It also creates the
two new shared classes every other story in this set depends on: `correlation/CorrelationIds`
(header/MDC-key constants, inbound precedence, allow-list validation, generation) and
`correlation/CorrelationScope` (the `AutoCloseable` that seeds MDC and restores the prior map on
close).

### Acceptance criteria
- [ ] AC-001: Given a request carries only the canonical header `CPPCLIENTCORRELATIONID`, when it is handled, then that value is the resolved correlation ID.
- [ ] AC-002: Given a request carries only the alias `X-Correlation-Id`, when it is handled, then the alias value is honoured as the resolved correlation ID.
- [ ] AC-003 (**precedence rule pinned at the Stage-4 gate, 2026-09-04 — OQ-109**): resolution is **first non-blank wins**, in the order `CPPCLIENTCORRELATIONID` then `X-Correlation-Id` (ADR-001(2)'s table). Concretely: (a) given both headers present with different non-blank values, the **canonical** value is resolved and the alias value appears nowhere; (b) given the canonical header present but **blank, whitespace-only, or rejected by AC-010's validation** and the alias carrying a usable value, the **alias value is resolved** — a blank canonical header must **not** shadow a usable alias into a freshly generated ID; (c) only when neither header yields a usable value is one generated (AC-004). A test pins the declared precedence **order**, not just one outcome, so reordering the list fails the test.
- [ ] AC-004: Given a request carries neither header, or one that is blank, when it is handled, then a non-blank correlation ID is generated.
- [ ] AC-005: The resolved value is present in MDC under exactly one documented key, `correlationId`, for the duration of request handling; a `MdcReservedKeyTest` asserts no `src/main` source file contains an `MDC.put` of `traceId` or `spanId` (ADR-002 — those keys are reserved to Micrometer Tracing).
- [ ] AC-006: The resolved value is set as the `X-Correlation-Id` response header on every response — 2xx and 4xx/5xx alike — set **before** the filter chain runs, so it cannot be missed by an already-committed response.
- [ ] AC-007: Given `X-Correlation-Id: <value>` is sent to `/discovery-scheduler/trigger`, when the request is handled, then `DiscoverySchedulerTriggerHttpLiveTest` passes with its existing `"correlationId":"<sent value>"` assertion **completely unmodified**.
- [ ] AC-008 (GATE-2, accepted — response-contract withdrawal): the `traceId` and `spanId` response headers `TracingFilter` used to echo are **withdrawn**; `filters/tracing/TracingFilter.java` and `TracingFilterTest.java` are deleted with the package; `TracingIntegrationTest` and its test-only `TestTracingConfig` double (which asserts a `UUID.randomUUID()` fallback production never had) are rewritten or deleted, not carried forward.
- [ ] AC-009: `RequestContextFilter`'s `finally` block restores the MDC map captured at filter entry rather than calling `MDC.clear()`, so a log line emitted by a later filter or handler is never destructively wiped; `RequestContextFilterTest.clearsMdcEvenIfChainThrowsException` continues to pass (only its mock type changes, from `ServletResponse` to `HttpServletResponse`, because the response header is now set here).
- [ ] AC-010: An inbound header value that fails the allow-list (`[A-Za-z0-9._:-]{1,64}`) is **rejected and a fresh value generated**, never sanitised-in-place and never used to fail the request; exactly one WARN is logged carrying the header name, the value's length and a reason code (`illegal-character` / `too-long`) — **never the rejected value itself** (NFR-002).

### NFR links
- NFR-001 (Data protection): the resolved correlation ID is an opaque identifier; a client-supplied alias value is never echoed anywhere it could carry case data.
- NFR-002 (Log-injection safety): allow-list + length bound on every externally sourced value, applied here for the inbound path.
- NFR-003 (Performance): resolution runs once per request in the filter chain; no I/O, no lock.
- NFR-005 (Backward compatibility): `X-Correlation-Id` stays honoured indefinitely; `DiscoverySchedulerTriggerHttpLiveTest`'s assertion is unmodified; `ErrorResponse`/`DiscoveryTriggerResponse` field names and types are unchanged.
- NFR-007 (Configurability) — **flagged deviation, GATE-1 accepted**: header names, the alias list and the MDC key are compile-time constants in `CorrelationIds`, not `@ConfigurationProperties`. Deliberate: a header name an environment can rename is a contract with the audit filter and other CPP services, not a tunable (ADR-001(6)).
- NFR-008 (Documentation): the convention (headers, aliases, precedence, MDC key) is written down in `02-design.md` §12 and must also land in `.claude/context/cdks-context.md` as part of this story's diff.

### Out of scope for this story
- The outbound interceptor rewrite and the GATE-6 security fix — Story 2.
- JobManager MDC restoration — Story 3.
- `ErrorResponse.traceId` — Story 4.
- `caseId`/`docId`/`transactionId` structured fields — Story 5.
- OTLP export configuration — Story 6.
- Cross-request/virtual-thread MDC leak assurance and the whole-ticket regression suite — Story 7.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (`CorrelationIdsTest`, `CorrelationScopeTest`, `RequestContextFilterTest` rewrite, `MdcReservedKeyTest`; integration: alias/precedence/no-header scenarios and the response-header echo against a running compose stack).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **Creates `correlation/CorrelationIds.java` and `correlation/CorrelationScope.java`.** Every other
  story in this set (2–7) depends on these two classes existing — this is shared infrastructure, not
  merely "the first story in sequence."
- GATE-1 (constants, not config) and GATE-2 (withdraw the `traceId`/`spanId` response headers) were
  both accepted as designed at the Stage-2 gate on 2026-09-03 — see ADR-001 and ADR-002.
- `.claude/context/cdks-context.md` should also be corrected for the two drift items design noted
  (OQ-014: 7 JobManager tasks not 8; `task-manager-service` 1.0.11 not 1.0.10) while this story's
  diff is already touching correlation-related documentation — housekeeping, non-blocking, but cheap
  to fold in here.
- Needs its own linked Jira sub-ticket before Stage 4. Placeholder: `DD-43260`.

---

## Story 2 — Outbound propagation, stop destroying MDC, and fix the APIM credential-logging leak
**Jira: `DD-43261`**
**Depends on Story 1** (`correlation/CorrelationIds`, `correlation/CorrelationScope`, the canonical
MDC key).

As a **production support engineer**,
I want **every outbound `RestClient` call to RAG, Progression and Hearing to carry the in-scope
correlation ID verbatim — never a fresh UUID, and never at the cost of destroying the ambient MDC
value for the rest of the request — and no APIM credential to ever appear in the outbound HTTP debug
log next to it**,
so that **a request's log lines still correlate after its first downstream call, downstream CPP
services' audit events join to CDKS's own, and CDKS's outbound HTTP logging cannot leak a bearer
token or subscription key**.

### Background
This is the ticket's highest-value fix and its most actively destructive bug in one place.
`CorrelationIdInterceptor` today puts a fresh `UUID.randomUUID()` into MDC and then **removes** the
key in its `finally` — not restores, removes — so the inbound correlation ID is deleted for the
remainder of the request the moment CDKS makes its first outbound call
(`RestClientFactoryConfig:117` attaches this interceptor to every `RestClient` unconditionally). The
accepted design (ADR-007) does not patch the `finally`; it removes the interceptor's ability to write
MDC at all, making it read-only, which closes both the "doesn't propagate outbound" defect and the
"destroys MDC" defect with one rewrite. Because this story is already rewriting the interceptor chain
on `RestClientFactoryConfig`'s clients, it is also where the Stage-2 gate placed **GATE-6**: the
sibling `DebugLoggingInterceptor` on that same chain logged the entire outbound header map at
DEBUG, including the APIM bearer token and/or subscription key injected by `ApimAuthHeaderService`
(`02-design.md` §6, "a security finding this ticket surfaces").

**GATE-6 has already shipped — 2026-09-03, PR #225, `fix/debug-logging-credential-redaction`,
merged to `develop` independently of this ticket.** Per this story's own Notes recommendation
below, the fix was expedited ahead of the rest of DD-43183's pipeline given the live
credential-exposure risk. The shipped implementation redacts by a **deny-list** of the two known
credential header names (`Authorization`, `Ocp-Apim-Subscription-Key`, case-insensitive), not a
strict allow-list as originally designed — a deliberate, reviewed deviation: an allow-list would
also have hidden this ticket's own new correlation headers and every future header from debug
output by default, which is a bigger loss of debuggability than the risk it defends against for a
closed, two-name credential set. `DebugLoggingInterceptorTest` already exists and passes. AC-007
below is retained as a **regression-only** acceptance criterion for this story — nothing further to
implement, only confirm on merge that PR #225's fix is present in this branch's history.

### Acceptance criteria
- [ ] AC-001: Given an inbound (or otherwise in-scope) correlation ID `abc-123`, when CDKS calls RAG, Progression or Hearing via a `RestClient` built by `RestClientFactoryConfig`, then the outbound request carries `abc-123` in **both** documented outbound headers, `CPPCLIENTCORRELATIONID` and `X-Correlation-Id`.
- [ ] AC-002: `CorrelationIdInterceptor` never substitutes a fresh `UUID.randomUUID()` for an in-scope value — a unit test asserts the in-scope value is transmitted verbatim, and that `X-Request-ID` and the interceptor's own `MDC_KEY` constant no longer exist on the class.
- [ ] AC-003: `CorrelationIdInterceptor` is MDC-**read-only** — it performs no `MDC.put` and no `MDC.remove` at all (no `try`/`finally`); a unit test asserts MDC is byte-for-byte unchanged immediately before, during, and immediately after `intercept(...)` executes. This is the direct test for the historical destruction bug, not merely for non-propagation.
- [ ] AC-004 (**oracle re-scoped at the Stage-4 gate, 2026-09-04 — OQ-102**): Given an inbound request with correlation ID `abc-123` makes an outbound call and then emits a further log line, when that line is emitted, then it still carries `abc-123`. Discharged in three parts, because the integration tier cannot read the app's log output: **(i) automated, unit tier** — AC-003's three-snapshot whole-map assertion proves the *mechanism* (the interceptor cannot destroy the ambient value); **(ii) automated, integration tier** — the observable proxy: after an outbound call has occurred on the request thread, the `X-Correlation-Id` response header still equals `abc-123` and, for `/discovery-scheduler/trigger`, so does the response body's `correlationId` (this is explicitly a *proxy*, recorded as such, not the log assertion); **(iii) manual — MV-1** (`02-design.md` §13): the captured JSON log line confirms the post-outbound line really carries `correlationId=abc-123`. No integration test shells out to `docker logs`.
- [ ] AC-005: Given a request or unit of work makes two or more outbound calls, when they execute, then every one carries the same correlation value — because the value is generated once per unit of work at the entry points built in Stories 1 and 3, not per outbound call inside the interceptor.
- [ ] AC-006: Given a unit of work with no inbound request (a scheduled run, a startup probe, a directly-constructed test client) makes an outbound call, when it executes, then the outbound request still carries a non-blank correlation value via `CorrelationIds.currentOrRandom()`'s defensive last-resort branch.
- [x] AC-007 (**GATE-6, security fix — SHIPPED 2026-09-03 in PR #225, ahead of this story**): `DebugLoggingInterceptor`'s outbound/inbound header logging redacts the APIM `Authorization` header and the APIM subscription-key header (deny-list, case-insensitive — see this story's Background for why an allow-list was not used); `DebugLoggingInterceptorTest` asserts the actual formatted log output never contains the raw credential values while a non-sensitive header remains visible. Retained here as a regression check, not open work.
- [ ] AC-008: All four existing `CorrelationIdInterceptorTest` methods, which currently assert the deleted behaviour (a generated UUID is transmitted; `MDC.get("correlationId")` is `null` after execution), are rewritten to assert the new contract — none of the four is left asserting the bug.

### NFR links
- NFR-001 (Data protection) — **this is where GATE-6 is tracked as an NFR, not only as an AC**: no credential, connection string, SAS token, account key, or subscription key may appear in any log line at any level, which the pre-existing `DebugLoggingInterceptor` behaviour violated.
- NFR-003 (Performance): the outbound interceptor adds no allocation beyond a header set; no MDC lock or copy.
- NFR-004 (Availability): a missing ambient correlation value never fails an outbound call — it degrades to a generated value.
- NFR-006 (Testability): unit coverage for save/restore-turned-read-only semantics; `integrationTest` coverage asserting a sent correlation ID reaches a WireMock-stubbed downstream request header.

### Out of scope for this story
- Inbound resolution and the `X-Correlation-Id` response header — Story 1 (a hard prerequisite).
- JobManager task MDC restoration — Story 3 (this story's "no inbound context" fallback covers scheduled runs and startup probes only, not JobManager tasks, which get their own unit-of-work entry point in Story 3).
- Any change to `ApimAuthHeaderService` itself, or to how the APIM token/subscription key is obtained — only how it is (not) logged.
- Any change to `RestClientFactoryConfig`'s client construction beyond the interceptor chain (e.g. adding an `observationRegistry` for `traceparent` propagation) — explicitly out of scope for the whole ticket (`01-requirements.md`).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (`CorrelationIdInterceptorTest` rewrite covering AC-002/AC-003/AC-008; a `DebugLoggingInterceptorTest` covering AC-007; `CorrelationPropagationHttpLiveTest` covering AC-001, AC-005, AC-006 and AC-004's parts (i)–(ii) against WireMock-stubbed RAG/Progression/Hearing) — **AC-004's part (iii) is MV-1, manual, and is recorded on the ticket rather than automated (OQ-102)**.
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic; no real APIM credential value in any test fixture (only synthetic stand-ins used to prove the allow-list excludes the right header names).
- [ ] Deployed to and verified on sandbox.
- [x] Jira ticket updated with test evidence, **for the GATE-6 fix (AC-007): see PR #225**, merged 2026-09-03, independent of this story's Jira sub-ticket.

### Notes / open questions
- **GATE-6 shipped independently of this story, 2026-09-03 (PR #225).** It was bundled into this
  story's design only because it sits on the same interceptor chain this story touches — not
  because it is otherwise related in scope or risk profile to correlation-ID unification. Acting on
  this story's own recommendation, the fix was expedited ahead of the rest of DD-43183's pipeline
  given the live credential-exposure risk, rather than waiting for Stage 4/5. AC-001–AC-006/AC-008
  are unaffected and still depend on Story 1 as before.
- Depends on Story 1 for `CorrelationIds`/`CorrelationScope`.
- Needs its own linked Jira sub-ticket before Stage 4. Placeholder: `DD-43261`.

---

## Story 3 — JobManager async correlation restoration (`JobCorrelationAspect`)
**Jira: `DD-43262`**
**Depends on Story 1** (`correlation/CorrelationScope`). **Cross-ticket dependency: DD-43182's
`TaskRetryMetricsAspect` story** — see the coordination note below; this dependency is a coordination
requirement, not a build-order blocker in either direction.

As a **production support engineer**,
I want **every JobManager task in `caseflow`, `queryflow` and `hearing` to restore the dispatching
request's correlation ID into MDC for the duration of its execution — and guarantee that context can
never leak onto the next unrelated job on the same pooled worker thread**,
so that **a task's log lines, wherever and whenever they run — a different thread, a different pod,
minutes or hours later — join the same search as the request that started the work**.

### Background
`JobManagerKeys.Params.REQUEST_ID` (`"requestId"`) already exists, is already seeded at all four
dispatch sites, and already survives an entire task chain via `createObjectBuilder(jobData)` — the
plumbing is built; nothing restores it into MDC (ADR-003). The accepted design does not add a second
`jobData` key or repeat a try/finally in all seven tasks: it adds **one** `@Aspect`
(`JobCorrelationAspect`, `@Around ExecutableTask.execute`) that restores `correlationId` (and, as a
side effect, `caseId`/`docId`/`transactionId` where present in `jobData` — Area E's async half, for
free, at zero extra edits) plus an MDC-clearing `TaskDecorator` on `jobExecutorThreadPool` as defence
in depth (ADR-004). `TaskRegistry` is proxy-aware (`AopUtils.getTargetClass`), which is *why* an
aspect is safe here and a hand-written decorator is not — a decorator would silently unregister all
seven tasks.

### Acceptance criteria
- [ ] AC-001: Given an ingestion is started with correlation ID `abc-123`, when a JobManager task in `caseflow`, `queryflow` or `hearing` later executes for that work, then the task restores `abc-123` into MDC from `ExecutionInfo`'s `jobData` (via `JobManagerKeys.Params.REQUEST_ID`) for the duration of `execute(...)`, via `JobCorrelationAspect` — no per-task code change.
- [ ] AC-002: After that task returns — normally or by throwing — the correlation value is no longer present on that worker thread's MDC; the aspect's `close()` restores the prior map exactly (not `MDC.clear()`, not `MDC.remove()`), so it is safe on a thread that legitimately carries other context.
- [ ] AC-003: The `jobData` correlation key is referenced via the `JobManagerKeys.Params.REQUEST_ID` constant at every read and write site; no inline `"requestId"`-style literal remains in `src/main` (fixes `RetrieveMaterialAndUploadTask:79` and `JobManagerService:59`).
- [ ] AC-004: Given a task's `jobData` is missing the correlation key, or it is blank, or it fails the allow-list validation, when the task executes, then it does not throw and the log lines carry a freshly generated correlation value rather than nothing (NFR-004).
- [ ] AC-005 (**test breadth pinned at the Stage-4 gate, 2026-09-04 — OQ-110**): Given a task chains a successor task via `createObjectBuilder(jobData)`, when the successor executes, then it carries the same correlation ID as its predecessor. Already structurally true across all nine successor dispatch sites (ADR-003 verified each), so the test **pins** the property rather than re-implementing it — and it does so with **two representative multi-hop chains, not nine individual per-site assertions**. The chains are `GET_CASES_FOR_HEARING → CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS → RETRIEVE_MATERIAL_AND_UPLOAD` and one answer-generation chain (`GENERATE_ANSWER_FOR_QUERY → CHECK_STATUS_OF_ANSWER_GENERATION`). Rationale: nine per-site assertions would couple the suite tightly to internal dispatch structure to re-prove one already-verified property; a *new* successor dispatch is guarded instead by AC-003's source-walk assertion plus Stage-6 review.
- [ ] AC-006: All four current dispatch sites (`DiscoveryService` ×2, `JobManagerService`, `IngestionProcessorByCaseService`) seed `jobData`'s `requestId` from the ambient in-scope correlation ID (`CorrelationIds.currentOrGenerate()`), not an unrelated fresh `UUID.randomUUID()` as today.
- [ ] AC-007: `JobCorrelationAspect` additionally seeds `caseId`, `docId` and `transactionId` into MDC from `jobData` where present — this is Area E's entire async-side deliverable; Story 5 must not duplicate MDC-seeding logic for JobManager tasks.
- [ ] AC-008 (defence in depth): `jobExecutorThreadPool` is given an MDC-clearing `TaskDecorator` (`JobExecutorMdcBeanPostProcessor`); with the pool's size forced to 1 in a test, a job dispatched immediately after a job that left MDC populated observes **nothing** left over from the prior job — proving the guarantee holds even if a future task, or the library itself, writes MDC outside the aspect's scope.
- [ ] AC-009 (**GATE-3, cross-ticket aspect-ordering — accepted**): `JobCorrelationAspect` is `@Order(Ordered.HIGHEST_PRECEDENCE)` — outermost of DD-43182's `TaskRetryMetricsAspect` on the same join point. A `JobCorrelationProxyingTest` asserts all seven `@Task` beans are AOP-proxied and remain resolvable via `TaskRegistry.getTask(<TaskNames value>)`, **and** asserts `JobCorrelationAspect`'s advice runs outside `TaskRetryMetricsAspect`'s if/when DD-43182 has also landed in the build — the ordering test must pass whether or not DD-43182 has merged yet.

### NFR links
- NFR-001 (Data protection): `caseId`/`docId`/`transactionId` seeded here are opaque UUIDs only; no case content or `CJSCPPUID`.
- NFR-004 (Availability): the aspect never introduces a `catch`, never swallows a thrown exception, and never rewrites the returned `ExecutionInfo` — a thrown `Throwable` and the return value both pass through untouched.
- NFR-006 (Testability): `JobCorrelationAspectTest` (stub `ExecutableTask`), `JobCorrelationProxyingTest` (Spring context, all seven tasks), `JobExecutorMdcLeakTest` (pool forced to size 1).

### Out of scope for this story
- Outbound propagation itself, including the JobManager-triggered outbound calls a task makes once its MDC is restored — Story 2 already covers how the value reaches `RestClient` calls; this story only restores the value into MDC.
- The non-JobManager halves of Area E (`RagAnswerAsyncServiceImpl`, the four named services, the schedulers) — Story 5.
- Enabling `cdk.metrics.enabled` or any change to `TaskRetryMetricsAspect` itself — that is DD-43182's own story; this story only fixes the **ordering** between the two aspects.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (`JobCorrelationAspectTest`, `JobCorrelationProxyingTest`, `JobExecutorMdcLeakTest`; integration: **one** addition to an existing JobManager live test asserting via **WireMock's request journal** that the outbound calls a task makes on a pool thread carry the dispatching request's correlation ID in both outbound headers — the automatable end-to-end proof that the aspect's MDC reached the interceptor off the request thread). **The equivalent log-line assertion is MV-1, manual (OQ-102)** — no live test reads container output.
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence, including the `JobCorrelationProxyingTest` ordering result.

### Notes / open questions
- **Cross-ticket coordination with DD-43182 (accurate as of both tickets' Stage-2 gates, 2026-09-03).**
  DD-43182 places an **optional** (`cdk.metrics.enabled`-gated, default `true`) `@Aspect`
  (`metrics/TaskRetryMetricsAspect`, retry-count metrics) on the **exact same join point**
  (`execution(* uk.gov.hmcts.cp.cdk.jobmanager..*.execute(..ExecutionInfo))`) that this story's
  `JobCorrelationAspect` uses. Both tickets' Stage-2 designs independently reached, and then jointly
  accepted, the same resolution: **`JobCorrelationAspect` ordered outermost of
  `TaskRetryMetricsAspect`** — this ticket's `02-design.md` §7.1/§13 and ADR-004, and DD-43182's
  `02-design.md` §7 / ADR-006 (see `docs/pipeline/DD-43182-operational-metrics-instrumentation/02-design.md`
  around its `TaskRetryMetricsAspect` code sample, which itself records that DD-43182's own earlier
  claim — "`cdk.metrics.enabled=false` removes the aspect bean and therefore the proxying entirely" —
  is **only true in isolation**: once this story's non-optional `JobCorrelationAspect` ships, the
  seven `@Task` beans stay CGLIB-proxied regardless of `cdk.metrics.enabled`, because Spring merges
  same-bean aspects into one proxy). **Whichever ticket's implementation lands second must not
  re-derive this AOP justification and must assert the ordering in a test** — this story's
  `JobCorrelationProxyingTest` (AC-009) is that test on this ticket's side; DD-43182's design already
  references it by name.
- **This story's implementer should coordinate merge order with DD-43182's `TaskRetryMetricsAspect`
  story.** DD-43182 has not yet cut Stage-3 sub-tickets in this session (it is at Stage 2/3 in
  parallel, no `03-stories.md` exists there yet as of this writing) — the real Jira reference for
  that story is **TBC** and must be captured here (or cross-linked from there) once both tickets have
  real sub-tickets.
- Depends on Story 1 for `CorrelationScope`.
- Needs its own linked Jira sub-ticket before Stage 4. Placeholder: `DD-43262`.

---

## Story 4 — `ErrorResponse.traceId` carries a searchable correlation value
**Jira: `DD-43263`**
**Depends on Story 1** (`CorrelationIds.currentOrGenerate()`, and the structural guarantee that
`RequestContextFilter` always leaves a non-blank `correlationId` in MDC).

As a **production support engineer**,
I want **every `ErrorResponse` returned by `GlobalExceptionHandler` to carry the same correlation ID
that appears on the response header and on every log line for that request**,
so that **a caller who only has an error response — not the original request headers — can retrieve
every relevant log line for that failure with one search**.

### Background
Stage 1 believed `GlobalExceptionHandler.traceId()` returns `""` because tracing is "disabled";
Stage 2 verified this premise is wrong on the resolved Boot 4.0.6 classpath — a real `OtelTracer`
bean always exists, so the field today typically already holds a real 32-hex OTel trace ID. That does
**not** make the field useful: the trace it belongs to is never exported (no OTLP endpoint
configured — Story 6), it is overwritten mid-request today by the now-deleted `TracingFilter`
whenever a client sends a bare `traceId` header, and — provably, not just today — it can never exist
on the JobManager, scheduler or downstream hops the same request fans out to, because no span context
is serialised into `jobData` and no outbound CDKS call carries `traceparent`. The accepted design
(ADR-005) therefore sets `traceId` to the correlation ID unconditionally, removes the now-unused
`Tracer` dependency, and removes the bare `catch (Exception ignored)` that used to guard the tracer
lookup.

**Scope note (confirmed 2026-09-06):** "a caller" above means a support engineer reading the raw
HTTP response or the log index directly — the AI Search UI does not display `traceId` anywhere and
has no planned change in this ticket to start doing so (`01-requirements.md` Actors table). This
story's value is realised through direct API/log inspection, not through the UI.

### Acceptance criteria
- [ ] AC-001: With the shipped tracing configuration exactly as-is (no property flip required — there is no master switch, per ADR-006/Story 6), every handler in `GlobalExceptionHandler` returns an `ErrorResponse` whose `traceId` is **non-blank**. A test asserting only non-blank, or only a 32-hex shape, is **not sufficient** on its own — see AC-002, which is the actual oracle.
- [ ] AC-002 (**oracle split at the Stage-4 gate, 2026-09-04 — OQ-102; the third clause is manual, not dropped**): `traceId` **equals** both the `X-Correlation-Id` response header **and** the `correlationId` JSON field on the log lines emitted for that request. This is the assertion that proves the field is actually searchable and the one that would have caught the historical defect — and its third clause is the one thing this repository cannot automate (`AbstractHttpLiveTest` exposes only a `RestTemplate` and a JDBC `Connection`; the `docker-compose` Gradle plugin gives tests no container handle, so there is no `getLogs()` seam, and the gate declined both ways of manufacturing one). Discharged as:
      - **Automated, integration tier:** `traceId` == the `X-Correlation-Id` response header == the value sent. The strongest fully-automatable proxy.
      - **Automated, unit tier:** `ListAppender` **event-shape** coverage — when a log statement fires, MDC carries `correlationId` with that value. Proves the event, **not** the `LogstashEncoder`'s JSON output; must not be claimed as the clause.
      - **Manual — MV-1** (`02-design.md` §13, ADR-005's Stage-4 addendum): with the compose stack up, one captured JSON log line confirms `correlationId` is a **top-level sibling of `message`** and equals both the response's `traceId` and the value sent. Captured by this story's implementer, confirmed by the Stage-6 reviewer, attached to DD-43183, and it **gates release of the ticket** — same treatment DD-43185 gave its un-automatable production-scale `EXPLAIN` evidence. **Do not** substitute a `matches("[0-9a-f]{32}")` or non-null-only assertion; both pass against the historical defect.
- [ ] AC-003: This holds for every handler in `GlobalExceptionHandler`: `ResponseStatusException`, `MethodArgumentNotValidException`, `ConstraintViolationException`, `HttpMessageNotReadableException`, `HttpRequestMethodNotSupportedException`, and the catch-all `Exception` — all six share one `base(...)` construction path, so this is structural, not six separate implementations.
- [ ] AC-004: `GlobalExceptionHandler` no longer has a `Tracer` constructor dependency, no `Objects.requireNonNull` around a tracer lookup, and no `catch (Exception ignored)` block — the class has no operation left that can throw in this area, so there is nothing to swallow.
- [ ] AC-005 (**GATE-4, explicit naming-mismatch acceptance**): `ErrorResponse.traceId` and `DiscoveryTriggerResponse.correlationId` now carry the same value under different field names. No OpenAPI field is added, renamed or removed by this story; `api-cp-crime-caseadmin-case-document-knowledge` stays at `0.0.11`.

### NFR links
- NFR-001 (Data protection): the value is an opaque correlation ID, never a case identifier.
- NFR-005 (Backward compatibility): no API field name or type change.
- NFR-006 (Testability): `GlobalExceptionHandlerTest` rewritten across all six handler methods, with no `Tracer`/`Span`/`TraceContext` mocking required to construct the advice.

### Out of scope for this story
- Adding an additive `ErrorResponse.correlationId` field to deprecate `traceId` honestly — recorded in `02-design.md` §14 as a follow-up requiring an OpenAPI version bump and consumer coordination; explicitly not this story.
- The OTLP export configuration itself — Story 6 (this story's correctness is deliberately independent of tracing/export state, per ADR-005).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (`GlobalExceptionHandlerTest` rewrite, all six handlers; integration: an error response's `traceId` compared against the **`X-Correlation-Id` response header** and the value sent, on a real request).
- [ ] **MV-1 captured and attached to DD-43183** — the one JSON log line proving AC-002's third clause (OQ-102). Not automatable here; not optional either.
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **GATE-4 accepted at the Stage-2 gate on 2026-09-03**: the field named `traceId` deliberately no
  longer contains a trace ID. This is a response-contract *meaning* change worth calling out to
  consumers in the release note (`02-design.md` §16), even though the field's name and type do not
  change.
- Depends on Story 1 for `CorrelationIds.currentOrGenerate()` and the structural non-blank guarantee.
- Needs its own linked Jira sub-ticket before Stage 4. Placeholder: `DD-43263`.

---

## Story 5 — Business identifiers as structured JSON log fields (non-JobManager half)
**Jira: `DD-43264`**
**Depends on Story 1** (`correlation/CorrelationScope`). **Aware of, not blocked by, Story 3** —
Story 3's `JobCorrelationAspect` already delivers the JobManager-task half of this same requirements
area (FR-012–FR-015) as a side effect; this story must not duplicate that MDC-seeding logic.

As a **production support engineer**,
I want **`caseId`, `docId` and `transactionId` to appear as discrete JSON log fields — not
interpolated into the message string — on RAG completion lines and on the case-facing service
classes, with no field at all when a unit of work legitimately has no case**,
so that **I can filter or aggregate log volume by case or document without parsing free text, on
every unit of work where those identifiers actually exist**.

### Background
`RagAnswerAsyncServiceImpl.answerUserQueryAsync` carries no identifier on its completion line today;
`answerUserQueryStatus` already logs a CRLF-sanitised `transactionId` but only as a message
parameter. Four named service classes (`IdpcAvailabilityService`, `IngestionProcessorByCaseService`,
`IngestionService`, `DocumentService`) get a `CorrelationScope`-backed `caseId`/`docId` MDC scope at
their public entry method. The two discovery schedulers and `StalledWorkMetrics` — which have no case
identifier at all — are wrapped in `CorrelationScope.openIfAbsent()` purely for correlation-ID
coverage (`StalledWorkMetrics`'s existing hand-rolled `MDC.put`/`remove` is replaced by the same
mechanism for consistency, not because it was broken). Design deliberately chose **no sentinel value**
for an absent `caseId` — an absent JSON field, not `"none"` — because a sentinel pollutes the index
and makes `caseId:*` searches lie (`02-design.md` §9).

### Acceptance criteria
- [ ] AC-001: `RagAnswerAsyncServiceImpl.answerUserQueryAsync`'s completion log line carries `transactionId` (sourced from the returned `UserQueryAnswerRequestAccepted.getTransactionId()`) as a structured field — it carries no identifier at all today.
- [ ] AC-002: `RagAnswerAsyncServiceImpl.answerUserQueryStatus`'s completion log line carries `transactionId` as a structured MDC field rather than only an interpolated message parameter; its existing CRLF sanitisation is retained unchanged.
- [ ] AC-003: A log statement emitted from `IdpcAvailabilityService`, `IngestionProcessorByCaseService`, `IngestionService` or `DocumentService`, handling work for a known case, has `caseId` — and `docId` where applicable — in MDC, via a scope opened at the public entry method where the identifier first exists.
- [ ] AC-004 (**oracle split at the Stage-4 gate, 2026-09-04 — OQ-102**): These identifiers appear as discrete top-level JSON fields, siblings of `message`, not embedded in it. Discharged as: **(i) automated, unit tier** — a `ListAppender` asserts the emitted **event's** MDC property map contains the expected keys and that the formatted message does **not** contain the value (i.e. the value is a field, not interpolated text), plus a check that any stale `{}`-style message placeholder that duplicated an identifier is removed in the same edit (no dangling placeholder — nothing in the build catches one, since `errorprone.InvalidLogMessageFormat` is excluded from the PMD ruleset); **(ii) manual — MV-1** for the **JSON encoding itself**, which only the running encoder can show. No `logback-spring.xml` change is needed or permitted — `LogstashEncoder` sets no `includeMdcKeyNames`, so every MDC entry is already a top-level field; a PR-diff check asserts the file is untouched.
- [ ] AC-005: A unit of work with no case — `/queries` list, `/query-catalogue`, both discovery schedulers, `StalledWorkMetrics` — emits **no `caseId` key at all**; no sentinel value such as `"none"` (or `""`, or `null`) is introduced. The assertion is key **absence**, not "absent or empty" — an "absent or empty" assertion would permit exactly what the design forbids. **Asserted at the unit tier** via `ListAppender` `doesNotContainKey("caseId")` on the emitted event's MDC property map, for all five cases including the two endpoints (OQ-102: no live test reads log output; MV-1's captured line is a spot-check, not the oracle for this AC).
- [ ] AC-006: `IntradayDiscoveryScheduler.run()` and `NightlyDiscoveryScheduler.run()` are wrapped in `CorrelationScope.openIfAbsent()` (neither carries any MDC today); `StalledWorkMetrics`'s existing hand-rolled `MDC.put`/`remove` is replaced by the same `CorrelationScope` mechanism, with its existing `job` MDC key and behaviour otherwise unchanged.
- [ ] AC-007: No document content, answer text, `llm_input` value, `CJSCPPUID`, court reference number or other personal data is logged at any level, or placed in MDC, a structured log field, or a propagated header, as a result of any change made by this story.

### NFR links
- NFR-001 (Data protection): applies absolutely to every field this story adds.
- NFR-006 (Testability): **automated** — a `ListAppender` test per changed class asserting the new fields are present in the emitted **event's** MDC property map and are not interpolated into the message; **manual (MV-1)** — one captured JSON log line confirming they are siblings of `message` in the encoded output. NFR-006's original wording ("a test that parses an emitted JSON log line") is not satisfiable here: no integration-tier log-reading seam exists and the Stage-4 gate decided not to build one (OQ-102, 2026-09-04).
- NFR-009 (Cardinality/cost): `caseId`/`docId`/`transactionId` are log fields and trace identifiers only — never a Micrometer metric tag or Prometheus label.

### Out of scope for this story
- The JobManager-task half of this same area — delivered by Story 3's `JobCorrelationAspect` as a side effect of restoring correlation MDC; this story must not re-implement it.
- Repositories, mappers or entity classes — excluded at Stage 1 (`01-requirements.md`'s Out of scope).
- Any `logback-spring.xml` / `LogstashEncoder` change — none is needed; every MDC entry is already emitted as a top-level JSON field.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit tests per named service/client class, `ListAppender`-based, covering the event shape and the no-sentinel key absence). **Field *siblinghood in the emitted JSON* is MV-1, manual (OQ-102)** — `CorrelationLogFieldHttpLiveTest` no longer reads log output and asserts only response-surface equivalences.
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **Scope CONFIRMED at the Stage-4 gate, 2026-09-04 (OQ-107) — no longer an open question.**
  `02-design.md` §9's scope decision for this area was flagged as needing requirements-owner
  confirmation (a design decision, not a formal ADR). The requirements owner has now confirmed it
  **exactly as designed and no wider**: the four named services (`IdpcAvailabilityService`,
  `IngestionProcessorByCaseService`, `IngestionService`, `DocumentService`), both
  `RagAnswerAsyncServiceImpl` completion lines, and all seven JobManager tasks via Story 3's shared
  aspect. The no-sentinel rule stands, and `transactionId` means the **RAG** transaction id
  (`JobManagerKeys.CTX_RAG_TRANSACTION_ID` / `UserQueryAnswerRequestAccepted.getTransactionId()`).
  FR-012's literal "a service, task, scheduler or client class" is **bounded to that list** — a
  wider sweep across `services/` and `clients/` is out of scope for DD-43183 and would need its own
  ticket. Nothing about this area is provisional any more: Stage 4's Scenarios 5.3 and 5.5 are
  cleared to be written.
- Depends on Story 1 for `CorrelationScope`. Does not block, and is not blocked by, Story 3 — but
  implementers of both stories should avoid touching the same MDC-seeding call sites twice.
- Needs its own linked Jira sub-ticket before Stage 4. Placeholder: `DD-43264`.

---

## Story 6 — Correct the OTLP tracing/export configuration
**Jira: `DD-43265`**
**No dependency on Stories 1–5 in this set** (configuration-only change; can be picked up in
parallel with any of them).

As a **platform/SRE-facing CDKS engineer**,
I want **the tracing and OTLP export configuration to bind to property keys Spring Boot 4.0.6
actually recognises, with trace export independently switchable from metrics export, and a sane
default sampling rate**,
so that **`OTEL_TRACES_ENABLED` actually does something, spans can reach a collector when a
non-production environment needs them, and CDKS is not silently paying full-sampling cost today
against configuration that has never worked**.

### Background
Three lines in `application-server-management.yml` are dead config: `management.tracing.enabled` does
not exist in Boot 4.0.6 at all (deprecated at level `error`, no replacement field on
`TracingProperties`); `management.otlp.tracing.enabled` does not exist even as a deprecated alias; and
`management.otlp.tracing.endpoint` was removed at deprecation level `error`. There is **no master
switch to flip** — tracing is already on unconditionally while `spring-boot-starter-opentelemetry` is
on the classpath, and only the *exporter* is switchable (ADR-006). This story replaces the dead keys
with the real ones (`management.tracing.export.otlp.enabled`,
`management.opentelemetry.tracing.export.otlp.endpoint`), corrects both default paths to the OTLP/HTTP
spec form (`/v1/traces`, `/v1/metrics`), drops the default sampling probability from `1.0` to Boot's
own `0.1` (**GATE-5**), and adds a standing test that would have caught all three dead keys years ago.

### Acceptance criteria
- [ ] AC-001: `OTEL_TRACES_ENABLED` — bound to the real key `management.tracing.export.otlp.enabled` — independently controls trace export; setting `OTEL_METRICS_ENABLED` alone has no effect on trace export.
- [ ] AC-002: `management.tracing.enabled`, `management.otlp.tracing.enabled` and `management.otlp.tracing.endpoint` are all removed from `application-server-management.yml`, replaced by `management.tracing.export.otlp.enabled` and `management.opentelemetry.tracing.export.otlp.endpoint`, with an in-file comment recording why the old keys are gone (so nobody re-adds them).
- [ ] AC-003: The default trace export endpoint path is `/v1/traces`; the default metrics export endpoint path is `/v1/metrics`.
- [ ] AC-004 (**scope corrected at the Stage-4 gate, 2026-09-04 — OQ-108**): A new `ConfigurationMetadataAuditTest` walks every key in every `src/main/resources/application*.yml` (all seven files), resolves each **`management.*` / `spring.*`** key against the aggregated classpath configuration metadata, and fails the build on any such key that is unknown or deprecated at level `error`, naming every offender with its file and line — with a documented allow-list of any pre-existing `management.*`/`spring.*` findings it surfaces elsewhere in those files, each allow-list entry naming the defect ticket that owns it, so this story does not silently absorb unrelated defects. The implementer must run the test **before** applying AC-002's YAML change and confirm it fails naming exactly the three dead tracing keys — a metadata audit that passes on the unfixed file is doing nothing.
      **The `management.*`/`spring.*` scope is correct and deliberate, and the clause previously asking this test to also catch DD-43182's `cdk.jobmanager.retry.default` binding gap has been dropped.** That is structurally impossible here: `spring-boot-configuration-processor` is not on this build, so CDKS's own `cdk.*` keys and library prefixes (`authz.http.*`, `job.executor.*`) have **no configuration metadata at all** and would each be reported "unknown" if the scope were widened. DD-43182's finding needs its own defect ticket, owned by DD-43182; no allow-list entry for it is needed or possible. Adding `spring-boot-configuration-processor` so `cdk.*` keys become auditable is a genuinely valuable but **separate** change, explicitly not taken here.
- [ ] AC-005 (**GATE-5, accepted**): `management.tracing.sampling.probability`'s default drops from `1.0` to `0.1`; `TRACING_SAMPLER_PROBABILITY` remains available to override to `1.0` in a non-production demonstration environment; a comment or test documents that trace IDs and log correlation are unaffected by the sampling rate at any value.
- [ ] AC-006: With `OTEL_TRACES_ENABLED` and `OTEL_METRICS_ENABLED` both unset, the service starts cleanly and neither exporter bean exists — the current effective default (export nothing) is preserved exactly.
- [ ] AC-007: Given `OTEL_TRACES_ENABLED=true` and `OTEL_TRACES_URL` pointing at a collector in one non-production environment, when requests are made, then spans appear in the collector, evidenced by a screenshot attached to Jira DD-43183 — a two-variable configuration change only, since there is no "master switch" to flip. (Tracked as **MV-2** in `02-design.md` §13: manual, owned jointly with platform/SRE, blocks nothing else in this ticket.)
- [ ] AC-008 (**added at the Stage-4 gate, 2026-09-04 — OQ-105; this story owns the compose file**): `docker/docker-compose.integration.yml` no longer pins the **pre-correction** OTLP paths. Its `app` service currently sets `OTEL_TRACES_URL: http://localhost:4318/traces` and `OTEL_METRICS_URL: http://localhost:4318/metrics` (~lines 155–158), so the integration stack keeps `/traces` and `/metrics` no matter what AC-003 changes in the YAML. **Delete both overrides** — preferable to re-pointing them at `/v1/*`, because one definition site cannot drift from another — so `gradle integration` actually exercises this ticket's corrected defaults. While in that block, delete the two **inert** variables `MANAGEMENT_TRACING_ENABLED` and `TRACING_ENABLED` (neither key exists in Boot 4.0.6 and CDKS binds nothing to `TRACING_ENABLED` — same class of dead config this story exists to remove; note `ConfigurationMetadataAuditTest` cannot see compose env vars, so only review catches these). `TRACING_SAMPLER_PROBABILITY: 1.0` may stay: AC-005's new default is asserted against the resolved YAML, never against the running stack. **After the change, `gradle integration` must still pass** — the endpoint being set while `OTEL_TRACES_ENABLED` is unset yields an `OtlpTracingConnectionDetails` bean and **no** exporter, which is harmless, and is why AC-006's assertion is about **exporter** beans specifically.

### NFR links
- NFR-007 (Configurability): `OTEL_TRACES_ENABLED` and `OTEL_METRICS_ENABLED` are independently settable, satisfying the half of NFR-007 not already covered by Story 1's GATE-1 deviation.
- NFR-009 (Cardinality/cost): sampling rate change is a cost control, not a correlation-field change.

### Out of scope for this story
- Enabling tracing export in production — a platform/SRE decision, explicitly out of scope for the whole ticket (`01-requirements.md`).
- `ErrorResponse.traceId` — Story 4, deliberately designed to be independent of this story's tracing/export state (ADR-005).
- Any new span, span attribute, or `@Observed` instrumentation — explicitly out of scope for the whole ticket.
- Owning or provisioning the collector endpoint itself — platform/SRE's responsibility; this story ships only the properties.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (`ConfigurationMetadataAuditTest`, `TracingConfigurationTest`); AC-007's collector evidence (**MV-2**) captured manually per the ticket's stated deliverable.
- [ ] AC-008's `docker/docker-compose.integration.yml` change is in **this story's reviewed diff** (it was deliberately not made at the documentation stages — see Notes), and `gradle integration` passes with it.
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence, including the AC-007 collector screenshot once available.

### Notes / open questions
- **GATE-5 (sampling default 1.0 → 0.1) accepted at the Stage-2 gate on 2026-09-03.**
- **OQ-105 resolved, 2026-09-04 — and deliberately left as this story's work rather than done at
  the documentation stage.** The stale OTLP paths in `docker/docker-compose.integration.yml` are a
  real, small config defect that this story must fix (AC-008), and the decision on *how* is made:
  **delete the two overrides** rather than re-point them. It was **not** edited while writing these
  documents, on purpose: that file is executed by `gradle integration` on every CI run, so changing
  it outside a story diff would put a CI-affecting edit on a documentation branch with no test run,
  no `implementation` stage and no code review — i.e. it would slip past Stages 5–7 and past this
  repo's "never proceed past a human gate" rule for the sake of four lines. It costs nothing to
  land it inside Story 6, where the YAML change it exists to complement also lands and where
  `gradle integration` proves the stack still comes up.
- **OQ-108 resolved, 2026-09-04:** AC-004's `management.*`/`spring.*` scope is confirmed correct and
  the cross-ticket `cdk.jobmanager.retry.default` clause is dropped — see AC-004. DD-43182 keeps
  ownership of that defect.
- No dependency on Stories 1–5; can be delivered in parallel with any of them.
- Needs its own linked Jira sub-ticket before Stage 4. Placeholder: `DD-43265`.

---

## Story 7 — MDC leak assurance and whole-ticket regression proof
**Jira: `DD-43266`**
**Depends on Stories 1–6** (cross-cutting integration/regression story, sequenced last — mirrors the
pattern used in `DD-43185-stalled-work-scheduler-monitoring/03-stories.md`'s own final story).

As a **CDKS developer / release engineer**,
I want **cross-request and cross-job MDC isolation proven under test — including a forward-looking
check under the currently-disabled virtual-threads toggle — plus confirmation that this ticket's
full diff introduces no regression**,
so that **the whole ticket can be merged and deployed with confidence that one request's or job's
correlation context can never leak into another's, and that nothing else broke along the way**.

### Background
`jobExecutorThreadPool` (`job-executor-*`) is the one pooled, thread-reusing executor in this service
that has never had any MDC hygiene at all — Story 3 fixes it structurally (aspect + `TaskDecorator`);
this story is where that guarantee is proven under test at the pool level, alongside the equivalent
proof for Tomcat request threads (already covered by Story 1's `RequestContextFilter` restore). Virtual
threads stay disabled in every environment (ADR-008, unchanged by this ticket) — the corresponding
test is a deliberately low-value, forward-looking regression check, not a claim of broader coverage.

### Acceptance criteria
- [ ] AC-001: With `jobExecutorThreadPool`'s pool size forced to 1 in a test, a job that sets `correlationId` (and `caseId`/`docId`) in MDC and then returns — or throws — is followed by a second, unrelated job on the same thread that observes **nothing** left over from the first, for both the normal-return and the throwing path.
- [ ] AC-002: Given request A sets a correlation value in MDC, when request B is subsequently handled on the same or a recycled Tomcat thread, then no MDC value from request A is visible while handling request B. **The deterministic, load-bearing proof is at the unit tier** — run the filter twice on one thread with different inputs and assert no bleed, including the case where request A throws inside the handler. The live sequential-request case is **best-effort only**: nothing guarantees two live requests share a Tomcat thread, so it can fail to detect a leak but cannot prove absence. Per OQ-102 (Stage-4 gate, 2026-09-04) the "B's log lines carry nothing from A" clause is asserted as **MDC state / `ListAppender` event MDC** at the unit tier, **not** by reading the container's log output.
- [ ] AC-003: The same assertion as AC-002 holds with `spring.threads.virtual.enabled=true` forced via `@TestPropertySource` — explicitly a low-value, kept-for-regression test (ADR-008), not evidence of production readiness for virtual threads, which remain out of scope for this whole ticket.
- [ ] AC-004: A standing `MdcReservedKeyTest` (introduced in Story 1) is re-run here as part of the whole-ticket regression pass: no `src/main` source file contains an `MDC.put` of `traceId` or `spanId`, across the entire diff produced by Stories 1–6, not only Story 1's own files.
- [ ] AC-005: `gradle clean build` (including `integration`) passes for the whole ticket; PMD and JaCoCo are green at existing, unmodified thresholds; CodeQL and the secrets scanner are clean.
- [ ] AC-006: No RAG response field is dropped or transformed anywhere in this ticket's diff — `doc_id` and `llm_input` continue to be persisted and served unaltered (CLAUDE.md's hard rule, checked explicitly across all six preceding stories' combined diff, not assumed).
- [ ] AC-007: The full diff across Stories 1–6 introduces no PII, case content, court reference number or `CJSCPPUID` into code, config, tests or fixtures; every correlation value used in tests and WireMock stubs is synthetic.
- [ ] AC-008: No existing OpenAPI field name or type changes anywhere in the ticket; `api-cp-crime-caseadmin-case-document-knowledge` stays at `0.0.11` and `version.cdk` is untouched.

### NFR links
- NFR-004 (Availability): the leak-assurance tests must themselves introduce no new failure mode into the suites they extend.
- NFR-006 (Testability): this story is the direct deliverable for NFR-006's "a test asserts that no MDC value set while handling request A is visible while handling request B" requirement, at both the HTTP-request and JobManager-pool levels.

### Out of scope for this story
- Enabling virtual threads in any environment — explicitly out of scope for the whole ticket.
- Enabling tracing export in production — Story 6's own out-of-scope item, unchanged here.
- Any new production code. This story should not need a production-code change beyond what Stories 1–6 already made; if it does, that is a signal one of the earlier stories under-delivered its own AC, not a reason to add scope here.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (`JobExecutorMdcLeakTest`, `MdcVirtualThreadIsolationTest`, a whole-ticket `gradle clean build` run).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence covering the full ticket, not just this story's own (minimal) diff.

### Notes / open questions
- Intentionally sequenced last — its cross-cutting tests exercise mechanisms built by every other
  story in this set, so it cannot be meaningfully completed (only partially stubbed) before Stories
  1–6 land.
- Needs its own linked Jira sub-ticket before Stage 4. Placeholder: `DD-43266`.

---

## Summary

| Story | Title | Jira | Depends on | Requirements area |
|---|---|---|---|---|
| 1 | One documented inbound correlation convention; `TracingFilter` deleted | `DD-43260` | none | A |
| 2 | Outbound propagation, stop destroying MDC, **GATE-6 credential-logging fix (already shipped, PR #225)** | `DD-43261` | Story 1 | B |
| 3 | JobManager async correlation restoration (`JobCorrelationAspect`) | `DD-43262` | Story 1; **cross-ticket: DD-43182's `TaskRetryMetricsAspect` story** | C |
| 4 | `ErrorResponse.traceId` carries a searchable correlation value | `DD-43263` | Story 1 | D |
| 5 | Business identifiers as structured JSON log fields (non-JobManager half) | `DD-43264` | Story 1; aware of Story 3 | E |
| 6 | Correct the OTLP tracing/export configuration | `DD-43265` | none | F |
| 7 | MDC leak assurance and whole-ticket regression proof | `DD-43266` | Stories 1–6 | G |

**GATE-6 (security fix) location, stated once for unambiguous tracking:** the `DebugLoggingInterceptor`
APIM-credential-logging fix is tracked at **Story 2, AC-007** — and has **already shipped**,
independently, in **PR #225** (merged 2026-09-03, deny-list redaction of the two credential header
names), acting on this pipeline's own recommendation to expedite it ahead of the rest of DD-43183's
correlation-ID work. AC-007 is retained in Story 2 as a regression check, not open work.

**Manual verification, stated once so no story quietly loses it (OQ-102, Stage-4 gate 2026-09-04):**
this repository has **no seam for reading the application's emitted log output at the integration
tier**, and the gate decided not to build one (no `docker compose logs` shell-out from the suite, no
file appender + bind mount changing production logging for tests). Every AC whose oracle was "read a
JSON log line from the container" is therefore discharged as automated unit-tier **event-shape**
coverage (a `ListAppender` proves the MDC keys are present when a log statement fires) plus automated
integration-tier **response-surface** proxies, plus **MV-1** — one captured JSON log line, confirmed
by the implementer and the Stage-6 reviewer and attached to DD-43183, proving the identifiers are
**top-level siblings of `message`** and that `correlationId` matches the response. MV-1 gates
**release of the ticket**, not the merge of any single story, and it is written down here for the
same reason DD-43185 wrote down its un-automatable production-scale `EXPLAIN` evidence: an
un-automatable requirement that is merely *dropped* is indistinguishable from one that was forgotten.
Affected ACs: **Story 2 AC-004(iii)**, **Story 4 AC-002** (third clause), **Story 5 AC-004(ii)**,
and the log-line half of Story 3's live JobManager addition. **MV-2** (Story 6 AC-007's collector
screenshot) is the ticket's other manual item and blocks nothing else. Full definition:
`02-design.md` §13.

**Cross-ticket coordination, stated once so sprint planning does not have to re-derive it:** Story 3
(`JobCorrelationAspect`) and DD-43182's `TaskRetryMetricsAspect` story both add an `@Aspect` on the
identical join point `ExecutableTask.execute`. Both tickets' Stage-2 designs accept
`JobCorrelationAspect` ordered outermost. Story 3's `JobCorrelationProxyingTest` (AC-009) asserts this
ordering and must pass whether DD-43182 has already landed or not. DD-43182 has not yet cut its own
Stage-3 sub-tickets in this session — coordinate merge order with that ticket's team once its
sub-ticket exists.

**Not a story here** (per `01-requirements.md`'s Out of scope, unchanged at Stage 3): enabling virtual
threads in any environment; enabling tracing export in production; adopting Micrometer
Observation/`@Observed` instrumentation or creating any new custom span; distributed W3C
`traceparent` propagation across CPP service boundaries (an explicitly-flagged worthwhile follow-up
once a collector exists, per `02-design.md` §14); any change to the audit payload or to
`cp-audit-filter-springboot`/`cp-auth-rules-filter`; any new or changed REST endpoint or OpenAPI field
(including the additive `ErrorResponse.correlationId` field that would resolve GATE-4's naming
mismatch honestly — recorded as a follow-up, not a story); any Flyway migration; any new custom
metric; log retention, index configuration, alerting, or dashboard configuration (OQ-011); backfill
or reprocessing of historical logs.

**Carried-forward follow-ups needing action before or shortly after this ticket ships**, for
visibility at sprint planning (none of these are stories in this set):
- **OQ-013** — Jira DD-43183's pasted brief was never confirmed against the live ticket/epic
  comments in this session (no Jira/Atlassian MCP tool available). Real sub-tickets `DD-43260`
  through `DD-43266` must be created and linked to the parent epic before Stage 4 starts, and the
  requester should confirm the pasted brief was complete and current.
- ~~**OQ-009 (Story 5's scope)**~~ — **resolved 2026-09-04 (carried as OQ-107).** The requirements
  owner confirmed design §9's resolution of "every operational log line" exactly as written and no
  wider: the four named services, both `RagAnswerAsyncServiceImpl` completion lines, and the seven
  JobManager tasks via Story 3's aspect; no sentinel for an absent `caseId`; `transactionId` = the
  RAG transaction id. Story 5's Notes carry the detail. No longer a follow-up.
- The additive `ErrorResponse.correlationId` OpenAPI field (GATE-4's honest fix) — needs an
  `api-cp-crime-caseadmin-case-document-knowledge` version bump and consumer coordination; not a
  story here.
- Distributed `traceparent` propagation to RAG/Hearing/Progression (build `RestClient`s with an
  `observationRegistry`) — a genuinely worthwhile follow-up once a collector exists; explicitly out
  of scope for this ticket.
- `cdk.jobmanager.retry.default` not binding to `defaultRetry` (found by DD-43182's design) — needs
  its own defect ticket, owned by DD-43182, not absorption into DD-43183. **Corrected 2026-09-04
  (OQ-108):** this ticket's `ConfigurationMetadataAuditTest` will **not** surface it and cannot —
  the audit is `management.*`/`spring.*`-scoped because `spring-boot-configuration-processor` is not
  on this build, so `cdk.*` keys have no configuration metadata to resolve against. Story 6 AC-004's
  clause asking for an allow-list entry for it has been dropped.
