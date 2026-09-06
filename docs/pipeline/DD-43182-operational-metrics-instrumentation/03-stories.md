# User Stories: Operational Metrics Instrumentation (Micrometer)

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43182.** Stage 1 (`01-requirements.md`) and Stage 2 (`02-design.md` +
> `../adrs/DD-43182-operational-metrics-instrumentation.md`, all ten ADRs `Accepted`, all six GATE
> items in design §14 accepted) are both approved. Real sub-tickets `DD-43267`–`DD-43273` were
> created and linked to the parent epic on 2026-09-04, satisfying CLAUDE.md's hard rule that every
> story needs a linked ticket before the test stage.
>
> **Amended 2026-09-04 at the Stage-4 gate — read before picking up any story.** Fourteen decisions
> (OQ-022 – OQ-037) were taken and are applied below. The one that changes scope is **OQ-022**:
> **`cdk_task_retry_exhausted_total` is descoped (Story 6) and `outcome=timed_out` is descoped with
> it (Story 5)** — both were gated on a state a JobManager task execution can never observe. The
> plain retry counter `cdk_task_retry_total` is **unaffected**; only exhaustion *detection* is
> descoped, not retry counting. Reasoning, evidence, the rejected workaround and the
> `task-manager-service` follow-up are in **ADR-011**; `02-design.md` §15 indexes all fourteen
> decisions against the sections they change. **GATE-2 is withdrawn.**
>
> Acceptance criteria below are **derived from, not duplicated verbatim from**, `01-requirements.md`'s
> AC-001–AC-030, rescoped to each story's slice and updated for every accepted Stage-2 gate decision:
> ~~the redefined `timed_out` (GATE-2)~~ (**withdrawn 2026-09-04**), the fifth `outcome=error` value
> (GATE-1), the `retry_policy` tag (GATE-3), the `cdk_http_pool_connections_leased` alias (GATE-4),
> the extra `GenerateAnswerForQueryTask` abandonment-path counting (GATE-5), and the re-scoped AC-024
> series/scrape-time ceiling (GATE-6). Full ADR text and rationale live in
> `../adrs/DD-43182-operational-metrics-instrumentation.md` and are not reopened here — no story below
> raises a new ADR.
>
> **Seven stories**, reconciled against `02-design.md`'s five capability areas (A(i)/A(ii) split out,
> as the design itself splits the ingestion phase counter from the ingestion duration timer into two
> mechanisms sharing one class). Requirements' Stage-1 preview proposed a separate Story 6 (retry
> counter) and Story 7 (retry-exhaustion counter); Design's ADR-006 found **one** mechanism —
> `TaskRetryMetricsAspect` plus the shared `TaskRetryDecision` predicate — computes both counters at
> the same call site from the same predicate, so splitting them into two stories would mean two PRs
> editing the same aspect class for no independently-shippable value. They were combined into Story 6
> below. **After OQ-022 there is only one counter left in Story 6 anyway**, which makes the
> combination moot rather than wrong — the story stays as one.
>
> **Cross-ticket coordination with DD-43183 (also at Stage 3, in parallel).** DD-43183 places a
> second, non-optional `@Aspect` (`JobCorrelationAspect`, MDC restoration, `@Order(Ordered.HIGHEST_PRECEDENCE)`)
> on the **exact same join point** this ticket's `TaskRetryMetricsAspect` advises —
> `execution(* uk.gov.hmcts.cp.cdk.jobmanager..*.execute(uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo))`.
> Both tickets' Stage-2 designs accept the same resolution (DD-43182 ADR-006 §7's note; DD-43183
> ADR-004 point 4, "GATE-3" there): `JobCorrelationAspect` is ordered outermost so a task's
> correlation ID is present in `TaskRetryMetricsAspect`'s own throttled WARN log line, and
> `TaskRetryMetricsAspect` is left at Spring AOP's default (lowest) precedence — no `@Order`
> annotation is added to it. **This ticket's own design's claim that
> `cdk.metrics.enabled=false` "removes the aspect bean and therefore the proxying entirely" is
> corrected by DD-43183 ADR-004's Consequences: once DD-43183 ships, the seven `@Task` beans stay
> CGLIB-proxied regardless of `cdk.metrics.enabled`, because Spring merges same-bean aspects into one
> proxy.** Story 6 below carries this coordination note explicitly and its test plan includes an
> aspect-ordering test, per both designs' instruction that "whichever of DD-43182/DD-43183 lands
> second updates this paragraph and adds the ordering test."

