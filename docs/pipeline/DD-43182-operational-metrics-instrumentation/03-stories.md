# User Stories: Operational Metrics Instrumentation (Micrometer)

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43182.** Stage 1 (`01-requirements.md`) and Stage 2 (`02-design.md` +
> `../adrs/DD-43182-operational-metrics-instrumentation.md`, all ten ADRs `Accepted`, all six GATE
> items in design §14 accepted) are both approved. **No Jira/Atlassian MCP tool is available in this
> session** (consistent with OQ-001 throughout Stages 1–2), so the seven sub-tickets below are cut as
> placeholders — `DD-43182-1` … `DD-43182-7` — and **must be replaced with real Jira sub-tickets,
> linked to the parent epic DD-43182, before Stage 4 (Test Specs) starts**, per CLAUDE.md's hard rule
> that every story needs a linked ticket before the test stage.
>
> Acceptance criteria below are **derived from, not duplicated verbatim from**, `01-requirements.md`'s
> AC-001–AC-030, rescoped to each story's slice and updated for every accepted Stage-2 gate decision:
> the redefined `timed_out` (GATE-2), the fifth `outcome=error` value (GATE-1), the `retry_policy` tag
> (GATE-3), the `cdk_http_pool_connections_leased` alias (GATE-4), the extra
> `GenerateAnswerForQueryTask` abandonment-path counting (GATE-5), and the re-scoped AC-024
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
> editing the same aspect class for no independently-shippable value. They are combined into Story 6
> below.
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
`claude-generated` + `needs-review` labels applied, linked to parent epic DD-43182 · **real Jira
sub-ticket created and linked before Stage 4 starts** (placeholder used below, per the note above).

---

## Story 1 — Document-ingestion phase counter
**Jira: `DD-43182-1`** *(placeholder — replace with a real sub-ticket before Stage 4)*

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
- NFR-002 (Cardinality): registers 5 series at construction, ≤5 worst case (the `source="unknown"` fallback shares the same 5 `phase` values) — part of the ticket's 243-series worst case computed in `02-design.md` §12.
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
- **Shares its implementation class and call sites with Story 2.** Both live in `metrics/IngestionMetrics`
  and both are called from the same three sites (`IdpcAvailabilityService`,
  `RetrieveMaterialAndUploadTask`, `CheckIngestionStatusForAllDefendantsTask`); the third site calls
  both methods in the same edit (design §3–§4). Not a hard AC-level dependency — this story's ACs are
  independently testable — but sequencing the two PRs (rather than working them in parallel) avoids a
  merge conflict on the same three files.
- Jira sub-ticket: `DD-43182-1` *(placeholder)*.

---

## Story 2 — Ingestion duration timer (end-to-end)
**Jira: `DD-43182-2`** *(placeholder — replace with a real sub-ticket before Stage 4)*
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
- NFR-002 (Cardinality): 12 series per phase (9 buckets incl. `+Inf`, count, sum, max) × 3 phases = 36 series — the largest single contribution to the ticket's 243-series worst case, computed explicitly in `02-design.md` §12.
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
- Jira sub-ticket: `DD-43182-2` *(placeholder)*.

---

## Story 3 — Outbound dependency call timer
**Jira: `DD-43182-3`** *(placeholder — replace with a real sub-ticket before Stage 4)*

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
- [ ] AC-005: Given a downstream call exceeds its configured response timeout (180 s for RAG, 15 s for Hearing/Progression, 3 s on connect for all), then `outcome=timeout` is recorded, distinguishable from `server_error`; for `dependency=azure_blob`, `outcome=timeout` specifically means the `cp.cdk.storage.copy-timeout-seconds` (default 120 s) copy poll was exceeded, recorded through an explicit-outcome entry point inside `copyFromUrl` because that method's existing timeout path discards the identifying `TimeoutException` cause before it reaches an outside classifier.
- [ ] AC-006: A metric-recording failure (a pathological cause chain, a registry error) is contained and never affects the business call — the business result or the business exception propagates exactly as it would without instrumentation.

### NFR links
- NFR-001 (Data protection): `dependency`, `operation` and `outcome` are all fixed, closed, compile-time sets; `operation` is structurally incapable of carrying a path variable.
- NFR-002 (Cardinality): 11 `(dependency, operation)` pairs × 5 `outcome` values, count+sum+max only (no buckets) = 33 registered / 165 worst-case series — the second-largest contribution to the ticket's 243-series total.
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
- **Shared-infrastructure note** — see Story 1's Notes: this story is one of the five (1, 2, 3, 5, 6)
  that can create `metrics/MetricsSafety` / `config/MetricsProperties` / `metrics/CdkMetricsConfig` if
  delivered first, or reuse them if delivered after another of those five.
