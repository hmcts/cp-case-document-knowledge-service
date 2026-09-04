# Test Specs: Unified Correlation-ID Handling and Trace Propagation

> **Stage 4 — Test Specs** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43183** · Stories: [`03-stories.md`](./03-stories.md) · Design: [`02-design.md`](./02-design.md) ·
> Requirements: [`01-requirements.md`](./01-requirements.md) ·
> ADRs: [`adrs/DD-43183-correlation-id-unification.md`](../adrs/DD-43183-correlation-id-unification.md)
> (ADR-001 – ADR-008, all **Accepted** at the Stage-2 gate on 2026-09-03 — not reopened here).
>
> **Written prospectively — no implementation exists yet (A-TDD).** Nothing below is evidence of
> coverage. Every scenario states **"To be proven by:"**, which names a test *to write*, not a test
> that passed. None of the named tests can compile until Stage 5 lands the production classes
> described in design §3–§10: `correlation/CorrelationIds`, `correlation/CorrelationScope`,
> `correlation/JobCorrelationAspect`, `correlation/JobExecutorMdcBeanPostProcessor`, the
> `config/RequestContextFilter` rewrite, the `http/CorrelationIdInterceptor` rewrite, the
> `controllers/GlobalExceptionHandler` rewrite, and the `application-server-management.yml`
> property migration.
>
> **One exception, and only one.** Story 2's **AC-007 (GATE-6)** is already implemented and tested:
> the `DebugLoggingInterceptor` credential-redaction fix shipped independently as **PR #225**
> (commit `cafc3dc`, 2026-09-03), and `DebugLoggingInterceptorTest` already exists and passes.
> Scenario 2.9 below is therefore a **regression-confirmation** scenario, not a test to write —
> it is the only scenario in this document that describes existing, passing coverage. See OQ-104
> for the one thing that still needs doing about it.
>
> **Test-authoring order follows the story dependency chain** (`03-stories.md` §Summary).
> **Story 1 must be written and landed first**: `CorrelationIds` and `CorrelationScope` are shared
> infrastructure that Stories 2–5 and 7 all assert against, so no test in those stories can compile
> before Story 1's production classes exist. **Story 6 is independent** and may be authored at any
> point, in parallel with any other story. **Story 7 is authored last** — its scenarios exercise
> mechanisms built by every other story.
>
> **Jira linkage — unresolved and blocking.** `03-stories.md` carries placeholder sub-tickets
> `DD-43183-1` – `DD-43183-7` only. CLAUDE.md's hard rule is *"Every story needs a linked Jira
> ticket before the test stage."* Real sub-tickets must be created and linked to the parent epic
> **before any test below is written**. This document uses the placeholders throughout and does not
> pretend otherwise. See OQ-101.

---

## Scope boundaries this document inherits and does not attempt to work around

1. **This ticket asserts against a correlation ID, never against a trace ID's *shape*.** Design §2
   verified that a real 32-hex OTel trace ID already exists on every request on this classpath —
   Stage 1's `Tracer.NOOP` → `""` premise is wrong. The consequence for testing is stated once here
   and repeated where it bites: **a `matches("[0-9a-f]{32}")` assertion, and a bare non-null
   assertion, would both pass today against the defect.** Neither may be used as an oracle anywhere
   in this document. The oracles are *non-blank* **and** *equal to the response header* **and**
   *equal to the `correlationId` log field* (ADR-005(4)).
2. **No contract tests.** No API, OpenAPI model, schema or ACL change anywhere in this ticket
   (design §11). `src/pactVerificationTest/` is untouched; `api-cp-crime-caseadmin-case-document-knowledge`
   stays at `0.0.11` and `api-cp-ai-rag` at `0.0.15`. Story 7's AC-008 asserts exactly this and
   nothing more.
3. **No accessibility tests.** CDKS is backend-only (CLAUDE.md's "Not applicable in this repo"
   list). The WCAG 2.1 AA hard rule applies to downstream consumers of CDKS's API, not to a
   correlation header.
4. **No Flyway migration, therefore no `migration-reviewer` involvement and no migration test.**
   Highest shipped version is unchanged.
5. **AC-030-style collector evidence is not automatable from this repository.** Story 6's AC-007
   (spans visible in a collector) is a manual, two-environment-variable demonstration whose
   deliverable is a screenshot on the Jira ticket. Scenario 6.7 states the procedure; it is
   explicitly **not** a test, and no scenario claims to prove it.
6. **Log-field assertions at the integration tier have no existing seam.** `AbstractHttpLiveTest`
   exposes HTTP (`RestTemplate`) and JDBC only; the compose stack is driven by the
   `com.avast.gradle.docker-compose` Gradle plugin, which hands tests host/port system properties
   and **no container handle**, so there is no `getLogs()` equivalent. Every scenario below that
   needs to read an emitted JSON log line is flagged and routed through **OQ-102**, which must be
   decided before those scenarios are written. Where a unit-tier `ListAppender` can prove the
   *event shape* but not the *JSON encoding*, both halves are stated separately and the unit test is
   not claimed to prove the encoding.

---

## The contract under test (design §12, ADR-001, ADR-002, ADR-007)

Every scenario asserts against exactly these. No test may introduce a header name, MDC key or
validation rule outside this table.

**Headers**

| Direction | Name | Role |
|---|---|---|
| Inbound | `CPPCLIENTCORRELATIONID` | **Canonical**, matched case-insensitively |
| Inbound | `X-Correlation-Id` | Accepted alias, deprecated, honoured indefinitely |
| Inbound | bare `traceId`, bare `spanId`, `X-Request-ID` | **Not read.** A test must prove they are ignored, not merely unused |
| Inbound | `traceparent` | Boot's propagator's business. **Not** a correlation alias — no CDKS code reads it |
| Response | `X-Correlation-Id` | The resolved value, on **every** response |
| Response | `traceId`, `spanId` | **WITHDRAWN** (GATE-2). A test must prove they are absent |
| Outbound | `CPPCLIENTCORRELATIONID` **and** `X-Correlation-Id` | Both carry the resolved value |
| Outbound | `X-Request-ID` | **Removed.** Must be absent from every outbound request |

**MDC keys and their owners**

| Key | Owner | Test consequence |
|---|---|---|
| `correlationId` | CDKS (`CorrelationIds.MDC_KEY`) | The one value to assert on. Read into `DiscoveryTriggerResponse.correlationId`; returned as `ErrorResponse.traceId` |
| `traceId`, `spanId` | **Micrometer Tracing** — reserved | `MdcReservedKeyTest` asserts no `src/main` file contains an `MDC.put` of either |
| `cluster`, `region`, `path` | CDKS (`RequestContextFilter`) | Unchanged; no scenario asserts against them beyond "still present" |
| `caseId`, `docId`, `transactionId` | CDKS (Stories 3 and 5) | **Absent rather than sentinel** when not applicable |
| `job`, `trigger`, `discoveryOperation` | CDKS (DD-43062/63/85) | Unchanged. `StalledWorkMetrics`'s `job` key must survive Story 5's rework |
| `applicationName` | **Removed** with `TracingFilter` | A test asserts it is no longer written |

**Job-data key:** `requestId` (`JobManagerKeys.Params.REQUEST_ID`) — carries the correlation ID; a
persisted wire format; deliberately a *different* name from the MDC key (ADR-003). Confirmed present
in the source at `JobManagerKeys:30`.

**Value rules (ADR-007), applied to inbound headers *and* `jobData` alike:**

| Rule | Value | Test consequence |
|---|---|---|
| Allow-list | `[A-Za-z0-9._:-]` | A rejected character regenerates the whole value |
| Length | 1–64 characters inclusive | The **65-character boundary** must be tested, not just "a long value" |
| On violation | **reject the whole value, generate a fresh UUID** | Never sanitise-in-place; never truncate |
| On violation, logging | one WARN carrying header name + length + reason code (`illegal-character` / `too-long`) | A test asserts the WARN **does not contain the rejected value** |
| Never | fail the request | `assertThatCode(...).doesNotThrowAnyException()` on every rejection path |

**The seven `@Task` beans** (confirmed in the source; Stage 1's count of 7, not 8, is correct — the
`cdks-context.md` "5 caseflow tasks" claim is drift, OQ-014): `GET_CASES_FOR_HEARING`,
`GENERATE_ANSWER_FOR_QUERY`, `CHECK_STATUS_OF_ANSWER_GENERATION`,
`CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS`, `CHECK_ALL_DOCUMENTS_INGESTION_STATUS`,
`RETRIEVE_MATERIAL_AND_UPLOAD`, `CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS`.

---

## Filter-order facts verified for this stage (they decide two scenarios)

Design §3 moves `RequestContextFilter` from `@Order(HIGHEST_PRECEDENCE + 1)` to `+10`. Two
consequences were verified from the resolved classpath during this stage, because Story 1's AC-006
("on 4xx/5xx alike") depends on them and neither is stated in Stages 1–3:

- **`cp-auth-rules-filter` 1.0.7 registers `cppHttpAuthzFilter` at order `-2147483618`** =
  `Ordered.HIGHEST_PRECEDENCE + 30`, unless `authz.http.filter-order` is set — and CDKS does not set
  it (`application-other.yml` configures `authz.http.*` but no `filter-order`). So at `+10` the
  correlation filter runs **before** the authz filter, and a `403` written by the authz filter is
  emitted on a response whose `X-Correlation-Id` header is already set. **Scenario 1.11 asserts
  exactly this**, and it is the cheapest available proof that AC-006's 4xx half is real rather than
  assumed.
- **`OncePerRequestFilter.shouldNotFilterErrorDispatch()` defaults to `true`.** Design §3 makes
  `RequestContextFilter` a `OncePerRequestFilter`, so it will **not** re-run on Boot's `ERROR`
  dispatch, and — because its `finally` has already restored the prior MDC map by then — any log
  line emitted by `BasicErrorController` on that dispatch carries **no** `correlationId`. Whether
  the response header survives the ERROR dispatch is a container-behaviour question this stage
  cannot settle by reading code. **Raised as OQ-103 with a concrete probe scenario (1.12)**, not
  assumed either way.

---

## Test inventory — files to create, extend, or delete