**Standard DoD (every story, per `hmcts-standards.md` and this repo's CLAUDE.md hard rules)**: code
reviewed & approved · all ACs covered by automated tests (unit + integration, Given/When/Then) ·
`gradle clean build` (incl. `integration`) passes · PMD/JaCoCo green at existing thresholds ·
CodeQL and secrets-scanner clean · no PII/case content/court reference/`CJSCPPUID` in code, config,
tests or fixtures · deployed to and verified on sandbox · Jira ticket updated with test evidence ·
`claude-generated` + `needs-review` labels applied, linked to parent epic DD-43182 · story has its
own linked Jira sub-ticket (`DD-43267`–`DD-43273`, below).

---

## Story 1 — Document-ingestion phase counter
**Jira: `DD-43267`**

As a **production support engineer**,
I want **a counter that increments once for every persisted `CaseDocument.ingestionPhase` transition,
tagged with the phase reached and a bounded `source` value**,
so that **I can see ingestion throughput and where documents currently sit in the pipeline, from
`/actuator/prometheus`, without querying the database**.

### Background
Design §3 / ADR-009 (accepted). `cdk.document.ingestion.phase` (renders as
`cdk_document_ingestion_phase_total`) is incremented by an explicit call immediately after each of
the **three** `saveAndFlush` call sites that actually persist a phase — `IdpcAvailabilityService`,
`RetrieveMaterialAndUploadTask` and `CheckIngestionStatusForAllDefendantsTask`. Deliberately unlike
DD-43185 ADR-004's "register unreachable values anyway" ruling: only the five phases the codebase can
actually write are pre-registered (`WAITING_FOR_UPLOAD`, `UPLOADED`, `INGESTED`, `FAILED`,
`EXCEEDED_FILE_SIZE_LIMIT`) — `UPLOADING`, `INGESTING` and `NOT_FOUND` are excluded, because a
transition counter for a phase nothing writes is not a missed-failure risk the way a stall gauge's
missing series would be. `source` is not read from the free-text `case_documents.source` column
as-is; it is membership-checked against a fixed allow-list (`IDPC` today, `unknown` otherwise), so the
tag is bounded by construction rather than by the column's current single value.

### Acceptance criteria
- [ ] AC-001: Given `CaseDocument.ingestionPhase` is persisted at one of the three write sites, when the `saveAndFlush` commits, then `cdk_document_ingestion_phase_total{phase=<the written phase>}` increments by exactly 1 — never on a read, and never once per enclosing task invocation (`CheckIngestionStatusForAllDefendantsTask` polls repeatedly but only calls the write method on a terminal answer).
- [ ] AC-002: `cdk_document_ingestion_phase_total` carries exactly the tags `phase` and `source`, plus the existing `service`/`cluster`/`region` common tags; `phase` values are `DocumentIngestionPhase` constants verbatim, drawn only from the **five reachable** values (ADR-009(4)) — `UPLOADING`, `INGESTING` and `NOT_FOUND` are not registered and cannot appear.
- [ ] AC-003: `source` is never read through from the `case_documents.source` column as free text; it is checked against a fixed allow-list and resolves to `IDPC` or `unknown` only — a test asserts no other value can ever be emitted, and that the series shape is stable even if the column later gains an unanticipated value.
- [ ] AC-004: No tag key or tag value on this meter contains a case id, document id, defendant id, material id, court reference or any other case identifier — asserted by a test that every emitted tag value is a member of a fixed, enumerated set.
- [ ] AC-005: All five `phase` × `source="IDPC"` series exist at value `0` immediately after `IngestionMetrics` is constructed (before any document has moved), so `increase(...) == 0` has a series to evaluate against.
- [ ] AC-006: A metric-recording failure inside `recordPhaseTransition(...)` (registry throwing, allow-list lookup throwing) is contained — the surrounding `saveAndFlush` and its calling method complete exactly as they would without instrumentation, and the original business outcome is unaffected.

### NFR links
- NFR-001 (Data protection): `phase` and `source` are both fixed, closed sets; nothing derived from request or job data.
- NFR-002 (Cardinality): registers 5 series at construction, ≤5 worst case (the `source="unknown"` fallback shares the same 5 `phase` values) — part of the ticket's **232**-series worst case computed in `02-design.md` §12 (243 before ADR-011).
- NFR-004 (Availability): a failing registration or tag lookup must not fail the write path that persists the phase.
- NFR-005 (Backward compatibility): no change to `CaseDocument`, its repository, or any existing phase-write call site's behaviour — the metric call is additive, after the existing `saveAndFlush`.
- NFR-008 (Naming consistency): extends `CdkMeters` with the new meter name, `TAG_SOURCE` and the five new `PHASE_*`/`SOURCE_*` constants (some already exist from DD-43185).

### Out of scope for this story
- The ingestion **duration** timer (`cdk_document_ingestion_duration_seconds`) — Story 2, which shares this story's three call sites and its `IngestionMetrics` class but is a separately testable AC set.
- Populating `UPLOADING`/`INGESTING` in production write paths, or registering them as `phase` values — a pre-existing phase-model defect (DD-43185's recorded follow-up), explicitly not fixed here.
- A `trigger="manual"|"scheduled"` dimension on `source` — a documented follow-up (ADR-009(3)), not built in this ticket.
- Any change to `CaseDocument`, its repository, or the three write sites' existing business logic beyond the one added metric call.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `IngestionMetricsTest` for series pre-registration, allow-list mapping, one-increment-per-write-site; integration: `cdk_document_ingestion_phase_total` visible with its full tag set on `/actuator/prometheus` in the compose stack).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **Creates or extends shared infrastructure, depending on delivery order.** `metrics/MetricsSafety`
  (the failure-containment helper, ADR-010) and `config/MetricsProperties` +
  `metrics/CdkMetricsConfig` (the `cdk.metrics.enabled` kill switch, ADR-010(4)) are used by every
  other DD-43182 story (2, 3, 5, 6). **Whichever of Stories 1, 2, 3, 5 or 6 is picked up first creates
  these three classes; every later story extends/reuses them rather than recreating them.** Sprint
  planning should assign this explicitly so the second-delivered story's PR is scoped as "reuse
  `MetricsSafety`" rather than "create `MetricsSafety`". `metrics/CdkMeters` is likewise already
  present (from DD-43185) and is **extended**, never recreated, by whichever DD-43182 story lands
  first.
- **OQ-032, decided 2026-09-04:** `CdkMeters.PHASE_UPLOADING` and `PHASE_INGESTING` already exist for
  DD-43185's stall gauge and must not be used by this counter. **A test-only negative control is the
  accepted guard** — Stage-4 Scenario 1.7's `containsExactlyInAnyOrder` on the registered `phase`
  tag-value set, which fails if either constant is ever picked up. **No structural restructuring of
  `CdkMeters`** (no nested `Phases.Reachable`/`Phases.Monitored` split) is required or wanted: it
  would churn DD-43185's shipped constants for a mistake a one-line assertion already catches.
- **Shares its implementation class and call sites with Story 2.** Both live in `metrics/IngestionMetrics`
  and both are called from the same three sites (`IdpcAvailabilityService`,
  `RetrieveMaterialAndUploadTask`, `CheckIngestionStatusForAllDefendantsTask`); the third site calls
  both methods in the same edit (design §3–§4). Not a hard AC-level dependency — this story's ACs are
  independently testable — but sequencing the two PRs (rather than working them in parallel) avoids a
  merge conflict on the same three files.
- Jira sub-ticket: `DD-43267`.

---

## Story 2 — Ingestion duration timer (end-to-end)
**Jira: `DD-43268`**
**Shares implementation class/call sites with Story 1 — see Story 1's Notes. No hard AC-level dependency.**

As a **production support engineer**,
I want **a histogram of how long a document's ingestion takes, from CDKS first learning about it to
CDKS learning RAG's terminal answer, with server-side p50/p95/p99 queryable in Prometheus**,
so that **I can see whether ingestion latency is degrading before it becomes a support ticket, without
correlating log timestamps by hand across pods**.

### Background
Design §4 / ADR-002 (accepted). The ticket's own scenario — "a document enters phase `UPLOADING`" as
the timer's start — describes an event that is **never persisted** (confirmed at both Stage 1 and
Design). The accepted start anchor is `case_documents.created_at` (written once, never re-stamped);
the accepted mechanism is **`Timer.record(Duration)` computed from two persisted timestamps at the
terminal write** — `created_at` → the terminal `ingestion_phase_at` — **never an in-process
`Timer.Sample`**, because the start and terminal writes happen in different JobManager tasks,
potentially different pods, minutes to hours apart, with no shared in-memory state to span them. Three
terminal stops are recorded, not two: `INGESTED`, `FAILED`, **and** `EXCEEDED_FILE_SIZE_LIMIT` (a real
terminal phase the ticket's own scenario omits). Buckets are eight explicit SLO boundaries
(`15s, 30s, 1m, 2m, 5m, 10m, 30m, 1h`), declared in code and overridable via
`management.metrics.distribution.slo.cdk.document.ingestion.duration` — `percentiles-histogram` stays
off and no client-side percentiles are configured, because only server-side `_bucket` series aggregate
correctly across pods.

### Acceptance criteria
- [ ] AC-001: Given a document reaches one of the three terminal phases (`INGESTED`, `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT`), when the terminal `saveAndFlush` commits, then `cdk_document_ingestion_duration_seconds{phase=<terminal phase>}` records one observation equal to `Duration.between(created_at, ingestion_phase_at)` on that same loaded entity — computed from persisted timestamps, never from an in-process timer/sample.
- [ ] AC-002: A document that has not yet reached a terminal phase (including one currently in `WAITING_FOR_UPLOAD` or `UPLOADED`) contributes **no** observation — the timer's `_count` reflects completed ingestions only, and this is documented as complementary to, not a replacement for, DD-43185's `cdk_documents_stalled{phase="UPLOADED"}` (which detects exactly the population this timer cannot see).
- [ ] AC-003: `cdk_document_ingestion_duration_seconds` publishes `_bucket` series on `/actuator/prometheus` for all eight configured SLO boundaries plus `le="+Inf"`, per `phase` — sufficient for `histogram_quantile` to return p50, p95 and p99 server-side; an integration test asserts the bucket boundaries are actually present (catching `management.metrics.distribution.*` being silently inert on this Spring Boot version, were that ever to happen).
- [ ] AC-004: A negative computed duration (possible from cross-pod clock skew between the two write sites) is clamped to `Duration.ZERO` before being recorded, and a throttled WARN is logged when the clamp fires — the clamp does not corrupt `_sum`.
- [ ] AC-005: The SLO boundaries are declared in code from `CdkMeters` constants and are overridable at runtime via `management.metrics.distribution.slo.cdk.document.ingestion.duration`, without a rebuild.
- [ ] AC-006: A metric-recording failure in the duration computation (e.g. an unexpectedly null `created_at`) is contained — the terminal phase write and its calling task complete exactly as they would without instrumentation.

### NFR links
- NFR-001 (Data protection): the only tag is `phase`, a fixed three-value enumeration.
- NFR-002 (Cardinality): 12 series per phase (9 buckets incl. `+Inf`, count, sum, max) × 3 phases = 36 series — the largest single contribution to the ticket's **232**-series worst case (243 before ADR-011), computed explicitly in `02-design.md` §12.
- NFR-003 (Performance/isolation): nothing is computed on scrape; the duration is computed once, at the terminal write, from already-loaded entity fields.
- NFR-004 (Availability): a throwing duration computation must not fail the terminal phase write.
- NFR-009 (Configurability): SLO buckets are configurable via the documented `management.metrics.distribution.slo.*` key, consistent with the repo's existing configuration convention.

### Out of scope for this story
- The phase-transition counter itself — Story 1 (shares the same class and call sites).
- An answer-generation duration timer — confirmed out of scope for this ticket (OQ-021/ADR-007(7)): unlike this timer, there is no persisted answer-generation start timestamp, so it is not a cheap analogue and needs its own ticket.
- Splitting this timer into "our leg" (`created_at`→`UPLOADED`) and "RAG's leg" (`UPLOADED`→terminal) — a recorded, nearly-free follow-up (ADR-002), not built here because the ticket does not ask for it.
- Repairing the `UPLOADING`/`INGESTING` phase-model defect that makes a `Timer.Sample` impossible in the first place.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `IngestionMetricsTest` for the three-terminal-stop mapping, the clock-skew clamp, and non-terminal phases recording nothing; integration: `_bucket` series with all eight SLO boundaries present on `/actuator/prometheus`).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **This metric is systematically success-biased, by design, and must be read alongside DD-43185's
  `cdk_documents_stalled{phase="UPLOADED"}`** (ADR-002(5)) — a fact the story's own tests and the
  `CdkMeters` Javadoc must both state, not just this document.
- **Shares its implementation class and call sites with Story 1** — see Story 1's Notes for the
  sequencing caution (not a hard AC dependency).
- The `management.metrics.distribution.slo` YAML block and the eight SLO `Duration` constants are new
  to this repo on Spring Boot 4.0.5 — AC-003's integration test is the one that catches the property
  path being inert, not a unit test.
- **OQ-033 — a verification task for this story's implementer, not a design question (2026-09-04).**
  Whether Boot's `PropertiesMeterFilter` **replaces** or **unions** an explicit
  `management.metrics.distribution.slo.cdk.document.ingestion.duration` with the code-declared
  `serviceLevelObjectives(...)` is **not resolvable from documents**, and the expected bucket set
  differs accordingly (`{1s, 2s, +Inf}` versus `{1s, 2s, 15s, …, 1h, +Inf}`). **Verify the actual
  behaviour against the running app on Spring Boot 4.0.5 before writing Stage-4 Scenario 2.10's
  assertion; do not write it against a guess.** It does not block AC-005 or this story's start —
  the code default is authoritative either way, and AC-003's integration assertion catches an inert
  property path regardless.
- Jira sub-ticket: `DD-43268`.

---

## Story 3 — Outbound dependency call timer
**Jira: `DD-43269`**

As a **production support engineer**,
I want **every call to RAG, Progression, Hearing and Azure Blob timed and outcome-tagged, on both the
success and the failure path, without altering what any client throws**,
so that **I can tell "RAG is slow" from "RAG is erroring" from "Progression timed out", per call type,
instead of grepping logs across four different client classes**.

### Background
Design §5 / ADR-003 (accepted) / ADR-004 (accepted). `cdk.external.call.duration` (renders as
`cdk_external_call_duration_seconds`) wraps all eleven live outbound call sites through one helper,
`metrics/ExternalCallMetrics`, timed with `System.nanoTime()` and recorded on both the return and the
throw path. `outcome` is derived by **walking the exception cause chain** at the recording site — the
finding that unblocks the ticket's own contradiction: `RagClientException` wraps both 4xx/5xx HTTP
errors and timeouts identically by type, so classifying by type alone would make `client_error` and
`timeout` permanently unreachable for RAG. Both throw sites preserve the original exception as the
*cause*, so the cause chain already carries the answer; **`RagClientException` itself is not modified
in any way** — no subclass, no field, no constructor change — and the same instance is always
rethrown, so type/message/cause/stack trace are identical with and without instrumentation. `outcome`
has **five** values, not the ticket's four: `success`, `client_error`, `server_error`, `timeout` and a
new `error` for genuinely status-less failures (a JSON parse failure, or Azure Blob's status-less
`IllegalStateException` on an aborted copy) — folding these into `server_error` would make that value
mean "5xx, or our own bug, or a failed copy", which is a tag that lies. `operation` is eleven
CDKS-invented, lowercase kebab-case literal constants — never a method name, a URI, or an OpenAPI
path — so a path variable (a RAG document reference or transaction id) can never become a tag value.

### Acceptance criteria
- [ ] AC-001: Given a call to any of the eleven instrumented operations across `rag`, `progression`, `hearing` or `azure_blob` completes normally, then `cdk_external_call_duration_seconds` records one observation with `outcome=success` and the correct `dependency`/`operation` tags, and the returned response object is passed back to the caller untouched — no field inspected, copied, mapped or dropped (protects `doc_id`, `llm_input`, `llmResponse`, `documentChunks`, `transactionId`, status).
- [ ] AC-002: Given the same call throws, then exactly one observation is still recorded with a classified `outcome`, and the **same exception instance** propagates to the caller unchanged in type, message, cause and stack trace — asserted for all four RAG client classes (including the two the ticket does not name, `ApimDocumentIngestionStatusClient` and `RagAnswerServiceImpl`), `HearingClientImpl`, `ProgressionClientImpl` and `AzureBlobStorageService`.
- [ ] AC-003: `outcome` classification walks the exception cause chain (depth-bounded, cycle-guarded) and resolves to exactly one of `success`, `client_error` (4xx), `server_error` (5xx), `timeout` (`SocketTimeoutException`/`ConnectTimeoutException`/`ConnectionRequestTimeoutException`/`TimeoutException` anywhere in the chain), or `error` (anything else with no HTTP status and not a timeout) — a `RagClientException` is classified by its cause, never blanket-mapped to `server_error`.
- [ ] AC-004: `dependency` takes exactly one of `rag`, `progression`, `hearing`, `azure_blob`; `operation` takes one of the eleven fixed `CdkMeters` constants, each passed as a literal argument at its call site — never derived from a method name, class name, or URI — so a path variable can never appear in a tag value. `StorageService.exists` and `getBlobSize` (no production call site) are not instrumented and get no `operation` value.
- [ ] AC-005: Given a downstream call exceeds its configured response timeout (180 s for RAG, 15 s for Hearing/Progression, 3 s on connect for all), then `outcome=timeout` is recorded, distinguishable from `server_error`; for `dependency=azure_blob`, `outcome=timeout` specifically means the `cp.cdk.storage.copy-timeout-seconds` (default 120 s) copy poll was exceeded, recorded through an explicit-outcome entry point inside `copyFromUrl` because that method's existing timeout path discards the identifying `TimeoutException` cause before it reaches an outside classifier. **Coverage tier, decided 2026-09-04 (OQ-026, OQ-027): this AC is closed at the *unit tier only*, and the gap is stated rather than the AC being quietly downgraded.** No compose read-timeout override is added — `CP_CDK_RAG_READ_TIMEOUT_MS` is 180 000 ms (a 180-second test is not shippable) and `CP_CDK_CQRS_READ_TIMEOUT_MS` is shared by every Hearing and Progression live test in the same single-app stack; and Azurite cannot be made to stall a `copyFromUrl` poll past 120 s. **What remains uncovered is the end-to-end wiring only** — that a real read timeout surfaces as a `ResourceAccessException(SocketTimeoutException)` reaching `OutcomeClassifier`. Classification itself is fully covered from real exception shapes. A seam (a dedicated WireMock path with a per-dependency override, or a second app container) is additive if ever funded.
- [ ] AC-006: A metric-recording failure (a pathological cause chain, a registry error) is contained and never affects the business call — the business result or the business exception propagates exactly as it would without instrumentation.

### NFR links
- NFR-001 (Data protection): `dependency`, `operation` and `outcome` are all fixed, closed, compile-time sets; `operation` is structurally incapable of carrying a path variable.
- NFR-002 (Cardinality): 11 `(dependency, operation)` pairs × 5 `outcome` values, count+sum+max only (no buckets) = 33 registered / 165 worst-case series — the second-largest contribution to the ticket's **232**-series total (243 before ADR-011).
- NFR-004 (Availability): the recording wrapper never affects the business call's exception propagation or return value.
- NFR-006 (RAG data preservation): **merge-blocking.** A dedicated parity test per RAG client asserts every response field (`doc_id`, `llm_input`, `llmResponse`, `documentChunks`, `transactionId`, status) is byte-identical with and without instrumentation.
- NFR-008 (Naming consistency): extends `CdkMeters` with the timer name, `TAG_DEPENDENCY`, `TAG_OPERATION`, the eleven `operation` constants, and the fifth `outcome=error` constant.

### Out of scope for this story
- The HTTP connection-pool gauge — Story 4 (different mechanism, different class, over the shared `PoolingHttpClientConnectionManager`).
- Fixing the discarded `TimeoutException` cause in `AzureBlobStorageService.copyFromUrl` (a one-word tidy-up, flagged as a separate ticket — FR-006 forbids altering any client's exception contract in this ticket).
- Fixing the `RestClientFactory.build(...)` shared-connection-manager mutation (OQ-015) — confirmed latent, flagged as a separate defect ticket; this timer's `outcome` is derived from the observed exception and is unaffected either way.
- Switching any client to the auto-configured `RestClient.Builder`, or adding a fourth `ClientHttpRequestInterceptor` — both mechanisms were evaluated and rejected at Design (ADR-003) on verified facts, not preference.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `OutcomeClassifierTest` against real exception shapes for all five outcomes including a cyclic cause chain; `ExternalCallMetricsTest` for the return/throw paths and exception-instance identity; extended client/storage unit tests plus the NFR-006 parity test, merge-blocking; integration: `ExternalCallMetricsHttpLiveTest` driving WireMock stubs to 200/404/503/delayed-timeout and asserting the right `{dependency,operation,outcome}` series increments).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence, including the NFR-006 parity evidence explicitly.

### Notes / open questions
- **GATE-1 (accepted at the Stage-2 gate):** the fifth `outcome=error` value is a widening of the
  ticket's stated four-value enumeration. Already accepted; restated here so the sub-ticket carries
  the decision forward without re-litigating it.
- Eleven production classes across `clients/rag`, `clients/hearing`, `clients/progression` and
  `storage/` each gain one constructor parameter — a compile-level edit to their existing unit tests,
  the same precedent DD-43185 ADR-006 accepted for its two schedulers.
- **OQ-025, decided 2026-09-04:** `OutcomeClassifier`'s cause-chain walk is depth-bounded at 5
  layers, and **at and beyond the bound the outcome is the generic `error`**. Stated explicitly in
  the classifier's Javadoc, with the accepted consequence: a status-bearing cause deeper than five
  layers is mis-tagged `error` rather than `client_error`/`server_error`. The bound is deep enough —
  CDKS's own deepest chain is depth 3
  (`RagClientException → ResourceAccessException → SocketTimeoutException`) — and `error` is the
  right degradation: "we could not tell", never a wrong positive claim.
- **OQ-036, decided 2026-09-04 (and already specified):** the **new** `RagAnswerServiceImplTest`
  drives `RagAnswerServiceImpl` as a **direct outbound client** — calling its methods directly — not
  through simulated MVC/web requests. Both `RagAnswer*ServiceImpl` are `@RestController`s *and*
  outbound clients, and driving them through MVC would measure CDKS's inbound surface instead of its
  outbound call. Applies to both the timing (Scenario 3.7) and NFR-006 parity (Scenario 3.8) cases.
- **Shared-infrastructure note** — see Story 1's Notes: this story is one of the five (1, 2, 3, 5, 6)
  that can create `metrics/MetricsSafety` / `config/MetricsProperties` / `metrics/CdkMetricsConfig` if
  delivered first, or reuse them if delivered after another of those five.
- Jira sub-ticket: `DD-43269`.

---

## Story 4 — HTTP connection-pool visibility
**Jira: `DD-43270`**
**No dependency on any other DD-43182 story — the smallest, lowest-risk story in this set; design
explicitly notes it could ship first, alone.**

As a **production support engineer**,
I want **the shared Apache HttpClient connection pool's leased, available, pending and maximum
connection counts published on `/actuator/prometheus`**,
so that **I can see pool exhaustion approaching before it causes request failures, instead of
diagnosing it after the fact from timeout logs**.

### Background
Design §6 / ADR-008 (accepted). One new `@Configuration` class, `metrics/HttpPoolMetricsConfig`,
registers Micrometer's own `PoolingHttpClientConnectionManagerMetricsBinder` — already on the
classpath, not a new dependency — over the existing shared `PoolingHttpClientConnectionManager` bean
(`setMaxConnTotal(200)`, `setMaxConnPerRoute(50)`, `setConnectionManagerShared(true)`). This publishes
four maintained series under `httpcomponents_httpclient_pool_*` (leased, available, pending, total
max, per-route max) — the ceiling as well as the leased count, which a bare "leased" gauge could not
express. Because the ticket names `cdk_http_pool_connections_leased` specifically, and renaming a
metric after alert rules exist elsewhere is a coordinated cross-repository change, one additional thin
alias gauge is also registered reading the same in-memory struct, so the two names can never disagree.

### Acceptance criteria
- [ ] AC-001: All five pool series are present on `/actuator/prometheus`: `httpcomponents_httpclient_pool_total_max`, `httpcomponents_httpclient_pool_total_connections{state="available"}`, `httpcomponents_httpclient_pool_total_connections{state="leased"}`, `httpcomponents_httpclient_pool_total_pending`, `httpcomponents_httpclient_pool_route_max_default`, all tagged `httpclient="cdk"`.
- [ ] AC-002 *(re-scoped per OQ-029, decided 2026-09-04)*: `cdk_http_pool_connections_leased` is also present and reads the identical in-memory leased count as the binder's own `state="leased"` series. **Agreement is proven two ways, neither of them by racing concurrent requests:** (a) a **single-snapshot equality check at idle** — both values parsed from **one** `/actuator/prometheus` body and asserted equal; and (b) a **structural/unit-level proof that they cannot disagree by construction** — both gauges are lambdas over the *same* `ConnPoolControl.getTotalStats()` struct on the *same* connection-manager bean, and the alias is registered with `strongReference(true)` so it cannot be collected and silently stop reporting. The original "agree at all times, asserted under concurrent load" wording is withdrawn: two gauges are sampled at two different instants even within one scrape, so a strict under-load equality assertion would be the flakiest test in the suite while proving less than (b) does.
- [ ] AC-003: `httpcomponents_httpclient_pool_total_max` reports **200** and `httpcomponents_httpclient_pool_route_max_default` reports **50**, matching `RestClientFactoryConfig`'s configured maxima — so a consumer can express "the pool is approaching exhaustion" as a ratio without hard-coding either limit into an alert rule.
- [ ] AC-004: The gauges read from the single shared `PoolingHttpClientConnectionManager` bean, so they reflect all Apache-HttpClient outbound traffic (RAG, Progression, Hearing) regardless of which `RestClient` issued the call; `dependency=azure_blob` is explicitly documented as **not** covered, because `AzureBlobStorageService` uses the Azure SDK's own HTTP stack, not this pool.
- [ ] AC-005: Nothing in this story computes a value on scrape — the gauges read `ConnPoolControl.getTotalStats()`, an in-memory struct, at scrape time; no database query, remote call, or lock is touched.

### NFR links
- NFR-002 (Cardinality): 5 series from the framework binder + 1 alias = 6, at essentially zero incremental risk against the ticket's **232**-series budget (243 before ADR-011).
- NFR-003 (Performance/isolation): structurally satisfied — gauges are in-memory reads, never computed on scrape.
- NFR-005 (Backward compatibility): no change to `RestClientFactoryConfig`'s timeouts, pool sizes, `disableAutomaticRetries()`, or connection-manager sharing.
- NFR-008 (Naming consistency): adds one `CdkMeters` constant (`HTTP_POOL_CONNECTIONS_LEASED`) for the alias; the framework binder's own names are not `CdkMeters` constants (they are Micrometer/framework-owned), and this is documented as the one deliberate exception to "every meter name is a `CdkMeters` constant".

### Out of scope for this story
- Any pool visibility for `dependency=azure_blob` — structurally impossible via this mechanism, stated in the Javadoc rather than built around.
- Registering the binder's per-route metrics (tagged by target host) — the aggregate is what "exhausted" means for a shared 200-connection pool serving three fixed hosts; `route.max.default` already exposes the per-route ceiling.
- Fixing the `RestClientFactory.build(...)` shared-connection-manager mutation (OQ-015) — a separate, already-latent defect, unaffected by this story.
- Switching `RestClientFactoryConfig` to per-client connection managers, or any other change to pool sizing or timeout configuration.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `HttpPoolMetricsConfig` bean wiring; integration: `HttpPoolMetricsHttpLiveTest` asserting all five binder series plus the alias, and their agreement, on `/actuator/prometheus` in the compose stack, ideally under concurrent load driven at the pool).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **GATE-4 (accepted at the Stage-2 gate):** ship both the framework binder and the `cdk_*` alias. If
  platform/SRE later confirms the `httpcomponents_*` family is acceptable on its own, the alias can be
  dropped in a follow-up (−1 series, no consumer impact if nothing yet depends on the alias name).