- Jira sub-ticket: `DD-43182-3` *(placeholder)*.

---

## Story 4 — HTTP connection-pool visibility
**Jira: `DD-43182-4`** *(placeholder — replace with a real sub-ticket before Stage 4)*
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
- [ ] AC-002: `cdk_http_pool_connections_leased` is also present, reads the identical in-memory leased count as the binder's own `state="leased"` series, and the two values agree at all times (asserted by an integration test that drives concurrent outbound calls and compares both series).
- [ ] AC-003: `httpcomponents_httpclient_pool_total_max` reports **200** and `httpcomponents_httpclient_pool_route_max_default` reports **50**, matching `RestClientFactoryConfig`'s configured maxima — so a consumer can express "the pool is approaching exhaustion" as a ratio without hard-coding either limit into an alert rule.
- [ ] AC-004: The gauges read from the single shared `PoolingHttpClientConnectionManager` bean, so they reflect all Apache-HttpClient outbound traffic (RAG, Progression, Hearing) regardless of which `RestClient` issued the call; `dependency=azure_blob` is explicitly documented as **not** covered, because `AzureBlobStorageService` uses the Azure SDK's own HTTP stack, not this pool.
- [ ] AC-005: Nothing in this story computes a value on scrape — the gauges read `ConnPoolControl.getTotalStats()`, an in-memory struct, at scrape time; no database query, remote call, or lock is touched.

### NFR links
- NFR-002 (Cardinality): 5 series from the framework binder + 1 alias = 6, at essentially zero incremental risk against the ticket's 243-series budget.
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
- This story does not need `metrics/MetricsSafety` — the gauge read cannot meaningfully throw in a way
  that needs containment (it is a struct field read, not a computed classification), so it has no
  dependency on whichever story creates the shared safety/config infrastructure described in Story 1's
  Notes.
- Recommended alert expressions (ratio of leased to max, and pending > 0) are documented in
  `02-design.md` §6 for the OQ-019 alert-rule owner — not built in this story.
- Jira sub-ticket: `DD-43182-4` *(placeholder)*.

---

## Story 5 — Answer-generation outcome counter
**Jira: `DD-43182-5`** *(placeholder — replace with a real sub-ticket before Stage 4)*

As a **production support engineer**,
I want **a counter that increments exactly once per answer-generation transaction, tagged with how it
ended and at what query level**,
so that **I can see the success/failure/give-up-waiting rate for AI Search answers, including the
transactions that silently vanish today with no signal at all**.

### Background
Design §8 / ADR-007 (accepted). `cdk.answer.generation` (renders as `cdk_answer_generation_total`) is
incremented at **six** points across `GenerateAnswerForQueryTask` and `CheckStatusOfAnswerGenerationTask`
— not the ticket's implied two or three. `outcome=timed_out` is **redefined** from the ticket's
unstated/unreachable meaning to something real and detectable: the `questions-retry` polling budget
(100 attempts × 10 s ≈ 17 minutes) being spent while RAG still reports `ANSWER_GENERATION_PENDING` —
detected using the same `TaskRetryDecision` predicate Story 6 introduces for JobManager exhaustion.
`outcome=failed` increments only when the `ANSWER_GENERATION_FAILED` re-dispatch budget is itself
spent (the existing `log.warn("Max retries reached…")` branch), not on every intermediate failure,
so one transaction cannot contribute up to 100 increments. `query_level="unknown"` is used, never
omitted, when `TaskUtils.parseQueryLevel` returns `null`. `GenerateAnswerForQueryTask`'s three own
terminal abandonment paths (missing identifiers, no `QueryDefinitionLatest`, and a RAG-start failure
that can never actually be retried) are also counted — without them, the counter's total would not
equal "transactions that ended", and `succeeded / total` would not be a true success rate.