| Tier | File | Action | Story |
|---|---|---|---|
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/correlation/CorrelationIdsTest.java` | **new** | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/correlation/CorrelationScopeTest.java` | **new** | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/config/RequestContextFilterTest.java` | **rewrite** (assertions preserved; mock types change) | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/correlation/MdcReservedKeyTest.java` | **new** (re-run in Story 7) | 1, 7 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/filters/tracing/TracingFilterTest.java` | **DELETE** with the filter | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/logging/TracingIntegrationTest.java` | **DELETE or rewrite** | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/logging/TestTracingConfig.java` | **DELETE** (asserts a fiction — design §2.5) | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/logging/TracingProbeController.java` | **DELETE or repurpose** (support class for the above) | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/http/CorrelationIdInterceptorTest.java` | **rewrite** — all four current methods assert the deleted behaviour | 2 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/http/DebugLoggingInterceptorTest.java` | **exists and passes** (PR #225) — unmodified, run as regression | 2 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/correlation/JobCorrelationAspectTest.java` | **new** | 3 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/correlation/JobCorrelationProxyingTest.java` | **new** (Spring context; carries the GATE-3 ordering assertion) | 3 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/correlation/JobExecutorMdcLeakTest.java` | **new** in 3, **re-run** in 7 | 3, 7 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/jobmanager/JobDataCorrelationSeedTest.java` | **new** (four dispatch sites) | 3 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/controllers/GlobalExceptionHandlerTest.java` | **rewrite** — currently mocks `Tracer`/`Span`/`TraceContext` | 4 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/clients/rag/RagAnswerAsyncServiceImplTest.java` | extend | 5 |
| Unit | per-service tests for `IdpcAvailabilityService`, `IngestionProcessorByCaseService`, `IngestionService`, `DocumentService` | extend | 5 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/metrics/StalledWorkMetricsTest.java` | extend (MDC behaviour is unasserted today — verified) | 5 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/config/ConfigurationMetadataAuditTest.java` | **new** | 6 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/config/TracingConfigurationTest.java` | **new** | 6 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/correlation/MdcVirtualThreadIsolationTest.java` | **new** | 7 |
| Integration | `src/integrationTest/java/uk/gov/hmcts/cp/cdk/http/CorrelationPropagationHttpLiveTest.java` | **new** | 1, 2 |
| Integration | `src/integrationTest/java/uk/gov/hmcts/cp/cdk/http/CorrelationLogFieldHttpLiveTest.java` | **new — blocked on OQ-102** | 4, 5 |
| Integration | `src/integrationTest/java/uk/gov/hmcts/cp/cdk/http/DiscoverySchedulerTriggerHttpLiveTest.java` | **unmodified — run as regression** | 1, 7 |
| Integration | `DiscoverySchedulerTriggerAclHttpLiveTest` | extend by one assertion (the 403 response header) | 1 |
| Integration | existing JobManager live tests (`CheckStatusOfAnswerGenerationRagTransactionIdLiveTest`, `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest`, `IngestionProcessByCaseHttpLiveTest`) | **unmodified** as regression; one may gain a correlation assertion (OQ-102) | 3, 7 |
| Integration | full existing live suite | **unmodified — run as regression** | 7 |
| Config | `docker/docker-compose.integration.yml` | **extend — see OQ-105** (`OTEL_TRACES_URL` / `OTEL_METRICS_URL` still carry the pre-spec paths) | 6 |

**Naming convention** (house style, matching what is already in these files):
`<method>_should<Outcome>_when<Condition>` or `should<Outcome>_when<Condition>`; one style per class,
consistent with that class's existing methods. Live tests: `<subject>_<behaviour>`.

**Log assertions use the established in-repo idiom** — a logback `ListAppender<ILoggingEvent>` on
the class logger, as `DiscoveryTriggerServiceTest` does (added by DD-43063). No new logging test
library, and no assertion that a `ListAppender` proves JSON encoding.

**Downstream-request assertions use WireMock's request journal.** WireMock 3.9.1 runs as a compose
service on `localhost:8089` with its admin API enabled (`/__admin/health` is already its healthcheck),
and the app's `CP_CDK_RAG_URL` and `CP_CDK_BASE_URL` both point at it — so `GET /__admin/requests`
or `POST /__admin/requests/find` can assert the headers CDKS actually sent. **No existing
integration test uses `/__admin`**, so this is a new-but-available idiom rather than an established
one; the journal is enabled by default and needs no stub change.

---

## Story 1 — One documented inbound convention; `TracingFilter` deleted (`DD-43183-1`)

Targets the new `correlation/CorrelationIds` and `correlation/CorrelationScope`, the
`config/RequestContextFilter` rewrite, and the deletion of `filters/tracing/`.

**Shared Given for 1.1–1.8** (`CorrelationIdsTest`): a `MockHttpServletRequest`, and a clean MDC
(`MDC.clear()` in `@BeforeEach` **and** `@AfterEach` — every test class in this ticket must do this;
a leaked MDC entry between test methods is the failure mode the whole ticket is about).

---

**Scenario 1.1 — The canonical header alone resolves to its own value** *(AC-001)*
- **Given** a request carrying only `CPPCLIENTCORRELATIONID: cdk-it-0001`
- **When** inbound resolution runs
- **Then** the resolved correlation ID is exactly `cdk-it-0001`, verbatim, with no transformation,
  and MDC `correlationId` holds that value for the duration of handling.
- **And** the match is **case-insensitive** on the header name — the same request sent as
  `cppclientcorrelationid` and as `CppClientCorrelationId` resolves identically, because
  `cp-audit-filter-springboot` matches it case-insensitively and CDKS must agree with it
  (ADR-001(1), design §2.6).
- **To be proven by:** `CorrelationIdsTest.resolveInbound_shouldReturnCanonicalHeaderValue_whenOnlyCanonicalPresent`,
  parameterised over three spellings of the header name.

**Scenario 1.2 — The alias alone is honoured** *(AC-002)*
- **Given** a request carrying only `X-Correlation-Id: cdk-it-0002`
- **When** inbound resolution runs
- **Then** the resolved correlation ID is `cdk-it-0002`. The alias is deprecated inbound but
  honoured indefinitely (ADR-001(2)) — no WARN, no deprecation log line, no behavioural penalty.
- **To be proven by:** `CorrelationIdsTest.resolveInbound_shouldHonourAlias_whenOnlyAliasPresent`,
  plus an assertion that **no** log event of any level was emitted by the resolution.
- **Why the "no WARN" half matters:** `DiscoverySchedulerTriggerHttpLiveTest` sends the alias on
  every parameterised case. A deprecation warning per request would flood the log with a line that
  says nothing actionable.

**Scenario 1.3 — Both headers present with different values: the canonical wins, deterministically** *(AC-003)*
- **Given** a request carrying `CPPCLIENTCORRELATIONID: cdk-canonical` **and**
  `X-Correlation-Id: cdk-alias`, two different values
- **When** inbound resolution runs
- **Then** the resolved value is `cdk-canonical`, and `cdk-alias` appears nowhere — not in MDC, not
  in the response header, not in a log field.
- **And** the test pins the precedence **order**, not just this one outcome: it asserts against the
  ordered list `CorrelationIds` declares, so reordering that list fails the test rather than
  silently changing behaviour.
- **To be proven by:** `CorrelationIdsTest.resolveInbound_shouldPreferCanonicalOverAlias_whenBothPresentWithDifferentValues`,
  plus `…shouldDeclareCanonicalFirstInPrecedenceOrder` reading the precedence list itself.

**Scenario 1.4 — No header, or a blank one, generates a non-blank value** *(AC-004)*
- **Given**, in turn: (a) neither header present; (b) `CPPCLIENTCORRELATIONID: ` (empty);
  (c) `CPPCLIENTCORRELATIONID: "   "` (whitespace only); (d) `X-Correlation-Id: ` (empty)
- **When** inbound resolution runs
- **Then** in every case the resolved value is **non-blank**, is a well-formed `UUID`, differs from
  the value resolved for a second, independent request, and the request is not failed.
- **To be proven by:** `CorrelationIdsTest.resolveInbound_shouldGenerateNonBlankValue_whenNoUsableHeaderPresent`
  (parameterised over the four cases), asserting `isNotBlank()` and
  `assertThatCode(() -> UUID.fromString(v)).doesNotThrowAnyException()`.
- **Anti-oracle note:** do not assert `isNotNull()` alone. `""` is non-null, and the empty string is
  precisely the value Stage 1 believed was being returned elsewhere in this ticket.

**Scenario 1.5 — A blank canonical header falls through to a populated alias** *(AC-003, AC-004 boundary)*
- **Given** a request carrying `CPPCLIENTCORRELATIONID: "  "` **and** `X-Correlation-Id: cdk-it-0005`
- **When** inbound resolution runs
- **Then** the resolved value is `cdk-it-0005` — precedence is **first non-blank wins**
  (ADR-001(2)'s table), not "first present wins". A blank canonical header must not shadow a usable
  alias into a generated value.
- **To be proven by:** `CorrelationIdsTest.resolveInbound_shouldFallThroughToAlias_whenCanonicalIsBlank`.
- **Why this is its own scenario:** ADR-001(2) says "first non-blank wins" in a table row and
  Story 1's AC-003 says only "the canonical header wins". Those differ for exactly this input. This
  scenario pins the ADR's reading; if the gate prefers the story's literal reading, say so before
  this test is written.

**Scenario 1.6 — A value failing the character allow-list is rejected and regenerated, and the WARN never carries it** *(AC-010)*
- **Given** a request carrying `X-Correlation-Id: abc/def+ghi=` (a base64-shaped value whose `/`,
  `+` and `=` are all outside the allow-list)
- **When** inbound resolution runs
- **Then** the resolved value is a **freshly generated UUID**, not the sent value and not a
  sanitised derivative of it; the request is **not** failed; and exactly **one** `WARN` event is
  emitted carrying (a) the header name, (b) the rejected value's **length**, and (c) the reason code
  `illegal-character`.
- **And** the WARN's formatted message and all its argument values are asserted **not to contain**
  the substring `abc/def+ghi=`, nor any 4-character-or-longer substring of it — logging a rejected
  log-injection payload is the injection (ADR-007(3)).
- **To be proven by:** `CorrelationIdsTest.resolveInbound_shouldRejectAndRegenerate_whenValueFailsAllowList`
  with a `ListAppender`; one `Level.WARN` event, `getFormattedMessage()` containing the reason code
  and not the value.

**Scenario 1.7 — The length bound is tested at the boundary, not "with something long"** *(AC-010)*
- **Given**, in turn, three otherwise-legal values of the allow-listed character class:
  63 characters, **64** characters, **65** characters
- **When** inbound resolution runs
- **Then** 63 and 64 are **accepted verbatim**; 65 is **rejected and regenerated**, with one WARN
  carrying the reason code `too-long` and the length `65` — and, again, not the value.
- **And** the rejected 65-character value is **not truncated to 64**: truncation manufactures
  collisions between distinct long values (ADR-007's "Alternatives considered").
- **To be proven by:** `CorrelationIdsTest.resolveInbound_shouldAcceptUpToSixtyFourCharacters_andRejectSixtyFive`,
  parameterised over the three lengths.
- **Why the boundary and not a round number:** an off-by-one in a `{1,64}` regex quantifier is the
  single most likely implementation defect here and a "give it 200 characters" test cannot see it.

**Scenario 1.8 — A CRLF payload cannot reach MDC and cannot forge a log record** *(AC-010, NFR-002)*
- **Given** a request carrying `X-Correlation-Id` set to a value containing `\r\n` followed by
  synthetic JSON resembling a second log record (e.g. `a\r\n{"level":"ERROR","message":"forged"}`)
- **When** inbound resolution runs and a log line is subsequently emitted
- **Then** MDC `correlationId` holds a generated UUID; the payload appears in **no** MDC key, **no**
  MDC value, and **no** log event's message or arguments; and the emitted line parses as exactly
  **one** JSON object.
- **To be proven by:** `CorrelationIdsTest.resolveInbound_shouldRejectCrlfPayload_andNeverPlaceItInMdc`
  for the MDC half. **The "parses as exactly one JSON object" half needs OQ-102's decision** — the
  unit tier can prove the value never enters MDC, which is the control that matters; it cannot
  observe the `LogstashEncoder`'s output. State both halves; do not claim the unit test proves the
  encoding.
- **Fixture note:** the payload must be obviously synthetic. No case reference, no `CJSCPPUID`
  shape, nothing resembling real data (CLAUDE.md, AC-007 of Story 7).

**Scenario 1.9 — `CorrelationScope.close()` restores the prior map — never clears, never removes** *(shared infrastructure; underpins AC-009, and Stories 2–5)*
- **Given** MDC pre-populated with an unrelated entry (`{"job":"pre-existing"}`)
- **When** a `CorrelationScope.open("cdk-it-0009")` is opened and closed
- **Then** during the scope MDC contains **both** `job=pre-existing` and `correlationId=cdk-it-0009`;
  after `close()` MDC contains exactly `{"job":"pre-existing"}` — the pre-existing entry survives
  and `correlationId` is gone.
- **And** the same holds when the scope body **throws** (try-with-resources still closes), and when
  two scopes are **nested** (the inner close restores the outer's value, not an empty map).
- **And** `openIfAbsent()` on a thread that already carries a `correlationId` is a **no-op on the
  value** — it does not overwrite an in-scope ID with a fresh one.
- **To be proven by:** `CorrelationScopeTest` — `close_shouldRestorePriorMap_notClearIt`,
  `close_shouldRestorePriorMap_whenBodyThrows`, `nestedScopes_shouldRestoreEnclosingValue_onInnerClose`,
  `openIfAbsent_shouldNotOverwriteAnInScopeValue`.
- **Why "restores, not clears" is the assertion:** `MDC.clear()` would also wipe the `traceId` /
  `spanId` keys Micrometer Tracing owns (ADR-002), and `MDC.remove()` is the exact shape of the bug
  Story 2 exists to delete. A test that only asserts "`correlationId` is absent after close" passes
  against all three implementations.

**Scenario 1.10 — The response header is set before the chain runs, and is present on a 2xx** *(AC-006)*
- **Given** a request to any endpoint carrying `CPPCLIENTCORRELATIONID: cdk-it-0010`
- **When** the response is returned with a 2xx status
- **Then** the response carries `X-Correlation-Id: cdk-it-0010`, and carries **no** `traceId` or
  `spanId` response header.
- **And** at the unit tier: the filter calls `response.setHeader(...)` **before**
  `chain.doFilter(...)` — asserted with a Mockito `InOrder` over the mocked `HttpServletResponse`
  and `FilterChain`, so a later refactor that moves the header set after the chain fails here rather
  than only on a streaming endpoint in production.
- **To be proven by:** `RequestContextFilterTest.doFilterInternal_shouldSetResponseHeaderBeforeChain`
  (unit, `InOrder`) and `CorrelationPropagationHttpLiveTest.request_shouldEchoResolvedCorrelationIdOnResponse` (live).

**Scenario 1.11 — The response header is present on a 4xx written *before* the handler is reached** *(AC-006)*
- **Given** a request to `/discovery-scheduler/trigger` **without** the System-Users group
  entitlement, carrying `CPPCLIENTCORRELATIONID: cdk-it-0011`, such that `cppHttpAuthzFilter`
  rejects it with `403` and the handler never runs
- **When** the response is returned
- **Then** the `403` response still carries `X-Correlation-Id: cdk-it-0011`.
- **And** the equivalent holds for a `400` produced by `GlobalExceptionHandler`
  (a malformed-JSON body to the same endpoint — `DiscoverySchedulerTriggerHttpLiveTest` already has
  a `malformedJson_isRejected` case to model it on).
- **To be proven by:** one added assertion on the existing `DiscoverySchedulerTriggerAclHttpLiveTest`
  403 case, plus `CorrelationPropagationHttpLiveTest.errorResponse_shouldStillCarryCorrelationIdHeader`.
- **Order note:** this scenario is only satisfiable because `cppHttpAuthzFilter` registers at
  `HIGHEST_PRECEDENCE + 30` (verified this stage — see §"Filter-order facts") while the correlation
  filter moves to `+10`. If a future release of `cp-auth-rules-filter` changes that default, or if
  `authz.http.filter-order` is ever configured, **this test is the thing that fails**, which is
  precisely why it is specified against a real 403 rather than a mocked one.

**Scenario 1.12 — Probe: does the header survive Boot's `ERROR` dispatch?** *(AC-006, and OQ-103)*
- **Given** a request to an **unmapped** path under the service context path, carrying
  `CPPCLIENTCORRELATIONID: cdk-it-0012`, which Boot resolves via a `/error` ERROR dispatch
- **When** the `404` is returned
- **Then** *the expected outcome is not asserted by this specification.* This scenario is written as
  a **probe**: run it, record the observed behaviour, and take the result to OQ-103.
- **Why it cannot be pre-asserted:** design §3 claims AC-006 holds "including … Boot's `/error`
  dispatch" on the strength of setting the header before the chain. But
  `OncePerRequestFilter.shouldNotFilterErrorDispatch()` defaults to `true`, so the rewritten filter
  will not re-run on the ERROR dispatch, and its `finally` has already restored the prior MDC map by
  then. Whether the previously-set response header survives the dispatch is container behaviour, and
  whether a `BasicErrorController` log line without a `correlationId` is acceptable is a product
  decision. **Guessing either would be inventing behaviour.** If the probe shows the header is lost,
  the fix is one overridden method (`shouldNotFilterErrorDispatch() → false`) and this scenario
  becomes a normal assertion.
- **To be proven by:** `CorrelationPropagationHttpLiveTest.unmappedPath_errorDispatch_correlationHeaderBehaviour`
  — written as an observation first, converted to an assertion once OQ-103 is answered.

**Scenario 1.13 — `TracingFilter` no longer exists: the class, the file, and the bean** *(AC-008, GATE-2)*
- **Given** the built application
- **When** the assertion runs
- **Then** all three of the following hold, and a test that checks only the third is insufficient:
  1. `Class.forName("uk.gov.hmcts.cp.cdk.filters.tracing.TracingFilter")` throws
     `ClassNotFoundException` — the class is **gone**, not merely unregistered;
  2. no source file exists under `src/main/java/uk/gov/hmcts/cp/cdk/filters/` (the package is
     removed, per design §4 — "the whole package goes");
  3. in a Spring context, **no** bean of a type named `TracingFilter` and no `FilterRegistrationBean`
     wrapping one is present, and no bean writes MDC `applicationName`.
- **To be proven by:** `MdcReservedKeyTest` (extended to walk `src/main/java` for the package and
  for `MDC.put` of `traceId` / `spanId` / `applicationName` — see Scenario 1.14) plus a
  `Class.forName` assertion.
- **Why the class-absence assertion and not a behavioural one:** Story 1 deletes the filter; it does
  not reconfigure it. A behavioural test ("the response has no `traceId` header") would also pass
  against a filter that still exists with its response-header lines commented out — which would keep
  the MDC collision that ADR-001(5) deletes the filter to end.

**Scenario 1.14 — `traceId` and `spanId` are reserved: no `src/main` code writes them** *(AC-005, ADR-002)*
- **Given** every `.java` file under `src/main/java`
- **When** the standing source-level assertion runs
- **Then** no file contains an `MDC.put` whose key argument is `"traceId"`, `"spanId"` or
  `"applicationName"` — as a literal, or as a constant whose value is one of those strings.
- **And** exactly one MDC key holds a correlation value at any moment: the only correlation-shaped
  key CDKS writes is `correlationId`, and `traceId` / `spanId` remain populated by Micrometer
  Tracing's `Slf4JEventListener` with real OTel identifiers — strictly more information than today,
  not a rename.
- **To be proven by:** `MdcReservedKeyTest.noSourceFileUnderMainWritesAReservedMdcKey` — a source
  walk, not reflection, so it also catches a key written from a class that is never instantiated in
  any test. Re-run as part of Story 7's whole-diff pass (Story 7 AC-004).
- **Constant-indirection caveat:** a source-text scan cannot follow `MDC.put(SOME_CONSTANT, …)` to
  its value. State the limitation in the test's own Javadoc and pair it with a Stage-6 review item,
  rather than implying the scan is complete.

**Scenario 1.15 — The prior MDC map is restored in `finally`, on both the normal and the throwing path** *(AC-009, AC-034 of Stage 1)*
- **Given** a filter chain that (a) completes normally, and (b) throws a `RuntimeException`
- **When** the filter runs each case on a thread whose MDC was empty at entry
- **Then** in both cases MDC is empty after the filter returns/propagates — identical to today's
  `MDC.clear()` behaviour, so `RequestContextFilterTest.clearsMdcEvenIfChainThrowsException`
  **keeps its assertions unmodified**; only its mock types change (`ServletResponse` →
  `HttpServletResponse`), because the filter now sets a response header and becomes a
  `OncePerRequestFilter`.
- **And** a third case: a thread whose MDC was **pre-populated** at entry has that prior map
  restored exactly, not cleared — the behaviour change this story actually makes.
- **To be proven by:** the existing `clearsMdcEvenIfChainThrowsException` (assertions untouched) plus
  a new `doFilterInternal_shouldRestorePriorMap_whenMdcWasPrePopulatedAtEntry`.
- **Review distinction to state on the PR:** a *construction-site* edit (mock type) is not an
  *assertion* edit. AC-009 permits the former and forbids the latter.

**Scenario 1.16 — `DiscoverySchedulerTriggerHttpLiveTest` passes with its existing assertion, completely unmodified** *(AC-007, NFR-005)*
- **Given** the existing test, which sends `X-Correlation-Id: <UUID.randomUUID()>` and asserts
  `"correlationId":"<sent value>"` in the `202` response body
- **When** `gradle integration` runs after Story 1 lands
- **Then** the test passes with **zero** changes to the file — not a reformat, not an import, not a
  `@DisplayName`.
- **Verified precondition:** the value it sends is a `UUID.randomUUID().toString()` — 36 characters
  of hex and hyphens — which passes ADR-007's allow-list and length bound. So the alias path plus
  the validation rules leave this assertion intact by construction, not by luck.
- **To be proven by:** the existing test as regression, plus a PR-diff check that the file is
  untouched.

**Scenario 1.17 — The dropped inbound conventions are actively ignored, not merely unused** *(AC-008, contract table)*
- **Given** a request carrying **only** dropped headers: bare `traceId: client-supplied-value`, bare
  `spanId: client-supplied-value`, and `X-Request-ID: client-supplied-value`
- **When** the request is handled
- **Then** the resolved correlation ID is a **generated** value; `client-supplied-value` appears in
  no MDC key, no log field, no response header and no outbound request header; and MDC `traceId` /
  `spanId` hold the tracer's own identifiers rather than the client's string.
- **To be proven by:** `CorrelationIdsTest.resolveInbound_shouldIgnoreDroppedHeaders_andGenerate`
  (unit) plus a live case in `CorrelationPropagationHttpLiveTest`.
- **Why this is a scenario and not a footnote:** design §2.4 shows the pre-fix behaviour was worse
  than "an unused header" — the client's `traceId` string **overwrote** the tracer's MDC value
  mid-request. A test that only checks the *correlation ID* is generated would not notice if that
  overwrite survived.

**Scenario 1.18 — The `TracingFilter`-era tests are deleted or rewritten, not carried forward** *(AC-008)*
- **Given** `TracingFilterTest`, `TracingIntegrationTest`, `TestTracingConfig` and
  `TracingProbeController` as they exist today
- **When** Story 1 lands
- **Then** `TracingFilterTest` and `TestTracingConfig` are **deleted**; `TracingIntegrationTest` is
  deleted or rewritten against the tracer's real behaviour; `TracingProbeController` is deleted or
  repurposed; and **no** test anywhere asserts a `UUID.randomUUID()` `traceId` fallback.
- **Grounding:** design §2.5 and ADR-001 establish that `TracingIntegrationTest` does not exercise
  the filter at all — it `@Import`s `TestTracingConfig`, a test-only `HandlerInterceptor` that
  re-implements the filter **and adds a UUID fallback production never had**, and its
  `incoming_request_should_add_new_tracing` case asserts a value no production path produces.
  Carrying that test forward would keep a green assertion over deleted behaviour.
- **To be proven by:** a PR-diff check (the four files are gone or rewritten) plus `gradle test`
  green.

---

## Story 2 — Outbound propagation and MDC read-only interceptor (`DD-43183-2`)

Targets the `http/CorrelationIdInterceptor` rewrite. **Depends on Story 1.**

**Shared Given for 2.1–2.4, 2.7, 2.8** (`CorrelationIdInterceptorTest`, rewritten): a mocked
`HttpRequest` returning a real `HttpHeaders`, a mocked `ClientHttpRequestExecution`, and
`MDC.clear()` in both `@BeforeEach` and `@AfterEach`.

---

**Scenario 2.1 — The in-scope value is transmitted verbatim in both outbound headers** *(AC-001)*
- **Given** MDC `correlationId = cdk-it-0201` (the value an inbound request resolved)
- **When** `intercept(...)` runs
- **Then** the outbound request carries `CPPCLIENTCORRELATIONID: cdk-it-0201` **and**
  `X-Correlation-Id: cdk-it-0201` — both, same value — and carries **no** `X-Request-ID` header.
- **And** the executed response instance is returned unchanged (`assertSame`).
- **To be proven by:** `CorrelationIdInterceptorTest.intercept_shouldSetBothOutboundHeadersToTheInScopeValue`.
- **Two headers, not one:** `CPPCLIENTCORRELATIONID` is what joins Hearing's and Progression's own
  audit events to CDKS's (both run `cp-audit-filter-springboot`); `X-Correlation-Id` is the generic
  name a non-CPP service (RAG) is likelier to log (ADR-001(3)). Asserting only one of the two would
  let half the fix regress silently.

**Scenario 2.2 — No fresh UUID is ever substituted for an in-scope value, and the old constants are gone from the class** *(AC-002)*
- **Given** MDC `correlationId = cdk-it-0202`
- **When** `intercept(...)` runs
- **Then** neither outbound header value is a newly-generated UUID: both equal `cdk-it-0202` exactly.
- **And**, structurally: the class declares **no** field or constant named `HEADER` with the value
  `X-Request-ID`, and **no** `MDC_KEY` constant — both are deleted (design §6). A reflective
  assertion over the class's declared fields pins this, so a "fixed" implementation that keeps the
  constants around cannot pass.
- **To be proven by:** `CorrelationIdInterceptorTest.intercept_shouldTransmitInScopeValueVerbatim_neverAGeneratedUuid`
  and `…shouldNoLongerDeclareXRequestIdOrMdcKeyConstants`.
- **Grounded on today's code:** the current test file asserts the *opposite* — it references
  `CorrelationIdInterceptor.HEADER` and `CorrelationIdInterceptor.MDC_KEY` in all four methods, and
  `shouldGenerateCorrelationId_whenHeaderMissing` explicitly asserts
  `assertDoesNotThrow(() -> UUID.fromString(cid))`. Those references stop compiling when the
  constants are deleted, which is the intended forcing function for Scenario 2.8.

**Scenario 2.3 — MDC is byte-for-byte unchanged immediately before, during, and immediately after `intercept(...)`** *(AC-003 — **the direct regression test for the destruction bug**)*
- **Given** MDC populated with a **multi-entry** map before the call:
  `{correlationId: cdk-it-0203, caseId: <synthetic uuid>, cluster: local, region: local, path: /x}`
- **When** `intercept(...)` runs, with the mocked execution capturing `MDC.getCopyOfContextMap()` at
  the moment it is invoked
- **Then** all three snapshots are **equal as maps**: the copy taken immediately before `intercept`,
  the copy captured inside the execution callback, and the copy taken immediately after `intercept`
  returns. Not "`correlationId` is still present" — the **whole map**, compared with
  `isEqualTo(...)`.
- **And** the interceptor performs **no** `MDC.put` and **no** `MDC.remove` at all: it has no
  `try`/`finally` (design §6, ADR-007(4)).
- **To be proven by:** `CorrelationIdInterceptorTest.intercept_shouldLeaveMdcByteForByteUnchanged_beforeDuringAndAfter`.
- **Why the whole-map comparison is the point.** The historical defect was
  `MDC.put(MDC_KEY, freshUuid)` followed by `MDC.remove(MDC_KEY)` in a `finally` — remove, not
  restore — which **deleted** the inbound correlation ID for the remainder of the request. A test
  asserting only "the correlation ID propagates outbound" passes against that bug, because the
  outbound header was set before the destruction. A test asserting only "`correlationId` is
  non-null after the call" passes against a save-and-restore implementation that still writes MDC.
  Only the three-snapshot whole-map equality distinguishes **read-only** from **repaired**, and
  read-only is what ADR-007(4) chose precisely so the failure class cannot recur by regression.
- **Multi-entry map, deliberately.** A single-entry map cannot detect an implementation that does
  `MDC.setContextMap(Map.of(MDC_KEY, cid))` — which would silently drop `caseId`, `cluster`,
  `region` and `path`.

**Scenario 2.4 — MDC is still unchanged when the downstream execution throws** *(AC-003, NFR-004)*
- **Given** the same multi-entry MDC as 2.3, and an execution that throws an `IOException` and then,
  in a second case, a `RuntimeException`
- **When** `intercept(...)` runs
- **Then** the **same exception instance** propagates out (`assertSame` on the caught throwable), and
  MDC after propagation equals the map captured before the call.
- **To be proven by:** `CorrelationIdInterceptorTest.intercept_shouldPropagateSameExceptionAndLeaveMdcUnchanged`,
  parameterised over the two exception types.
- **Contrast with today:** the current `shouldClearMdc_evenWhenExecutionThrows` asserts
  `assertNull(MDC.get(MDC_KEY))` after the throw — i.e. it asserts the destruction is thorough. That
  method is one of the four Scenario 2.8 rewrites.

**Scenario 2.5 — A log line emitted after an outbound call still carries the request's correlation ID (end-to-end)** *(AC-004)*
- **Given** a live request carrying `CPPCLIENTCORRELATIONID: cdk-it-0205` to an endpoint that makes
  at least one WireMock-stubbed downstream call and then emits a further application log line
  (`/ingestions/start-by-case` is the natural choice — it is synchronous and calls
  `IdpcAvailabilityService` inline on the request thread)
- **When** the request completes
- **Then** the log line emitted **after** the downstream call carries
  `correlationId = cdk-it-0205` as a JSON field.
- **To be proven by:** `CorrelationPropagationHttpLiveTest.logLineAfterOutboundCall_shouldStillCarryTheRequestCorrelationId`.
  **Blocked on OQ-102** — this is the scenario that most needs a log-reading seam, because it is the
  only one that proves AC-004 at the level the defect occurred at. The unit-tier proxy for it is
  Scenario 2.3, which is strictly weaker (it proves the mechanism, not the outcome).
- **Interim fallback if OQ-102 lands on "no log seam":** assert the observable proxy instead — the
  `X-Correlation-Id` response header still equals `cdk-it-0205` **and**, for
  `/discovery-scheduler/trigger`, the response body's `correlationId` still equals the sent value
  *after* an outbound call has occurred on that thread. Record explicitly that this is a proxy for
  the log assertion, not the assertion itself.

**Scenario 2.6 — Two or more outbound calls in one unit of work carry the same value** *(AC-005)*
- **Given** a live request carrying `CPPCLIENTCORRELATIONID: cdk-it-0206` to an endpoint that makes
  **at least two** downstream calls (RAG and Progression, or two RAG calls)
- **When** the request completes
- **Then** WireMock's request journal shows **every** matching downstream request carrying
  `CPPCLIENTCORRELATIONID: cdk-it-0206` and `X-Correlation-Id: cdk-it-0206` — the same value on all
  of them, not one value each.
- **And** none of the recorded requests carries an `X-Request-ID` header.
- **To be proven by:** `CorrelationPropagationHttpLiveTest.multipleDownstreamCalls_shouldAllCarryTheSameCorrelationId`,
  reading `GET /__admin/requests` (or `POST /__admin/requests/find`) on `localhost:8089` and filtering
  by the correlation value.
- **Structural, not incidental:** the value is generated **once per unit of work** at the entry point
  (Story 1's filter, Story 3's aspect, the schedulers' `openIfAbsent`), never per call inside the
  interceptor (design §6's entry-point table). This test pins the consequence; it does not
  re-implement the guarantee.

**Scenario 2.7 — With no ambient correlation value, the outbound call still carries a non-blank one, and MDC stays empty** *(AC-006)*
- **Given** an **empty** MDC (a directly-constructed interceptor in a test; production analogues are
  a startup probe or a path with no unit-of-work scope)
- **When** `intercept(...)` runs
- **Then** both outbound headers carry the **same** non-blank, UUID-shaped value; the request is not
  failed; and MDC is **still empty** afterwards — `currentOrRandom()` generates without writing
  (design §5's signature comment: "as above; never writes MDC").
- **To be proven by:** `CorrelationIdInterceptorTest.intercept_shouldGenerateNonBlankValue_whenNoAmbientCorrelationId`
  plus an assertion that `MDC.getCopyOfContextMap()` is `null` or empty after the call.
- **Why the "MDC stays empty" half is essential:** it is the difference between
  `currentOrRandom()` and `currentOrGenerate()`. Only the former is safe in the interceptor; the
  latter would reintroduce an MDC write on the outbound path and re-open Scenario 2.3's failure
  class through a different door.

**Scenario 2.8 — All four legacy `CorrelationIdInterceptorTest` methods are rewritten; none is left asserting the bug** *(AC-008)*
- **Given** the four methods that exist today — `shouldUseExistingCorrelationId_whenHeaderPresent`,
  `shouldGenerateCorrelationId_whenHeaderMissing`, `shouldGenerateCorrelationId_whenHeaderBlank`,
  `shouldClearMdc_evenWhenExecutionThrows` — every one of which asserts deleted behaviour (a
  generated UUID on the wire; `assertNull(MDC.get(MDC_KEY))` after execution; an inbound read of
  `X-Request-ID` off the *outbound* request)
- **When** Story 2 lands
- **Then** none of the four survives in a form that asserts any of: a `UUID.fromString` check on the
  transmitted value when a value was in scope; `MDC.get(...)` being null *because the interceptor
  removed it*; or a read of `X-Request-ID`.
- **To be proven by:** the rewritten class covering Scenarios 2.1–2.4 and 2.7, plus a PR-diff review
  item confirming all four original method bodies are gone. **The deletion of the `HEADER` and
  `MDC_KEY` constants makes this a compile error rather than a judgement call**, which is the
  cheapest available enforcement.

**Scenario 2.9 — GATE-6 regression confirmation only: PR #225's credential redaction is present and unmodified** *(AC-007 — **already shipped; nothing to implement**)*
- **Given** PR #225 (commit `cafc3dc`, 2026-09-03), which redacts the `Authorization` and
  `Ocp-Apim-Subscription-Key` headers (deny-list, case-insensitive) from `DebugLoggingInterceptor`'s
  outbound/inbound header logging, and ships `DebugLoggingInterceptorTest` (88 lines) asserting that
  the formatted log output never contains the raw credential values while a non-sensitive header
  stays visible
- **When** Story 2's changes land on the same interceptor chain
- **Then** `DebugLoggingInterceptorTest` still exists, still passes, and is **unmodified** by Story
  2's diff; `DebugLoggingInterceptor`'s redaction logic is unmodified by Story 2's diff; and Story
  2 adds **no** new outbound header that would need redacting (the two correlation headers carry an
  opaque identifier, never a credential — NFR-001).
- **To be proven by:** the existing `DebugLoggingInterceptorTest` run as regression, plus a PR-diff
  check that neither `DebugLoggingInterceptor.java` nor its test appears in Story 2's changed-files
  list.
- **No new test is written for AC-007.** The substantive Story-2 test effort is Scenarios 2.1–2.8.
- **One thing does still need doing — see OQ-104.** Verified during this stage: commit `cafc3dc`
  exists on `fix/debug-logging-credential-redaction` (locally and on `origin`) and is **not an
  ancestor of the current working branch**. `03-stories.md` describes it as "merged to `develop`",
  and AC-007 asks the implementer to "confirm on merge that PR #225's fix is present in this
  branch's history". That confirmation is a concrete, mechanisable check, and it has not happened
  yet.

---

## Story 3 — JobManager async correlation restoration (`DD-43183-3`)

Targets the new `correlation/JobCorrelationAspect` and `correlation/JobExecutorMdcBeanPostProcessor`,
the four dispatch sites, and the two inline `"requestId"` literals. **Depends on Story 1.**

**Shared Given for 3.1–3.4, 3.9** (`JobCorrelationAspectTest`): a purpose-built stub
`ExecutableTask` in the test source set whose `execute(ExecutionInfo)` captures
`MDC.getCopyOfContextMap()` and then returns (or throws, per scenario), plus a synthetic
`ExecutionInfo` whose `jobData` is a hand-built `JsonObject`. No real task, no Spring context — a
failure must localise to the aspect.

---

**Scenario 3.1 — The aspect restores `jobData.requestId` into MDC for the duration of `execute(...)`** *(AC-001)*
- **Given** an `ExecutionInfo` whose `jobData` contains `requestId = cdk-it-0301` (read via
  `JobManagerKeys.Params.REQUEST_ID`, never a literal), executing on a thread whose MDC is empty
- **When** the aspect's `@Around` advice invokes the stub task
- **Then** the MDC snapshot captured **inside** `execute(...)` contains
  `correlationId = cdk-it-0301`.
- **And** no per-task code change is required to make this true — the same assertion holds for a
  second, differently-shaped stub task, because the pointcut matches the method signature, not the
  class.
- **To be proven by:** `JobCorrelationAspectTest.aroundExecute_shouldRestoreCorrelationIdIntoMdc_forTheDurationOfExecute`.

**Scenario 3.2 — After `execute(...)` the prior map is restored exactly — on the normal path and the throwing path — and neither the return value nor the throwable is altered** *(AC-002, NFR-004)*
- **Given** a thread whose MDC is **pre-populated** with `{job: pre-existing}`, and an
  `ExecutionInfo` carrying `requestId = cdk-it-0302`
- **When** the advice runs, in two cases: the stub task returns an `ExecutionInfo`, and the stub task
  throws
- **Then** in both cases MDC after the advice equals `{job: pre-existing}` exactly — the
  pre-existing entry survives, `correlationId` is gone. Not `MDC.clear()`, not
  `MDC.remove("correlationId")`.
- **And** on the normal path the returned object is **reference-identical** (`assertSame`) to what
  the stub returned — the aspect never rewrites `ExecutionInfo`; on the throwing path the **same
  throwable instance** propagates (`assertSame`), including for an `Error` as well as an `Exception`
  (the aspect declares `throws Throwable` and has no `catch` at all — ADR-004).
- **To be proven by:** `JobCorrelationAspectTest.aroundExecute_shouldRestorePriorMapAndPassThroughReturnValue`
  and `…shouldRestorePriorMapAndRethrowTheSameInstance` (parameterised over `Exception` and `Error`).
- **Why `Error` is included here but excluded from DD-43182's aspect:** DD-43182's
  `TaskRetryMetricsAspect` deliberately catches only `Exception` and lets `Error` pass unrecorded.
  `JobCorrelationAspect` catches **nothing**, so `Error` must still hit the try-with-resources
  `close()`. Asserting only `Exception` would leave the `Error` path unproven for a mechanism whose
  entire purpose is not leaking context.

**Scenario 3.3 — Absent, blank, or allow-list-rejected `requestId` degrades to a generated value without throwing** *(AC-004, NFR-004)*
- **Given**, in turn, an `ExecutionInfo` whose `jobData`: (a) has no `requestId` key at all;
  (b) has `requestId` present but empty; (c) has `requestId` present but whitespace;
  (d) has `requestId = <65 legal characters>`; (e) has `requestId = a\r\n{"forged":true}`;
  (f) has `jobData` itself `null` or empty
- **When** the advice runs
- **Then** in **every** case: the task executes (the aspect does not throw and does not skip
  `proceed()`), the MDC snapshot inside `execute(...)` contains a **non-blank, UUID-shaped**
  `correlationId`, and for (d) and (e) the offending value appears in no MDC key or value.
- **And** the same validation applies to `jobData` as to inbound headers — a persisted JSON document
  whose values originally came from inbound headers gets the same allow-list and length bound for
  one line of extra code (ADR-007's "Alternatives considered": "Validate only inbound headers,
  trusting `jobData` — rejected").
- **To be proven by:** `JobCorrelationAspectTest.aroundExecute_shouldGenerateCorrelationId_whenJobDataValueIsUnusable`,
  parameterised over the six cases, with
  `assertThatCode(...).doesNotThrowAnyException()` on each.
- **Rollout relevance:** case (a) is not hypothetical — it is what a `jobData` row written by an
  older deploy looks like if the key were ever changed. ADR-003 reuses `requestId` specifically so
  in-flight rows *do* carry it; this scenario proves the degradation path is safe anyway.

**Scenario 3.4 — All seven `@Task` beans are AOP-proxied *and* still resolvable through `TaskRegistry`** *(AC-009, first half)*
- **Given** a Spring context containing `JobCorrelationAspect` and all seven `@Task` beans
- **When** the assertion runs
- **Then** for each of the seven: `AopUtils.isAopProxy(bean)` is `true`, **and**
  `TaskRegistry.getTask(<the bean's TaskNames value>)` returns non-null and resolves to that bean's
  proxy.
- **And** the assertion is driven from the seven `TaskNames` constants, not a hand-written list, so
  an eighth task added later without a matching entry fails here.
- **To be proven by:** `JobCorrelationProxyingTest.allSevenTaskBeans_shouldBeProxiedAndStillResolvableViaTaskRegistry`.
- **This is the test that fails loudly on the two things a pointcut string cannot protect.**
  (1) A package rename of `uk.gov.hmcts.cp.cdk.jobmanager..*` silently stops the pointcut matching —
  design §7.1 and ADR-004's Consequences both name this as the mechanism's one structural liability.
  (2) `TaskRegistry.autoRegisterTasks()` resolves `@Task` via `AopUtils.getTargetClass(bean)`, which
  is *why* a Spring AOP proxy is safe input and a hand-written delegating decorator is not — a
  decorator would take the registry's `"Skipping ExecutableTask without @Task annotation"` branch at
  **debug** level and silently unregister all seven tasks (design §2.7). If a future refactor
  reaches for a decorator, this test is the only thing standing between that change and jobs that
  never run.

**Scenario 3.5 — MANDATORY: `JobCorrelationAspect`'s advice runs *outside* `TaskRetryMetricsAspect`'s, and the test passes whether or not DD-43182 has landed** *(AC-009, second half — GATE-3)*
- **Given** the same Spring context, plus — in the test source set only — a **probe aspect** on the
  same join point declared at `@Order(Ordered.LOWEST_PRECEDENCE)`, which records
  `MDC.get("correlationId")` at its own entry
- **When** any of the seven tasks executes with `jobData.requestId = cdk-it-0305`
- **Then** the probe aspect observes `correlationId = cdk-it-0305` **already present** at its entry —
  proving `JobCorrelationAspect` (`@Order(Ordered.HIGHEST_PRECEDENCE)`) is the **outermost** advice
  on that join point, and therefore that a lower-precedence aspect's own log lines carry the
  correlation ID.
- **And**, conditionally on DD-43182 having landed: if
  `uk.gov.hmcts.cp.cdk.metrics.TaskRetryMetricsAspect` is present on the classpath, the test
  additionally reads the proxy's advisor chain (`((Advised) bean).getAdvisors()`) and asserts that
  the advisor derived from `JobCorrelationAspect` precedes the one derived from
  `TaskRetryMetricsAspect`. If the class is absent, that half is skipped — not failed, not silently
  passed: the skip is reported with a reason.
- **To be proven by:** `JobCorrelationProxyingTest.jobCorrelationAspect_shouldRunOutermostOnExecutableTaskExecute`
  (the probe-aspect half, unconditional) and
  `…shouldOrderJobCorrelationAspectBeforeTaskRetryMetricsAspect_whenDd43182HasLanded` (the advisor-chain
  half, guarded by a classpath-presence assumption).
- **Why the probe aspect is the primary mechanism and the advisor-chain read is secondary.** AC-009
  requires the ordering test to "pass whether or not DD-43182 has merged yet". An advisor-chain
  assertion alone cannot satisfy that — with DD-43182 absent there is only one advisor and nothing
  to order. The probe aspect makes the *property* ("correlation MDC is established before any other
  advice on this join point runs") testable today, in isolation, and it keeps testing something
  meaningful after DD-43182 lands. It also does not depend on `cdk.metrics.enabled`, which gates
  DD-43182's aspect bean but not this one (`JobCorrelationAspect` is deliberately unconditional —
  ADR-004: "a service that can be configured to stop correlating its own logs has the bug this
  ticket closes").
- **Cross-ticket reconciliation status — nothing to reconcile, and that is itself a finding.**
  `docs/pipeline/DD-43182-operational-metrics-instrumentation/` contains `00-input-brief.md`,
  `01-requirements.md`, `02-design.md` and `03-stories.md` — **there is no `04-test-specs.md` there
  as of this stage**, so this scenario had no counterpart to reconcile against and is specified
  unilaterally. DD-43182's own design (§7, around its `TaskRetryMetricsAspect` code sample) already
  refers to this test **by name** ("`JobCorrelationProxyingTest` in DD-43183's design already
  asserts it") and its ADR-006 records the same ordering decision. **DD-43182's Stage 4 must adopt
  this scenario by reference rather than write a second, differently-shaped ordering test** — two
  tests asserting the same ordering with different mechanisms is how the two tickets end up
  disagreeing about it. Raised as OQ-106.
- **Verified detail that makes the ordering deterministic:** DD-43182's `TaskRetryMetricsAspect`
  declares `@Aspect @Component @ConditionalOnProperty(...)` and **no `@Order`**, which yields
  `Ordered.LOWEST_PRECEDENCE`. So `@Order(Ordered.HIGHEST_PRECEDENCE)` on `JobCorrelationAspect` is
  sufficient and no change to DD-43182 is required (ADR-004(4) says exactly this: "its design
  specifies no order, i.e. lowest precedence, which is already correct").

**Scenario 3.6 — No inline `"requestId"` literal remains in `src/main`** *(AC-003)*
- **Given** every `.java` file under `src/main/java`
- **When** the standing source-level assertion runs
- **Then** no file contains the string literal `"requestId"` — every read and write goes through
  `JobManagerKeys.Params.REQUEST_ID`. The two known sites are
  `RetrieveMaterialAndUploadTask` (~`:79`) and `JobManagerService` (~`:59`); the assertion is written
  against *any* occurrence, not those two, so a third cannot appear later.
- **And** `JobManagerKeys.Params.REQUEST_ID` keeps its name **and its value** `"requestId"` —
  verified present at `JobManagerKeys:30`. A rename of either would orphan every in-flight `job`
  row's `jobData` (ADR-003's decisive argument). A test asserts the constant's value is exactly
  `"requestId"`.
- **To be proven by:** `MdcReservedKeyTest` extended (or a sibling source-walk test) —
  `noSourceFileUnderMainUsesAnInlineRequestIdLiteral` — plus
  `JobManagerKeysTest.requestIdConstant_shouldRemainRequestId_becauseItIsAPersistedWireFormat`.
- **PMD note:** `errorprone.AvoidDuplicateLiterals` is enabled in `.github/pmd-ruleset.xml`, so
  consolidating onto the constant also keeps PMD quiet — but PMD's threshold is a count, not zero,
  so it is not a substitute for this assertion.

**Scenario 3.7 — All four dispatch sites seed `jobData.requestId` from the ambient correlation ID, not a fresh UUID** *(AC-006)*
- **Given** MDC `correlationId = cdk-it-0307` on the dispatching thread
- **When** each of the four dispatch sites builds its `jobData` —
  `DiscoveryService` (×2: `toJobDataForCaseEligibility`, `toJobDataForGetCaseHearings`),
  `JobManagerService`, and `IngestionProcessorByCaseService`
- **Then** each resulting `jobData` carries `requestId = cdk-it-0307` — the **inbound** request's
  value, via `CorrelationIds.currentOrGenerate()`, not an unrelated `UUID.randomUUID()`.
- **And** with MDC **empty** on the dispatching thread (a scheduled run with no scope, defensively),
  each still produces a non-blank UUID-shaped `requestId` and does not throw.
- **To be proven by:** `JobDataCorrelationSeedTest`, one parameterised method per dispatch site:
  `<site>_shouldSeedRequestIdFromAmbientCorrelationId` and
  `<site>_shouldSeedGeneratedRequestId_whenNoAmbientCorrelationId`.
- **Why four sites and not "the seeding path":** they are four independent call sites in three
  classes with no shared helper, and design §7.3 lists each one's current `randomUUID()` separately.
  A single test over one of them would leave three unfixed sites green.

**Scenario 3.8 — A chained successor task carries its predecessor's correlation ID** *(AC-005)*
- **Given** a task whose `jobData` carries `requestId = cdk-it-0308`, chaining a successor via
  `createObjectBuilder(jobData)`
- **When** the successor's `jobData` is built
- **Then** it carries `requestId = cdk-it-0308` unchanged.
- **And** the assertion covers the **nine** successor dispatch sites design §7.3 enumerates
  (`GetCasesForHearingTask`, `RetrieveMaterialAndUploadTask`, `GenerateAnswerForQueryTask`,
  `CheckStatusOfAnswerGenerationTask`, `CheckAllDocumentsIngestionStatusTask`,
  `CheckIngestionStatusForAllDefendantsTask` ×3, `RetrieveMaterialAndUploadJobDataService`) — or, if
  covering nine sites individually is judged disproportionate, at least the two multi-hop chains
  (`GET_CASES_FOR_HEARING → CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS → RETRIEVE_MATERIAL_AND_UPLOAD`).
- **To be proven by:** `JobDataCorrelationSeedTest.chainedSuccessor_shouldInheritPredecessorRequestId`.
- **This test pins existing behaviour rather than new behaviour.** ADR-003 verified all nine sites
  already copy the parent map, so AC-005 is *already structurally satisfied*; the story says so
  explicitly ("already structurally true … a test pins it rather than re-implementing it"). The
  scenario exists because the property is load-bearing for the whole async half of the ticket and
  nothing currently asserts it.

**Scenario 3.9 — The aspect also seeds `caseId`, `docId` and `transactionId` from `jobData` — and seeds no key that is absent** *(AC-007)*
- **Given** an `ExecutionInfo` whose `jobData` carries `requestId`, `caseId` (synthetic UUID),
  `docId` (synthetic UUID) and `ragTransactionId` (synthetic UUID)
- **When** the advice runs
- **Then** the MDC snapshot inside `execute(...)` contains `correlationId`, `caseId`, `docId` and
  `transactionId` — note the **key-name translation**: the `jobData` key is `ragTransactionId`
  (`JobManagerKeys.CTX_RAG_TRANSACTION_ID`, verified at `JobManagerKeys:19`) while the MDC/log field
  is `transactionId` (design §9). `caseId` and `docId` keep their names
  (`CTX_CASE_ID_KEY`, `CTX_DOC_ID_KEY`, verified at `JobManagerKeys:5–6`).
- **And** with a `jobData` carrying **only** `requestId`, the MDC snapshot contains `correlationId`
  and **no `caseId` key, no `docId` key, no `transactionId` key at all** — absent, not `"none"`, not
  `""` (design §9's no-sentinel rule).
- **And** all four values are opaque UUIDs. No document name, no answer text, no `llm_input`, no
  `CJSCPPUID` (NFR-001).
- **To be proven by:** `JobCorrelationAspectTest.aroundExecute_shouldSeedBusinessIdentifiersFromJobData_whenPresent`
  and `…shouldSeedNoBusinessIdentifierKeys_whenAbsentFromJobData`.
- **Scope boundary with Story 5:** this is Area E's **entire** async-side deliverable, delivered at
  one site for zero per-task edits. Story 5 must not re-implement MDC seeding for JobManager tasks
  (Story 5's out-of-scope list says so). A reviewer should check for exactly that duplication.

**Scenario 3.10 — With the pool forced to size 1, a job observes nothing left over from the previous job** *(AC-008, defence in depth)*
- **Given** `jobExecutorThreadPool` with its pool size forced to **1** (so thread reuse is
  guaranteed, not merely likely), and the `JobExecutorMdcBeanPostProcessor`'s MDC-clearing
  `TaskDecorator` installed
- **When** job A is submitted, writes MDC entries **outside** any `CorrelationScope` (simulating a
  future task, or the library itself, writing MDC where the aspect cannot see it) and then returns —
  and, in a second case, throws — followed by job B on the same thread
- **Then** job B observes an **empty** MDC in both cases.
- **To be proven by:** `JobExecutorMdcLeakTest.secondJob_shouldObserveNoMdcFromFirstJob_onNormalReturnAndOnThrow`.
- **Why the leftover MDC must be written outside a scope:** if job A used a `CorrelationScope`, its
  `close()` would already have restored the map and the test would pass without the `TaskDecorator`
  existing — i.e. it would prove nothing about AC-008. The decorator's whole justification is
  covering writes the aspect's scope does **not** wrap (design §7.2).
- **Verified precondition:** `ThreadPoolTaskExecutor$1.execute` reads the `taskDecorator` field at
  **submission** time, so the `BeanPostProcessor` setting it in `postProcessBeforeInitialization`
  takes effect (design §7.2). If a future Spring version changes that, this test fails — which is
  the desired outcome.
- **Re-run in Story 7** (its AC-001) as part of the whole-ticket pass; same test, not a second one.

**Scenario 3.11 — End-to-end: a task's log lines carry the dispatching request's correlation ID** *(AC-001, AC-005 at the live tier)*
- **Given** a live `POST /ingestions/start-by-case` carrying `CPPCLIENTCORRELATIONID: cdk-it-0311`,
  which dispatches `RETRIEVE_MATERIAL_AND_UPLOAD` and its successors
- **When** the JobManager pool picks the job up (a different thread, after the response has been
  returned)
- **Then** the log lines emitted by the task carry `correlationId = cdk-it-0311`, and the outbound
  calls the task makes carry it in both outbound headers (visible in WireMock's journal even though
  no log line is readable).
- **To be proven by:** extending one existing JobManager live test — the
  `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest` or
  `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` idiom is the right shape, and
  `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest`'s existing comment shows the polling seam
  (`job.executor.poll-interval`) these tests already use.
- **Split by seam availability.** The **WireMock-journal half is writable today**: it needs no log
  access, and it is a genuine end-to-end proof that the aspect's MDC reached the interceptor on a
  pool thread — which is the async half of the whole ticket. The **log-line half is blocked on
  OQ-102.** Write the journal half now; gate the log half.
- **Existing JobManager live tests stay unmodified** apart from this one addition; the rest run as
  Story 7 regression.

---

## Story 4 — `ErrorResponse.traceId` carries a searchable correlation value (`DD-43183-4`)

Targets `controllers/GlobalExceptionHandler`. **Depends on Story 1.**

**Shared Given for 4.1, 4.3–4.5** (`GlobalExceptionHandlerTest`, rewritten): the advice constructed
with **no arguments** — no `Tracer`, no `Span`, no `TraceContext` mock anywhere in the class — and
MDC seeded per scenario.

---

**Scenario 4.1 — Every one of the six handlers returns a non-blank `traceId` equal to the ambient correlation ID** *(AC-001, AC-003)*
- **Given** MDC `correlationId = cdk-it-0401`
- **When** each of the six handlers is invoked with an appropriate exception —
  `ResponseStatusException`, `MethodArgumentNotValidException`, `ConstraintViolationException`,
  `HttpMessageNotReadableException`, `HttpRequestMethodNotSupportedException`, and the catch-all
  `Exception`
- **Then** each returns an `ErrorResponse` whose `traceId` is **non-blank**, is **not** the empty
  string, and **equals `cdk-it-0401` exactly**.
- **And** all six are asserted, not one plus an argument that they share `base(...)`. Design §8 says
  AC-021 "holds by construction — Stage 4 should still assert all six", and ADR-005(3) repeats it.
  Six parameterised cases is the cheapest way to keep that true through a later refactor that
  introduces a seventh handler or bypasses `base(...)`.
- **To be proven by:** `GlobalExceptionHandlerTest.everyHandler_shouldReturnTraceIdEqualToAmbientCorrelationId`,
  parameterised over the six exception types.
- **Also asserted:** each response's `error`, `message` and `timestamp` fields are unchanged in
  name, type and population from today — this story changes one field's *source*, nothing else.

**Scenario 4.2 — THE ORACLE: `traceId` equals the response header *and* equals the `correlationId` log field** *(AC-002)*
- **Given** a live request carrying `CPPCLIENTCORRELATIONID: cdk-it-0402` that provokes an
  `ErrorResponse` (a malformed JSON body to `/discovery-scheduler/trigger` is the cheapest — the
  existing live test already has that shape)
- **When** the error response is returned
- **Then** **all three of these are the same string**:
  1. the response body's `ErrorResponse.traceId`;
  2. the `X-Correlation-Id` **response header**;
  3. the `correlationId` **JSON log field** on the log lines emitted for that request;
  and all three equal `cdk-it-0402`.
- **To be proven by:** `CorrelationLogFieldHttpLiveTest.errorResponse_traceId_shouldEqualResponseHeaderAndLogField`.
  Clauses 1 and 2 are writable today. **Clause 3 is blocked on OQ-102** and is the reason that OQ is
  the highest-priority one in this document.
- **Why nothing weaker will do — this restates Stage 1's original, now-corrected premise.** Stage 1
  wrote AC-018 as "`traceId` is populated with a non-null value", on the belief that the field
  returns `""` via `Tracer.NOOP`. Design §2 verified that premise is **wrong**: a real `OtelTracer`
  bean always exists on this classpath, `ServerHttpObservationFilter` is registered unconditionally
  at `HIGHEST_PRECEDENCE + 1`, and the field today typically already holds a real **32-hex OTel
  trace ID**. So:
  - a **non-null** assertion passes today, against the defect;
  - a **non-blank** assertion also passes today, against the defect;
  - a `matches("[0-9a-f]{32}")` **shape** assertion passes today, against the defect, and would
    *fail* after the fix (the value becomes a 36-character UUID or a client-supplied string) — so it
    is not merely weak, it is actively wrong.
  The defect was never "the field is empty"; it was that **the value cannot be found in the logs**.
  Only clause 3 tests that. Clause 2 is the strongest fully-automatable proxy for it.
- **Value-shape note:** because the correlation ID may be client-supplied, `traceId`'s shape is now
  caller-dependent (a UUID when generated, `cdk-it-0402` here). No test may assert a shape.

**Scenario 4.3 — `GlobalExceptionHandler` has no `Tracer` dependency, no `requireNonNull`, and no empty `catch`** *(AC-004)*
- **Given** the rewritten class
- **When** the assertion runs
- **Then** all four hold:
  1. the class is constructible with **no** constructor arguments (no `Tracer` field, no
     `@RequiredArgsConstructor` over one) — asserted by simply doing
     `new GlobalExceptionHandler()` in the test, which is the assertion;
  2. the class declares no field of type `io.micrometer.tracing.Tracer`;
  3. the source contains no `Objects.requireNonNull` in the `traceId` path;
  4. the source contains **no** `catch (Exception ignored)` block — the only empty catch block in
     the class is deleted along with the code that needed it.
- **To be proven by:** `GlobalExceptionHandlerTest` — the no-arg construction is implicit in every
  other method in the class; add `shouldDeclareNoTracerDependency` (reflective) and a source-level
  check for (3) and (4).
- **Grounded on today's code:** the class currently declares `private final Tracer tracer;` under
  `@RequiredArgsConstructor`, and `traceId()` is
  `try { traceId = Objects.requireNonNull(tracer.currentSpan()).context().traceId(); } catch (Exception ignored) {}`
  — verified in the source. The existing test class mocks `Tracer`/`Span`/`TraceContext` across its
  methods; all of those mocks disappear, which is the visible signal the dependency is genuinely
  gone rather than merely unused.
- **PMD note for the reviewer:** that empty catch passes PMD today only because
  `errorprone.EmptyCatchBlock` permits a variable named `ignored` (design §8). Deleting it removes a
  latent swallow rather than relying on a naming convention.

**Scenario 4.4 — With MDC empty, `traceId` is still non-blank** *(AC-001, fallback branch)*
- **Given** an **empty** MDC (a unit test, or a hypothetical non-filtered path)
- **When** any handler returns an `ErrorResponse`
- **Then** `traceId` is non-blank and UUID-shaped — `currentOrGenerate()`'s fallback.
- **And** two successive invocations with empty MDC produce **different** values, confirming the
  fallback generates rather than returning a constant.
- **To be proven by:** `GlobalExceptionHandlerTest.traceId_shouldStillBeNonBlank_whenMdcIsEmpty`.
- **Note on reachability, stated honestly:** design §8 and ADR-005(3) argue this branch is
  **unreachable in production**, because `RequestContextFilter` runs for every HTTP request and
  always leaves a non-blank `correlationId`. The test exists for unit-tier construction and to keep
  the `ErrorResponse` contract total; it should not be presented as covering a production path.
  Scenario 1.12's ERROR-dispatch probe (OQ-103) is the one place that claim could turn out to be
  softer than stated.

**Scenario 4.5 — GATE-4: two differently-named fields carry the same value, and no OpenAPI field changes** *(AC-005)*
- **Given** a single live request carrying `CPPCLIENTCORRELATIONID: cdk-it-0405` to
  `/discovery-scheduler/trigger` (which returns `DiscoveryTriggerResponse`), and a second request to
  the same endpoint provoking an `ErrorResponse`
- **When** both responses are inspected
- **Then** `DiscoveryTriggerResponse.correlationId` and `ErrorResponse.traceId` both equal
  `cdk-it-0405` — the same value under two different field names, which is GATE-4 accepted
  explicitly rather than discovered later.
- **And**, structurally: no OpenAPI field is added, renamed, retyped or removed;
  `api-cp-crime-caseadmin-case-document-knowledge` stays at `0.0.11`; `version.cdk` is untouched.
- **To be proven by:** `CorrelationLogFieldHttpLiveTest.errorResponseTraceId_andDiscoveryTriggerCorrelationId_shouldCarryTheSameValue`
  plus a `build.gradle` / `gradle.properties` diff check. **Duplicated deliberately** with Story 7's
  AC-008 whole-ticket check — this one is scoped to Story 4's own diff so the failure localises.
- **Release-note item, not a test:** the field named `traceId` deliberately no longer contains a
  trace ID (design §16). A test cannot assert that consumers were told; the PR must carry the note.

---

## Story 5 — Business identifiers as structured JSON log fields (`DD-43183-5`)

Targets `clients/rag/RagAnswerAsyncServiceImpl`, four named services, the two discovery schedulers
and `metrics/StalledWorkMetrics`. **Depends on Story 1; aware of Story 3.**

> **Scope caveat carried forward, not resolved here.** `02-design.md` §9 and Story 5's own Notes
> both state that this area's scope decision — which classes are in scope, the no-sentinel rule, and
> which `transactionId` is meant — **needs requirements-owner confirmation** and was not made an
> ADR. Until that confirmation lands (OQ-107), the class list in Scenarios 5.3 and 5.5 is
> provisional and those two scenarios should not be written.

---

**Scenario 5.1 — `answerUserQueryAsync`'s completion line carries `transactionId` as a structured field** *(AC-001)*
- **Given** `RagAnswerAsyncServiceImpl.answerUserQueryAsync` returning a
  `UserQueryAnswerRequestAccepted` whose `getTransactionId()` is a synthetic UUID
- **When** the completion line is emitted
- **Then** MDC contains `transactionId = <that value>` at the moment of emission, and the log
  **message** does not interpolate it.
- **Grounding:** this line carries **no identifier at all** today (design §9, and Stage 1's
  correction that only *this* one of the two lacks an identifier — `answerUserQueryStatus` already
  logs a sanitised `transactionId`).
- **To be proven by:** `RagAnswerAsyncServiceImplTest.answerUserQueryAsync_shouldPlaceTransactionIdInMdcOnCompletion`,
  with a `ListAppender` asserting the event's MDC property map contains the key and the formatted
  message does not contain the value.

**Scenario 5.2 — `answerUserQueryStatus` moves `transactionId` from a message parameter to a structured field, keeping its CRLF sanitisation** *(AC-002)*
- **Given** `answerUserQueryStatus` completing with a synthetic `transactionId`, and separately with
  one containing `\r\n`
- **When** the completion line is emitted
- **Then** `transactionId` is present in the event's MDC property map; the `{}` placeholder that
  carried it is **removed from the message** (no dangling placeholder); and the **existing CRLF
  sanitisation is retained unchanged** — the `\r\n` characters are still replaced with `_` before
  the value is used.
- **Divergence stated deliberately:** this value is sanitised **in place**, while an inbound header
  is **rejected and regenerated** (ADR-007(2)). The reason is the CLAUDE.md RAG-data rule — this is a
  downstream RAG identifier CDKS is required not to lose, so mangling one character beats discarding
  it; an inbound header can be regenerated at zero cost. The divergence is documented, not an
  inconsistency, and a test asserting the *wrong* policy at either site would be a real defect.
- **To be proven by:** `RagAnswerAsyncServiceImplTest.answerUserQueryStatus_shouldPlaceSanitisedTransactionIdInMdc_notInMessage`,
  plus a source-level check that no `{}` placeholder without a matching argument remains.
- **Why the placeholder check is explicit:** `errorprone.InvalidLogMessageFormat` is **excluded**
  from `.github/pmd-ruleset.xml` (design §7.3, ADR-003(5)), so nothing in the build catches a stale
  `{}`. It has to be a test or a review item; this specification says test.

**Scenario 5.3 — The four named services open a `caseId`/`docId` scope at their public entry method** *(AC-003 — provisional, gated on OQ-107)*
- **Given** each of `IdpcAvailabilityService`, `IngestionProcessorByCaseService`, `IngestionService`
  and `DocumentService`, invoked with a synthetic case (and, where applicable, document) identifier
- **When** a log statement is emitted anywhere within that call
- **Then** MDC contains `caseId` — and `docId` where the unit of work has one — for the whole
  duration of the public method, and the prior MDC map is restored on exit (normal and throwing).
- **And** the scope is opened at the **public entry method where the identifier first exists**, not
  at each log site — so a line added later inside the same call inherits the fields without an edit.
- **To be proven by:** one test method per service:
  `<method>_shouldScopeCaseIdIntoMdc_forTheDurationOfTheCall` and
  `…_shouldRestorePriorMdc_whenTheCallThrows`.
- **Do not duplicate Story 3.** `IngestionProcessorByCaseService` is both a Story-5 target *and* a
  Story-3 dispatch site. Story 3's aspect seeds MDC for **task execution**; Story 5's scope seeds it
  for the **request-thread** call. Those are different units of work on different threads; a
  reviewer should confirm the two mechanisms are not both applied to the same call site.

**Scenario 5.4 — The identifiers are discrete top-level JSON fields, siblings of `message`** *(AC-004)*
- **Given** a live request that exercises a case-bearing service path
- **When** a log line is emitted and parsed as JSON
- **Then** `correlationId`, `caseId` and (where applicable) `docId` / `transactionId` are top-level
  keys, **siblings of `message`**, not substrings of `message`'s value.
- **And** `traceId` and `spanId` are also present as top-level fields and are **32-hex / 16-hex**
  respectively — i.e. the tracer's own values, not a client-supplied string (which is what
  `TracingFilter`'s deletion buys, and the only assertion in this document that checks a hex shape;
  it is checking the *tracer's* field, never the correlation ID).
- **To be proven by:** `CorrelationLogFieldHttpLiveTest.emittedLogLine_shouldCarryIdentifiersAsSiblingsOfMessage`.
  **Blocked on OQ-102.**
- **No `logback-spring.xml` change is needed or permitted.** `LogstashEncoder` sets no
  `includeMdcKeyNames`, so every MDC entry is already emitted as a top-level field (design §9,
  verified). If this test needs an encoder change to pass, the implementation has done something
  wrong. A PR-diff check asserts `logback-spring.xml` is untouched.

**Scenario 5.5 — A unit of work with no case emits no `caseId` key at all — no sentinel** *(AC-005 — provisional, gated on OQ-107)*
- **Given**, in turn: `GET /queries` (list, no case), `GET /query-catalogue`,
  `IntradayDiscoveryScheduler.run()`, `NightlyDiscoveryScheduler.run()`, and
  `StalledWorkMetrics.refresh()`
- **When** log lines are emitted
- **Then** the emitted line contains **no `caseId` key whatsoever** — not `caseId: "none"`, not
  `caseId: ""`, not `caseId: null`.
- **Why no sentinel:** design §9 — a sentinel pollutes the index and makes `caseId:*` searches lie.
  A test that asserts `caseId` is "absent or empty" would permit exactly what the design forbids;
  the assertion is key **absence**.
- **To be proven by:** unit-tier `ListAppender` assertions on the event MDC property map for the
  scheduler and metrics cases (`doesNotContainKey("caseId")`), and — for the two endpoints — a live
  assertion **blocked on OQ-102**.

**Scenario 5.6 — Both schedulers gain `openIfAbsent()`; `StalledWorkMetrics` switches onto `CorrelationScope` with its `job` key and behaviour otherwise unchanged** *(AC-006)*
- **Given** `IntradayDiscoveryScheduler.run()` and `NightlyDiscoveryScheduler.run()` — neither of
  which carries any MDC today — and `StalledWorkMetrics.refresh()`, which today does its own
  `MDC.put("job", …)` / `MDC.put("correlationId", UUID.randomUUID())` and `MDC.remove(...)` of both
  in a `finally` (verified at `StalledWorkMetrics:100–101` and `:112–113`)
- **When** each runs
- **Then**: each scheduler run's log lines carry a **non-blank `correlationId`**, and two successive
  runs carry **different** values (one per run, not one per process); `StalledWorkMetrics.refresh()`
  still emits `job = stalled-work-metrics-refresh` and still carries a per-refresh `correlationId`;
  and after each returns — normally or by throwing — the **prior MDC map is restored** rather than
  the two keys being individually removed.
- **To be proven by:** `IntradayDiscoverySchedulerTest` / `NightlyDiscoverySchedulerTest` extended
  with `run_shouldEmitLogLinesCarryingACorrelationId`, and `StalledWorkMetricsTest` extended with
  `refresh_shouldUseCorrelationScope_andKeepTheJobKeyUnchanged`.
- **Merge-order coordination — grounded.** DD-43185 shipped `StalledWorkMetrics` recently (its
  implementation commit is in this branch's history) and, verified this stage, its
  `StalledWorkMetricsTest` contains **no MDC assertions at all** — so nothing existing pins the
  current `MDC.remove` behaviour and this change breaks no test. ADR-007's Consequences still
  flag "coordinate on merge order"; the concrete risk is a textual conflict in
  `StalledWorkMetrics.java`, not a behavioural regression.
- **DD-43185's own tests must stay green unmodified** — `SchedulerMetricsHttpLiveTest`,
  `MonitoringMetricsHttpLiveTest`, `IntradayDiscoverySchedulerLiveTest`,
  `NightlyDiscoverySchedulerLiveTest`.

**Scenario 5.7 — No PII, case content or credential enters MDC, a log field, or a header as a result of this story** *(AC-007, NFR-001)*
- **Given** every MDC key and value this story adds
- **When** the diff is reviewed and the tests run
- **Then** every added value is an **opaque identifier** — `caseId`, `docId`, `transactionId` are
  UUIDs. No document content, no answer text, no `llm_input`, no document name, no court reference
  number, no `CJSCPPUID` value is placed in MDC, in a log field, in a propagated header, or in an
  `ErrorResponse` field.
- **And** `cppuid` is **not** promoted to an MDC field by this ticket — it stays behind
  `JobManagerService.sanitizeForLog(...)` (design §9).
- **And** `NFR-009` holds: none of these identifiers becomes a Micrometer tag or a Prometheus label.
  A test asserts no new meter registered by this ticket carries a `caseId`, `docId`,
  `transactionId` or `correlationId` tag — high-cardinality identifiers are log fields only.
- **To be proven by:** a `ListAppender`-based assertion per changed class that the emitted event's
  MDC property map keys are a subset of the documented inventory, plus the secrets scanner, the
  `block-pii` / `block-secrets` hooks, and reviewer sign-off. The meter-tag half is a
  `SimpleMeterRegistry` assertion.

---

## Story 6 — Correct the OTLP tracing/export configuration (`DD-43183-6`)

Targets `src/main/resources/application-server-management.yml` and two new unit tests.
**Independent of Stories 1–5.**

---

**Scenario 6.1 — `OTEL_TRACES_ENABLED` alone controls trace export; `OTEL_METRICS_ENABLED` does not affect it** *(AC-001)*
- **Given** four property combinations, each in its own Spring context:
  (a) both unset; (b) `OTEL_TRACES_ENABLED=true` only; (c) `OTEL_METRICS_ENABLED=true` only;
  (d) both `true` — each with an OTLP endpoint property present so the exporter is constructible
- **When** each context starts
- **Then** the OTLP **span** exporter bean (`otlpHttpSpanExporter`) is present in (b) and (d) and
  absent in (a) and (c); the OTLP **metrics** exporter follows `OTEL_METRICS_ENABLED` independently.
  Setting the metrics variable alone changes nothing about trace export.
- **To be proven by:** `TracingConfigurationTest.otlpSpanExporter_shouldFollowOtelTracesEnabledOnly`,
  parameterised over the four combinations.
- **Verified conditions this scenario relies on** (design §2.2): `OnEnabledTracingExportCondition`
  reads `management.tracing.export.otlp.enabled` then `management.tracing.export.enabled` and gates
  `OtlpTracingConfigurations$Exporters` only — never the tracer;
  `OtlpTracingConfigurations$ConnectionDetails` is
  `@ConditionalOnProperty("management.opentelemetry.tracing.export.otlp.endpoint")`; and `Exporters`
  is `@ConditionalOnBean(OtlpTracingConnectionDetails)`. So "endpoint set + enabled false" yields a
  connection-details bean and **no** exporter — a fifth case worth asserting, because it is the one
  that proves the *enabled* flag is doing the work rather than the endpoint's presence.

**Scenario 6.2 — The three dead keys are gone, the two real keys are present, and a comment records why** *(AC-002)*
- **Given** `src/main/resources/application-server-management.yml` after the change
- **When** the file is parsed
- **Then** it contains **none** of `management.tracing.enabled`, `management.otlp.tracing.enabled`,
  `management.otlp.tracing.endpoint`; it **does** contain
  `management.tracing.export.otlp.enabled` and
  `management.opentelemetry.tracing.export.otlp.endpoint`; and it carries an in-file comment
  explaining that `management.tracing.enabled` does not exist in Boot 4.0.6 and must not be
  re-added.
- **Grounded on today's file:** all three dead keys are verified present at lines 34, 40 and 41,
  with `management.otlp.tracing.enabled` bound to `${OTEL_METRICS_ENABLED:false}` — the metrics
  variable, on the tracing key, on a key that does not exist. Three separate defects on three
  consecutive lines.
- **To be proven by:** `ConfigurationMetadataAuditTest` (which fails on any of the three
  automatically — Scenario 6.4) plus an explicit
  `TracingConfigurationTest.serverManagementYaml_shouldNotContainAnyDeadTracingKey` and a
  comment-presence assertion.
- **Deleting `management.tracing.enabled` changes no behaviour**, because it never had any — the
  property does not exist, `TracingProperties` has no `enabled` field, and span creation is
  unconditional while `spring-boot-starter-opentelemetry` is on the classpath (ADR-006). No test may
  assert that "tracing is now enabled" or "now disabled": **there is no master switch**, and OQ-011
  was dissolved rather than answered.

**Scenario 6.3 — The default endpoint paths are the OTLP/HTTP spec paths** *(AC-003)*
- **Given** the shipped YAML with no `OTEL_TRACES_URL` / `OTEL_METRICS_URL` set
- **When** the properties are resolved
- **Then** the trace endpoint default ends in **`/v1/traces`** and the metrics export URL default
  ends in **`/v1/metrics`** — not `/traces` and `/metrics` as today (verified at lines 41 and 45).
- **To be proven by:** `TracingConfigurationTest.defaultExportPaths_shouldBeTheOtlpHttpSpecPaths`,
  asserting the resolved property values, not the raw YAML text (so an environment-variable
  placeholder is exercised as Spring would resolve it).
- **See OQ-105:** `docker/docker-compose.integration.yml` sets `OTEL_TRACES_URL` and
  `OTEL_METRICS_URL` **explicitly** to the old `/traces` and `/metrics` paths, so the integration
  stack keeps the pre-spec paths regardless of this change unless the compose file is updated too —
  and design §11's "Files touched" table does not list the compose file.

**Scenario 6.4 — `ConfigurationMetadataAuditTest`: no unknown or `error`-deprecated `management.*` / `spring.*` key exists in any `application*.yml`** *(AC-004 — the standing control)*
- **Given** every key in **every** `src/main/resources/application*.yml` — all seven files:
  `application.yml`, `application-artemis-jms.yml`, `application-cdk.yml`, `application-clients.yml`,
  `application-datasource.yml`, `application-other.yml`, `application-server-management.yml`
- **When** each `management.*` or `spring.*` key is resolved against the aggregated
  `META-INF/spring-configuration-metadata.json` on the test classpath
- **Then** the test **fails** on any such key that is **unknown** or **deprecated at level `error`**,
  naming every offender with its file and line.
- **And** the test carries a **documented allow-list** of pre-existing findings, so this story does
  not silently absorb unrelated defects it merely surfaces — each allow-list entry names the defect
  ticket that owns it.
- **Red-first check the implementer must actually perform:** run this test **before** applying
  Scenario 6.2's YAML change and confirm it fails, naming exactly
  `management.tracing.enabled` (deprecated at level `error`),
  `management.otlp.tracing.enabled` (unknown), and
  `management.otlp.tracing.endpoint` (deprecated at level `error`). A metadata-audit test that
  passes on the unfixed file is not doing anything, and this is the only cheap way to know.
- **To be proven by:** `ConfigurationMetadataAuditTest.noApplicationYamlKeyIsUnknownOrErrorDeprecated`.
- **Scope, and a correction to Story 6's AC-004 wording.** The test resolves **only** `management.*`
  and `spring.*` keys — deliberately, because `spring-boot-configuration-processor` is **not** on
  this build (verified: it appears nowhere in `build.gradle`), so CDKS's own `cdk.*` keys, and
  library-owned prefixes such as `authz.http.*` and `job.executor.*`, have **no configuration
  metadata at all** and would every one of them be reported "unknown". A consequence Story 6's
  AC-004 gets wrong: it asks for an allow-list entry for **DD-43182's `cdk.jobmanager.retry.default`
  binding gap**, but a `management.*`/`spring.*`-scoped audit can never surface a `cdk.*` key, so no
  such entry is needed or possible. Raised as OQ-108 rather than silently dropped.
- **Why this test is the real deliverable of Story 6.** Three inert configuration lines survived
  multiple releases with nothing to notice them. Fixing them is a five-minute edit; the test is what
  catches the *next* one (ADR-006(7)).

**Scenario 6.5 — GATE-5: the sampling default drops to `0.1`, remains overridable, and correlation is unaffected at any rate** *(AC-005)*
- **Given** the shipped YAML with `TRACING_SAMPLER_PROBABILITY` unset
- **When** `management.tracing.sampling.probability` is resolved
- **Then** it is **`0.1`** — Boot's own default — not `1.0` (verified as `1.0` today at line 36).
- **And** with `TRACING_SAMPLER_PROBABILITY=1.0` set, it resolves to `1.0`, so a non-production
  demonstration environment can still sample everything.
- **And** correlation is unaffected at any rate: with the probability forced to `0.0`, a request
  still resolves a `correlationId`, still echoes `X-Correlation-Id`, and still puts the correlation
  ID on `ErrorResponse.traceId`. An **unsampled** OTel span still carries a valid trace ID and
  `Slf4JEventListener` still populates MDC (ADR-006(5)), so lowering the rate does not weaken log
  correlation.
- **To be proven by:** `TracingConfigurationTest.samplingProbability_shouldDefaultToBootDefault_andRemainOverridable`
  and `…correlationBehaviour_shouldBeUnaffectedBySamplingProbability`.
- **See OQ-105:** `docker/docker-compose.integration.yml` sets `TRACING_SAMPLER_PROBABILITY: 1.0`
  explicitly, so the integration stack does **not** exercise the new default. The default must be
  asserted by reading the resolved YAML, never by observing the compose stack.

**Scenario 6.6 — With both variables unset the service starts cleanly and neither exporter bean exists** *(AC-006)*
- **Given** `OTEL_TRACES_ENABLED` and `OTEL_METRICS_ENABLED` both unset
- **When** the context starts
- **Then** it starts without error; **no** OTLP span exporter bean and **no** OTLP metrics exporter
  bean exists; a real `OtelTracer` bean **does** exist (unchanged — it always has);
  and `traceId` / `spanId` still appear as MDC-sourced log fields.
- **And** at the integration tier: the compose stack comes up and the whole live suite passes with
  no `OTEL_*` change beyond OQ-105's path correction — today's effective behaviour ("export nothing")
  is preserved exactly.
- **To be proven by:** `TracingConfigurationTest.withNoOtelVariablesSet_shouldStartCleanlyAndExportNothing`,
  plus `gradle integration` green.
- **"Export nothing" is not "trace nothing".** A test that asserts no tracer exists would be
  asserting Stage 1's disproven premise and would fail. Spans are created and discarded today, and
  after this story too.

**Scenario 6.7 — Collector evidence: manual, and stated as such** *(AC-007 — **not a test**)*
- **Given** one non-production environment with `OTEL_TRACES_ENABLED=true` and `OTEL_TRACES_URL`
  pointing at a platform-owned collector's `/v1/traces` path
- **When** requests are made against that environment
- **Then** spans appear in the collector, evidenced by a screenshot attached to Jira DD-43183.
- **Two variables, no code, and no "master switch" to flip** — ADR-006(6) dissolved OQ-011. Platform
  and SRE own the collector endpoint; this story ships only the properties.
- **Not automatable from this repository**, and no scenario above claims to prove it. It is a
  story-DoD item satisfied by an attachment, tracked on the ticket, and it blocks nothing else in
  this ticket (`ErrorResponse.traceId`'s correctness is deliberately independent of export state —
  ADR-005).

---

## Story 7 — MDC leak assurance and whole-ticket regression proof (`DD-43183-7`)

Cross-cutting; authored last. **Depends on Stories 1–6.** This story should add **no production
code** — if it needs any, an earlier story under-delivered its own AC.

---

**Scenario 7.1 — `jobExecutorThreadPool` leak assurance at pool size 1, both paths** *(AC-001)*
- Identical to **Scenario 3.10**, extended to also seed `caseId` and `docId` in job A and assert
  their absence in job B, and re-run here as part of the whole-ticket pass.
- **To be proven by:** the same `JobExecutorMdcLeakTest`, not a second test class. Story 7 re-runs
  it; it does not re-implement it.
- **Why `job-executor-*` and not the request thread.** ADR-008 enumerated the pooled executors:
  Tomcat threads have been covered by `RequestContextFilter`'s `finally` since DD-43063,
  `discoveryTriggerExecutor` by `MdcCopyingTaskDecorator`, and `ShedLockConfig.taskScheduler` by
  whatever each job does for itself. `jobExecutorThreadPool` (core 5 / max 10 / queue 100) is the
  **one** pooled, thread-reusing executor in this service that has never had any MDC hygiene at all.
  This is the test FR-019 was actually asking for.

**Scenario 7.2 — Cross-request MDC isolation on a recycled Tomcat thread** *(AC-002)*
- **Given** request A carrying `CPPCLIENTCORRELATIONID: cdk-it-0702-a`, followed by request B
  carrying `CPPCLIENTCORRELATIONID: cdk-it-0702-b`, executed sequentially so thread reuse is likely,
  and separately with a container/thread-pool configuration that guarantees reuse
- **When** request B is handled
- **Then** no MDC value from request A is visible at any point during request B: B's
  `correlationId` is `cdk-it-0702-b`, B's response header is `cdk-it-0702-b`, and B's log lines
  carry no `caseId` / `docId` value from A.
- **And** a third case: request A **throws** inside the handler (provoking a `GlobalExceptionHandler`
  path) before request B runs — the isolation still holds.
- **To be proven by:** an extension of `RequestContextFilterTest` (deterministic: run the filter
  twice on the same thread with different inputs and assert no bleed) plus a live sequential-request
  case in `CorrelationPropagationHttpLiveTest`.
- **The unit-tier version is the load-bearing one.** A live sequential-request test cannot
  *guarantee* the same Tomcat thread served both requests, so it can only fail to detect a leak, not
  prove its absence. Running the filter twice on one thread is deterministic. State both; do not
  present the live case as the proof.

**Scenario 7.3 — The same isolation holds with `spring.threads.virtual.enabled=true`** *(AC-003 — deliberately low-value)*
- **Given** one Spring test context with `spring.threads.virtual.enabled=true` forced via
  `@TestPropertySource`
- **When** two requests are handled in sequence
- **Then** no MDC value from the first is visible while handling the second.
- **To be proven by:** `MdcVirtualThreadIsolationTest`.
- **Stated plainly, per ADR-008(3) and Story 7's own Background: this is a low-value,
  forward-looking regression test and nothing more.** Virtual threads are enabled in **no**
  environment (`application-other.yml:22` binds `spring.threads.virtual.enabled` to
  `${VIRTUAL_THREADS:false}`, and `VIRTUAL_THREADS` is set nowhere in the repo — verified again this
  stage across `docker/`, `.github/workflows/` and every `application*.yml`). Virtual threads also
  *reduce* leak risk, because there is one thread per task and no pooling. Its only value is that it
  fails on the day someone flips the toggle **and** a leak exists. **Stage 4's recommendation: do not
  over-invest in this scenario** — one context, two requests, one assertion. Do not add a
  virtual-thread variant of any other scenario in this document.

**Scenario 7.4 — `MdcReservedKeyTest` re-run across the whole ticket's diff** *(AC-004)*
- **Given** the combined diff of Stories 1–6
- **When** `MdcReservedKeyTest` runs
- **Then** no `src/main` source file contains an `MDC.put` of `traceId`, `spanId` or
  `applicationName` — across **all** files touched by the ticket, not only Story 1's.
- **To be proven by:** the same test introduced in Story 1 (Scenario 1.14), re-run as a gate on the
  full diff. Story 3's aspect and Story 5's scopes are the realistic places a reserved key could
  creep back in, since both write multiple MDC keys.

**Scenario 7.5 — GATE-2 whole-response-surface regression: the `traceId` and `spanId` response headers are genuinely absent** *(AC-004/AC-005 remit; GATE-2)*
- **Given** the full live suite, exercising every endpoint family (`/queries`, `/query-catalogue`,
  `/documents/{docId}/material-content-url`, `/ingestions/*`, `/cases/**/answers/*`,
  `/discovery-scheduler/*`, `/actuator/*`), across 2xx, 4xx and 5xx responses, **including** requests
  that send bare `traceId` / `spanId` headers inbound
- **When** every response is inspected
- **Then** **no** response carries a `traceId` or a `spanId` header — including, critically, the case
  where the client **sent** them. That is the only case where the old filter ever set them (`:33`,
  `:37` — it echoed only when the request already carried the header), so a test that does not send
  them cannot distinguish "withdrawn" from "was never going to be set for this request anyway".
- **And** every response carries `X-Correlation-Id`.
- **To be proven by:** `CorrelationPropagationHttpLiveTest.noResponseCarriesAWithdrawnTracingHeader`,
  parameterised over the endpoint families, with the inbound-echo case asserted explicitly.
- **This is the "genuinely absent, not merely unused" assertion.** Scenario 1.13 proves the *class*
  is gone; this proves the *behaviour* is gone across the whole response surface, in the one input
  condition that used to trigger it. Both are needed: the class-absence test would pass against a
  filter that still exists with its header lines removed (keeping the MDC collision), and this
  behavioural test would pass against a filter that exists but is never reached.
- **Release-note dependency:** the withdrawal is a response-contract change (design §16). ADR-001's
  Consequences argue it is harmless because the echo only ever returned the caller's own input, and
  GATE-2 was accepted on that basis — but it still belongs in the release note, which a test cannot
  assert.

**Scenario 7.6 — Quality gates green at unmodified thresholds** *(AC-005)*
- **Given** the full ticket's diff
- **When** `gradle clean build` runs (which includes `test` **and** `integration` — `build` and
  `check` both depend on `integration` in this repo)
- **Then** it passes; PMD and JaCoCo are green at **existing, unmodified** thresholds; CodeQL and
  the secrets scanner are clean.
- **To be proven by:** the CI workflows (`ci-build-publish`, `code-analysis`, `codeql`,
  `secrets-scanner`) plus a `.github/pmd-ruleset.xml` and JaCoCo-configuration diff check proving
  no threshold moved.
- **JaCoCo watch item:** `CorrelationIds` is a constants-plus-static-helpers class with a private
  constructor throwing `AssertionError`. The `util/TimeUtils` and `metrics/CdkMeters` precedents show
  how this repo handles the coverage shape (design §13). Do not lower a threshold to accommodate it.

**Scenario 7.7 — No RAG response field is dropped or transformed anywhere in the ticket's diff** *(AC-006, CLAUDE.md hard rule)*
- **Given** the combined diff of Stories 1–6
- **When** the ingestion and answer-serving flows are exercised end-to-end against WireMock-stubbed
  RAG responses containing `doc_id` and `llm_input`
- **Then** both fields are persisted and served **unaltered**; no mapper, DTO or entity in the RAG
  path changed; and the existing RAG-path live tests
  (`AnswersHttpLiveTest`, `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest`,
  `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest`, `IngestionStatusHttpLiveTest`) pass
  **unmodified**.
- **To be proven by:** those live tests as regression, plus an explicit PR-diff check across all six
  stories' combined diff — **checked, not assumed**, which is what Story 7's AC-006 asks for. The
  one place this ticket touches RAG code at all is Story 5's two `RagAnswerAsyncServiceImpl`
  completion **log lines**; a reviewer must confirm nothing in the mapping or persistence path moved.

**Scenario 7.8 — No PII, case content, court reference or `CJSCPPUID` in the combined diff; every correlation value synthetic** *(AC-007)*
- **Given** the combined diff, including all test fixtures, WireMock stub mappings and any Azurite
  seed data touched
- **When** it is reviewed and scanned
- **Then** no PII, case content, court reference number or real `CJSCPPUID` value appears anywhere;
  every correlation value in every test and stub is obviously synthetic (this document uses the
  `cdk-it-NNNN` convention throughout for exactly that reason); and no real APIM credential value
  appears in any fixture — `DebugLoggingInterceptorTest`'s existing synthetic stand-ins are the
  pattern.
- **To be proven by:** the secrets scanner, the `block-pii` / `block-secrets` hooks (which run on
  every `Write`/`Edit`), CodeQL, and reviewer sign-off.

**Scenario 7.9 — No OpenAPI field name or type changes; both API artefact versions untouched** *(AC-008)*
- **Given** the combined diff
- **When** `build.gradle` and `gradle.properties` are inspected
- **Then** `api-cp-crime-caseadmin-case-document-knowledge` is still `0.0.11`, `api-cp-ai-rag` is
  still `0.0.15`, `version.cdk` is unchanged, and no OpenAPI model field is added, renamed, retyped
  or removed anywhere.
- **And** `ErrorResponse` and `DiscoveryTriggerResponse` keep their field names and types — the
  *meaning* of `ErrorResponse.traceId` changes (GATE-4), the contract does not.
- **To be proven by:** a diff check plus the `api-contract-check` skill over the consumed OpenAPI
  artefact. **`src/pactVerificationTest/` is untouched and needs no new test.**
- **Deliberately duplicated** with Scenario 4.5's story-scoped version: this is the whole-ticket
  gate, that one localises the failure to Story 4.

---

## Coverage summary — **planned**, not achieved

No row below is evidence of a passing test, with the single exception of Story 2's AC-007.

### Story 1 — `DD-43183-1`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 canonical header resolves | 1.1 | planned | planned | `CorrelationIdsTest.resolveInbound_shouldReturnCanonicalHeaderValue_…` (3 spellings) |
| AC-002 alias honoured, no WARN | 1.2 | planned | planned | `…shouldHonourAlias_whenOnlyAliasPresent` |
| AC-003 canonical wins; order pinned | 1.3, 1.5 | planned | planned | `…shouldPreferCanonicalOverAlias_…`; `…shouldDeclareCanonicalFirstInPrecedenceOrder` |
| AC-004 none/blank → generated non-blank | 1.4 | planned (4 cases) | planned | `…shouldGenerateNonBlankValue_whenNoUsableHeaderPresent` |
| AC-005 one MDC key; `traceId`/`spanId` reserved | 1.14 | planned (source walk) | — | `MdcReservedKeyTest…` — **constant-indirection caveat stated** |
| AC-006 response header on 2xx and 4xx/5xx | 1.10, 1.11, **1.12 (probe)** | planned (`InOrder`) | planned (403 + 400) | `RequestContextFilterTest…`; `DiscoverySchedulerTriggerAclHttpLiveTest` +1 assertion — **ERROR dispatch is OQ-103** |
| AC-007 `DiscoverySchedulerTriggerHttpLiveTest` unmodified | 1.16 | — | planned (regression + diff check) | existing test, zero changes |
| AC-008 `TracingFilter` deleted; headers withdrawn; old tests gone | 1.13, 1.17, 1.18, **7.5** | planned | planned | `Class.forName` + package walk; `…shouldIgnoreDroppedHeaders_andGenerate`; diff check |
| AC-009 prior-map restore, normal + throwing | 1.15, 1.9 | planned | — | existing `clearsMdcEvenIfChainThrowsException` (assertions untouched) + `…shouldRestorePriorMap_whenMdcWasPrePopulated…` |
| AC-010 reject-and-regenerate; WARN without the value | 1.6, 1.7, 1.8 | planned (allow-list, 63/64/65 boundary, CRLF) | — | `CorrelationIdsTest…` + `ListAppender` |

### Story 2 — `DD-43183-2`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 both outbound headers carry the value | 2.1, 2.6 | planned | planned (WireMock journal) | `intercept_shouldSetBothOutboundHeadersToTheInScopeValue`; `CorrelationPropagationHttpLiveTest` |
| AC-002 no UUID substitution; constants deleted | 2.2 | planned (+ reflective) | — | `…shouldTransmitInScopeValueVerbatim_neverAGeneratedUuid`; `…shouldNoLongerDeclareXRequestIdOrMdcKeyConstants` |
| AC-003 **MDC byte-for-byte unchanged** | **2.3**, 2.4 | planned (**3-snapshot whole-map equality, multi-entry**) | — | `intercept_shouldLeaveMdcByteForByteUnchanged_beforeDuringAndAfter` — the direct destruction-bug regression |
| AC-004 log line after an outbound call still correlates | 2.5 | (proxied by 2.3) | **blocked — OQ-102** | `…logLineAfterOutboundCall_…`; documented header/body proxy as interim |
| AC-005 all calls in one unit of work share a value | 2.6 | — | planned (WireMock journal) | `…multipleDownstreamCalls_shouldAllCarryTheSameCorrelationId` |
| AC-006 non-blank with no ambient value; MDC stays empty | 2.7 | planned | — | `…shouldGenerateNonBlankValue_whenNoAmbientCorrelationId` |
| **AC-007 GATE-6 credential redaction** | 2.9 | **DONE — PR #225, exists and passes** | — | existing `DebugLoggingInterceptorTest`, unmodified — **regression only; see OQ-104** |
| AC-008 all four legacy methods rewritten | 2.8 | planned (compile-forced) | — | rewritten class + diff review |

### Story 3 — `DD-43183-3`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 aspect restores `requestId` → MDC for the duration | 3.1, 3.11 | planned | planned (journal half) | `JobCorrelationAspectTest.aroundExecute_shouldRestoreCorrelationIdIntoMdc_…` |
| AC-002 prior map restored exactly; return/throw untouched | 3.2 | planned (`assertSame`, incl. `Error`) | — | `…shouldRestorePriorMapAndPassThroughReturnValue` / `…AndRethrowTheSameInstance` |
| AC-003 constant everywhere; no inline literal | 3.6 | planned (source walk + constant value) | — | `…noSourceFileUnderMainUsesAnInlineRequestIdLiteral` |
| AC-004 absent/blank/rejected → generated, no throw | 3.3 | planned (6 cases) | — | `…shouldGenerateCorrelationId_whenJobDataValueIsUnusable` |
| AC-005 chained successor inherits the value | 3.8 | planned | planned (3.11) | `…chainedSuccessor_shouldInheritPredecessorRequestId` — pins existing behaviour |
| AC-006 four dispatch sites seed from ambient ID | 3.7 | planned (4 sites × 2 cases) | — | `JobDataCorrelationSeedTest` |
| AC-007 `caseId`/`docId`/`transactionId` seeded; absent → no key | 3.9 | planned | — | `…shouldSeedBusinessIdentifiersFromJobData_whenPresent` / `…shouldSeedNoBusinessIdentifierKeys_whenAbsent…` |
| AC-008 pool `TaskDecorator`, pool size 1 | 3.10, 7.1 | planned | — | `JobExecutorMdcLeakTest…` — **leftover MDC written outside any scope** |
| **AC-009 GATE-3 proxying + aspect ordering** | **3.4, 3.5** | planned (probe aspect + conditional advisor-chain read) | — | `JobCorrelationProxyingTest…` — **passes with or without DD-43182; see OQ-106** |

### Story 4 — `DD-43183-4`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 non-blank `traceId`, all six handlers | 4.1, 4.4 | planned (6 parameterised) | — | `everyHandler_shouldReturnTraceIdEqualToAmbientCorrelationId`; `…whenMdcIsEmpty` |
| **AC-002 = response header AND = log field** | **4.2** | — | partial (**clauses 1–2 writable; clause 3 blocked — OQ-102**) | `errorResponse_traceId_shouldEqualResponseHeaderAndLogField` — **non-blank-only and 32-hex-shape oracles both explicitly invalid** |
| AC-003 all six handlers | 4.1 | planned | — | same parameterised test |
| AC-004 no `Tracer`, no `requireNonNull`, no empty `catch` | 4.3 | planned (no-arg construction + reflective + source) | — | `shouldDeclareNoTracerDependency` + source check |
| AC-005 GATE-4 same value, two field names; no API change | 4.5 | — | planned | `errorResponseTraceId_andDiscoveryTriggerCorrelationId_shouldCarryTheSameValue` + diff check |

### Story 5 — `DD-43183-5`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 `answerUserQueryAsync` carries `transactionId` | 5.1 | planned | — | `answerUserQueryAsync_shouldPlaceTransactionIdInMdcOnCompletion` |
| AC-002 `answerUserQueryStatus` structured; CRLF sanitisation kept | 5.2 | planned | — | `…shouldPlaceSanitisedTransactionIdInMdc_notInMessage` + stale-`{}` check |
| AC-003 four named services scope `caseId`/`docId` | 5.3 | planned (**provisional — OQ-107**) | — | one method per service |
| AC-004 discrete top-level fields, siblings of `message` | 5.4 | partial (event shape) | **blocked — OQ-102** | `emittedLogLine_shouldCarryIdentifiersAsSiblingsOfMessage` |
| AC-005 no `caseId` key at all when no case; no sentinel | 5.5 | planned (**provisional — OQ-107**) | partial — OQ-102 | `doesNotContainKey("caseId")` assertions |
| AC-006 schedulers `openIfAbsent`; `StalledWorkMetrics` on `CorrelationScope` | 5.6 | planned | planned (DD-43185 suites as regression) | scheduler tests + `StalledWorkMetricsTest` extension |
| AC-007 no PII/case content; NFR-009 no metric tag | 5.7 | planned | planned | MDC-key subset assertions + scanners + `SimpleMeterRegistry` tag check |

### Story 6 — `DD-43183-6`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 `OTEL_TRACES_ENABLED` independent of metrics | 6.1 | planned (5 combinations) | — | `otlpSpanExporter_shouldFollowOtelTracesEnabledOnly` |
| AC-002 three dead keys gone; two real keys + comment | 6.2, 6.4 | planned | — | `serverManagementYaml_shouldNotContainAnyDeadTracingKey` + audit test |
| AC-003 `/v1/traces`, `/v1/metrics` defaults | 6.3 | planned (resolved values) | — | `defaultExportPaths_shouldBeTheOtlpHttpSpecPaths` — **compose overrides: OQ-105** |
| **AC-004 `ConfigurationMetadataAuditTest`** | **6.4** | planned (**7 yml files; red-first check mandated**) | — | `noApplicationYamlKeyIsUnknownOrErrorDeprecated` — **allow-list scope: OQ-108** |
| AC-005 GATE-5 sampling `1.0 → 0.1`; correlation unaffected | 6.5 | planned | — | `samplingProbability_shouldDefaultToBootDefault_…` — **compose override: OQ-105** |
| AC-006 both unset → starts clean, exports nothing | 6.6 | planned | planned (`gradle integration`) | `withNoOtelVariablesSet_shouldStartCleanlyAndExportNothing` |
| AC-007 collector screenshot | 6.7 | — | — | **manual, not a test** — Jira attachment |

### Story 7 — `DD-43183-7`

| Story AC | Scenario(s) | Unit | Integration | Planned test |
|---|---|---|---|---|
| AC-001 pool-size-1 leak, return + throw | 7.1 (= 3.10) | planned | — | `JobExecutorMdcLeakTest` re-run, extended with `caseId`/`docId` |
| AC-002 cross-request isolation on a recycled thread | 7.2 | planned (**deterministic, load-bearing**) | planned (best-effort only) | `RequestContextFilterTest` extension + live sequential case |
| AC-003 same under `spring.threads.virtual.enabled=true` | 7.3 | planned (**deliberately minimal**) | — | `MdcVirtualThreadIsolationTest` — one context, two requests, one assertion |
| AC-004 `MdcReservedKeyTest` over the whole diff | 7.4 | planned | — | Story 1's test, re-run as a whole-diff gate |
| GATE-2 whole-response-surface header absence | **7.5** | — | planned (**incl. the inbound-echo case**) | `noResponseCarriesAWithdrawnTracingHeader` |
| AC-005 build / PMD / JaCoCo / CodeQL / secrets green | 7.6 | planned | planned | CI workflows + threshold diff check |
| AC-006 no RAG field dropped (`doc_id`, `llm_input`) | 7.7 | — | planned (4 RAG live tests unmodified) | existing suites + explicit diff check |
| AC-007 no PII; synthetic correlation values | 7.8 | planned | planned | scanners, hooks, review |
| AC-008 no OpenAPI change; `0.0.11` / `0.0.15` / `version.cdk` | 7.9 (+ 4.5) | — | — | diff check + `api-contract-check` |

### Requirements-level ACs not fully closable in this repository

| Requirements AC | Status |
|---|---|
| AC-020 / Story 4 AC-002 clause 3 (the `traceId` on the response equals the `correlationId` **log field**) | **Not closable until OQ-102 is decided.** Clauses 1–2 (response body vs response header) are writable today and are the strongest available proxy. This is the single most important gap in this document, because clause 3 is the only assertion that tests the actual defect. |
| AC-023 / Story 5 AC-004 (identifiers as **JSON** siblings of `message`) | **Partially closable.** The unit tier proves the *event* shape (MDC property map); the `LogstashEncoder`'s JSON output is observable only with OQ-102's seam. The encoder itself is unchanged and that is a diff-level check. |
| AC-030 / Story 6 AC-007 (spans in a collector) | **Not closable here.** Manual, one non-production environment, platform-owned collector, screenshot deliverable. Scenario 6.7 states the procedure and asserts nothing. |
| AC-033 / Story 7 AC-003 (virtual threads) | **Closable but low-value by construction** (ADR-008). Kept for regression; explicitly not evidence of virtual-thread readiness. |
| AC-032 / Story 7 AC-002 at the **live** tier | **Best-effort only.** No mechanism guarantees two sequential live requests share a Tomcat thread. The deterministic proof is unit-tier (Scenario 7.2). |

### Tier notes

- **Nothing is integration-only** except the four assertions that structurally cannot be made at the
  unit tier: the **response header** on a real 4xx written before the handler (Scenario 1.11), the
  **WireMock-recorded outbound headers** (2.1/2.6/3.11), the **emitted JSON log line** (blocked —
  OQ-102), and the **`DiscoveryTriggerResponse.correlationId` published-field** value (1.16/4.5).
  Every behavioural AC otherwise has a unit-tier plan, so a failure localises to a class rather than
  to "the compose stack".
- **Story 6 has no integration-tier test of its own** — configuration binding and bean presence are
  fully observable in a Spring test context, and the compose stack cannot say anything useful about
  an exporter that is deliberately disabled. Its only integration involvement is "`gradle
  integration` still passes" plus OQ-105's compose-file correction.
- **No contract tests, no accessibility tests, no migration tests** — see §"Scope boundaries".

---

## Risks and open points carried into implementation

New open questions raised by Stage 4. These are **questions, not assumptions** — none is resolved
here, and each needs an owner's answer before the affected scenario is written.

- **OQ-101 (blocking — no real Jira sub-tickets exist).** `03-stories.md` carries placeholders
  `DD-43183-1` – `DD-43183-7` only, and its own Notes say real sub-tickets are required "before
  Stage 4 (Test Specs) starts". CLAUDE.md makes this a hard rule. They do not exist. Create and link
  them to the parent epic DD-43183 before any test below is written, and confirm OQ-013's outstanding
  question (the pasted brief was never verified against the live ticket in any session of this
  pipeline — no Jira/Atlassian MCP tool has been available at Stage 1, 2, 3 or 4). — Owner: requester ·
  Due: **before Stage 5**, and before the first test is authored.
- **OQ-102 (highest priority — the integration tier cannot read a log line).** Design §13 specifies
  `CorrelationLogFieldHttpLiveTest` as parsing "a JSON log line from the app container's stdout", and
  Story 4's AC-002 makes "equals the `correlationId` log field" the **oracle for the whole of Area D**.
  Verified this stage: `AbstractHttpLiveTest` exposes only a `RestTemplate` and a JDBC `Connection`;
  the compose stack is driven by the `com.avast.gradle.docker-compose` Gradle plugin, which supplies
  host/port system properties and **no container handle**, so there is no `getLogs()` equivalent and
  no existing test reads container output. Three options, none free:
  (a) shell out to `docker compose logs cdks_application` (or `docker logs`) from the test and parse
  stdout — cheap, but couples the suite to a Docker CLI on the CI runner;
  (b) add a file appender + a bind-mounted volume in `docker-compose.integration.yml` and read the
  file — no CLI dependency, but changes production logging configuration for tests, which the
  "no `logback-spring.xml` change" boundary was written to prevent;
  (c) accept unit-tier `ListAppender` coverage for the event shape and downgrade AC-002's clause 3
  to a documented manual verification, keeping clauses 1–2 automated.
  Decide before Scenarios 2.5, 4.2, 5.4 and 5.5's live halves are written. **Stage 4's view: (c) is
  the honest fallback, but it leaves the defect's actual oracle unautomated, so (a) is worth its
  cost.** — Owner: requester + the Story 4 implementer · Due: **Stage 4 gate**.
- **OQ-103 (does the `X-Correlation-Id` header survive Boot's `ERROR` dispatch?).** Design §3 asserts
  AC-006 holds "including responses produced by `GlobalExceptionHandler` and by Boot's `/error`
  dispatch". The `GlobalExceptionHandler` half is sound (those exceptions are handled inside the
  `DispatcherServlet` on the original `REQUEST` dispatch). The `/error` half is not established:
  `OncePerRequestFilter.shouldNotFilterErrorDispatch()` defaults to **`true`**, so the rewritten
  filter will not re-run on the ERROR dispatch, and its `finally` has already restored the prior MDC
  map — meaning any `BasicErrorController` log line on that dispatch carries **no** `correlationId`,
  and whether the previously-set response header survives is container behaviour this stage cannot
  settle by reading code. Scenario 1.12 is written as a **probe** rather than an assertion for
  exactly this reason. Decide: (i) accept whatever the probe shows and document it; (ii) override
  `shouldNotFilterErrorDispatch()` to `false` so the filter re-runs and re-establishes both the
  header and MDC. — Owner: design reviewer + the Story 1 implementer · Due: before Scenario 1.12 is
  converted from a probe to an assertion.
- **OQ-104 (PR #225 is not in this branch's history — name the concrete merge gate).** Verified this
  stage: commit `cafc3dc` ("Redact Authorization and Ocp-Apim-Subscription-Key from debug HTTP
  logs", with `DebugLoggingInterceptor.java` +25 and `DebugLoggingInterceptorTest.java` +88) exists
  on `fix/debug-logging-credential-redaction` locally and on `origin`, and
  `git merge-base --is-ancestor cafc3dc HEAD` reports **not an ancestor** of the current working
  branch — `DebugLoggingInterceptorTest.java` is absent from `src/test/java/.../http/` here.
  `03-stories.md` describes the fix as "merged to `develop` on 2026-09-03" and Story 2's AC-007 asks
  the implementer to "confirm on merge that PR #225's fix is present in this branch's history".
  That confirmation should be a mechanised gate, not a manual recollection: either a
  `git merge-base --is-ancestor` check in Story 2's PR, or — better, because it also survives a
  rebase — a Story-2 assertion that `DebugLoggingInterceptorTest` exists and passes, so its
  disappearance fails the build. Confirm which, and confirm the fix is actually on the integration
  branch Story 2 will branch from. — Owner: Story 2 implementer + release engineer · Due: before
  Story 2 starts.
- **OQ-105 (the compose file carries the pre-spec OTLP paths, and design §11 does not list it).**
  Verified: `docker/docker-compose.integration.yml:155–158` sets `TRACING_SAMPLER_PROBABILITY: 1.0`,
  `OTEL_METRICS_ENABLED: "false"`, `OTEL_TRACES_URL: http://localhost:4318/traces` and
  `OTEL_METRICS_URL: http://localhost:4318/metrics`. Two consequences for Story 6: (i) the
  integration stack keeps the **old** `/traces` and `/metrics` paths regardless of AC-003's YAML
  default change, unless the compose file is updated or those two overrides are deleted so the
  corrected defaults apply; (ii) the compose stack never exercises GATE-5's new `0.1` sampling
  default, so Scenario 6.5 must assert the resolved YAML value rather than observe the running stack.
  Design §11's "Files touched" table lists neither. Decide: update the two compose paths to `/v1/*`,
  or delete the overrides entirely (preferable — fewer places to drift). Also note that once
  `OTEL_TRACES_URL` maps to `management.opentelemetry.tracing.export.otlp.endpoint`, the compose
  stack will create an `OtlpTracingConnectionDetails` bean (the endpoint is set) while the exporter
  stays absent (`OTEL_TRACES_ENABLED` unset ⇒ `false`) — harmless, but Scenario 6.6's
  "neither exporter bean exists" assertion must be about **exporters**, not connection details. —
  Owner: Story 6 implementer · Due: before Scenario 6.3 is written.
- **OQ-106 (DD-43182 has no Stage-4 specs, so the GATE-3 ordering test is specified unilaterally
  here).** `docs/pipeline/DD-43182-operational-metrics-instrumentation/` contains only `00`–`03`;
  there is **no `04-test-specs.md`** to reconcile against, so nothing was reconciled and nothing
  contradicted. DD-43182's `02-design.md` §7 already names `JobCorrelationProxyingTest` as the test
  that asserts the ordering, and its ADR-006 records the same decision — so the intent is aligned,
  but the test does not yet exist on either side. Two things need agreeing: (i) DD-43182's Stage 4
  must **adopt Scenario 3.5 by reference** rather than author a second, differently-shaped ordering
  test (two mechanisms asserting one ordering is how the tickets end up disagreeing about it); and
  (ii) confirm the merge order and who owns the test file if DD-43182 lands first. Also confirm that
  DD-43182's `TaskRetryMetricsAspect` will continue to declare **no `@Order`** — Scenario 3.5's
  conditional half assumes `LOWEST_PRECEDENCE`, and an `@Order` added there later would need this
  test updated. — Owner: sprint planning + both tickets' implementers · Due: before whichever of the
  two aspect stories starts.
- **OQ-107 (Story 5's scope is a design decision, not an ADR, and is still unconfirmed).** Both
  `02-design.md` §9 and Story 5's own Notes state that the Area E scope resolution — which classes
  are in scope, the no-sentinel rule, and which `transactionId` is meant — **needs
  requirements-owner confirmation** and was deliberately not made an ADR. It has not been confirmed.
  Scenarios 5.3 and 5.5 name a specific class list on that basis and are marked **provisional**;
  they should not be written until the owner confirms. Confirm also whether "a service, task,
  scheduler or client class" (FR-012's literal wording) is genuinely bounded to the four named
  services, or whether the requirements owner expects wider coverage — the difference is a large
  diff across `services/` and `clients/`. — Owner: requester · Due: at Story 5 kickoff, per the
  story's own Notes.
- **OQ-108 (Story 6 AC-004's allow-list asks for something the test cannot surface).** AC-004
  requires `ConfigurationMetadataAuditTest` to carry "a documented allow-list of pre-existing
  findings elsewhere in the file (e.g. DD-43182's already-reported `cdk.jobmanager.retry.default`
  binding gap)". But the test is scoped to `management.*` / `spring.*` keys — necessarily, because
  `spring-boot-configuration-processor` is **not** on this build (verified: absent from
  `build.gradle`), so CDKS's own `cdk.*` keys and library prefixes (`authz.http.*`,
  `job.executor.*`) have no configuration metadata at all and would every one be reported
  "unknown". A `management.*`/`spring.*` audit therefore **cannot** surface `cdk.jobmanager.retry.default`,
  and no allow-list entry for it is needed or possible. Decide: (i) accept the narrower scope and
  drop that clause from AC-004 (recommended — the ticket's dead keys are all `management.*`);
  (ii) add `spring-boot-configuration-processor` so `cdk.*` keys gain metadata and can be audited
  too, which is a genuinely valuable but separate change and would immediately surface
  DD-43182's finding as a build failure needing its own allow-list entry and defect ticket. —
  Owner: Story 6 implementer + requester · Due: before Scenario 6.4 is written.
- **OQ-109 (Scenario 1.5's precedence reading — ADR vs story wording).** ADR-001(2)'s table says
  resolution is "**first non-blank wins**", which means a blank `CPPCLIENTCORRELATIONID` falls
  through to a populated `X-Correlation-Id`. Story 1's AC-003 says only "the canonical header wins
  deterministically", which read literally would have a blank canonical header shadow a usable alias
  into a generated value. Those differ for exactly one input, and Scenario 1.5 pins the ADR's
  reading. Confirm, so the test is not written against the wrong one. — Owner: design reviewer ·
  Due: before Scenario 1.5 is written.
- **OQ-110 (Scenario 3.8's breadth — nine successor sites, or two representative chains?).**
  ADR-003 verified all nine successor dispatch sites already copy the parent `jobData` map, so AC-005
  is structurally satisfied and the test only pins it. Asserting all nine individually is thorough
  but repetitive; asserting the two multi-hop chains covers the behaviour with far less test code
  and less coupling to internal dispatch structure. Confirm which the gate wants, since "a test pins
  it" (Story 3 AC-005's wording) does not say how many. — Owner: Story 3 implementer · Due: before
  Scenario 3.8 is written.

Carried forward from earlier stages and **still unresolved**: **OQ-013** (the Jira brief was never
confirmed against the live ticket, and no summary comment has been posted to the epic at any stage —
now folded into OQ-101), **OQ-009** (Area E scope — now OQ-107), **OQ-014** (context-doc drift:
`cdks-context.md` says 5 caseflow tasks where there are 4 / 7 total, and says
`task-manager-service` 1.0.10 and Spring Boot 4.0.5 where `gradle.properties` and `build.gradle`
pin 1.0.11 and 4.0.6 — Story 1's Notes fold the correction into its diff; non-blocking but it
should actually happen, along with adding design §12's convention table to `cdks-context.md` per
NFR-008).

---

## Stage-4 gate

Test Specs is a **human gate**. Do not proceed to Stage 5 (Code) until:

1. The scenarios above are approved.
2. **OQ-101 is resolved** — real Jira sub-tickets exist and are linked. This is a CLAUDE.md hard
   rule, not a preference, and it blocks the first test being written rather than the last.
3. **OQ-102 has a decision** — it determines whether Area D's actual oracle (Story 4 AC-002's
   clause 3) is automated, proxied, or manual, and it gates four scenarios across three stories.
4. OQ-103 – OQ-110 have decisions, or an explicit agreement to defer each to the implementer of the
   story it affects.