- **OQ-029 (accepted 2026-09-04)** re-scopes AC-002's proof mechanism — see the AC itself. The
  substance of the requirement is unchanged (the two names must never disagree); only the way it is
  demonstrated changes, moving the load-bearing half to the tier where it is actually provable.
- This story does not need `metrics/MetricsSafety` — the gauge read cannot meaningfully throw in a way
  that needs containment (it is a struct field read, not a computed classification), so it has no
  dependency on whichever story creates the shared safety/config infrastructure described in Story 1's
  Notes.
- Recommended alert expressions (ratio of leased to max, and pending > 0) are documented in
  `02-design.md` §6 for the OQ-019 alert-rule owner — not built in this story.
- Jira sub-ticket: `DD-43270`.

---

## Story 5 — Answer-generation outcome counter
**Jira: `DD-43271`**
**Scope reduced 2026-09-04 (OQ-022 / ADR-011): `outcome=timed_out` is descoped. Four increment
points, not six; `{succeeded, failed}`, not three values; 8 series, not 12.**

As a **production support engineer**,
I want **a counter that increments at most once per answer-generation transaction, tagged with how it
ended and at what query level**,
so that **I can see the success and failure rate for AI Search answers at every point CDKS can
actually observe a transaction ending**.

### Background
Design §8 / ADR-007 (accepted, partially superseded by **ADR-011**).
`cdk.answer.generation` (renders as `cdk_answer_generation_total`) is incremented at **four** points
across `GenerateAnswerForQueryTask` and `CheckStatusOfAnswerGenerationTask`.