### Acceptance criteria
- [ ] AC-001: Given an answer-generation transaction reaches `ANSWER_GENERATED`, then `cdk_answer_generation_total{outcome="succeeded"}` increments by exactly 1, tagged with the transaction's `query_level`.
- [ ] AC-002: Given `CheckStatusOfAnswerGenerationTask` sees `ANSWER_GENERATION_FAILED` and the re-dispatch retry budget is spent, then `outcome="failed"` increments by exactly 1; given the budget is **not** yet spent, the task re-dispatches and **no** increment occurs — a transaction whose answer eventually succeeds after several `ANSWER_GENERATION_FAILED` cycles contributes exactly one increment in total, not one per cycle.
- [ ] AC-003: Given the `questions-retry` polling budget is spent while the transaction is still `ANSWER_GENERATION_PENDING` (or an equivalent null/non-2xx response) — i.e. `TaskRetryDecision.willBeRetried(...)` returns false on that path — then `outcome="timed_out"` increments by exactly 1; a `catch (Exception)` path reaching the same exhausted-budget state increments `outcome="failed"`, not `timed_out`, because a dependency error and a give-up-waiting event are different incidents.
- [ ] AC-004: Given `GenerateAnswerForQueryTask` ends a transaction via missing identifiers, a missing `QueryDefinitionLatest`, or a RAG-start failure (which per Story 6's predicate can never actually be retried), then `outcome="failed"` increments by exactly 1 for each — these three paths are currently invisible and must not remain so.
- [ ] AC-005: `outcome` takes exactly one of `succeeded`, `failed`, `timed_out`; `query_level` takes one of `CASE`, `DEFENDANT`, `CASE_ALL_DOCUMENTS` (verbatim `QueryLevel` constants) or `unknown` when `parseQueryLevel(...)` returns `null` — the increment is never omitted for a null `query_level`.
- [ ] AC-006: Tracing every path through the pair of tasks, exactly one of the six increment points fires per transaction — no path double-counts (e.g. the success handoff from `GenerateAnswerForQueryTask` to `CheckStatusOfAnswerGenerationTask` increments nothing) and no path is silently uncounted.
- [ ] AC-007: A metric-recording failure in outcome/level computation is contained and does not affect the task's own return value, its persisted job data, or its `ExecutionInfo`.

### NFR links
- NFR-001 (Data protection): `outcome` (3 values) and `query_level` (4 values) are both fixed, closed sets; no case, query or transaction identifier in either tag.
- NFR-002 (Cardinality): 3 × 4 = 12 series, pre-registered at construction — part of the ticket's 243-series worst case.
- NFR-004 (Availability): a throwing outcome/level computation must not fail either task or alter its `ExecutionInfo`.
- NFR-005 (Backward compatibility): the one behaviour-neutral code move (hoisting `query_level` parsing to the top of `CheckStatusOfAnswerGenerationTask.execute`) has no side effects and cannot throw.

### Out of scope for this story
- An answer-generation **duration** timer — confirmed intentionally out of scope (OQ-021/ADR-007(7)): there is no persisted start timestamp to compute one from, unlike Story 2's ingestion duration, so it is not a cheap analogue and needs its own ticket with that cost stated.
- `cdk_task_retry_exhausted_total{task_name="CHECK_STATUS_OF_ANSWER_GENERATION"}` — Story 6. This story's `timed_out` and that counter fire on the same underlying event by design (documented as two views of one event, not duplicates) but are built and tested independently.
- Any change to the `questions-retry` budget (100 × 10 s), or to `GenerateAnswerForQueryTask`'s missing `getRetryDurationsInSecs()` override — both are pre-existing, out-of-scope behaviour this story only makes measurable.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `AnswerGenerationMetricsTest` for series pre-registration and enumeration bounds; extended `CheckStatusOfAnswerGenerationTaskTest` and `GenerateAnswerForQueryTaskTest` covering all six rows of the outcome table, including the "budget not yet spent → zero increments" case; integration: outcome series visible and correctly tagged on `/actuator/prometheus`).
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green; CodeQL and secrets-scanner clean.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures synthetic.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- **GATE-2 (accepted at the Stage-2 gate):** `timed_out`'s redefinition — from an unreachable/unstated
  ticket value to "abandoned while still `ANSWER_GENERATION_PENDING`" — is a redefinition of a
  ticket-specified tag value's meaning. Already accepted; restated so the sub-ticket carries the
  decision forward.
- **GATE-5 (accepted at the Stage-2 gate):** counting `GenerateAnswerForQueryTask`'s three abandonment
  paths widens the ticket's stated scope (which named only `CheckStatusOfAnswerGenerationTask`'s
  states). Already accepted.
