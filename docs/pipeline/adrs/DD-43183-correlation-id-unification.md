# Architecture Decision Records — Unified Correlation-ID Handling and Trace Propagation

> Service: `cp-case-document-knowledge-service` (CDKS) · Jira: DD-43183 · Taken at Stage 2
> (Architecture & Design), resolving Stage 1 open questions OQ-001 – OQ-003, OQ-005 – OQ-008,
> OQ-010 – OQ-012 and NFR-002.
> Requirement: [`../DD-43183-correlation-id-unification/`](../DD-43183-correlation-id-unification/) ·
> Requirements: [`01-requirements.md`](../DD-43183-correlation-id-unification/01-requirements.md) ·
> Design: [`02-design.md`](../DD-43183-correlation-id-unification/02-design.md)
>
> **Status of this file: Stage-2 human gate cleared on 2026-09-03. All eight ADRs are `Accepted`.**
> All six gate items in `02-design.md` §15 (GATE-1 – GATE-6) are **accepted as designed**,
> including GATE-6 (the `DebugLoggingInterceptor` credential-logging fix is accepted for inclusion
> in this ticket's diff, not deferred to a separate defect ticket) and the cross-ticket coordination
> with DD-43182 (`JobCorrelationAspect` ordered outermost of `TaskRetryMetricsAspect` on
> `ExecutableTask.execute`).
>
> ---
>
> **Read this first — Stage 1's central factual premise about tracing is wrong, and it changes
> three of the decisions below.** Design verified the resolved Spring Boot 4.0.6 classpath by
> reading the shipped configuration metadata, decompiling the auto-configuration classes, and
> **running the auto-configurations in a real `SpringApplication`**. Four findings, all
> reproducible (see `02-design.md` §2):
>
> 1. **`management.tracing.enabled` is not a Spring Boot 4.0.6 property either.** It is declared in
>    `spring-boot-micrometer-tracing-4.0.6`'s metadata as deprecated at level **`error`**, replaced
>    by `management.tracing.export.enabled`, and `TracingProperties` has **no `enabled` field at
>    all** (only `sampling`, `baggage`, `propagation`). Line 34 of
>    `application-server-management.yml` is therefore *also* dead config. There is no master
>    switch, and OQ-011's question ("who owns flipping it?") has no subject.
> 2. **Tracing is already on, and cannot be switched off by property.**
>    `OpenTelemetryTracingAutoConfiguration` carries only `@ConditionalOnClass` — no tracing-enabled
>    condition of any kind — so a real `io.micrometer.tracing.otel.bridge.OtelTracer` bean is
>    created unconditionally. `NoopTracerAutoConfiguration` is `@ConditionalOnMissingBean(Tracer)`
>    and therefore never engages. **Stage 1's `Tracer.NOOP` → `TraceContext.NOOP` → `""` chain does
>    not exist on this classpath.** `ConditionalOnEnabledTracingExport` gates only the *exporters*.
> 3. **Micrometer Tracing already writes `traceId` and `spanId` into MDC.**
>    `OpenTelemetryTracingAutoConfiguration` registers an `otelSlf4JEventListener`
>    (`io.micrometer.tracing.otel.bridge.Slf4JEventListener`), wired through the
>    `OpenTelemetryEventPublisherBeansApplicationListener` that the jar registers in
>    `META-INF/spring.factories`. Verified live: inside an observation scope, with
>    `management.tracing.enabled=false` set exactly as CDKS ships it, `MDC` contains
>    `{traceId=<32 hex>, spanId=<16 hex>}` and is restored to `{}` on scope close.
>    **`filters/tracing/TracingFilter` writes the same two MDC keys**, so it is not a redundant
>    third convention — it is an active corruption of the tracer's own log-correlation fields.
> 4. **`ErrorResponse.traceId` is therefore almost certainly a real 32-hex OTel trace ID today**,
>    not `""` and not `null`, for every exception raised inside the `DispatcherServlet`
>    (`WebMvcObservationAutoConfiguration` registers `ServerHttpObservationFilter` unconditionally
>    at order `HIGHEST_PRECEDENCE + 1`, and it opens the observation scope around the whole filter
>    chain). The Area D defect is **not** "the field is empty". It is that **the value on the
>    response cannot be found in the logs**: it belongs to a trace that is never exported, it is
>    overwritten in MDC by `TracingFilter` whenever a client sends a bare `traceId` header, and it
>    does not exist at all on the JobManager, scheduler and downstream hops the same request fans
>    out to.
>
> That reframing is the reason ADR-001 deletes a filter rather than reordering it, ADR-005 chooses
> the correlation ID over the trace ID, and ADR-006 is a dead-key cleanup rather than a
> tracing-enablement decision.
>
> ---
>
> **Cross-ticket coordination — DD-43182 is in flight in this repository and lands an aspect on the
> same join point.** `docs/pipeline/DD-43182-operational-metrics-instrumentation/02-design.md`
> proposes `metrics/TaskRetryMetricsAspect`, an `@Aspect` `@Around` on
> `execution(* uk.gov.hmcts.cp.cdk.jobmanager..*.execute(..ExecutionInfo))`, resting on the same
> `TaskRegistry` / `AopUtils.getTargetClass` evidence ADR-004 relies on. ADR-004 deliberately
> matches that mechanism rather than inventing a second one, and fixes the advice ordering between
> them. See ADR-004's Consequences for the one claim in DD-43182's design that DD-43183 invalidates.

---

## ADR-001: `CPPCLIENTCORRELATIONID` is the canonical inbound correlation header; `X-Correlation-Id` is the single accepted alias; the bare `traceId` header is not an alias and `TracingFilter` is deleted

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43183 · **Resolves:** OQ-001, OQ-002 (header half)
- **Artefacts:** `01-requirements.md` (FR-001, FR-003, FR-004, NFR-005, NFR-007, NFR-008, AC-001 – AC-004, AC-006, OQ-001, OQ-002) · `02-design.md` (§3, §4, §5)

### Context

Six correlation mechanisms exist (Stage 1's Context table). Design confirmed all six and adds the
detail that decides between them:

| # | Mechanism | Verified detail | Is it a *convention*? |
|---|---|---|---|
| 1 | `config/RequestContextFilter` | reads `X-Correlation-Id` (`:31`), UUID fallback (`:33`), `MDC.put("correlationId", …)`, `MDC.clear()` in `finally` | Yes — CDKS-local |
| 2 | `filters/tracing/TracingFilter` | reads bare `traceId`/`spanId`; `MDC.put(TRACE_ID/SPAN_ID)`; echoes both as response headers; **no `@Order`** ⇒ `LOWEST_PRECEDENCE` | **No** — see below |
| 3 | `http/CorrelationIdInterceptor` | reads `X-Request-ID` off the **outbound** request it is building; nothing ever sets it there; always a fresh `UUID.randomUUID()` | **No** — dead branch |
| 4 | `cp-audit-filter-springboot` 1.0.5 | `AuditPayloadGenerationService` calls `getHeaderMatchingKey(headers, "CPPCLIENTCORRELATIONID")` — case-insensitive — into `_metadata.correlation.client`. Confirmed by decompiling the shipped jar | **Yes — and it is the CPP platform's, not CDKS's** |
| 5 | `JobManagerKeys.Params.REQUEST_ID` | seeded at 4 dispatch sites, survives every chain via `createObjectBuilder(jobData)` | Transport, not an inbound convention (ADR-003) |
| 6 | `metrics/StalledWorkMetrics` | `MDC.put("correlationId", randomUUID())` / `remove` in `finally` (`:100`, `:113`) | Already conforms to key `correlationId` |

Three facts settle the choice:

- **Mechanism 4 is already the platform convention, and CDKS already consumes it.** It is not a
  candidate to be adopted in future — `cp-audit-filter-springboot` reads it on *every* CDKS request
  today and writes it into the audit event that goes to Artemis. Every other CPP service using the
  same filter does the same. Choosing anything else as canonical means CDKS's log correlation and
  CDKS's own audit correlation are keyed on two different values, which is the precise failure the
  ticket exists to end.
- **Mechanism 2's `traceId` is not a convention that can be honoured.** The key it writes is the key
  Micrometer Tracing owns (header note 3 above). Accepting a bare `traceId` header as a correlation
  alias would mean a client-supplied string competing for the same MDC key as the tracer's own
  32-hex ID, flipping back and forth every time a span scope opens or closes — e.g. on each
  outbound `RestClient` call. It is also a non-standard spelling of W3C vocabulary; the
  standards-aligned inbound form, `traceparent`, is **already** consumed by Boot
  (`management.tracing.propagation.consume` defaults to `[W3C, B3, B3_MULTI]`) and needs no CDKS
  code at all.
- **Mechanism 2's response-header echo can be withdrawn without depriving any caller of anything.**
  `TracingFilter` sets the `traceId`/`spanId` response headers **only when the request carried them**
  (`:33`, `:37`). The echo therefore only ever returns a value the caller itself supplied. No
  consumer can learn anything from it that it did not already know. This closes NFR-005's
  "withdrawing them is a breaking change" concern with evidence rather than judgement.

One further finding: the only test that appears to cover `TracingFilter` end-to-end,
`src/test/java/uk/gov/hmcts/cp/cdk/logging/TracingIntegrationTest`, does **not** exercise it. It
`@Import`s `TestTracingConfig`, a test-only `HandlerInterceptor` that re-implements the filter's
behaviour **and adds a `UUID.randomUUID()` fallback production does not have** (`TestTracingConfig:29–34`).
`incoming_request_should_add_new_tracing` asserts a generated `traceId` that no production code path
produces. The test asserts a fiction and must be rewritten with the filter's removal.

### Decision

**1 — Canonical inbound header: `CPPCLIENTCORRELATIONID`.** Matched case-insensitively, exactly as
`cp-audit-filter-springboot` matches it, so that a single request header drives CDKS's log
correlation, CDKS's audit event, and every downstream CPP service's audit event.

**2 — One alias, with a fixed precedence order.** Resolution order, first non-blank wins:

| Order | Header | Status |
|---|---|---|
| 1 | `CPPCLIENTCORRELATIONID` | Canonical |
| 2 | `X-Correlation-Id` | **Alias, deprecated but honoured indefinitely.** Existing CDKS callers use it; `DiscoverySchedulerTriggerHttpLiveTest` sends it and AC-007 requires that test to pass unmodified |
| 3 | *(none present, or present but blank, or rejected by ADR-007's validation)* | Generate `UUID.randomUUID()` |

**Not aliases:** bare `traceId`, bare `spanId`, `X-Request-ID`, `traceparent`. The first two for the
MDC-collision reason above; `X-Request-ID` because it is only ever *written* outbound by CDKS and
never read inbound; `traceparent` because Boot's propagator already handles it and treating it as a
correlation-ID alias would put a 55-character composite W3C header value into a field meant for an
opaque identifier.

**3 — Outbound header names: `CPPCLIENTCORRELATIONID` **and** `X-Correlation-Id`, both carrying the
same resolved value.** `X-Request-ID` is dropped. Two headers is a deliberate choice, not
indecision: Hearing and Progression are CPP services behind APIM running the same audit filter, so
`CPPCLIENTCORRELATIONID` is what makes their audit events join to CDKS's; `X-Correlation-Id` is the
generic name a non-CPP service (RAG) is more likely to log. Dropping `X-Request-ID` is safe because
it only ever carried a fresh, meaningless UUID — nothing downstream can have a dependency on a value
that correlates with nothing.

**4 — Response header: `X-Correlation-Id` only**, set on every response, success and error alike,
before the chain runs (so it cannot be missed by a committed response). One name, because a
response echoing a header named after a *client request* convention is not itself a CPP convention,
and `X-Correlation-Id` is the name CDKS's current callers already know.

**5 — Delete `filters/tracing/TracingFilter` and its package.** All three of its MDC keys go:
`traceId` and `spanId` because Micrometer Tracing owns them (ADR-002), `applicationName` because
`logback-spring.xml` already emits `{"app":"cp-case-document-knowledge-service","service":"cp-case-document-knowledge-service"}`
as static `customFields` on every line — the MDC key is a per-request copy of a constant.

**6 — Header names, the alias list and the MDC key are compile-time constants**, in one new final
class `uk.gov.hmcts.cp.cdk.correlation.CorrelationIds` (private constructor throwing
`AssertionError`, the `util/TimeUtils` and `metrics/CdkMeters` precedent) — **not**
`@ConfigurationProperties`. This is a deliberate, flagged deviation from NFR-007's "one documented
place, following the `application-*.yml` + `CP_CDK_*` convention": a header name an environment can
rename is exactly the environment-drift failure mode DD-43185 ADR-006 rejected for metric identity,
and the correlation header is a contract with the audit filter and with other CPP services, not a
tunable. NFR-007's other half (`OTEL_TRACES_ENABLED` independent of `OTEL_METRICS_ENABLED`) is
satisfied in full by ADR-006. **GATE-1: accept the constants-not-config deviation, or reject and
take `@ConfigurationProperties` with the drift risk stated here.**

### Alternatives considered

- **Keep `X-Correlation-Id` canonical and treat `CPPCLIENTCORRELATIONID` as the alias.** The
  smallest diff, and it keeps the live integration test literally unchanged. Rejected: it leaves
  CDKS's audit correlation (`_metadata.correlation.client`) keyed on a header CDKS treats as
  second-class, so a caller that sends only `CPPCLIENTCORRELATIONID` — the documented CPP way —
  gets a *generated* correlation ID in the logs and its own value only in the audit event. That is
  a second identifier namespace surviving the ticket that is supposed to remove them.
- **Adopt W3C `traceparent` as canonical.** Standards-aligned, propagates natively, and Boot already
  consumes it. Rejected as *canonical*: it is a composite (version-traceid-spanid-flags), the
  ticket's deliverable is a single opaque searchable value, callers today send neither of the
  candidate headers in that form, and it would make the correlation ID environment-dependent on
  sampling decisions. Boot's existing consumption of it is untouched and is a free bonus.
- **Invent a CDKS-specific header (`X-CDK-Correlation-Id`).** Rejected outright: a seventh
  identifier in a ticket about having one.
- **Keep `TracingFilter` but strip its MDC writes, retaining only the response echo.** Rejected once
  the echo was shown to return only what the caller sent — a filter whose entire remaining function
  is to hand a value back to the party that supplied it.
- **Keep `TracingFilter` and give the tracer different MDC keys** (`management.tracing.baggage.correlation.fields`
  or a custom `Slf4JEventListener`). Rejected: it preserves two trace-identifier namespaces and
  renames the field that every Boot-shipped log pattern, every platform log-index convention, and
  every other HMCTS Boot service calls `traceId`.

### Consequences

- **Positive:** one inbound value drives logs, the audit event, the response, every downstream call
  and every JobManager hop. The audit trail and the log index join without a lookup table.
- **Positive:** three MDC keys and one whole filter/package are deleted; the tracer's `traceId` /
  `spanId` become truthful for the first time.
- **Accepted — a behaviour withdrawal:** the `traceId` and `spanId` response headers stop being
  echoed. Argued harmless above; still worth naming in the release note. **GATE-2: confirm no
  consumer reads them.** Design's position is that this cannot be true in any useful sense, because
  the echo only ever returned the caller's own input.
- **Accepted:** outbound calls carry two correlation headers for the foreseeable future. One extra
  header per request, no allocation of consequence (NFR-003).
- **Needs external confirmation, not blocking:** which header RAG, Hearing and Progression actually
  log. If any of them documents a different one, it is an additive change to one constant list.
- **Accepted:** `TracingIntegrationTest` and `TestTracingConfig` are rewritten or deleted, and
  `TracingFilterTest` is deleted with the filter. These tests currently pin behaviour that either
  does not exist in production or must stop existing.
- **Reversibility:** good on the header set (constants, one edit). Poor on the deletion of
  `TracingFilter` once released, in the same sense any withdrawn response header is poor —
  reinstating it is a commit, but a consumer that broke in the interim has already broken.

---

## ADR-002: `correlationId` is the single canonical MDC key; `traceId` and `spanId` are reserved to Micrometer Tracing and no CDKS code may write them

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43183 · **Resolves:** OQ-002 (MDC-key half), OQ-003
- **Artefacts:** `01-requirements.md` (FR-002, FR-013, NFR-005, AC-005, AC-007, OQ-002, OQ-003) · `02-design.md` (§3, §4, §12)

### Context

`correlationId` is already written by four places (`RequestContextFilter`, `CorrelationIdInterceptor`,
`StalledWorkMetrics`, and inherited by `MdcCopyingTaskDecorator`'s consumers) and — decisively — is
**read into a published API field**: `DiscoverySchedulerController:53` does
`.correlationId(MDC.get("correlationId"))` on `DiscoveryTriggerResponse`, asserted verbatim by
`DiscoverySchedulerTriggerHttpLiveTest:78`. Renaming it silently nulls an OpenAPI field.

The open half of OQ-003 was what happens to the `traceId` / `spanId` / `applicationName` keys
`TracingFilter` sets. Finding 3 in this file's preamble answers it: `traceId` and `spanId` are not
CDKS's to allocate. They are populated and cleared by `Slf4JEventListener` on every span-scope
transition, which on a request thread means at least twice per request and once more per outbound
call. Any CDKS write to those keys is a race with a library that will win.

### Decision

**1 — The canonical MDC key stays `correlationId`.** Held as `CorrelationIds.MDC_KEY`. Every read
and write in `src/main` goes through the constant; no string literal `"correlationId"` remains
(this is also what keeps PMD's `errorprone.AvoidDuplicateLiterals` quiet — the full `errorprone`
category is enabled in `.github/pmd-ruleset.xml`).

**2 — `traceId` and `spanId` are reserved keys.** No `src/main` code writes them. They continue to
appear as JSON log fields, supplied by Micrometer Tracing, carrying real OTel identifiers. A unit
test asserts no `src/main` source file contains an `MDC.put` of either key.

**3 — `applicationName` is removed** (ADR-001(5)); `logback-spring.xml`'s static `app` / `service`
custom fields already carry it, unchanged.

**4 — The documented MDC inventory** (`02-design.md` §12) becomes part of the deliverable NFR-008
asks for, with an owner per key:

| MDC key | Owner | Lifetime |
|---|---|---|
| `correlationId` | CDKS — `CorrelationIds` / `CorrelationScope` | Unit of work |
| `cluster`, `region`, `path` | CDKS — `RequestContextFilter` | Request |
| `traceId`, `spanId` | **Micrometer Tracing** — reserved, never written by CDKS | Span scope |
| `caseId`, `docId`, `transactionId` | CDKS — Area E (`02-design.md` §9) | Unit of work, where applicable |
| `job`, `trigger`, `discoveryOperation`, `scheduler` | CDKS — existing DD-43062/63/85 keys, unchanged | Unit of work |

### Alternatives considered

- **Rename the MDC key to `correlation_id` / `cppClientCorrelationId` to match the header.**
  Rejected: it nulls `DiscoveryTriggerResponse.correlationId`, breaks AC-007's unmodified live-test
  assertion, and changes a field name the platform log index already receives — for cosmetic
  symmetry with a header name.
- **Use the tracer's `traceId` as the single MDC correlation field and drop `correlationId`
  entirely.** Superficially the cleanest possible outcome: one field, framework-managed, no CDKS
  code. Rejected on three counts, any one of which is fatal: (a) it nulls the published
  `DiscoveryTriggerResponse.correlationId`; (b) the trace context does **not** reach JobManager task
  execution — nothing serialises a span context into `jobData`, and tasks run on other threads and
  other pods — so the field would be absent on exactly the hops that are hardest to debug; (c) CDKS
  builds its `RestClient`s from the static `RestClient.builder()` in
  `RestClientFactoryConfig:114`, with no `observationRegistry`, so **no outbound call carries
  `traceparent`** and the trace ends at CDKS's boundary.
- **Keep both `correlationId` and a CDKS-written `traceId` in sync.** Rejected: that is the
  collision, restated as a feature.

### Consequences

- **Positive:** AC-005 ("no second MDC key holds a different correlation value at the same time")
  becomes structurally true rather than a matter of discipline, because the only other
  trace-shaped keys are owned by a library and mean something different and correct.
- **Positive:** AC-007 passes with the existing assertion untouched, and no OpenAPI field changes
  (AC-038).
- **Positive:** log lines carry *both* a CDKS correlation ID that spans every hop and a real OTel
  trace ID for the synchronous portion — strictly more information than today.
- **Accepted:** `correlationId` and `traceId` are different values on the same line, and a support
  engineer must be told which to search. `02-design.md` §12 and the release note say: search
  `correlationId`.
- **Reversibility:** total for (1) and (3); (2) is a rule, not a code artefact.

---

## ADR-003: The correlation ID rides on the existing `JobManagerKeys.Params.REQUEST_ID` (`"requestId"`) job-data key — no new key is added

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43183 · **Resolves:** OQ-005
- **Artefacts:** `01-requirements.md` (FR-008, FR-009, AC-015, AC-016, AC-017, OQ-005) · `02-design.md` (§7)

### Context

`JobManagerKeys.Params.REQUEST_ID = "requestId"` already exists and the plumbing is already
complete. Verified at Design:

- **Seeded at all four dispatch sites**, every one with an unrelated fresh UUID:
  `DiscoveryService:153` and `:163` (`randomUUID()`), `JobManagerService:55` (via the **inline
  literal** `"requestId"` at `:59`), `IngestionProcessorByCaseService:85` (passed into
  `RetrieveMaterialAndUploadJobDataService.enrich(...)`, which adds it at `:63`).
- **Survives every chain**: all nine successor dispatches copy the parent map —
  `GetCasesForHearingTask:101`, `RetrieveMaterialAndUploadTask:131`,
  `GenerateAnswerForQueryTask:101`, `CheckStatusOfAnswerGenerationTask:164`,
  `CheckAllDocumentsIngestionStatusTask:69`, `CheckIngestionStatusForAllDefendantsTask:125/:152/:173`,
  and `RetrieveMaterialAndUploadJobDataService:43` — all `createObjectBuilder(jobData)`. AC-017 is
  already structurally satisfied.
- **Read by three tasks** for logging only: `CheckIdpcAvailabilityAllDefendantsTask:59` (constant),
  `GetCasesForHearingTask:57` (constant), `RetrieveMaterialAndUploadTask:79` (**inline literal**).
- **Never enters MDC anywhere.** It is only ever a `log.*` message parameter.

So the missing pieces are seeding it from the inbound correlation ID and putting it into MDC —
not new transport.

### Decision

**Reuse `REQUEST_ID`. Add no second job-data key.**

1. `JobManagerKeys.Params.REQUEST_ID` keeps its name and value (`"requestId"`) and gains Javadoc
   stating that it carries **the correlation ID** as defined by `CorrelationIds.MDC_KEY`, that it is
   a persisted wire format, and that it must not be renamed.
2. The two surviving inline literals — `RetrieveMaterialAndUploadTask:79` and
   `JobManagerService:59` — move onto the constant (AC-015).
3. All four dispatch sites seed it from the ambient correlation ID
   (`CorrelationIds.currentOrGenerate()`) instead of a fresh `randomUUID()` (FR-009).
4. The MDC key (`correlationId`) and the job-data key (`requestId`) deliberately differ. The
   asymmetry is documented once, in `02-design.md` §12, with the reason in (5) below.
5. The tasks stop passing `requestId` as a log parameter, because it is in MDC and therefore already
   a discrete JSON field on every line (FR-013). Removing the message parameter is a *behavioural
   improvement*, not a loss: `errorprone.InvalidLogMessageFormat` is excluded in the PMD ruleset, so
   there is nothing to catch a stale placeholder — the parameters are removed with the placeholders
   in the same edit.

**The decisive argument for reuse over a new key is the rollout window, not churn.** `jobData` is
persisted in the task manager's `job` table. At the moment DD-43183 deploys there will be in-flight
job rows — retrying tasks, `CHECK_STATUS_OF_ANSWER_GENERATION` polling on a 100 × 10 s budget,
`CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS` on 50 × 5 s — whose `jobData` was written by the *old*
code. Those rows contain `requestId` and nothing else. A new key would be **absent from every
in-flight job**, so every one of them would take ADR-007's generate-a-fresh-value degradation path
for the remainder of its life, silently detaching each from the request that started it. Reusing
`requestId` means in-flight jobs are correlated by their existing value from the first restart
onwards, with no migration and no dual-read.

### Alternatives considered

- **Add `JobManagerKeys.Params.CORRELATION_ID = "correlationId"` alongside `requestId`.** The
  tidier-looking option, and it makes the MDC key and the job-data key the same string. Rejected on
  the in-flight-jobs argument above, plus: two identifiers per job needing a documented
  relationship, a dual-read fallback in the aspect for the transition, and a decision about what
  `requestId` then means — which is "the same thing, historically".
- **Rename the constant to `CORRELATION_ID` while keeping the value `"requestId"`.** Rejected as
  actively misleading: a constant named `CORRELATION_ID` whose value is `"requestId"` is worse than
  either honest option. Javadoc on the existing name carries the meaning at zero risk.
- **Migrate in-flight rows** (a `UPDATE job SET job_data = jsonb_set(...)`). Rejected: `jobData` is
  another library's persisted column, this ticket ships no migration (AC/scope), and the payoff over
  reuse is nil.

### Consequences

- **Positive:** FR-007 – FR-009 need no new transport at all. The change is four seed-site edits,
  two literal-to-constant edits, and one aspect.
- **Positive:** in-flight jobs across the deployment boundary stay correlated.
- **Accepted:** the MDC key and the job-data key have different names for ever. Documented in one
  place; the alternative costs a correlation gap on every in-flight job.
- **Accepted:** `requestId` appears in the persisted `job_data` of historical rows with values that
  are unrelated fresh UUIDs. Pre-existing, unchanged, and not worth a migration.
- **Reversibility:** excellent — additive Javadoc plus call-site edits.

---

## ADR-004: One AOP aspect around `ExecutableTask.execute` restores MDC for all seven JobManager tasks, backed by an MDC-clearing `TaskDecorator` on the JobManager thread pool — no per-task try/finally

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43183 · **Resolves:** OQ-006
- **Artefacts:** `01-requirements.md` (FR-007, FR-012, FR-019, AC-013, AC-014, AC-016, AC-022, NFR-004, OQ-006, OQ-012) · `02-design.md` (§7, §9, §13)

### Context

The seven `@Task`-annotated `ExecutableTask` beans are invoked by `task-manager-service` **1.0.11**
(Stage 1 and `.claude/context/tech-stack.md` both say 1.0.10; `gradle.properties` says
`version.jobManager=1.0.11` — noted, immaterial). CDKS owns no code on the invocation path, so
Stage 1 correctly identified that there is no obvious central hook. Design decompiled the library
and found one, plus a hard constraint that eliminates two of Stage 1's four options:

- `TaskExecutor.run()` (library) resolves the bean from `TaskRegistry.getTask(name)` and calls
  `execute(...)`. It is submitted to `jobExecutorThreadPool`, a `ThreadPoolTaskExecutor` with
  `job.executor.core-pool-size:5`, `max-pool-size:10`, `queue-capacity:100`,
  `thread-name-prefix:job-executor-`. **A pooled platform-thread executor with reused threads and
  no MDC hygiene whatsoever.**
- **`TaskRegistry` is explicitly proxy-aware.** `autoRegisterTasks()` calls
  `org.springframework.aop.support.AopUtils.getTargetClass(bean)` and *then*
  `.getAnnotation(Task.class)`, storing the **proxy** in `taskProxyByNameMap` (its own log message
  reads `"Registering Work Task proxy [type={}], [name={}]"`). A Spring AOP proxy is a supported
  input to this library, not a workaround.
- **The same evidence rules out a plain decorator.** If a `BeanPostProcessor` wrapped each task bean
  in a hand-written delegating class, `AopUtils.getTargetClass(...)` would return the *decorator's*
  class, which carries no `@Task`, and `autoRegisterTasks()` would take its
  `"Skipping ExecutableTask without @Task annotation: {}"` branch at **debug** level. Every task
  would silently stop being dispatched. A base class fails for the same reason unless every subclass
  re-declares `@Task`, which it does — but a base class cannot restore MDC around a subclass
  override without each subclass calling `super`, i.e. the seven copy-pastes again.
- `spring-aop` 7.0.7 and `aspectjweaver` 1.9.25.1 are already on the runtime classpath
  (`org.aspectj:aspectjweaver` ← `spring-aspects` ← `spring-boot-starter-data-jpa`, confirmed by
  `gradlew dependencyInsight`), and `AopAutoConfiguration` registers annotation-driven auto-proxying
  when `org.aspectj.weaver.Advice` is present. **No new dependency.**
- `MdcCopyingTaskDecorator` does **not** help here and was never wired to: it is set only on
  `DiscoveryTriggerConfig.discoveryTriggerExecutor`, and it copies the *submitting* thread's MDC —
  which for a JobManager task is the library's poller thread, not the request that created the job.

### Decision

**1 — One `@Aspect`, `uk.gov.hmcts.cp.cdk.correlation.JobCorrelationAspect`,** `@Around` the same
join point DD-43182 uses:

```java
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)   // outermost: DD-43182's metrics aspect logs inside this scope
public class JobCorrelationAspect {

    @Around("execution(* uk.gov.hmcts.cp.cdk.jobmanager..*.execute(uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo))")
    public Object aroundExecute(final ProceedingJoinPoint pjp) throws Throwable {
        final ExecutionInfo info = (ExecutionInfo) pjp.getArgs()[0];
        try (var scope = CorrelationScope.fromJobData(info.getJobData())) {   // AutoCloseable
            return pjp.proceed();                                             // never altered
        }
    }
}
```

`CorrelationScope.fromJobData(...)` reads `JobManagerKeys.Params.REQUEST_ID`, runs it through
ADR-007's validation, generates a UUID if it is absent, blank or rejected (AC-016, NFR-004), puts it
in MDC under `CorrelationIds.MDC_KEY`, and additionally seeds `caseId`, `docId` and `transactionId`
from `CTX_CASE_ID_KEY`, `CTX_DOC_ID_KEY` and `CTX_RAG_TRANSACTION_ID` where present (Area E,
FR-012). `close()` restores the **prior** MDC map exactly — not `MDC.clear()`, not `MDC.remove` —
so the aspect is safe if it is ever nested or if the pool thread legitimately carries other context.

**Not conditional.** DD-43182 gates its aspect behind `cdk.metrics.enabled`; this one has no kill
switch, because a service that can be configured to stop correlating its own logs has the bug the
ticket is closing.

**Throws nothing, changes nothing.** `pjp.proceed()`'s return value and any thrown `Throwable` pass
through untouched — no `catch`, no swallow, no `ExecutionInfo` rewriting (NFR-004, and CLAUDE.md's
"no behaviour change introduced by the fix itself"). `CorrelationScope`'s own operations are MDC map
puts; they cannot fail in a way worth guarding.

**2 — Defence in depth: an MDC-clearing `TaskDecorator` on `jobExecutorThreadPool`.** A
`BeanPostProcessor` (`correlation/JobExecutorMdcBeanPostProcessor`) intercepts the bean named
`jobExecutorThreadPool` in `postProcessBeforeInitialization` and calls
`setTaskDecorator(runnable -> () -> { try { runnable.run(); } finally { MDC.clear(); } })`.
Verified safe: `ThreadPoolTaskExecutor$1.execute` reads the `taskDecorator` field **at submission
time**, so setting it before `afterPropertiesSet()` (and in fact at any point before first
submission) takes effect. The bean is `@Bean @ConditionalOnMissingBean(name = "jobExecutorThreadPool")`,
so replacing it wholesale is possible — deliberately **not** done, because that would mean copying
the library's eight `@Value` defaults into CDKS and letting them drift on the next library bump.

This is what makes AC-014 and FR-019 true for the JobManager pool *structurally*: even a future task
that writes MDC outside the aspect, or a library change that logs after `execute(...)` returns,
cannot leak onto the next job on that thread.

**3 — Explicitly not done: seven per-task try/finally blocks.** They would satisfy FR-007 and fail
its intent — task #8 will forget, and DD-43182 is already adding a second aspect at the same join
point, so the aspect infrastructure is arriving regardless.

**4 — Advice ordering with DD-43182 is fixed here, in whichever order the tickets land.**
`JobCorrelationAspect` is `@Order(Ordered.HIGHEST_PRECEDENCE)`; `TaskRetryMetricsAspect` must be
strictly lower (its design specifies no order, i.e. lowest precedence, which is already correct).
Correlation is therefore the outermost advice and the metrics aspect's throttled WARN lines carry
the correlation ID. **GATE-3: whichever ticket lands second must not re-derive the AOP
justification, and must assert the ordering in a test.**

### Alternatives considered

- **Seven per-task `try`/`finally` blocks.** Rejected: seven copies of leak-prevention logic, no
  compile-time defence for task #8, and 14 new places for a `finally` to be wrong. Stage 1 rated it
  "simple"; it is simple per site and fragile in aggregate.
- **`BeanPostProcessor` wrapping each task in a plain delegating decorator.** Rejected on the
  `AopUtils.getTargetClass` evidence above: it would silently unregister all seven tasks, and the
  failure surfaces only as a `debug` log line and jobs that never run. This is the single most
  dangerous option on Stage 1's list and it looks the safest.
- **`BeanPostProcessor` building a Spring AOP proxy via `ProxyFactory` + a `MethodInterceptor`.**
  Works, and avoids the AspectJ pointcut *string*. Rejected only because DD-43182 is landing an
  `@Aspect` at the identical join point in the same release train, and two proxying mechanisms for
  one join point is worse than one pointcut expression. If DD-43182 is dropped, this becomes the
  preferred form and the decision is a one-class swap.
- **An extension point in `task-manager-service`.** Investigated: there is none — no filter, no
  listener, no interceptor SPI. `TaskExecutor` is instantiated with `new` inside
  `JobExecutor.executeJob(...)` and is not a bean. A library change is out of this repository.
- **Replace the `jobExecutorThreadPool` bean with a CDKS-owned one carrying the decorator.**
  Rejected: duplicates eight `@Value`-bound library defaults with no mechanism to keep them in step.
- **Rely on the aspect alone, with no pool decorator.** Tempting (the aspect's `finally` restores
  exactly). Rejected because the aspect cannot cover MDC written by the *library* around
  `execute(...)`, or by a future task via a path the pointcut misses, and the whole point of FR-019
  is an assurance rather than a careful implementation.

### Consequences

- **Positive:** FR-007, AC-013, AC-014 and AC-016 are satisfied for all seven tasks and every future
  task by one class, and Area E's async half (AC-022) comes free from the same scope.
- **Positive:** the highest-risk pooled executor in the service gains MDC hygiene it has never had.
- **Accepted:** the seven `@Task` beans become AOP proxies (CGLIB, since `@Component` classes with a
  single interface still proxy by class under Boot's `spring.aop.proxy-target-class=true` default).
  Verified compatible with `TaskRegistry`. No CDKS code injects a task by concrete type — only three
  Javadoc mentions in `services/` — so no injection site breaks.
- **Accepted, and it invalidates a claim in DD-43182's design:** DD-43182 §10 states that
  `cdk.metrics.enabled=false` "removes the aspect bean and therefore the proxying entirely". Once
  DD-43183 lands, proxying is unconditional. DD-43182's design note must be corrected; nothing
  about its behaviour changes.
- **Accepted:** an AOP pointcut expression is a string, and a package rename of
  `uk.gov.hmcts.cp.cdk.jobmanager..*` would silently stop matching. Mitigated by a unit test that
  asserts each of the seven `@Task` beans is proxied and restores MDC — a test that fails loudly on
  a rename, unlike the pointcut.
- **Reversibility:** excellent. Delete two classes.

---

## ADR-005: `ErrorResponse.traceId` carries the canonical correlation ID unconditionally; the `Tracer` dependency and the silent `catch (Exception ignored)` are removed from `GlobalExceptionHandler`

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43183 · **Resolves:** OQ-007, OQ-008
- **Artefacts:** `01-requirements.md` (FR-010, FR-011, AC-018 – AC-021, NFR-004, OQ-007, OQ-008) · `02-design.md` (§8)

### Context

Stage 1's chain (`management.tracing.enabled: false` → `Tracer.NOOP` → `TraceContext.NOOP` →
`traceId() == ""`) is wrong at its first link, for the reasons in this file's preamble. The verified
behaviour of `GlobalExceptionHandler.traceId()` (`:36–43`) today is:

| Situation | Returned value |
|---|---|
| Exception inside the `DispatcherServlet`, i.e. every handler in this class in normal operation | a **real 32-hex OTel trace ID** — `ServerHttpObservationFilter` has an observation scope open, so `tracer.currentSpan()` is non-null |
| No span in scope (a unit test, a path outside the observation scope) | `null` — `Objects.requireNonNull` throws NPE, `catch (Exception ignored)` swallows it, the local stays `null` |
| Never | `""` |

So the AC's "populated with a non-null value" is not merely a weak oracle — it is testing for the
wrong failure. The real defect is that **the returned value is not findable**:

1. It belongs to a trace that is **never exported** (no OTLP exporter is created without an
   endpoint — ADR-006), so there is no trace backend to look it up in.
2. Whenever a client sends a bare `traceId` header, `TracingFilter` overwrites MDC `traceId` with the
   client's value, so the log lines and the response body disagree. And because `Slf4JEventListener`
   restores the real value on every span-scope close, MDC `traceId` flip-flops mid-request around
   each outbound call.
3. It does not exist on the hops the request fans out to. Nothing serialises a span context into
   `jobData`, tasks run on other threads and pods, and `RestClientFactoryConfig:114` uses the static
   `RestClient.builder()` with no `observationRegistry` — so **no outbound CDKS call carries
   `traceparent`** and no downstream span joins the trace.

AC-020's requirement — "search that one value, get every log line for that request" — is therefore
unachievable with a trace ID, in principle, not just in this configuration.

### Decision

**`ErrorResponse.traceId` is set to the canonical correlation ID (ADR-002), always, in every
environment, regardless of tracing or sampling state.** Option (a) of OQ-008.

```java
private String traceId() {
    return CorrelationIds.currentOrGenerate();   // MDC correlationId; UUID if genuinely absent
}
```

1. **The `Tracer` constructor dependency is removed** from `GlobalExceptionHandler`. It has no other
   use in the class.
2. **The `Objects.requireNonNull` and the `catch (Exception ignored)` block are removed.** There is
   no longer an operation that can throw, so there is nothing to swallow. This closes the
   swallowed-tracer-failure defect Stage 1 flagged in OQ-007 by deleting the code rather than
   logging around it, and it keeps the diff compliant with "no exception swallowing introduced by
   the fix" — the fix removes the only instance in this class.
3. **Non-blank is structural.** `RequestContextFilter` runs for every HTTP request and always leaves
   a non-blank `correlationId` in MDC, so the fallback branch is unreachable in production and
   exists only for unit tests and non-filtered paths. All six handlers share `base(...)`, so AC-021
   holds by construction rather than by six assertions — though Stage 4 should still assert all six.
4. **The AC oracle is restated** (OQ-007). AC-018/AC-019 must assert, for each handler:
   - `traceId` is **non-blank** (and not `""`), **and**
   - `traceId` **equals the `X-Correlation-Id` response header**, **and**
   - `traceId` equals the `correlationId` JSON field on the log lines emitted for that request.
   The third assertion is the one that actually tests AC-020 and the only one that would have caught
   today's defect. A `matches("[0-9a-f]{32}")`-style assertion must **not** be used: it would pass
   today, against the bug.
5. **Nothing is added to the API.** `ErrorResponse` keeps its field name and type;
   `api-cp-crime-caseadmin-case-document-knowledge` stays at 0.0.11 (AC-038).

### Alternatives considered

- **(b) Real OTel trace ID when tracing is on, correlation ID as fallback.** Rejected: it makes the
  field's meaning environment-dependent — a support engineer gets a 32-hex value in one environment
  and a UUID in another, for the same failure — and in the environments where it *is* a trace ID it
  is the value that provably does not reach the async hops (point 3 above). It also keeps the
  `Tracer` dependency and the swallow.
- **(c) Enable tracing everywhere so a genuine trace ID always exists.** Rejected, and ADR-006 shows
  the premise is already true in a way that does not help: a real trace ID *already* always exists.
  It still stops at CDKS's boundary and never enters `jobData`.
- **Add a `correlationId` field to `ErrorResponse` and leave `traceId` as the trace ID.** The most
  honest naming, and Design's preference on aesthetics alone. Rejected because it is an OpenAPI
  change, explicitly out of scope, requiring an `api-cp-crime-caseadmin-case-document-knowledge`
  bump and a consumer-coordination window — for a rename.
- **Serialise the field as absent when there is no value.** Moot under this decision: there is
  always a value.

### Consequences

- **Positive:** one value on the error response, identical to the `correlationId` field on every log
  line of that request — including the JobManager, scheduler and downstream-call lines. AC-020
  becomes achievable for the first time.
- **Positive:** `GlobalExceptionHandler` loses a dependency, a `requireNonNull`, and an empty catch
  block. Three fewer things.
- **Accepted, and it needs to be said out loud:** a field named `traceId` no longer contains a trace
  ID. `ErrorResponse.traceId` and `DiscoveryTriggerResponse.correlationId` will carry the same value
  under different names. **GATE-4: accept the naming mismatch, or fund the additive OpenAPI change
  (`ErrorResponse.correlationId`) as a follow-up.** Design's recommendation: accept now, raise the
  additive field as a follow-up ticket, and deprecate `traceId` in a later API version.
- **Accepted:** because the correlation ID may be client-supplied, two unrelated requests can share
  a `traceId` if a client reuses the header. That is what a correlation ID is for, and ADR-007 bounds
  what such a value may contain.
- **Accepted:** `GlobalExceptionHandlerTest` mocks `Tracer`, `Span` and `TraceContext` across seven
  methods; all of them change. Expected — they pin the behaviour being fixed.
- **Reversibility:** total. One method body.

---

## ADR-006: Migrate to the real Boot 4.0.6 tracing/OTLP property keys, delete both dead keys, and record that tracing cannot be switched off by property — only the exporter can

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43183 · **Resolves:** OQ-010, OQ-011
- **Artefacts:** `01-requirements.md` (FR-016 – FR-018, NFR-007, AC-027 – AC-031, OQ-010, OQ-011) · `02-design.md` (§2, §10)

### Context

Verified against the resolved classpath by reading `spring-configuration-metadata.json` /
`additional-spring-configuration-metadata.json` from the shipped jars, decompiling the condition
classes, and running the auto-configurations in a `SpringApplication`:

| Key in `application-server-management.yml` | Status in Boot 4.0.6 | Real replacement |
|---|---|---|
| `management.tracing.enabled` (`:34`) | **Does not exist.** Deprecated at level `error`; `TracingProperties` has no `enabled` field | `management.tracing.export.enabled` (default **`true`**) — but see below: it gates *exporters*, not the tracer |
| `management.otlp.tracing.enabled` (`:40`) | **Does not exist at all** — not even as a deprecated alias | `management.tracing.export.otlp.enabled` (default `true`) |
| `management.otlp.tracing.endpoint` (`:41`) | Deprecated at level `error` | `management.opentelemetry.tracing.export.otlp.endpoint` (default *unset*) |
| `management.otlp.metrics.export.enabled` (`:44`) | **Valid** | — |
| `management.otlp.metrics.export.url` (`:45`) | **Valid**; only the path is wrong | — |
| `management.tracing.sampling.probability` (`:36`) | **Valid**; Boot default `0.1`, CDKS sets `1.0` | — |

Behavioural findings that decide the shape of the fix:

- `OnEnabledTracingExportCondition` reads `management.tracing.export.<exporter>.enabled`, then
  `management.tracing.export.enabled`, then matches with *"tracing is enabled by default"*. It gates
  `OtlpTracingConfigurations$Exporters` only.
- `OtlpTracingConfigurations$ConnectionDetails` is `@ConditionalOnProperty("management.opentelemetry.tracing.export.otlp.endpoint")`
  and `Exporters` is `@ConditionalOnBean(OtlpTracingConnectionDetails)`. Verified live: **with no
  endpoint set, no `otlpHttpSpanExporter` bean is created**, whatever the enabled flags say. With the
  endpoint set and `management.tracing.export.otlp.enabled=false`, the connection-details bean
  appears but the exporter still does not.
- `OpenTelemetryTracingAutoConfiguration` has **no** tracing-enabled condition, so the tracer, the
  `SdkTracerProvider`, the sampler, the propagators and the `Slf4JEventListener` MDC population are
  all unconditional. **There is no property that disables span creation.** The only mechanisms are
  removing `spring-boot-starter-opentelemetry` from the build or
  `spring.autoconfigure.exclude`-ing the auto-configuration.

So today CDKS creates a real tracer, samples 100 % of spans, writes real trace IDs into MDC, and
exports nothing — and every property line that looks like it controls any of that is inert.

### Decision

Replace lines 33–45 of `src/main/resources/application-server-management.yml` with:

```yaml
  tracing:
    # NOTE: 'management.tracing.enabled' does NOT exist in Boot 4.0.6 (removed at deprecation
    # level 'error'). Span creation is unconditional while spring-boot-starter-opentelemetry is on
    # the classpath; only export is switchable. See adrs/DD-43183 ADR-006.
    sampling:
      probability: ${TRACING_SAMPLER_PROBABILITY:0.1}
    export:
      otlp:
        enabled: ${OTEL_TRACES_ENABLED:false}

  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_TRACES_URL:http://localhost:4318/v1/traces}

  otlp:
    metrics:
      export:
        enabled: ${OTEL_METRICS_ENABLED:false}
        url: ${OTEL_METRICS_URL:http://localhost:4318/v1/metrics}
```

1. **`management.tracing.enabled` is deleted**, with a comment in its place explaining why, so the
   next engineer does not re-add it. Deleting a dead key changes no behaviour.
2. **`management.otlp.tracing.*` is deleted** and replaced by the two real keys.
3. **`OTEL_TRACES_ENABLED` is independent of `OTEL_METRICS_ENABLED`** (AC-027, FR-016), and defaults
   to `false` so the current effective behaviour — no trace export — is preserved (AC-031).
4. **`/v1/traces` and `/v1/metrics`** — the OTLP/HTTP spec paths (FR-017, AC-029).
5. **The sampling default drops from `1.0` to Boot's `0.1`.** With no exporter, 1.0 costs only span
   recording; with an exporter it is 10× the span volume and cost, on a service whose hot path
   includes 180-second RAG calls. `TRACING_SAMPLER_PROBABILITY` still allows `1.0` in a non-production
   environment for AC-030's demonstration. **GATE-5: accept the sampling-default change, or keep
   `1.0` and take the production volume decision explicitly with platform/SRE.** Note that trace IDs
   remain available at any sampling rate — an unsampled OTel span still has a valid trace ID and
   `Slf4JEventListener` still populates MDC — so lowering the rate does not weaken log correlation.
6. **OQ-011 is answered by dissolving it.** This ticket does not enable tracing, because tracing is
   already on and cannot be turned off by property. AC-030's collector evidence needs only
   `OTEL_TRACES_ENABLED=true` plus an `OTEL_TRACES_URL` pointing at a collector, in one
   non-production environment. Platform/SRE own the collector endpoint; CDKS ships the properties.
   Nothing about `ErrorResponse.traceId` depends on it (ADR-005), so no AC is blocked on the
   platform conversation.
7. **AC-028 gets a real oracle, not a promise:** a new unit test walks every key in every
   `src/main/resources/application*.yml`, resolves it against the aggregated
   `META-INF/spring-configuration-metadata.json` on the test classpath, and fails on any
   `management.*` / `spring.*` key that is unknown or deprecated at level `error`. That is what
   catches the *next* dead key, which is the actual lesson of this ticket — three inert lines lived
   in this file through multiple releases with nothing to notice. Cheaper and permanent, unlike
   temporarily adding `spring-boot-properties-migrator` to CI.

### Alternatives considered

- **Add `spring-boot-properties-migrator` to the build temporarily.** It reports exactly these keys
  and would have caught all three. Rejected as the *primary* control: it is a temporary dependency
  whose removal is the moment the protection ends, it only warns at startup rather than failing a
  build, and it does not cover CDKS's own `cdk.*` keys. Recommended as a one-off local diagnostic
  while implementing, not as the deliverable.
- **Keep `management.tracing.enabled: false` "just in case".** Rejected: it is precisely the kind of
  line that makes a reader believe tracing is off when it is not. Dead config is worse than no
  config because it is load-bearing in people's heads.
- **Also bind `management.tracing.export.enabled` to an env var** as a global export switch.
  Rejected as redundant: OTLP is the only exporter on this classpath, so the otlp-specific key is
  the same switch with a clearer name and no second way to say the same thing.
- **Suppress span creation entirely via `spring.autoconfigure.exclude`** so that CDKS matches the
  intent the `management.tracing.enabled: false` line expressed. Rejected: it would delete the real
  `traceId` / `spanId` log fields that are the only correct trace correlation CDKS has, to honour a
  configuration line that was written under a mistaken belief.
- **Set the OTLP endpoint to empty by default** so the connection-details bean never appears.
  Rejected: the enabled flag is the documented switch, verified to work with the endpoint set, and
  an empty-string URL is a worse failure mode than a disabled exporter.

### Consequences

- **Positive:** the tracing configuration block finally means what it says, `OTEL_TRACES_ENABLED`
  works, and the collector demonstration is a two-variable change in one environment.
- **Positive:** the metadata-audit test makes this class of bug non-recurring across the whole
  configuration surface, not just the six lines this ticket touches.
- **Accepted:** default sampling drops to 0.1 (GATE-5). Span *recording* becomes 10× rarer; trace IDs
  and log correlation are unaffected.
- **Accepted:** no behaviour changes at all in any environment that sets neither `OTEL_*` variable —
  which is every environment today (AC-031).
- **Accepted:** the audit test will very likely surface further unbound keys elsewhere in
  `application-*.yml` (DD-43182's design already reports one:
  `cdk.jobmanager.retry.default` does not bind to `defaultRetry`). Those are separate defects; the
  test must be introduced with an explicit, documented allow-list of pre-existing findings so this
  ticket does not silently absorb them.
- **Reversibility:** total — configuration only, no code.

---

## ADR-007: Reject-and-regenerate an inbound correlation value that fails an allow-list and length check; the outbound interceptor becomes MDC-read-only

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43183 · **Resolves:** NFR-002, FR-005, FR-006
- **Artefacts:** `01-requirements.md` (FR-005, FR-006, NFR-001 – NFR-004, AC-004, AC-009 – AC-012) · `02-design.md` (§5, §6)

### Context

Two problems share one answer.

**The MDC-destruction bug.** `CorrelationIdInterceptor:24` does `MDC.put(MDC_KEY, cid)` with a fresh
UUID and `:28` does `MDC.remove(MDC_KEY)` in `finally` — remove, not restore. The inbound
correlation ID is not merely bypassed on the outbound call, it is **deleted for the remainder of the
request**: every log line after CDKS's first outbound HTTP call carries no `correlationId` field at
all. `RestClientFactoryConfig:117` attaches this interceptor to every `RestClient` unconditionally,
so it affects RAG, Hearing and Progression alike. The live blast radius includes
`DiscoverySchedulerController:53`, which reads `MDC.get("correlationId")` into a published API field
— safe today only because no outbound call happens to precede it on that thread.

**Log-injection safety (NFR-002).** The inbound value is attacker-controllable and goes straight
into MDC, from where `LogstashEncoder` writes it as a JSON field value. `logback-spring.xml` sets no
`includeMdcKeyNames`, so every MDC entry is emitted. The in-repo precedent is
`RagAnswerAsyncServiceImpl` (~`:99–103`), which sanitises a `transactionId` with
`.replace('\n','_').replace('\r','_')` before logging — verified still present. That precedent is
*sanitise-in-place*, and it is the right call there (the value is a downstream identifier CDKS must
not lose). It is the wrong call for an inbound header, for the reason in the decision below.

### Decision

**1 — A single validating resolver, `CorrelationIds.sanitise(String)`, applied to every externally
sourced value** — inbound headers and `jobData` alike:

| Rule | Value |
|---|---|
| Allowed characters | `A`–`Z`, `a`–`z`, `0`–`9`, `-`, `_`, `.`, `:` |
| Maximum length | **64** characters (a UUID is 36; a W3C `traceparent` is 55) |
| Minimum length | 1 (blank ⇒ absent) |
| On any violation | **reject the whole value and generate a fresh `UUID.randomUUID()`** |

The allow-list, not a CRLF blocklist, is the control. It excludes `\r`, `\n`, `\t`, every other ISO
control character, `"`, `{`, `}`, `\` and every multi-byte character, so no accepted value can split
or forge a JSON log record, break out of a JSON string, or smuggle ANSI escapes into a terminal
reading the log. Length is bounded separately because an unbounded accepted value is a
log-amplification vector even when every character is legal.

**2 — Reject-and-regenerate, not sanitise-in-place.** Deliberately diverging from the
`RagAnswerAsyncServiceImpl` precedent, and the reason matters: a sanitised value *silently differs*
from what the client sent, so the client's own correlation search fails while everything looks
healthy. A rejected value is replaced by a fresh, obviously-different UUID, so the mismatch is
visible the first time anyone compares. The RAG precedent stays as it is — there, the value is a
downstream identifier CDKS is required not to lose (CLAUDE.md's RAG-data rule), so mangling one
character beats discarding it.

**3 — Rejection is logged once, at WARN, without the offending value.** The log line carries the
header name, the value's length, and a reason code (`illegal-character` / `too-long`) — **never the
value itself**, because logging a rejected log-injection payload is the injection. A rejection never
fails the request (NFR-004); it always degrades to a generated ID (AC-004).

**4 — `CorrelationIdInterceptor` becomes MDC-read-only.** It writes no MDC key, so the destruction
bug cannot recur by regression:

```java
public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution ex) {
    final String cid = CorrelationIds.currentOrRandom();     // reads MDC; never writes it
    request.getHeaders().set(CorrelationIds.HEADER_CPP, cid);
    request.getHeaders().set(CorrelationIds.HEADER_X_CORRELATION_ID, cid);
    return ex.execute(request, body);                         // no try/finally at all
}
```

`HEADER` (`X-Request-ID`) and `MDC_KEY` are deleted from the class. FR-005 asked the interceptor to
*restore* the prior MDC value rather than remove it; this goes further and removes the reason to
restore anything, which is strictly safer and deletes the `finally` block rather than fixing it.

**5 — FR-006's "generated once per unit of work" is satisfied at the entry points, not in the
interceptor.** A small `AutoCloseable` `CorrelationScope` seeds MDC if absent and restores the prior
map on close. It is opened at every unit-of-work boundary:

| Entry point | Correlation source |
|---|---|
| Any HTTP request | `RequestContextFilter` — canonical header, alias, else generated (ADR-001) |
| JobManager task | `JobCorrelationAspect` — `jobData.requestId`, else generated (ADR-004) |
| `IntradayDiscoveryScheduler`, `NightlyDiscoveryScheduler` | generated per run — **neither carries any MDC today** |
| `StalledWorkMetricsRefreshJob` → `StalledWorkMetrics.refresh()` | already generates its own (`:100–113`); switched onto `CorrelationScope` for one implementation |
| Manual discovery trigger (`DiscoveryTriggerService`) | inherited from the request thread by the existing `MdcCopyingTaskDecorator` |

Because every real unit of work opens a scope, `currentOrRandom()`'s fresh-UUID branch is a
defensive last resort (a startup probe, a direct client construction in a test), and AC-011 /
AC-012's "all calls in one unit of work share one value" holds structurally.

### Alternatives considered

- **Sanitise in place (strip CR/LF, truncate to 64), matching the RAG precedent.** Rejected on the
  silent-divergence argument above. Truncation is worse than rejection twice over: it also
  manufactures collisions between distinct long values.
- **Reject the request with `400 Bad Request`.** Rejected: NFR-004 forbids a new failure mode, and
  failing a court-facing request because a caller's logging header contained a stray character is a
  catastrophic trade.
- **Keep the interceptor writing MDC but save-and-restore the prior value** (FR-005 as literally
  worded). Rejected: correct if the `finally` is correct, and the whole defect is a `finally` that
  was not. Removing the write removes the failure class.
- **A `ThreadLocal` / `ScopedValue` correlation holder instead of MDC** (OQ-004's alternative).
  Rejected: MDC *is* the required carrier — `LogstashEncoder` reads only MDC, and
  `DiscoverySchedulerController` reads only MDC. A second holder would need copying into MDC at every
  log site, and `ScopedValue` cannot be set across the servlet-filter/`RestClient` boundary without
  restructuring the request. `CorrelationScope` gives the try-with-resources ergonomics without a
  second source of truth.
- **Validate only inbound headers, trusting `jobData`.** Rejected: `jobData` is a persisted JSON
  document whose values originally came from inbound headers, so the same validation applies for one
  line of extra code.

### Consequences

- **Positive:** FR-005's defect is deleted rather than repaired; AC-010 becomes trivially true
  because nothing removes the key.
- **Positive:** one validation function guards every externally sourced correlation value, on the
  inbound, async and outbound paths.
- **Positive:** `DiscoveryTriggerResponse.correlationId` stops being accidentally correct.
- **Accepted:** a caller sending a correlation ID longer than 64 characters, or containing a legal
  character outside the allow-list (`/`, `+`, `=` in a base64 identifier, for instance), silently
  gets a generated ID and a WARN. If a real CPP caller does this, the fix is one character in the
  allow-list. Design chose a conservative list on purpose.
- **Accepted:** all four `CorrelationIdInterceptorTest` methods assert the behaviour being deleted
  (generated UUID, `MDC.get(...)` null after execution) and are rewritten.
- **Accepted:** two scheduler classes and `StalledWorkMetrics` gain a `CorrelationScope` — a small
  diff in files DD-43185 has just touched. Coordinate on merge order.
- **Reversibility:** excellent.

---

## ADR-008: Virtual threads stay disabled; MDC-leak assurance targets the pooled platform-thread executors, with the virtual-thread case covered as a forward-looking regression test only

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43183 · **Resolves:** OQ-012
- **Artefacts:** `01-requirements.md` (FR-019, FR-020, AC-032 – AC-034, Out-of-scope, OQ-012) · `02-design.md` (§13)

### Context

Confirmed: `application-other.yml:22` binds `spring.threads.virtual.enabled` to
`${VIRTUAL_THREADS:false}`, and `VIRTUAL_THREADS` is set nowhere — no compose file, no CI workflow,
no profile. Nothing runs on virtual threads.

Stage 1's own observation is the right one and Design endorses it: virtual threads *reduce* MDC-leak
risk, because there is one virtual thread per task and no pooling, so there is no reuse for context
to leak across. Writing this ticket's leak assurance against virtual threads would test the safest
configuration and leave the dangerous one untested. Design enumerated the actual reuse surfaces:

| Pooled executor | Threads | MDC hygiene today | After DD-43183 |
|---|---|---|---|
| `jobExecutorThreadPool` (`task-manager-service`) | `job-executor-*`, core 5 / max 10, queue 100 | **None** | `JobCorrelationAspect` restore + pool `TaskDecorator` (ADR-004) |
| Servlet container request threads | Tomcat pool | `RequestContextFilter`'s `MDC.clear()` in `finally` | Save-and-restore of the prior map (`02-design.md` §3) |
| `discoveryTriggerExecutor` (`DiscoveryTriggerConfig`) | configured pool | `MdcCopyingTaskDecorator` — copy in, `MDC.clear()` in `finally` | Unchanged |
| `ShedLockConfig.taskScheduler` | `scheduler-*`, poolSize 10 | Only what each job does for itself (`StalledWorkMetrics` does; the two discovery schedulers do nothing) | `CorrelationScope` at each scheduler entry point (ADR-007(5)) |

The highest-risk of these is the one nothing in the codebase has ever guarded: `job-executor-*`,
where a task that put MDC and did not remove it would leak into the next unrelated job — a different
case, on the same thread.

### Decision

**Option (b) of OQ-012. Do not enable virtual threads. Prove leak-safety where the risk is.**

1. `VIRTUAL_THREADS` keeps its `false` default and is set in no environment. This ticket makes no
   virtual-thread configuration change.
2. **FR-019 / AC-032 are tested against the pooled executors**, primarily `job-executor-*`: submit
   job A which sets `correlationId`, then job B on the same pool (pool size forced to 1 so reuse is
   guaranteed rather than likely), and assert B observes no MDC value from A — both when A returns
   normally and when A throws. This is the test that would fail without ADR-004, and it is the test
   FR-019 is actually asking for.
3. **FR-020 / AC-033 are covered as a cheap forward-looking regression test**, not a platform change:
   one Spring test context with `spring.threads.virtual.enabled=true` forced via
   `@TestPropertySource`, asserting cross-request MDC isolation. Its value is that it fails on the
   day someone flips the toggle *and* a leak exists — nothing more is claimed for it, and the design
   says so rather than implying broader coverage.
4. **AC-034 is preserved:** `RequestContextFilterTest.clearsMdcEvenIfChainThrowsException` continues
   to pass. The filter's `finally` changes from `MDC.clear()` to restoring the map captured at entry;
   on a fresh request thread that map is null or empty, so the assertion is unaffected. The test's
   *mock types* change (`ServletResponse` → `HttpServletResponse`) because the filter now sets a
   response header and becomes a `OncePerRequestFilter`. That is a construction-site edit, not an
   assertion change — the distinction DD-43185 ADR-006 drew for the same situation.

### Alternatives considered

- **Option (a): enable virtual threads and prove correlation survives.** Rejected. Enabling virtual
  threads changes the concurrency model of every request, every JDBC call through HikariCP, every
  `synchronized` block and every `ThreadLocal` in the service and its libraries. It is a
  substantial, separately-testable platform change wearing a correlation ticket's clothes, and it is
  explicitly out of scope in `01-requirements.md`.
- **Skip FR-020 entirely as untestable-until-enabled.** Rejected: the test is a `@TestPropertySource`
  line and one assertion, and it is the only artefact that will still be there when the toggle is
  flipped.
- **Assert leak-safety only for HTTP requests** (the literal reading of AC-032, "request A / request
  B"). Rejected as testing the already-safe path: Tomcat threads have been covered by
  `RequestContextFilter`'s `finally` since DD-43063, and `RequestContextFilterTest` already asserts
  it. The untested surface is `job-executor-*`.

### Consequences

- **Positive:** the assurance lands on the executor that has never had any, and AC-014 and AC-032
  are covered by the same test.
- **Positive:** no production concurrency change, so no new risk from a ticket about logging.
- **Accepted:** AC-033 is a low-value test by construction, kept for its regression value. Said
  plainly so Stage 4 does not over-invest in it.
- **Accepted:** the virtual-thread question returns whenever someone proposes flipping the toggle,
  with this ADR as the record that correlation handling was designed not to care.
- **Reversibility:** not applicable — nothing is enabled.