**What changed at the Stage-4 gate, and why — this was traced, not assumed.** Stage 2 redefined
`outcome=timed_out` as "the `questions-retry` polling budget was spent while RAG still reported
`ANSWER_GENERATION_PENDING`", detected with the same `TaskRetryDecision.willBeRetried(...)` predicate
Story 6 uses. OQ-022 then showed that a task execution **never observes** an exhausted budget: the
last granted retry writes `retry_attempts_remaining = 0` and `JobsRepository` never re-assigns a row
at `0`, so the job is abandoned *between* executions with no task run to notice. The question put at
the gate was whether `CheckStatusOfAnswerGenerationTask` has an **independent** way to know its
polling budget is spent. **It does not** — the `PENDING` branch's only action is
`return retry(executionInfo)`, which keeps no count of its own, and the task's one independent
counter (`CTX_ANSWER_RETRY_COUNT`) tracks `ANSWER_GENERATION_FAILED` **re-dispatch cycles**, not
polling attempts, and is never touched on the `PENDING` path. So `timed_out` shares the same
unreachable mechanism and is **withdrawn**, along with the `catch`-path increment that was gated on
the identical condition. **GATE-2 is withdrawn.** See `02-design.md` §8's banner and ADR-011.

**What still ships, unchanged.** `outcome=failed` increments only when the
`ANSWER_GENERATION_FAILED` re-dispatch budget is itself spent (the existing
`log.warn("Max retries reached…")` branch, driven by CDKS's **own** `CTX_ANSWER_RETRY_COUNT` and so
independent of the library counter), not on every intermediate failure, so one transaction cannot
contribute up to 100 increments. `query_level="unknown"` is used, never omitted, when
`TaskUtils.parseQueryLevel` returns `null`. `GenerateAnswerForQueryTask`'s three own terminal
abandonment paths (missing identifiers, no `QueryDefinitionLatest`, and a RAG-start failure that can
never actually be retried) are still counted (GATE-5) — the third of those survives because its
`!willBeRetried(...)` is false on the `getRetryDurationsInSecs().isEmpty()` clause, which is read
from the **task's own configuration** and needs no observation of the library's counter.