- **Soft dependency on Story 6's `TaskRetryDecision` predicate.** `timed_out` detection reuses the same
  `willBeRetried(ExecutionInfo, ExecutableTask)` predicate Story 6 introduces for JobManager retry
  exhaustion (design §7/§8 share the class). If Story 6 has not yet landed when this story starts,
  this story must introduce `metrics/TaskRetryDecision` itself; if Story 6 lands first, this story
  reuses it. Either order works — flag at sprint planning so the second-delivered story's PR is scoped
  as "reuse" rather than "create".
- **Shared-infrastructure note** — see Story 1's Notes: this story is one of the five (1, 2, 3, 5, 6)
  that can create or must reuse `metrics/MetricsSafety` / `config/MetricsProperties` /
  `metrics/CdkMetricsConfig`.
- Jira sub-ticket: `DD-43182-5` *(placeholder)*.

---

## Story 6 — JobManager retry and retry-exhaustion counters
**Jira: `DD-43182-6`** *(placeholder — replace with a real sub-ticket before Stage 4)*
**Cross-ticket coordination required with DD-43183 — see the dedicated section below. Not a strict
code dependency (implementation can proceed independently), but merge order, aspect ordering and the
ordering test must be coordinated before either PR merges.**

As a **production support engineer**,
I want **counters for every JobManager retry that will actually be granted, and for the exact moment a
task's retry budget is exhausted**,
so that **I can see "work is being silently abandoned" as a first-class signal — a `jobs` row that is
permanently orphaned today produces no log line, no alert and no metric anywhere in this service**.

### Background
Design §7 / ADR-006 (accepted). Stage 1 concluded retry exhaustion was "not obtainable from CDKS
code" because `task-manager-service` exposes no hook. Design overturned this on bytecode evidence:
`ExecutionInfo.getRetryAttemptsRemaining()` is public and carries **the same value**
`TaskExecutor.canRetry(...)` tests internally, so the library's retry decision is fully predictable
from inputs a task already has. One shared predicate, `metrics/TaskRetryDecision.willBeRetried(...)`,
replicates `canRetry` exactly. **One** `@Aspect`, `metrics/TaskRetryMetricsAspect`, applied
`@Around` `ExecutableTask.execute(..)`, computes both counters at the same call site — no task
business logic changes at all, and the seven `@Task` beans are not edited. `cdk.task.retry` counts
retries that will actually be **granted** (not merely requested); `cdk.task.retry.exhausted` counts
the one moment a budget runs out, which per the library's own assignment query happens **exactly
once** per job (an exhausted row is never re-selected). Both counters carry a second tag,
`retry_policy` (`default-retry`, `verify-document-status`, `questions-retry`, `none`), functionally
determined by `task_name` at **zero** extra series cost, answering FR-012's documentation requirement
in the metric itself. `GENERATE_ANSWER_FOR_QUERY` is documented, not fixed, as a task that can never
be retried (`retry_policy="none"`) — the metric surfaces this pre-existing defect rather than papering
over it.

### Acceptance criteria
- [ ] AC-001: Given a JobManager task returns `ExecutionInfo(INPROGRESS, shouldRetry=true)` (or throws, and `TaskExecutor` synthesises the same outcome outside CDKS code) with `getRetryAttemptsRemaining() > 0` and the task's own `getRetryDurationsInSecs()` present, then `cdk_task_retry_total{task_name=<the task's TaskNames constant>, retry_policy=<its policy>}` increments by exactly 1 — this covers the throw path (live today in the unguarded `CheckAllDocumentsIngestionStatusTask.execute`), not only the explicit `retry(...)` helper paths.
- [ ] AC-002: Given the same `INPROGRESS`+`shouldRetry` outcome but `getRetryAttemptsRemaining()` is `0`, or `getRetryDurationsInSecs()` is empty (the `GENERATE_ANSWER_FOR_QUERY` shape), then `cdk_task_retry_exhausted_total{task_name=..., retry_policy=...}` increments by exactly 1 instead — the two counters partition the `INPROGRESS` outcome exactly; every `INPROGRESS`-returning execution increments precisely one of them, never both, never neither.
- [ ] AC-003: `task_name` is read from the target class's `@Task` annotation (via `AopUtils.getTargetClass`, proxy-aware) and is membership-checked against the seven `TaskNames` values before any increment; a target with no `@Task`, or a value outside the seven, records nothing — no other value can ever be emitted.
- [ ] AC-004: `retry_policy` takes exactly one of `default-retry`, `verify-document-status`, `questions-retry`, `none`, determined solely by `task_name`, adding a label to the existing 7+7 series at **zero** additional series cost.
- [ ] AC-005: A task that returns `COMPLETED` records nothing on either counter; the aspect never alters the returned `ExecutionInfo` or rethrows anything other than the exact exception instance the task threw.
- [ ] AC-006: An integration test drives a task to genuine exhaustion against a shortened compose retry budget and asserts **both** that `cdk_task_retry_exhausted_total` incremented for that `task_name` **and** that the corresponding `jobs` row has `retry_attempts_remaining = 0` and is never re-executed — tying the CDKS-side replicated predicate to the library's actual behaviour, so a future `task-manager-service` bump that changes `canRetry` fails this test instead of silently making the counter wrong.
- [ ] AC-007: In-repo documentation (`CdkMeters` Javadoc) identifies `cdk_task_retry_exhausted_total` as the primary "work is being silently abandoned" signal, lists all seven `task_name` values, states each one's **effective** governing budget (not the YAML's stated budget — `cdk.jobmanager.retry.default` is confirmed not to bind, so the *effective* default-retry budget is the Java field default of 3×20s, which happens to match today but is documented as the effective value, not the configured one), and states plainly that `GENERATE_ANSWER_FOR_QUERY` cannot be retried at all.
- [ ] AC-008: A metric-recording failure inside the aspect's advice is contained — the target task's return value, thrown exception and `ExecutionInfo` are unaffected, whatever the recording failure.

### NFR links
- NFR-001 (Data protection): `task_name` (7 values) and `retry_policy` (4 values, determined by `task_name`) are both fixed sets sourced from compile-time constants, never from job data.
- NFR-002 (Cardinality): 7 + 7 = 14 series, `retry_policy` adding a label at zero extra series cost.
- NFR-004 (Availability): the aspect must never alter a task's return value or exception, and a failing recording must not affect task execution.
- NFR-005 (Backward compatibility): zero lines of the seven `@Task` beans' business logic change — the strongest available position against a regression in JobManager task behaviour.
- NFR-009 (Configurability): `TaskRetryMetricsAspect` is the **one** bean in this ticket gated by `@ConditionalOnProperty("cdk.metrics.enabled")` — disabling the flag removes the bean (and, **until DD-43183 ships**, the CGLIB proxying of the seven task beans with it; see the coordination note below for what changes once DD-43183 lands).

### Out of scope for this story
- Any change to the seven `@Task` beans' business logic, retry budgets, or `getRetryDurationsInSecs()` overrides (including fixing `GenerateAnswerForQueryTask`'s missing override) — documented as a defect, not fixed, per the ticket's own out-of-scope list.
- Raising a `task-manager-service` change to publish a first-class exhaustion event, or a ShedLock-guarded gauge over `jobs.retry_attempts_remaining = 0` — both recorded as strong follow-up recommendations (ADR-006), not built here.
- Fixing the confirmed-inert `CDK_JOBMANAGER_RETRY_DEFAULT_MAX_ATTEMPTS` / `CDK_JOBMANAGER_RETRY_DEFAULT_DELAY_SECONDS` environment variables (the `cdk.jobmanager.retry.default` YAML key does not bind to `JobManagerRetryProperties.setDefaultRetry(...)`) — a separate defect ticket, only documented here.
- DD-43183's `JobCorrelationAspect` itself and its MDC-restoration behaviour — a different ticket's story; this story only coordinates ordering and adds the ordering test against whichever of the two aspects exists at the time this story is implemented.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests (unit: `TaskRetryDecisionTest` pinning the replicated `canRetry` predicate against every input combination, including the `GENERATE_ANSWER_FOR_QUERY` shape; `TaskRetryMetricsAspectTest` covering the return path, the throw path, `COMPLETED`, and the AC-003 membership check; integration: `TaskRetryExhaustionHttpLiveTest` driving genuine exhaustion in compose and asserting both the counter and the `jobs` row state per AC-006).
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
- **The one genuine implementation liability in this story** (ADR-006's own words): CDKS now holds a
  *replica* of a library predicate (`TaskExecutor.canRetry`). AC-006's exhaustion integration test is
  the mitigation, not a comment — do not treat it as optional coverage.
- **Shared-infrastructure note** — see Story 1's Notes: this story is one of the five (1, 2, 3, 5, 6)
  that can create or must reuse `metrics/MetricsSafety` / `config/MetricsProperties` /
  `metrics/CdkMetricsConfig`. This story additionally has a **hard** dependency on
  `config/MetricsProperties`/`metrics/CdkMetricsConfig` existing before `TaskRetryMetricsAspect` can
  compile its `@ConditionalOnProperty("cdk.metrics.enabled")` annotation — if this story is picked up
  before Stories 1, 2, 3 or 5, it must create those two classes itself.
- **Soft dependency, either order, with Story 5** — both stories use `metrics/TaskRetryDecision`. See
  Story 5's Notes.
- Jira sub-ticket: `DD-43182-6` *(placeholder)*.

---

## Story 7 — Cardinality budget, scrape-time bound and cross-cutting safety harness
**Jira: `DD-43182-7`** *(placeholder — replace with a real sub-ticket before Stage 4)*
**Depends on Stories 1–6 for its whole-endpoint series-count and full-surface assertions; the
`baseline-series-count.md` capture itself should happen as early as possible — see Notes.**

As a **CDKS developer / release engineer**,
I want **the whole ticket's series count and scrape time measured and bounded by a merge-blocking
test, every new meter name/tag value pinned in `CdkMeters`' Javadoc, and the failure-containment path
proven with an injected throwing registry**,
so that **the ticket can be merged and deployed with confidence that it does not silently blow the
2,000-series budget or the scrape-time budget, and that a metrics bug can never take down the business
path it instruments**.

### Background
Design §9, §10, §12 / ADR-001, ADR-005, ADR-010 (all accepted). This story is where the six other
stories' individually-computed series counts are proven against the whole-endpoint reality: **243
series worst case** for this ticket (106 registered at construction), **257** combined with DD-43185's
unchanged 14. The 2,000-series budget is met with roughly 8× headroom, achieved specifically by
declining `percentiles-histogram` and putting explicit SLO buckets on the ingestion timer only (Story
2), never on the external-call timer (Story 3). Two deliverables make this a proof rather than an
assertion: a **`baseline-series-count.md`** artefact recording the measured pre-DD-43182 whole-endpoint
series count, and a **merge-blocking** `integrationTest` asserting the total stays under a ceiling
tighter than 2,000 in the compose stack (design proposes 1,200, to be fixed once the real baseline is
measured), because the compose stack's series count is a *lower* bound on production's. **AC-024 is
re-scoped at the accepted GATE-6**: the "under 1 second" scrape-time bound becomes a CI smoke bound
(design proposes 2 s) in the compose stack, plus a one-off production timing captured in
`deploy-notes.md` — a hard sub-second assertion on shared CI hardware is a flaky test, not a guarantee.
This story also owns the shared `metrics/MetricsSafety` failure-containment helper's own direct test
coverage (as opposed to each area's own containment AC, which exercises it indirectly) and the final
`CdkMeters` Javadoc pass covering every meter this ticket adds.

### Acceptance criteria
- [ ] AC-001: When `/actuator/prometheus` is scraped against the compose stack with all six DD-43182 stories deployed, then every metric named in `02-design.md` §2 is present, including every counter/timer series that has not yet been incremented (pre-registered at value `0`).
- [ ] AC-002: Every `cdk_*` series added by this ticket carries the existing common tags `service`, `cluster` and `region` from `management.metrics.tags`.
- [ ] AC-003: `baseline-series-count.md` records the measured whole-endpoint series count on the compose stack **before** this ticket's changes are present, captured the same way DD-43185's `baseline-actuator-prometheus.md` was captured; a merge-blocking integration test then asserts the whole-endpoint series count **after** this ticket's changes is below a stated ceiling tighter than 2,000, with the compose-is-not-production reasoning stated in the assertion's own failure message.
- [ ] AC-004 *(re-scoped per GATE-6, accepted)*: A CI smoke-bound assertion (design proposes 2 seconds) on `/actuator/prometheus` scrape time in the compose stack, explicitly labelled in its assertion message as a CI smoke bound rather than a production guarantee; a one-off production scrape timing is captured separately in `deploy-notes.md` at Stage 8.
- [ ] AC-005: Given metric recording throws for any reason (registry failure, tag-computation failure), when the surrounding business operation runs (an ingestion phase write, an outbound call, a JobManager task execution, an answer-generation state transition), then it completes exactly as it would without instrumentation — same HTTP status/body, same persisted phase, same `ExecutionInfo`, same propagated exception, no RAG response field dropped or altered — proven with an injected throwing `MeterRegistry`/meter across all four business paths, not just one.
- [ ] AC-006: Given repeated metric-recording failures, a WARN is logged at most once per 60 seconds globally (not once per site, not once per occurrence), the suppressed-failure count is included in the WARN line, the line contains no case content/case identifier/`CJSCPPUID`, and it is emitted as structured JSON via the existing `logback-spring.xml`.
- [ ] AC-007: `CdkMeters`' Javadoc mapping table is extended to cover all seven new meters plus the pool binder, states the Timer naming rule (no `.seconds` segment on a registered Timer name), and documents `cdk_task_retry_exhausted_total` as the primary "work is being silently abandoned" signal per Story 6's AC-007.
- [ ] AC-008: `ActuatorHttpLiveTest`, `MonitoringMetricsHttpLiveTest` and `SchedulerMetricsHttpLiveTest` pass with their existing assertions completely unmodified; DD-43185's six meter names and 14 series, and every existing timeout, retry budget, pool size and cron expression, are unchanged.
- [ ] AC-009: `gradle clean build` (including `integration`) passes end-to-end for the whole ticket; PMD and JaCoCo are green at existing, unmodified thresholds; CodeQL and the secrets scanner are clean.
- [ ] AC-010: The full diff across Stories 1–7 introduces no PII, case content, court reference number, or `CJSCPPUID` into code, config, tests or fixtures; every fixture value is synthetic.

### NFR links
- NFR-002 (Cardinality): the direct deliverable for NFR-002's "computed and recorded" requirement — this story is where the 243/257-series arithmetic is proven against a running scrape, not just written down in the design doc.
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
- **The `baseline-series-count.md` capture should happen as early as practical — ideally before Story
  1 starts, not deferred to this story's own start.** It is a "pre-implementation" measurement by
  definition (§12); capturing it late means comparing against a partially-instrumented baseline instead
  of the true starting point. The **ceiling assertion** itself (comparing the post-ticket count against
  a stated number) is this story's own deliverable and is correctly sequenced last.
- **Whichever of Stories 1, 2, 3, 5 or 6 lands first is responsible for `metrics/MetricsSafety` and
  `config/MetricsProperties`/`metrics/CdkMetricsConfig`** (see Story 1's Notes) — this story's own
  `MetricsSafetyTest` exercises that shared class regardless of which earlier story created it, and
  should be scoped as "add missing coverage" rather than assume it is untested.
- Jira sub-ticket: `DD-43182-7` *(placeholder)*.

---

## Summary

| Story | Title | Jira (placeholder) | Depends on | Area |
|---|---|---|---|---|
| 1 | Document-ingestion phase counter | `DD-43182-1` | none | A(i) |
| 2 | Ingestion duration timer (end-to-end) | `DD-43182-2` | shares class/call sites with Story 1 (soft) | A(ii) |
| 3 | Outbound dependency call timer | `DD-43182-3` | none | B(i) |
| 4 | HTTP connection-pool visibility | `DD-43182-4` | none — smallest, could ship first alone | B(ii) |
| 5 | Answer-generation outcome counter | `DD-43182-5` | shares `TaskRetryDecision` with Story 6 (soft) | C |
| 6 | JobManager retry and retry-exhaustion counters | `DD-43182-6` | shares `TaskRetryDecision` with Story 5 (soft); **cross-ticket coordination with DD-43183's `JobCorrelationAspect` story (ordering + merge sequencing, not a code dependency)** | D |
| 7 | Cardinality budget, scrape-time bound and cross-cutting safety harness | `DD-43182-7` | Stories 1–6 | E |

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
backfilled metrics for work completed before first deployment; an answer-generation duration timer
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
- A `task-manager-service` change to publish a first-class retry-exhaustion event, and a
  ShedLock-guarded gauge over permanently-abandoned `jobs` rows — both recorded in Story 6's Notes as
  strong recommendations, each needing its own ticket and, for the first, external lead time with the
  library maintainers.
- The `cdk.jobmanager.retry.default` YAML-key/`defaultRetry`-field binding defect, the
  `RestClientFactory.build(...)` shared-connection-manager mutation (OQ-015), and
  `AzureBlobStorageService`'s discarded `TimeoutException` cause — three small, independently-ticketed
  defects this design discovered while instrumenting around them, none fixed by any story above.