**The accepted cost, which this story must not hide.** The counter's total is now an **undercount**
of answer-generation transactions that ended: a transaction abandoned while `PENDING`, or abandoned
from the `catch` path, records **nothing**. `succeeded / (succeeded + failed)` therefore
**overstates** the success rate, and a permanently-empty AI Search result caused by a spent polling
budget still has no detector anywhere in CDKS. This must be stated in `CdkMeters`' Javadoc, and the
`task-manager-service` exhaustion-event follow-up (ADR-011(4), see Story 6's Notes) is what closes
it.

### Acceptance criteria
- [ ] AC-001: Given an answer-generation transaction reaches `ANSWER_GENERATED`, then `cdk_answer_generation_total{outcome="succeeded"}` increments by exactly 1, tagged with the transaction's `query_level`.
- [ ] AC-002: Given `CheckStatusOfAnswerGenerationTask` sees `ANSWER_GENERATION_FAILED` and the re-dispatch retry budget is spent, then `outcome="failed"` increments by exactly 1; given the budget is **not** yet spent, the task re-dispatches and **no** increment occurs — a transaction whose answer eventually succeeds after several `ANSWER_GENERATION_FAILED` cycles contributes exactly one increment in total, not one per cycle.
- [ ] AC-003 *(rewritten 2026-09-04 per OQ-022 / ADR-011 — this was the `timed_out` AC)*: `outcome="timed_out"` is **not implemented and must not appear anywhere** — not as a `CdkMeters` constant, not as a registered series, not on a scrape. Correspondingly: given `CheckStatusOfAnswerGenerationTask` sees `ANSWER_GENERATION_PENDING` (or a null / non-2xx response), then **no** series increments and the task returns `INPROGRESS` + `shouldRetry` exactly as it does today, whether or not its polling budget is nearly spent; and given the `catch (Exception)` path is reached, then **no** series increments and the task returns `INPROGRESS` + `shouldRetry` as today. Both are covered by explicit negative-control tests, and the two resulting uncounted terminations are documented in `CdkMeters`' Javadoc as a known gap owned by the `task-manager-service` follow-up — **not** left for a reader to infer from the absence of a series.
- [ ] AC-004: Given `GenerateAnswerForQueryTask` ends a transaction via missing identifiers, a missing `QueryDefinitionLatest`, or a RAG-start failure (which per Story 6's predicate can never actually be retried), then `outcome="failed"` increments by exactly 1 for each — these three paths are currently invisible and must not remain so.
- [ ] AC-005: `outcome` takes exactly one of `succeeded`, `failed` — **two values, not three (`timed_out` descoped)**; `query_level` takes one of `CASE`, `DEFENDANT`, `CASE_ALL_DOCUMENTS` (verbatim `QueryLevel` constants) or `unknown` when `parseQueryLevel(...)` returns `null` — the increment is never omitted for a null `query_level`. **`failed` must come from a new, distinctly-named `CdkMeters` constant (`OUTCOME_FAILED`, `"failed"`), never from DD-43185's existing `OUTCOME_FAILURE` (`"failure"`), which would compile silently in its place (OQ-031)** — asserted on the literal string, not on the constant.
- [ ] AC-006 *(re-scoped 2026-09-04 per ADR-011)*: Tracing every path through the pair of tasks, **at most one** of the four increment points fires per transaction — `1` for every observable termination, `0` for exactly the two documented unobservable ones (abandoned while `PENDING`; abandoned from the `catch` path). No path double-counts (e.g. the success handoff from `GenerateAnswerForQueryTask` to `CheckStatusOfAnswerGenerationTask` increments nothing), and any path that is uncounted is one of those two and is named as such. **Do not assert "exactly one per transaction"** — that would encode a completeness claim that is no longer true.
- [ ] AC-007: A metric-recording failure in outcome/level computation is contained and does not affect the task's own return value, its persisted job data, or its `ExecutionInfo`.

### NFR links
- NFR-001 (Data protection): `outcome` (**2** values) and `query_level` (4 values) are both fixed, closed sets; no case, query or transaction identifier in either tag.
- NFR-002 (Cardinality): 2 × 4 = **8** series (12 before ADR-011), pre-registered at construction — part of the ticket's **232**-series worst case.
- NFR-004 (Availability): a throwing outcome/level computation must not fail either task or alter its `ExecutionInfo`.
- NFR-005 (Backward compatibility): **no existing line is moved.** The `query_level` parse hoist this story originally carried is **withdrawn with rows 3 and 4** (ADR-007(6) as amended) — it existed only to give those two increments a `query_level`, and the two surviving `CheckStatusOfAnswerGenerationTask` increments already sit after the existing parse at lines 94–95. "No code move at all" is now this story's strongest NFR-005 position; do not hoist.

### Out of scope for this story
- An answer-generation **duration** timer — confirmed intentionally out of scope (OQ-021/ADR-007(7)): there is no persisted start timestamp to compute one from, unlike Story 2's ingestion duration, so it is not a cheap analogue and needs its own ticket with that cost stated.
- ~~`cdk_task_retry_exhausted_total{task_name="CHECK_STATUS_OF_ANSWER_GENERATION"}` — Story 6.~~ **Descoped from the whole ticket 2026-09-04 (ADR-011)** — it is not built by Story 6 either, so there is no overlap to coordinate and no "two views of one event" paragraph to write.
- **Detecting the give-up-waiting event at all.** After this story, a transaction abandoned while `ANSWER_GENERATION_PENDING` is still invisible. Closing that needs the `task-manager-service` exhaustion event (ADR-011(4)) — a follow-up ticket against the library, not work in this story.
- Any change to the `questions-retry` budget (100 × 10 s), or to `GenerateAnswerForQueryTask`'s missing `getRetryDurationsInSecs()` override — both are pre-existing, out-of-scope behaviour this story only makes measurable.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `AnswerGenerationMetricsTest` for the **8**-series pre-registration and the two-value `outcome` bound asserted on literals; extended `CheckStatusOfAnswerGenerationTaskTest` and `GenerateAnswerForQueryTaskTest` covering all **four** surviving rows of the outcome table, including the "budget not yet spent → zero increments" case **and the two negative controls for the withdrawn `PENDING` and `catch` paths**; integration: outcome series visible and correctly tagged on `/actuator/prometheus`, **and `outcome="timed_out"` absent**).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **~~GATE-2 (accepted at the Stage-2 gate)~~ — WITHDRAWN 2026-09-04 (ADR-011).** `timed_out`'s
  redefinition was accepted at Stage 2 on the premise that the event was "cheaply detectable". That
  premise was false (OQ-022). The tag value is **not built**; the sub-ticket must carry *this*
  decision forward, not the Stage-2 one. Re-adding the value later is purely additive — the one
  direction of tag-set change that is not a one-way door — so nothing here forecloses the fix.
- **GATE-5 (accepted at the Stage-2 gate):** counting `GenerateAnswerForQueryTask`'s three abandonment
  paths widens the ticket's stated scope (which named only `CheckStatusOfAnswerGenerationTask`'s
  states). Already accepted.
- **Soft dependency on Story 6's `TaskRetryDecision` predicate — it survives OQ-022.** The
  `timed_out` detection that originally needed it is gone, but **AC-004's third path still uses it**:
  `GenerateAnswerForQueryTask`'s RAG-start failure increments `failed` when
  `!willBeRetried(...)`, which is false via the `getRetryDurationsInSecs().isEmpty()` clause. Keep
  the predicate call rather than incrementing unconditionally — if that task ever gains the missing
  override, the predicate stops the counter over-reporting automatically. So: if Story 6 has not yet
  landed when this story starts, this story must introduce `metrics/TaskRetryDecision` itself; if
  Story 6 lands first, this story reuses it. Either order works — flag at sprint planning so the
  second-delivered story's PR is scoped as "reuse" rather than "create".
- **OQ-023, decided 2026-09-04:** `willBeRetried(info, task)` takes **no `shouldRetry` input** —
  the caller tests `shouldRetry` separately. That is the form this story's AC-004 path needs, because
  it calls the predicate where `shouldRetry` is about to be set true and is not yet on any
  `ExecutionInfo` the caller holds.
- **Shared-infrastructure note** — see Story 1's Notes: this story is one of the five (1, 2, 3, 5, 6)
  that can create or must reuse `metrics/MetricsSafety` / `config/MetricsProperties` /
  `metrics/CdkMetricsConfig`.
- Jira sub-ticket: `DD-43271`.

---

## Story 6 — JobManager retry counter
**Jira: `DD-43272`**
**Scope reduced 2026-09-04 (OQ-022 / ADR-011): `cdk_task_retry_exhausted_total` is descoped and is
not built. `cdk_task_retry_total` — the plain retry counter — is unaffected and ships as designed.**
**Cross-ticket coordination required with DD-43183 — see the dedicated section below. Not a strict
code dependency (implementation can proceed independently), but merge order, aspect ordering and the
ordering test must be coordinated before either PR merges.**

As a **production support engineer**,
I want **a counter for every JobManager retry that will actually be granted, per task and per retry
policy**,
so that **I can see how much of this service's work is being re-attempted rather than completing
first time, per task, without reading logs**.

### Background
Design §7 / ADR-006 (accepted, decision (2)'s exhaustion counter superseded by **ADR-011**).

**What ships.** `ExecutionInfo.getRetryAttemptsRemaining()` is public and carries **the same value**
`TaskExecutor.canRetry(...)` tests internally, so the library's *grant* decision is fully predictable
from inputs a task already has. One shared predicate,
`metrics/TaskRetryDecision.willBeRetried(info, task)`, replicates `canRetry`'s budget clauses — with
the **caller** testing `shouldRetry` separately (OQ-023). **One** `@Aspect`,
`metrics/TaskRetryMetricsAspect`, applied `@Around` `ExecutableTask.execute(..)`, records at that one
call site — no task business logic changes at all, and the seven `@Task` beans are not edited.
`cdk.task.retry` counts retries that will actually be **granted**, not merely requested, and covers
the throw path (live today in the unguarded `CheckAllDocumentsIngestionStatusTask.execute`), which is
the main reason an aspect beats seven explicit call sites. The counter carries a second tag,
`retry_policy` (`default-retry`, `verify-document-status`, `questions-retry`, `none`), functionally
determined by `task_name` at **zero** extra series cost (GATE-3).

**What was descoped at the Stage-4 gate, and why.** Design's ADR-006 also specified
`cdk.task.retry.exhausted`, "the one moment a budget runs out". OQ-022 read the library one step
further: `TaskExecutor.performRetry(...)` writes `remaining - 1`, so the **last granted retry writes
`0`**, and `JobsRepository`'s assignment query is
`… WHERE worker_id IS NULL AND (retry_attempts_remaining IS NULL OR retry_attempts_remaining > 0)
AND assigned_task_start_time <= :currentTime …` — a row at `0` is **never selected again**. The
exhaustion therefore happens **between executions, in the scheduler, with no task run at all**, so a
counter incremented from inside a task execution cannot see it. CDKS's own
`CheckIngestionStatusForAllDefendantsTask.LAST_RETRY_COUNT = 1` already encodes this asymmetry. The
counter would have read permanently `0` for all six tasks with a real budget — including
`CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS` (50 × 5 s), whose exhaustion is exactly the event
FR-011/FR-012 exist for. **A series an alert rule would be written against, reading `0` while work
is abandoned, is worse than no series.** So it is not built; FR-011, FR-012's exhaustion half and
AC-020 are **descoped**; and the gap is escalated to `task-manager-service` (see Notes).
The `remaining == 1` "final execution" workaround was considered and rejected (ADR-011(5)).

`GENERATE_ANSWER_FOR_QUERY` is still documented, not fixed, as a task that can never be retried
(`retry_policy="none"`) — but note the signal is now **weaker**: with no exhaustion counter it emits
nothing on either counter, so what surfaces the defect is a *permanently zero*
`cdk_task_retry_total{task_name="GENERATE_ANSWER_FOR_QUERY"}` series, which reads identically to
"this task never failed". AC-007 requires the Javadoc to say both things.

### Acceptance criteria
- [ ] AC-001: Given a JobManager task returns `ExecutionInfo(INPROGRESS, shouldRetry=true)` (or throws, and `TaskExecutor` synthesises the same outcome outside CDKS code) with `getRetryAttemptsRemaining() > 0` and the task's own `getRetryDurationsInSecs()` present, then `cdk_task_retry_total{task_name=<the task's TaskNames constant>, retry_policy=<its policy>}` increments by exactly 1 — this covers the throw path (live today in the unguarded `CheckAllDocumentsIngestionStatusTask.execute`), not only the explicit `retry(...)` helper paths.
- [ ] AC-002 *(rewritten 2026-09-04 per OQ-022 / OQ-024 / ADR-011 — this was the exhaustion AC)*: `cdk_task_retry_exhausted_total` is **not implemented and must not appear anywhere** — no `CdkMeters` constant, no `recordRetryExhausted(...)` method, no registered series, nothing on a scrape. Correspondingly, and asserted explicitly rather than left implicit: given an `INPROGRESS` + `shouldRetry` outcome where `getRetryAttemptsRemaining()` is `0`/`null` **or** `getRetryDurationsInSecs()` is empty (the `GENERATE_ANSWER_FOR_QUERY` shape), then **nothing is recorded on any series**; and **given an `INPROGRESS` result with `shouldRetry=false`, nothing is recorded either — it counts as neither retried nor exhausted (OQ-024)**, which does not occur with today's seven tasks but is pinned by a test so the boundary is not left to an implementer's reading. The earlier claim that "the two counters partition the `INPROGRESS` outcome exactly" is **withdrawn**: there is one counter, and `willBeRetried(...) == false` is an explicitly *uncounted* outcome.
- [ ] AC-003: `task_name` is read from the target class's `@Task` annotation (via `AopUtils.getTargetClass`, proxy-aware) and is membership-checked against the seven `TaskNames` values before any increment; a target with no `@Task`, or a value outside the seven, records nothing — no other value can ever be emitted.
- [ ] AC-004: `retry_policy` takes exactly one of `default-retry`, `verify-document-status`, `questions-retry`, `none`, determined solely by `task_name`, adding a label to the existing **7** series (7+7 before ADR-011) at **zero** additional series cost — asserted as 7 series per counter, not 28.
- [ ] AC-005: A task that returns `COMPLETED` records nothing on either counter; the aspect never alters the returned `ExecutionInfo` or rethrows anything other than the exact exception instance the task threw.
- [ ] AC-006 *(re-aimed, not dropped — 2026-09-04 per ADR-011 and OQ-028)*: An integration test **seeds a `jobs` row directly via JDBC** with a small explicit `retry_attempts_remaining` (`INSERT INTO jobs (…, retry_attempts_remaining, …)`, already the house idiom in `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` and `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest`, reachable through `AbstractHttpLiveTest.openConnection()`), drives it to the end of its budget against a never-terminal WireMock stub, and asserts that `cdk_task_retry_total{task_name=…}` increased by **exactly the number of retries the library actually granted**, that the `jobs` row ends at `retry_attempts_remaining = 0`, is not re-executed and is not deleted, **and that `cdk_task_retry_exhausted_total` is absent from the scrape entirely**. This is still ADR-006's named mitigation for the replicated-predicate liability — it ties the CDKS-side prediction to the library's real behaviour, so a `task-manager-service` bump that changes `canRetry` fails CI — and the negative assertion pins the descoping. **Do not shorten a compose retry budget to do this (OQ-028):** `CDK_JOBMANAGER_RETRY_VERIFY_DOC_MAX_ATTEMPTS` governs two tasks for the whole live suite, and `IngestionProcessHttpLiveTest`, `IngestionStatusHttpLiveTest` and `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest` all depend on polling to completion within the shipped budget. Delete seeded rows in a `finally` block — the compose database is shared.
- [ ] AC-007 *(rewritten 2026-09-04 per ADR-011)*: In-repo documentation (`CdkMeters` Javadoc) lists all seven `task_name` values, states each one's **effective** governing budget (not the YAML's stated budget — `cdk.jobmanager.retry.default` is confirmed not to bind, so the *effective* default-retry budget is the Java field default of 3×20s, which happens to match today but is documented as the effective value, not the configured one), states plainly that `GENERATE_ANSWER_FOR_QUERY` cannot be retried at all **and that its permanently-zero `cdk_task_retry_total` series is therefore not evidence of health**, and — **the clause that replaces the withdrawn "primary work-is-being-silently-abandoned signal" claim** — states explicitly that **retry-budget exhaustion is not detected anywhere in CDKS**: `cdk.task.retry` counts granted retries only, a task whose budget runs out is abandoned by the library between executions and is counted nowhere, `cdk_answer_generation_total`'s total is consequently an undercount of ended transactions, and the `task-manager-service` exhaustion event is the follow-up that closes it. An SRE reading `CdkMeters` must not infer from a healthy-looking `cdk_task_retry_total` that nothing is being abandoned.
- [ ] AC-008: A metric-recording failure inside the aspect's advice is contained — the target task's return value, thrown exception and `ExecutionInfo` are unaffected, whatever the recording failure.

### NFR links
- NFR-001 (Data protection): `task_name` (7 values) and `retry_policy` (4 values, determined by `task_name`) are both fixed sets sourced from compile-time constants, never from job data.
- NFR-002 (Cardinality): **7** series (7 + 7 = 14 before ADR-011), `retry_policy` adding a label at zero extra series cost.
- NFR-004 (Availability): the aspect must never alter a task's return value or exception, and a failing recording must not affect task execution.
- NFR-005 (Backward compatibility): zero lines of the seven `@Task` beans' business logic change — the strongest available position against a regression in JobManager task behaviour.
- NFR-009 (Configurability): `TaskRetryMetricsAspect` is the **one** bean in this ticket gated by `@ConditionalOnProperty("cdk.metrics.enabled")` — disabling the flag removes the bean (and, **until DD-43183 ships**, the CGLIB proxying of the seven task beans with it; see the coordination note below for what changes once DD-43183 lands).

### Out of scope for this story
- Any change to the seven `@Task` beans' business logic, retry budgets, or `getRetryDurationsInSecs()` overrides (including fixing `GenerateAnswerForQueryTask`'s missing override) — documented as a defect, not fixed, per the ticket's own out-of-scope list.
- **Detecting retry-budget exhaustion at all.** Descoped 2026-09-04 (ADR-011) — `cdk_task_retry_exhausted_total` is not built, and FR-011, FR-012's exhaustion half and AC-020 are not met by this story or by this ticket.
- Raising the `task-manager-service` change that publishes a first-class exhaustion event — **now the recommended fix rather than an alternative**, and this ticket's highest-priority follow-up (see Notes), but a separate ticket against the library with its own lead time.
- A ShedLock-guarded gauge over `jobs.retry_attempts_remaining = 0` — a complementary CDKS follow-up (it measures the accumulated *backlog* of abandoned work, including anything abandoned before DD-43182, which no counter can), recorded as a strong recommendation (ADR-006, ADR-011(4)), not built here.
- Fixing the confirmed-inert `CDK_JOBMANAGER_RETRY_DEFAULT_MAX_ATTEMPTS` / `CDK_JOBMANAGER_RETRY_DEFAULT_DELAY_SECONDS` environment variables (the `cdk.jobmanager.retry.default` YAML key does not bind to `JobManagerRetryProperties.setDefaultRetry(...)`) — a separate defect ticket, only documented here.
- DD-43183's `JobCorrelationAspect` itself and its MDC-restoration behaviour — a different ticket's story; this story only coordinates ordering and adds the ordering test against whichever of the two aspects exists at the time this story is implemented.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `TaskRetryDecisionTest` pinning the replicated predicate against every input combination — including the `GENERATE_ANSWER_FOR_QUERY` shape, the `remaining == 1` final-execution row, and the assertion that `shouldRetry` is **not** an input (OQ-023); `TaskRetryMetricsAspectTest` covering the return path, the throw path, `COMPLETED`/`STARTED`, `INPROGRESS`-without-`shouldRetry` (OQ-024), the no-retry-configuration case recording **nothing**, and the AC-003 membership check; integration: `TaskRetryHttpLiveTest` — renamed from `TaskRetryExhaustionHttpLiveTest` — seeding a `jobs` row per AC-006 and asserting the granted-retry count, the row's end state, and the **absence** of `cdk_task_retry_exhausted_total`).
- [ ] **The aspect-ordering test is included or explicitly referenced**, asserting `JobCorrelationAspect` (DD-43183) runs outermost of `TaskRetryMetricsAspect` (this story) on `ExecutableTask.execute` — whichever of DD-43182/DD-43183 lands second is responsible for adding this test, per both designs' shared instruction; this story's DoD is not complete until that test exists and passes, even if it lands in the other ticket's PR.
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence, including the exhaustion integration test's evidence and the aspect-ordering test's evidence.

### Cross-ticket coordination with DD-43183 (mandatory reading before implementation)
- **Same join point, two aspects.** DD-43183's `JobCorrelationAspect` (MDC/correlation-ID restoration,
  read `docs/pipeline/DD-43183-correlation-id-unification/02-design.md` §7 and
  `docs/pipeline/adrs/DD-43183-correlation-id-unification.md` ADR-004 before starting this story) advises
  the **identical** join point as this story's `TaskRetryMetricsAspect`:
  `execution(* uk.gov.hmcts.cp.cdk.jobmanager..*.execute(uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo))`.
  Both Stage-2 designs, accepted independently, agree on the same resolution.
- **Ordering.** `JobCorrelationAspect` is `@Order(Ordered.HIGHEST_PRECEDENCE)` (outermost).
  `TaskRetryMetricsAspect` in this story must **not** add an `@Order` annotation — Spring AOP's
  default (lowest precedence) already places it inside `JobCorrelationAspect`'s scope, which is what
  makes this story's own throttled WARN log lines (`MetricsSafety.warnThrottled(...)`) carry a
  correlation ID when both aspects are present. Do not add `@Order` to `TaskRetryMetricsAspect` "for
  clarity" — that would invert the required ordering.
- **The corrected proxying claim.** This ticket's own design (`02-design.md` §7 / §10) states that
  `cdk.metrics.enabled=false` "removes the aspect bean and therefore the proxying entirely" — **true
  only while DD-43183 has not yet shipped.** Once `JobCorrelationAspect` is also present, Spring merges
  same-bean aspects into one proxy, so the seven `@Task` beans stay CGLIB-proxied regardless of
  `cdk.metrics.enabled`; the flag still stops this story's own recording, it just no longer doubles as
  a full unwind of the proxying overhead. Whoever implements this story must not carry forward the
  uncorrected claim into code comments or the `CdkMeters` Javadoc.
- **No blocking code dependency.** This story's `TaskRetryMetricsAspect` can be implemented and tested
  in isolation (a `SimpleMeterRegistry` and a stub `ExecutableTask`, per the unit test above) whether or
  not DD-43183's aspect exists yet in the codebase. The coordination requirement is about **merge
  order and the ordering test**, not implementation order: whichever of the two aspects merges second
  must add the ordering test (see DoD above) and must not omit the `@Order` reasoning from its own
  design note.
- **Real Jira link needed.** DD-43183 is at Stage 3 in parallel with this ticket, so its own sub-ticket
  for the `JobCorrelationAspect` story does not yet have a confirmed identifier in this session. This
  story's real Jira sub-ticket (once cut) must be linked to DD-43183's equivalent sub-ticket as soon as
  both exist, so sprint planning sees the coordination requirement without re-reading both designs.

### Notes / open questions
- **GATE-3 (accepted at the Stage-2 gate):** the `retry_policy` tag is additive to the ticket's stated
  tag set, at zero series cost. Already accepted.
- **The one genuine implementation liability in this story** (ADR-006's own words): CDKS holds a
  *replica* of a library predicate (`TaskExecutor.canRetry`). **This is unchanged by ADR-011** — the
  replica is still there, now for one counter instead of two — so AC-006's integration test is still
  the mitigation, not a comment. Do not treat it as optional coverage, and do not delete it along
  with the exhaustion counter it was originally aimed at.
- **Follow-up ticket this story must raise (ADR-011(4)) — the highest-priority output of DD-43182
  besides the metrics themselves.** A `task-manager-service` change to publish a **first-class
  retry-exhaustion event**. Give the maintainers a concrete ask, not a complaint: publish an
  event/callback where `TaskExecutor` decides `!canRetry` on an `INPROGRESS` result — immediately
  before `updateNextTaskDetails(jobId, name, unchanged-start-time, 0)` + `releaseJob(jobId)` —
  carrying at minimum the job id, the assigned task name and the exhausted budget. That is the only
  place in the system where the event is known. An `ApplicationEvent`, or an opt-in no-op extension
  interface consumers can implement without forking, is an acceptable alternative shape.
  **Consumer-side polling of `jobs.retry_attempts_remaining` is not acceptable as the primary fix**
  (cross-schema read; yields a gauge of stranded rows, not a counter of events) though it remains
  worth doing in CDKS as an independent, complementary follow-up. Raise this when Story 6 is picked
  up, not after the ticket ships — until it lands, retry-budget exhaustion is undetected and
  `cdk_answer_generation_total` undercounts ended transactions (Story 5). If the library change is
  refused, ADR-011(5) records the interim `remaining == 1` option and exactly why it was rejected.
- **OQ-035 re-verified after the OQ-022 changes (2026-09-04):** Scenario 6.16's deferral to DD-43183
  Scenario 3.5 as the canonical aspect-ordering test **still holds**. `TaskRetryMetricsAspect` is not
  removed by ADR-011 — it still records `cdk.task.retry` — so the join point, the ordering
  requirement and the "no `@Order` on `TaskRetryMetricsAspect`" rule are all unaffected.
- **Shared-infrastructure note** — see Story 1's Notes: this story is one of the five (1, 2, 3, 5, 6)
  that can create or must reuse `metrics/MetricsSafety` / `config/MetricsProperties` /
  `metrics/CdkMetricsConfig`. This story additionally has a **hard** dependency on
  `config/MetricsProperties`/`metrics/CdkMetricsConfig` existing before `TaskRetryMetricsAspect` can
  compile its `@ConditionalOnProperty("cdk.metrics.enabled")` annotation — if this story is picked up
  before Stories 1, 2, 3 or 5, it must create those two classes itself.
- **Soft dependency, either order, with Story 5** — both stories use `metrics/TaskRetryDecision`. See
  Story 5's Notes.
- Jira sub-ticket: `DD-43272`.

---

## Story 7 — Cardinality budget, scrape-time bound and cross-cutting safety harness
**Jira: `DD-43273`**
**Depends on Stories 1–6 for its whole-endpoint series-count and full-surface assertions. The
`baseline-series-count.md` capture is this story's own deliverable, taken when this story is built
from a DD-43182-free commit — OQ-034, decided 2026-09-04; the earlier "as early as possible / before
Story 1" framing is withdrawn. See Notes.**

As a **CDKS developer / release engineer**,
I want **the whole ticket's series count and scrape time measured and bounded by a merge-blocking
test, every new meter name/tag value pinned in `CdkMeters`' Javadoc, and the failure-containment path
proven with an injected throwing registry**,
so that **the ticket can be merged and deployed with confidence that it does not silently blow the
2,000-series budget or the scrape-time budget, and that a metrics bug can never take down the business
path it instruments**.

### Background
Design §9, §10, §12 / ADR-001, ADR-005, ADR-010 (all accepted; ADR-005's arithmetic amended by
**ADR-011**). This story is where the six other stories' individually-computed series counts are
proven against the whole-endpoint reality: **232 series worst case** for this ticket (**95**
registered at construction) — 243 / 106 before ADR-011 withdrew `cdk_task_retry_exhausted_total`
(−7) and `outcome=timed_out` (−4) — and **246** combined with DD-43185's unchanged 14. **Seven**
rendered `cdk_*`/pool metric names, not eight. The 2,000-series budget is met with more headroom
than before, achieved specifically by declining `percentiles-histogram` and putting explicit SLO
buckets on the ingestion timer only (Story 2), never on the external-call timer (Story 3). Two
deliverables make this a proof rather than an assertion: a **`baseline-series-count.md`** artefact
recording the measured pre-DD-43182 whole-endpoint series count, and a **merge-blocking**
`integrationTest` asserting the total stays under a ceiling tighter than 2,000 in the compose stack
(design proposes 1,200, to be fixed once the real baseline is measured), because the compose stack's
series count is a *lower* bound on production's. **AC-024 is
re-scoped at the accepted GATE-6**: the "under 1 second" scrape-time bound becomes a CI smoke bound
(design proposes 2 s) in the compose stack, plus a one-off production timing captured in
`deploy-notes.md` — a hard sub-second assertion on shared CI hardware is a flaky test, not a guarantee.
This story also owns the shared `metrics/MetricsSafety` failure-containment helper's own direct test
coverage (as opposed to each area's own containment AC, which exercises it indirectly) and the final
`CdkMeters` Javadoc pass covering every meter this ticket adds.

### Acceptance criteria
- [ ] AC-001: When `/actuator/prometheus` is scraped against the compose stack with all six DD-43182 stories deployed, then every metric named in `02-design.md` §2 is present — **all seven rendered names** (eight before ADR-011) — including every counter/timer series that has not yet been incremented (**95** pre-registered at value `0`); **and the two withdrawn signals are asserted absent: no `cdk_task_retry_exhausted_total` anywhere, and no `outcome="timed_out"` on `cdk_answer_generation_total`** (ADR-011). The absence assertions are cheap and are what stop a descoped meter being re-introduced by a later merge without a decision.
- [ ] AC-002: Every `cdk_*` series added by this ticket carries the existing common tags `service`, `cluster` and `region` from `management.metrics.tags`.
- [ ] AC-003 *(capture timing settled 2026-09-04 per OQ-034)*: `baseline-series-count.md` records the measured whole-endpoint series count on the compose stack **from a commit that does not contain this ticket's changes** (a clean `origin/develop` checkout, or a `git stash` of the working tree), captured the same way DD-43185's `baseline-actuator-prometheus.md` was captured. **It is captured when this story is built — not before Story 1 starts.** "Pre-implementation" is a property of the **commit measured**, not of the calendar date, so deferring the capture to the story that consumes the number costs nothing and avoids a hand-off between otherwise-unrelated stories. A merge-blocking integration test then asserts the whole-endpoint series count **after** this ticket's changes is below a stated ceiling tighter than 2,000, with the compose-is-not-production reasoning stated in the assertion's own failure message.
- [ ] AC-004 *(re-scoped per GATE-6, accepted)*: A CI smoke-bound assertion (design proposes 2 seconds) on `/actuator/prometheus` scrape time in the compose stack, explicitly labelled in its assertion message as a CI smoke bound rather than a production guarantee; a one-off production scrape timing is captured separately in `deploy-notes.md` at Stage 8.
- [ ] AC-005: Given metric recording throws for any reason (registry failure, tag-computation failure), when the surrounding business operation runs (an ingestion phase write, an outbound call, a JobManager task execution, an answer-generation state transition), then it completes exactly as it would without instrumentation — same HTTP status/body, same persisted phase, same `ExecutionInfo`, same propagated exception, no RAG response field dropped or altered — proven with an injected throwing `MeterRegistry`/meter across all four business paths, not just one.
- [ ] AC-006: Given repeated metric-recording failures, a WARN is logged at most once per 60 seconds globally (not once per site, not once per occurrence), the suppressed-failure count is included in the WARN line, the line contains no case content/case identifier/`CJSCPPUID`, and it is emitted as structured JSON via the existing `logback-spring.xml`. **`MetricsSafety` carries a package-private, settable time source (a `LongSupplier` of epoch seconds, or an equivalent settable clock field) defaulting to the real clock, so the 60-second window is testable without a 60-second wait — OQ-030, decided 2026-09-04.** The seam is test-only by convention: package-private, no public setter, **no property binding, not a new production knob**, and production code never passes anything but the default.
- [ ] AC-007 *(amended 2026-09-04 per ADR-011)*: `CdkMeters`' Javadoc mapping table is extended to cover all **six** new meters plus the pool binder, states the Timer naming rule (no `.seconds` segment on a registered Timer name), and — **replacing the withdrawn "`cdk_task_retry_exhausted_total` is the primary work-is-being-silently-abandoned signal" clause** — states plainly that **there is no such signal in this ticket**: retry-budget exhaustion is not detected anywhere in CDKS, `cdk_task_retry_total` counts granted retries only, `cdk_answer_generation_total`'s total is an undercount of ended transactions, `succeeded / (succeeded + failed)` must not be published as a success rate, and the `task-manager-service` exhaustion event (ADR-011(4)) is the follow-up that closes it. It must also document DD-43185's `OUTCOME_FAILURE` (`"failure"`) and this ticket's `OUTCOME_FAILED` (`"failed"`) as two deliberately distinct values, each naming the meter it belongs to (OQ-031).
- [ ] AC-008: `ActuatorHttpLiveTest`, `MonitoringMetricsHttpLiveTest` and `SchedulerMetricsHttpLiveTest` pass with their existing assertions completely unmodified; DD-43185's six meter names and 14 series, and every existing timeout, retry budget, pool size and cron expression, are unchanged.
- [ ] AC-009: `gradle clean build` (including `integration`) passes end-to-end for the whole ticket; PMD and JaCoCo are green at existing, unmodified thresholds; CodeQL and the secrets scanner are clean.
- [ ] AC-010: The full diff across Stories 1–7 introduces no PII, case content, court reference number, or `CJSCPPUID` into code, config, tests or fixtures; every fixture value is synthetic.

### NFR links
- NFR-002 (Cardinality): the direct deliverable for NFR-002's "computed and recorded" requirement — this story is where the **232 / 246**-series arithmetic (243 / 257 before ADR-011) is proven against a running scrape, not just written down in the design doc.
- NFR-003 (Performance/isolation): the direct deliverable for the scrape-time half of NFR-003, re-scoped per GATE-6.
- NFR-004 (Availability): the direct deliverable for the cross-cutting containment proof (each area story's own AC-006/AC-007/AC-008-equivalent exercises `MetricsSafety` once; this story proves it across all four business-path shapes together).
- NFR-007 (Testability): this story is the ticket's equivalent of DD-43185's Story 5 — the one place all new series are asserted together.
- NFR-008 (Naming consistency): the final, ticket-wide `CdkMeters` Javadoc pass.
- NFR-009 (Configurability): confirms `cdk.metrics.enabled` (default `true`, `CP_CDK_METRICS_ENABLED`) is bound and documented, and that it is not overridden in compose (the suite exercises the shipped default).

### Out of scope for this story
- Writing the production code for any of the seven meters or the pool binder — Stories 1–6.
- Alert rules, recording rules, dashboards, or on-call routing (OQ-019) — a follow-up ticket owned by platform/SRE; this story documents the recommended PromQL expressions from `02-design.md` §6/§8, it does not build them.
- Contract tests — no API, schema, or contract change anywhere in this ticket; `pactVerificationTest` is unaffected.
- Platform/SRE confirmation of the `cdk_` prefix for the eight new names (OQ-018's second half) — outside this repository's control, tracked as a carried-forward item, not a story task.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by the new `OperationalMetricsHttpLiveTest` and `PrometheusSeriesBudgetHttpLiveTest`, plus confirmation runs of the three existing unmodified live tests and a dedicated `MetricsSafetyTest`.
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence covering the full ticket, not just this story's own diff.
- [ ] `deploy-notes.md` carries the one-off production scrape-time capture (AC-004) once this ticket reaches Stage 8.

### Notes / open questions
- **GATE-6 (accepted at the Stage-2 gate):** AC-024's re-scoping (whole-endpoint series count against a
  compose-tighter ceiling; scrape time as a CI smoke bound plus a production capture) is a widening of
  what the original ticket's AC could ever have asserted as written. Already accepted; Stage 4 should
  write its test spec directly against the re-scoped form above, not the original "under 1 second /
  under 2,000 series" wording.
- **Sequenced last, like DD-43185's Story 5**, because its central integration test scrapes metrics
  registered by all six other stories and cannot be meaningfully completed (only partially stubbed)
  before they land.
- **The `baseline-series-count.md` capture happens when this story is built — OQ-034, decided
  2026-09-04.** The earlier "ideally before Story 1 starts" framing is **withdrawn**. The concern it
  addressed (comparing against a partially-instrumented baseline) is handled by *what is measured*
  rather than *when*: the capture is taken from a commit without DD-43182's changes present, so it is
  a genuine pre-implementation measurement regardless of the date. Keeping the capture and the
  **ceiling assertion** it feeds in the same story, with the same owner, is simpler than a hand-off
  from a story that has no other reason to touch it.
- **Whichever of Stories 1, 2, 3, 5 or 6 lands first is responsible for `metrics/MetricsSafety` and
  `config/MetricsProperties`/`metrics/CdkMetricsConfig`** (see Story 1's Notes) — this story's own
  `MetricsSafetyTest` exercises that shared class regardless of which earlier story created it, and
  should be scoped as "add missing coverage" rather than assume it is untested.
- Jira sub-ticket: `DD-43273`.

---

## Summary

| Story | Title | Jira | Depends on | Area |
|---|---|---|---|---|
| 1 | Document-ingestion phase counter | `DD-43267` | none | A(i) |
| 2 | Ingestion duration timer (end-to-end) | `DD-43268` | shares class/call sites with Story 1 (soft) | A(ii) |
| 3 | Outbound dependency call timer | `DD-43269` | none | B(i) |
| 4 | HTTP connection-pool visibility | `DD-43270` | none — smallest, could ship first alone | B(ii) |
| 5 | Answer-generation outcome counter — **`timed_out` descoped, 4 increment points, 8 series** | `DD-43271` | shares `TaskRetryDecision` with Story 6 (soft, still required) | C |
| 6 | JobManager retry counter — **exhaustion counter descoped** | `DD-43272` | shares `TaskRetryDecision` with Story 5 (soft); **cross-ticket coordination with DD-43183's `JobCorrelationAspect` story (ordering + merge sequencing, not a code dependency)** | D |
| 7 | Cardinality budget, scrape-time bound and cross-cutting safety harness | `DD-43273` | Stories 1–6 | E |

**Shared-infrastructure sequencing, stated once so sprint planning does not have to re-derive it:**
- `metrics/MetricsSafety`, `config/MetricsProperties` and `metrics/CdkMetricsConfig` are created by
  **whichever of Stories 1, 2, 3, 5 or 6 is implemented first**; every other story in that group
  extends/reuses them. Story 6 has an additional **hard** dependency on `MetricsProperties`/
  `CdkMetricsConfig` existing, because `TaskRetryMetricsAspect`'s `@ConditionalOnProperty` annotation
  needs the property key to compile against — if Story 6 is picked up first, it creates them.
- `metrics/CdkMeters` already exists (from DD-43185) and is **extended**, never recreated, by
  whichever DD-43182 story lands first; every later story adds its own constants to the same file —
  sequence PRs rather than working them fully in parallel to avoid merge conflicts on one file.
- `metrics/TaskRetryDecision` (the replicated `canRetry` predicate) is shared, either-order, between
  Stories 5 and 6.
- Story 1 and Story 2 share one implementation class (`metrics/IngestionMetrics`) and the same three
  call sites — not a hard AC-level dependency, but sequence the two PRs.
- Story 4 (HTTP pool) is fully independent of every other story in this set and does not need
  `MetricsSafety` at all — the natural first pick if the team wants a same-afternoon early win.
- Story 7 is sequenced last by necessity — it scrapes and bounds the combined output of Stories 1–6.

**Cross-ticket coordination carried forward from Story 6, restated here for visibility at sprint
planning:** DD-43183's `JobCorrelationAspect` story advises the identical `ExecutableTask.execute`
join point as this ticket's `TaskRetryMetricsAspect` (Story 6). Both Stage-2 designs accept
`JobCorrelationAspect` ordered outermost, `TaskRetryMetricsAspect` left at default (lowest) precedence,
and both designs' note that DD-43182's original claim — "`cdk.metrics.enabled=false` removes the
proxying entirely" — is corrected once DD-43183 ships (proxying persists regardless of the flag once
both aspects exist; only this ticket's own recording is what the flag still stops). **Whichever of
DD-43182 Story 6 or DD-43183's equivalent story merges second must add the aspect-ordering test.**
Coordinate merge order directly with the DD-43183 story owner; do not assume either ticket's sequencing
from this document alone, since DD-43183 is at Stage 3 in parallel and its own sub-ticket numbering is
not yet available in this session.

**Not a story here** (per `01-requirements.md`'s Out of scope, unchanged at Stage 3): Prometheus alert
rules, recording rules, Grafana dashboards, or on-call routing (OQ-019 — a follow-up ticket owned by
platform/SRE, required before this ticket delivers any real value); fixing the ingestion phase model
(`UPLOADING`/`INGESTING` unreachable); any change to timeouts, retry budgets, pool sizes,
`disableAutomaticRetries()`, or the `RestClientFactory` build path's behaviour; fixing
`GenerateAnswerForQueryTask`'s missing `getRetryDurationsInSecs()` override; any new or changed REST
endpoint; any Flyway migration (none needed — `V1014` remains the highest, `V1015` stays free);
distributed tracing, log correlation, or OTLP metric export; re-litigating the DD-43185 meter-naming
convention; metrics for Artemis, HikariCP, JVM, or the inbound HTTP request path; retrospective/
backfilled metrics for work completed before first deployment; **detecting JobManager retry-budget
exhaustion, and therefore `cdk_task_retry_exhausted_total` and
`cdk_answer_generation_total{outcome="timed_out"}` — both descoped 2026-09-04 (OQ-022 / ADR-011),
with the fix escalated to `task-manager-service` as this ticket's highest-priority follow-up**; an
answer-generation duration timer
(OQ-021, confirmed non-trivial — needs a new persisted start anchor); a `trigger="manual"|"scheduled"`
dimension on the ingestion phase counter's `source` tag; splitting the ingestion duration timer into
"our leg" vs "RAG's leg"; a first-class exhaustion event in `task-manager-service`, or a
ShedLock-guarded gauge over abandoned `jobs` rows (both recorded as strong follow-up recommendations,
not built here); fixing the inert `CDK_JOBMANAGER_RETRY_DEFAULT_*` environment variables; fixing the
`RestClientFactory` shared-connection-manager mutation (OQ-015); fixing `AzureBlobStorageService`'s
discarded `TimeoutException` cause.

**Carried-forward follow-ups needing action before or shortly after this ticket ships**, for visibility
at sprint planning (none of these are stories in this set):
- **OQ-019 — the alert-rule/dashboard follow-up ticket itself, owned by platform/SRE.** Design states
  plainly that without it, this ticket ships signals nobody is watching, which does not meet the
  ticket's stated intent. Raise this ticket when this one is picked up, not after it ships.
- **OQ-018's second half** — platform/SRE confirmation that the scrape config and alert rules expect
  the `cdk_` prefix for these eight new names, inherited unchanged from DD-43185 ADR-001, now with a
  wider blast radius. Settle before any name is relied upon by an alert rule.
- **OQ-020** — security-reviewer sign-off that `/actuator/prometheus`'s exposure of `dependency`,
  `operation`, `task_name` and `retry_policy` tags (CDKS's internal call topology) plus the ingestion
  duration histogram (its performance profile) is acceptable for an OFFICIAL-SENSITIVE service, on top
  of what DD-43185 already established. Required before merge, not a story.
- **OQ-001** — Jira DD-43182's pasted brief was never confirmed against the live ticket/epic comments
  in this session (no Jira/Atlassian MCP tool available). Once the seven real sub-tickets above are
  cut and linked, this OQ still asks the requester to confirm the original pasted brief was complete
  and current before Stage 5 starts.
- **A `task-manager-service` change to publish a first-class retry-exhaustion event — promoted
  2026-09-04 from "strong recommendation" to *this ticket's highest-priority follow-up* (ADR-011(4)),
  because DD-43182 no longer delivers the capability without it.** Until it lands, retry-budget
  exhaustion is undetected in CDKS, `cdk_answer_generation_total` undercounts ended transactions, and
  a permanently-empty AI Search result caused by a spent polling budget has no detector anywhere.
  Concrete ask and acceptable alternative shapes are in Story 6's Notes and ADR-011(4). Needs
  external lead time with the library maintainers, so **raise it when Story 6 is picked up.**
- A ShedLock-guarded gauge over permanently-abandoned `jobs` rows
  (`SELECT count(*) FROM jobs WHERE retry_attempts_remaining = 0`) — a complementary CDKS follow-up
  measuring the accumulated *backlog*, including work abandoned before DD-43182 shipped, which no
  counter can. Its own ticket (it reaches into `task-manager-service`'s schema).
- The `cdk.jobmanager.retry.default` YAML-key/`defaultRetry`-field binding defect, the
  `RestClientFactory.build(...)` shared-connection-manager mutation (OQ-015), and
  `AzureBlobStorageService`'s discarded `TimeoutException` cause — three small, independently-ticketed
  defects this design discovered while instrumenting around them, none fixed by any story above.
