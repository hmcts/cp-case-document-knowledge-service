# Requirements: Unified Correlation-ID Handling and Trace Propagation

> **Stage 1 — Requirements** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43183**
> One theme (make a single correlation identifier survive end-to-end) delivered across seven
> separable concerns: (A) inbound header convention, (B) outbound propagation through
> `RestClientFactoryConfig`, (C) MDC restore for JobManager async tasks, (D) a usable `traceId` on
> `ErrorResponse`, (E) business identifiers as structured JSON fields, (F) OTLP tracing
> configuration, (G) MDC leak assurance. (B) is the highest-value fix and (F) is the smallest.
> The header/MDC-key convention (OQ-002, OQ-003), the JobManager MDC-restore mechanism (OQ-006),
> the `traceId` source (OQ-008) and the OTLP property migration (OQ-010) are deliberately left open
> for the Design stage; each is expected to be recorded in
> `adrs/DD-43183-correlation-id-unification.md`.

---

> **Note — the ticket says "three mechanisms"; there are at least six, and three of the ticket's
> specific factual claims are wrong.** Verified against the codebase at commit `7b9ac99`:
>
> 1. **`GlobalExceptionHandler.traceId()` does not return `null` — it returns the empty string
>    `""`.** With `management.tracing.enabled: false`, Spring Boot 4.0.6's
>    `NoopTracerAutoConfiguration` supplies `Tracer.NOOP`. `Tracer.NOOP.currentSpan()` returns
>    `Span.NOOP` (**non-null**, so the `Objects.requireNonNull` guard passes), whose `.context()` is
>    `TraceContext.NOOP`, whose `.traceId()` is a hard-coded `""`. Consequence for testing: an AC
>    asserting `traceId` is *non-null* **passes today against the broken behaviour**. ACs must
>    assert non-**blank**. See OQ-007.
> 2. **`management.otlp.tracing.enabled` is not a Spring Boot property at all** — not in Boot 4.0.6,
>    and not even as a deprecated alias. The whole line is inert; so is the adjacent
>    `management.otlp.tracing.endpoint`, which Boot 4.0.0 removed at deprecation level **`error`**.
>    Both lines of `application-server-management.yml` are dead config, so the bug is larger than
>    "bound to the wrong env var". See OQ-010.
> 3. **`CorrelationIdInterceptor` does not read an inbound header.** It reads `X-Request-ID` off the
>    *outbound* request CDKS is building. No CDKS code ever sets that header outbound, so the branch
>    is dead and the value is *always* a fresh `UUID.randomUUID()`. It is not a third inbound
>    convention. See OQ-001.
>
> Additionally, two correlation mechanisms the ticket does not mention are already live: the
> **`CPPCLIENTCORRELATIONID`** inbound header consumed by `cp-audit-filter-springboot`, and the
> **`requestId`** identifier already threaded through JobManager `jobData`. Both materially affect
> the "one documented convention" decision. See OQ-001 and OQ-005.

---

## Context

CDKS handles a request that fans out a long way. An inbound REST call is filtered, may call RAG,
Progression or Hearing over APIM, and may dispatch a chain of JobManager tasks that execute later on
other threads and in other pods. A production support engineer given an error reference today cannot
follow that request through the logs, because the identifier changes — or vanishes — at every hop.

### What actually exists today

Six distinct correlation mechanisms, in five separate identifier namespaces, none of which connect
to each other:

| # | Mechanism | Reads / writes | MDC effect | Ticket mentions it? |
|---|---|---|---|---|
| 1 | `config/RequestContextFilter` (`@Order(HIGHEST_PRECEDENCE + 1)`, bean name `correlationMdcFilter`) | inbound `X-Correlation-Id`, else fresh `UUID.randomUUID()` | puts `correlationId`, `cluster`, `region`, `path`; `MDC.clear()` in `finally` | Yes |
| 2 | `filters/tracing/TracingFilter` (`OncePerRequestFilter`, **no `@Order`** → `LOWEST_PRECEDENCE`) | inbound bare `traceId` / `spanId` headers; echoes both to response headers | puts `applicationName` always; puts `traceId`/`spanId` **only if the header is present** — no fallback generation. No `finally` cleanup of its own | Yes |
| 3 | `http/CorrelationIdInterceptor` (added to every `RestClient` by `RestClientFactoryConfig.build()`) | **outbound** `X-Request-ID`, always a fresh UUID (see note 3 above) | **overwrites** `correlationId`, then `MDC.remove("correlationId")` in `finally` | Yes, but mischaracterised |
| 4 | `cp-audit-filter-springboot` 1.0.5 → `AuditPayloadGenerationService` | inbound **`CPPCLIENTCORRELATIONID`** (case-insensitive match) → audit payload `_metadata.correlation.client` | none — never touches MDC | **No** |
| 5 | `JobManagerKeys.Params.REQUEST_ID` (`"requestId"`) in JobManager `jobData` | fresh `UUID.randomUUID()` per dispatch, in `DiscoveryService` (×2), `IngestionProcessorByCaseService`, `JobManagerService` | none — only ever a `log.*` message parameter | **No** |
| 6 | `metrics/StalledWorkMetrics` (DD-43185) | self-generated | puts a fresh UUID as `correlationId` for its scheduled refresh, removes in `finally` | **No** |

Plus one propagation helper, not a new identifier: `config/MdcCopyingTaskDecorator` (DD-43063,
commit `96eec11`) copies the MDC map onto the manual-discovery executor thread and clears it in
`finally`. It is wired **only** through `DiscoveryTriggerConfig` for `DiscoveryTriggerService` — it
does **not** cover JobManager task execution.

### The break at the service boundary

Mechanism 3 is worse than the ticket's "the chain is broken at every service boundary". Because it
does `MDC.put("correlationId", <fresh uuid>)` and then `MDC.remove(MDC_KEY)` — rather than saving and
**restoring** the previous value — the inbound correlation ID is not merely bypassed on the outbound
call, it is **destroyed for the remainder of the request**. Every log line emitted after CDKS's first
outbound HTTP call carries no `correlationId` field at all.

This has a live blast radius beyond logging: `DiscoverySchedulerController.triggerDiscovery()`
populates the OpenAPI response field `DiscoveryTriggerResponse.correlationId` from
`MDC.get("correlationId")`. That contract holds today only because no outbound call precedes it on
the request thread; `src/integrationTest/.../DiscoverySchedulerTriggerHttpLiveTest` asserts it
(`"correlationId":"<sent X-Correlation-Id>"`). Any redesign must keep that assertion passing.

### Facts confirmed against the codebase

| Ticket statement | Verified? | Detail |
|---|---|---|
| `RequestContextFilter` reads `X-Correlation-Id` | Yes | `RequestContextFilter:31`; UUID fallback at :33 |
| `RequestContextFilter` clears MDC in a `finally` block | Yes | `:40–42`, asserted by `RequestContextFilterTest.clearsMdcEvenIfChainThrowsException` |
| `TracingFilter` reads bare `traceId` / `spanId` headers | Yes | `:33–40`; also sets both as **response** headers |
| `CorrelationIdInterceptor` reads `X-Request-ID` | **Mischaracterised** | Reads it from the *outbound* request, never inbound; branch is dead, always a fresh UUID. OQ-001 |
| It replaces the value with `UUID.randomUUID()` | Yes | `:20–23`; **and** destroys inbound MDC `correlationId` at `:28`. Worse than stated |
| The resolved value is returned to the caller on the response | **No such behaviour today** | Nothing sets an `X-Correlation-Id` response header. Only `TracingFilter` echoes `traceId`/`spanId` |
| `RestClientFactoryConfig` builds the RAG / Progression / Hearing clients | Yes | `RestClientFactoryConfig.RestClientFactory.build()` attaches `CorrelationIdInterceptor` at `:117` unconditionally |
| JobManager tasks in `caseflow`, `queryflow`, `hearing` | Yes — **7 tasks**, not 8 | `caseflow` has **4** (`.claude/context/cdks-context.md` says 5 — context drift, OQ-014), `queryflow` 2, `hearing` 1 |
| Correlation can be restored from `ExecutionInfo` `jobData` | Yes, mechanically | `executionInfo.getJobData()` returns a `JsonObject`; chained tasks copy the parent map via `createObjectBuilder(jobData)`, so a seeded key **does** survive the whole chain |
| "A `JobManagerKeys` constant is added for the key" | **Already partly exists** | `JobManagerKeys.Params.REQUEST_ID = "requestId"`. But `RetrieveMaterialAndUploadTask:79` and `JobManagerService:59` still use the inline literal `"requestId"`. OQ-005 |
| `management.tracing.enabled` is `false` | Yes | `application-server-management.yml:34` — hard-coded `false`, **not** env-var bound |
| ⇒ `GlobalExceptionHandler.traceId()` always returns `null` | **No — returns `""`** | See note 1 above. OQ-007 |
| `management.otlp.tracing.enabled` bound to `${OTEL_METRICS_ENABLED:false}` | Yes, literally — **but the key does not exist** | `:40`. Inert in Boot 4.0.6. So is `:41`'s `management.otlp.tracing.endpoint` (removed at level `error` in 4.0.0). OQ-010 |
| Default endpoints are not the OTLP/HTTP spec paths | Yes | `/traces` and `/metrics`, not `/v1/traces` and `/v1/metrics` |
| `management.otlp.metrics.export.{enabled,url}` | **Valid** | These two *are* real Boot 4.0.6 properties; only the tracing half is broken |
| `LogstashEncoder` emits MDC as discrete JSON fields | Yes | `logback-spring.xml`; no `includeMdcKeyNames` restriction, so all MDC entries are included |
| `RagAnswerAsyncServiceImpl` "completed successfully" carries no identifier | **Only one of the two** | `:61` (`answerUserQueryAsync`) carries none — correct. `:104` (`answerUserQueryStatus`) **already** logs a CRLF-sanitised `transactionId`. OQ-009 |
| `caseId` / `docId` / `transactionId` are message-interpolated, not MDC fields | Yes | e.g. `RetrieveMaterialAndUploadTask:128`, `CheckIdpcAvailabilityAllDefendantsTask:64`. **No** `src/main` code puts a business identifier into MDC |
| `spring.threads.virtual.enabled` exists | Yes — **and defaults to `false`** | `application-other.yml:22` = `${VIRTUAL_THREADS:false}`. `VIRTUAL_THREADS` is set **nowhere** in the repo — not in compose, CI, or any profile. **Virtual threads are not enabled in any current configuration.** OQ-012 |
| `micrometer-registry-otlp` / OTLP exporters on the classpath | Yes | Transitively via `spring-boot-starter-opentelemetry` (`build.gradle:145`): `micrometer-tracing-bridge-otel` 1.6.5, `opentelemetry-exporter-otlp` 1.55.0 |

Two further notes for Design. First, `management.tracing.enabled: false` is the **master switch**:
correcting the OTLP export keys alone will still produce zero spans until that is flipped, so FR-011
depends on FR-009. Second, existing unit tests pin the current broken behaviour and must be rewritten
alongside the fix — `CorrelationIdInterceptorTest` explicitly asserts a generated UUID and asserts
`MDC.get("correlationId")` is `null` after execution (four separate test methods).

### Actors

| Actor | Interest in this change |
|---|---|
| Production support engineer | Primary. Given one error reference from a user, must retrieve every log line for that request in a single query. |
| Platform / SRE (log + trace pipeline owners) | Own the log index and the OTLP collector; consume the MDC field names and the trace export. Field-name and header conventions must match platform expectations (OQ-002, OQ-011). |
| CDKS engineers | Diagnose from the same fields; must not regress request latency or break the `DiscoveryTriggerResponse.correlationId` contract. |
| Upstream API consumers (AI Search UI, other CPP services) | Send the inbound correlation header and read `traceId` off `ErrorResponse`. Alias acceptance protects them from a breaking change. |
| Security / data-protection reviewer | Confirms no case content, answer text, `llm_input` or `CJSCPPUID` enters MDC, a log field, or a propagated header (NFR-001). |

**Note on source:** derived from the pasted Jira text at `00-input-brief.md`. The ticket itself was
not fetched in this session — no Jira/Atlassian MCP tool is available here, so no summary comment has
been posted to the epic either (OQ-013).

---

## Functional Requirements

### Area A — one inbound convention

| ID | Requirement |
|----|-------------|
| FR-001 | Exactly **one** inbound header is the documented correlation header. The others in current use are accepted as **aliases** for backwards compatibility, with a documented precedence order. Candidate set, resolved by OQ-002: `X-Correlation-Id` (`RequestContextFilter`), bare `traceId` (`TracingFilter`), and `CPPCLIENTCORRELATIONID` (already consumed by `cp-audit-filter-springboot`). If no alias is present, a value is generated. |
| FR-002 | The resolved value is placed in MDC under **one** documented key. The key must remain `correlationId` unless OQ-003 decides otherwise, because `DiscoverySchedulerController` reads `MDC.get("correlationId")` into an OpenAPI response field and an integration test asserts it. |
| FR-003 | The resolved value is returned to the caller on the HTTP **response**. This is new behaviour — no response header carries it today. Header name per OQ-002; applies to success and error responses alike. |

### Area B — outbound propagation (highest value)

| ID | Requirement |
|----|-------------|
| FR-004 | Every outbound call made through a `RestClient` built by `RestClientFactoryConfig.RestClientFactory.build()` — RAG, Progression, Hearing — carries the correlation ID currently in scope. Outbound header name per OQ-004. |
| FR-005 | `CorrelationIdInterceptor` no longer substitutes a fresh `UUID.randomUUID()` when a correlation ID is in scope. It must also stop **destroying** the ambient MDC value: it must restore the prior MDC state on exit rather than unconditionally removing the key, so log lines after an outbound call still carry the inbound correlation ID. |
| FR-006 | Where no correlation ID is in scope — a scheduled run, a JobManager task, a startup probe — the outbound call still carries a non-blank correlation value, generated once at the top of that unit of work rather than per outbound call, so all calls in one unit of work share a value. |

### Area C — asynchronous work inherits the correlation ID

| ID | Requirement |
|----|-------------|
| FR-007 | When a JobManager task in `caseflow`, `queryflow` or `hearing` (7 tasks) executes, it restores the correlation ID from `ExecutionInfo`'s `jobData` into MDC **for the duration of execution**, and removes/restores it afterwards so nothing leaks onto the pooled worker thread. |
| FR-008 | The `jobData` key is referenced through a `JobManagerKeys` constant, never an inline string literal. Note `JobManagerKeys.Params.REQUEST_ID` already exists and is already propagated end-to-end; reuse-versus-new-key is OQ-005. The two surviving inline `"requestId"` literals (`RetrieveMaterialAndUploadTask:79`, `JobManagerService:59`) are brought onto the constant. |
| FR-009 | The correlation ID seeded into `jobData` at dispatch is the **inbound request's** correlation ID where one exists, not an unrelated fresh UUID. This covers the four current dispatch sites: `DiscoveryService` (×2), `IngestionProcessorByCaseService`, `JobManagerService`. |

### Area D — error responses carry a usable trace identifier

| ID | Requirement |
|----|-------------|
| FR-010 | Every `ErrorResponse` returned by any handler in `GlobalExceptionHandler` carries a **non-blank** `traceId` in **every** environment, including with tracing disabled. "Non-blank" not "non-null": the field is `""` today, so a non-null assertion is not a valid oracle (OQ-007). Source of the value per OQ-008. |
| FR-011 | Searching a log index for that single returned value retrieves every log line for that request — i.e. the value placed on `ErrorResponse.traceId` and the value emitted as a structured log field are the same value. |

### Area E — business identifiers as structured fields

| ID | Requirement |
|----|-------------|
| FR-012 | A log statement emitted from a service, JobManager task, scheduler or client class has `caseId` in MDC, and `docId` and `transactionId` in MDC where applicable to that unit of work. Scope of "where applicable" per OQ-009. |
| FR-013 | These identifiers appear as **discrete JSON fields** via `LogstashEncoder`, not interpolated into the message string. The existing `logback-spring.xml` already emits all MDC entries as top-level fields, so no encoder change is required — the work is putting the values into MDC. |
| FR-014 | `RagAnswerAsyncServiceImpl`'s completion log lines carry the `transactionId`. `answerUserQueryAsync` (`:61`) has none today and can take it from the returned `UserQueryAnswerRequestAccepted.getTransactionId()`; `answerUserQueryStatus` (`:104`) already logs it as a message parameter and moves to a structured field. |
| FR-015 | No document content, answer text, `llm_input` value, `CJSCPPUID`, court reference number or personal data is logged at **any** level, and none is placed in MDC or in a propagated header. |

### Area F — OTLP export configuration

| ID | Requirement |
|----|-------------|
| FR-016 | Tracing export is controlled by its **own** `OTEL_TRACES_ENABLED` environment variable, independent of `OTEL_METRICS_ENABLED`. Because `management.otlp.tracing.enabled` does not exist in Boot 4.0.6, this must bind to the real key — `management.tracing.export.otlp.enabled` — and the dead `management.otlp.tracing.endpoint` must move to `management.opentelemetry.tracing.export.otlp.endpoint` (OQ-010). |
| FR-017 | Default endpoints are the OTLP/HTTP spec paths: `/v1/traces` and `/v1/metrics` (currently `/traces` and `/metrics`). |
| FR-018 | Enabling tracing in a non-production environment produces spans in the collector, evidenced by a screenshot attached to the Jira ticket. This requires `management.tracing.enabled` to become enable-able — it is hard-coded `false` today and is the master switch that makes FR-016 inert on its own (OQ-011). |

### Area G — MDC hygiene

| ID | Requirement |
|----|-------------|
| FR-019 | A test asserts that no MDC value set while handling request A is visible while handling request B, on the same or a recycled thread. |
| FR-020 | That assurance holds when `spring.threads.virtual.enabled` is `true`. Virtual threads are **not** enabled in any current configuration (`VIRTUAL_THREADS` defaults `false` and is set nowhere), so this is a forward-looking guarantee under a toggle — see OQ-012 for whether enabling the toggle is in scope. |

---

## Out of scope

- **Enabling virtual threads in production.** `VIRTUAL_THREADS` stays `false` by default. FR-020 asserts correlation handling *would* be safe under the toggle; flipping it for any environment is a separate decision (OQ-012).
- **Enabling tracing in production.** FR-018 is explicitly a non-production demonstration. Production sampling rates, collector endpoints and cost are the platform team's call (OQ-011).
- **Adopting Micrometer Observation / `@Observed` instrumentation, or creating custom spans.** No new spans, span names, or span attributes are requested — only that correlation and existing trace identifiers propagate and are logged.
- **Distributed tracing across CPP services** (W3C `traceparent` end-to-end, cross-service span linking). This ticket unifies *CDKS's own* correlation handling.
- **Changing the audit payload or the `cp-audit-filter-springboot` / `cp-auth-rules-filter` libraries.** `CPPCLIENTCORRELATIONID` may be *read* as an alias (FR-001); the audit filter's own behaviour and version are untouched.
- **Any new or changed REST endpoint.** `DiscoveryTriggerResponse.correlationId` and `ErrorResponse.traceId` are existing OpenAPI fields being correctly populated, not new contract. `api-cp-crime-caseadmin-case-document-knowledge` 0.0.11 is not bumped.
- **Any Flyway migration or schema change.** Nothing in this ticket is persisted; the highest shipped version stays `V1011`.
- **New custom metrics.** DD-43185's gauges are unaffected; `StalledWorkMetrics`'s self-generated MDC `correlationId` is only revisited if it conflicts with the FR-002 convention.
- **Retrofitting `caseId`/`docId` MDC fields onto repository, mapper or entity classes.** FR-012 names service, task, scheduler and client classes only.
- **Log retention, index configuration, log-based alerting, or Kibana/Grafana saved searches.** Outside this repository (OQ-011).
- **Backfill or reprocessing of historical logs** emitted before deployment.

---

## Non-Functional Requirements

Trimmed to NFRs carrying ticket-specific decision content. Migration governance, PMD/JaCoCo,
platform versions and Managed-Identity rules are covered generically by CLAUDE.md's hard rules and
are not repeated here.

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-001 | Data protection | No case content, answer text, `llm_input`, document name, `CJSCPPUID`, court reference number or other personal data may appear in an MDC key or value, a structured log field, an outbound propagated header, or an `ErrorResponse` field. The correlation ID itself must be an opaque identifier carrying no case information — a client-supplied inbound alias value must not be echoed anywhere it could leak case data, which constrains OQ-002. |
| NFR-002 | Log-injection safety | A correlation value accepted from an inbound header is attacker-controllable. Before entering MDC it must be validated or sanitised against CRLF injection and unbounded length, so it cannot forge or split a JSON log record. `RagAnswerAsyncServiceImpl:99–103` already sets the in-repo precedent for CRLF stripping. Reject-and-regenerate versus sanitise is a Design decision. |
| NFR-003 | Performance | Correlation resolution runs once per request in the filter chain and must add no measurable latency; the outbound interceptor must not add a per-call allocation beyond a header set. No new I/O, no lookup, no lock on the request path. |
| NFR-004 | Availability | No new failure mode. A missing, malformed or oversized correlation header, an absent `jobData` key, or a disabled tracer must never fail a request, fail a JobManager task, fail a scheduled run, or fail startup. Every correlation path degrades to a generated value. |
| NFR-005 | Backward compatibility | Aliases keep existing callers working (FR-001). `DiscoverySchedulerTriggerHttpLiveTest`'s assertion on `"correlationId":"<sent value>"` must pass **unmodified**. `TracingFilter`'s `traceId`/`spanId` response headers must not be withdrawn without a decision (OQ-002). `ErrorResponse` and `DiscoveryTriggerResponse` field names and types are unchanged. |
| NFR-006 | Testability | Unit coverage for inbound resolution and alias precedence, the outbound interceptor's save/restore semantics (FR-005), and the JobManager MDC restore. `integrationTest` coverage asserting: a sent correlation ID reaches a WireMock-stubbed downstream request header; the same value is on the response; and it appears as a JSON log field. `CorrelationIdInterceptorTest`'s four methods currently assert the broken behaviour and must be rewritten. `gradle clean build` (including `integration`) passes. |
| NFR-007 | Configurability | Header names, alias precedence and the MDC key are not scattered string literals — one documented place, following the repo's `application-*.yml` + `CP_CDK_*` / `OTEL_*` env-var convention. `OTEL_TRACES_ENABLED` and `OTEL_METRICS_ENABLED` are independently settable. |
| NFR-008 | Documentation | The chosen convention is written down — inbound header, aliases and precedence, MDC key, outbound header, response header, `jobData` key — as a durable artefact, since "one **documented** convention" is the story's stated deliverable. Location per OQ-002. |
| NFR-009 | Cardinality / cost | Correlation IDs are high-cardinality by nature. They are log **fields** and trace identifiers only, and must never become a Micrometer metric tag or a Prometheus label. |

---

## Acceptance Criteria

Derived one-for-one from the seven Gherkin scenarios in `00-input-brief.md`. Nothing here extends the
ticket's scope; where the ticket is silent or wrong, an open question is raised instead.

**One correlation convention replaces the current several (FR-001, FR-002, FR-003)**
- AC-001: Given a request arrives carrying only the single documented inbound correlation header, when it is handled, then that value is the resolved correlation ID.
- AC-002: Given a request arrives carrying only an accepted alias (`X-Correlation-Id`, bare `traceId`, or `CPPCLIENTCORRELATIONID` per OQ-002), when it is handled, then the alias value is honoured as the resolved correlation ID.
- AC-003: Given a request arrives carrying both the documented header and one or more aliases with different values, when it is handled, then the documented precedence order (OQ-002) selects the value deterministically, and a test pins that order.
- AC-004: Given a request arrives carrying none of the accepted headers, or carrying one that is blank, when it is handled, then a non-blank correlation ID is generated.
- AC-005: The resolved value is present in MDC under exactly one documented key for the duration of request handling, and no second MDC key holds a different correlation value at the same time.
- AC-006: The resolved value is returned to the caller on the response, on both a 2xx and a 4xx/5xx response.
- AC-007: Given `X-Correlation-Id: <value>` is sent to `/discovery-scheduler/trigger`, when the request is handled, then the response body's `correlationId` field equals `<value>` — i.e. `DiscoverySchedulerTriggerHttpLiveTest` passes with its existing assertion unmodified.

**Correlation propagates to every downstream call (FR-004, FR-005, FR-006)**
- AC-008: Given an inbound request carries correlation ID `abc-123`, when CDKS calls the RAG service, Progression, or Hearing via a `RestClient` built by `RestClientFactoryConfig`, then the outbound request carries `abc-123` in the documented outbound header.
- AC-009: `CorrelationIdInterceptor` does not replace an in-scope correlation ID with a fresh `UUID.randomUUID()`; a unit test asserts the in-scope value is transmitted verbatim.
- AC-010: Given an inbound request with correlation ID `abc-123` makes an outbound call and then emits a further log line, when that line is emitted, then it still carries `abc-123` — the interceptor restores rather than removes the ambient MDC value (FR-005).
- AC-011: Given a request makes two or more outbound calls, when they execute, then every one carries the same `abc-123`, not one value each.
- AC-012: Given a unit of work with no inbound request (scheduled run or JobManager task) makes an outbound call, when it executes, then the outbound request still carries a non-blank correlation value, shared across all outbound calls in that unit of work.

**Asynchronous work inherits the correlation ID (FR-007, FR-008, FR-009)**
- AC-013: Given an ingestion is started with correlation ID `abc-123`, when a JobManager task in `caseflow`, `queryflow` or `hearing` later executes for that work, then the task restores `abc-123` into MDC from `ExecutionInfo`'s `jobData` for the duration of execution.
- AC-014: After that task returns — normally or by throwing — the correlation value is no longer present on that worker thread's MDC, so it cannot leak into an unrelated task (relates to FR-019).
- AC-015: The `jobData` correlation key is referenced via a `JobManagerKeys` constant at every read and write site; no inline `"requestId"`-style literal remains in `src/main` (currently `RetrieveMaterialAndUploadTask:79` and `JobManagerService:59`).
- AC-016: Given a task's `jobData` is missing the correlation key, when the task executes, then it does not throw and the log lines carry a generated correlation value rather than nothing (NFR-004).
- AC-017: Given a task chains a successor task via `createObjectBuilder(jobData)`, when the successor executes, then it carries the same correlation ID as its predecessor.

**Error responses carry a usable trace identifier (FR-010, FR-011)**
- AC-018: Given tracing is disabled (`management.tracing.enabled: false`, the current default), when any handler in `GlobalExceptionHandler` returns an `ErrorResponse`, then `traceId` is **non-blank** — specifically not the empty string `""` it returns today. A non-null-only assertion is not acceptable, as it passes against current broken behaviour (OQ-007).
- AC-019: The same holds with tracing enabled, and in every environment/profile.
- AC-020: Given a support engineer takes the `traceId` from an error response, when they search the log index for that exact value, then they retrieve the log lines for that request — i.e. the `traceId` on the response and the correlation field on the log lines are the same value (FR-011).
- AC-021: This holds for every handler in `GlobalExceptionHandler`: `ResponseStatusException`, `MethodArgumentNotValidException`, `ConstraintViolationException`, `HttpMessageNotReadableException`, `HttpRequestMethodNotSupportedException`, and the catch-all `Exception`.

**Business identifiers on every operational log line (FR-012, FR-013, FR-014, FR-015)**
- AC-022: Given a log statement is emitted from a service, JobManager task, scheduler or client class handling work for a known case, when the line is emitted, then MDC contains `caseId`, and `docId` and `transactionId` where applicable to that unit of work.
- AC-023: Those identifiers appear as discrete top-level JSON fields in the emitted line, not embedded in the `message` string; a test parses an emitted JSON log line and asserts the fields exist as siblings of `message`.
- AC-024: Given `RagAnswerAsyncServiceImpl.answerUserQueryAsync` completes successfully, when its completion line is emitted, then it carries the `transactionId` (from the returned `UserQueryAnswerRequestAccepted`) — it carries no identifier at all today.
- AC-025: Given `RagAnswerAsyncServiceImpl.answerUserQueryStatus` completes successfully, when its completion line is emitted, then the `transactionId` is present as a structured field rather than only interpolated into the message.
- AC-026: No emitted log line at any level contains document content, answer text, an `llm_input` value, a `CJSCPPUID` value, a court reference number, or personal data.

**OTLP export configuration is corrected (FR-016, FR-017, FR-018)**
- AC-027: Tracing export is controlled by `OTEL_TRACES_ENABLED`, and setting `OTEL_METRICS_ENABLED` alone does not enable or disable tracing export.
- AC-028: The tracing configuration binds to property keys that Spring Boot 4.0.6 actually recognises; a test or startup check asserts no correlation/tracing property in `application-server-management.yml` is an unbound or `error`-level-deprecated key. `management.otlp.tracing.enabled` and `management.otlp.tracing.endpoint` are both gone (OQ-010).
- AC-029: The default trace endpoint path is `/v1/traces` and the default metrics endpoint path is `/v1/metrics`.
- AC-030: Given tracing is enabled in a non-production environment, when requests are made, then spans appear in the collector, evidenced by a screenshot attached to Jira DD-43183. Note this requires `management.tracing.enabled` to be enable-able (OQ-011); AC-027 alone does not achieve it.
- AC-031: With `OTEL_TRACES_ENABLED` and `OTEL_METRICS_ENABLED` both unset, the service starts cleanly and exports neither — the current effective default is preserved.

**MDC does not leak between requests (FR-019, FR-020)**
- AC-032: Given request A sets a correlation value in MDC, when request B is subsequently handled on the same or a recycled thread, then no MDC value from request A is visible while handling request B.
- AC-033: The same assertion holds with `spring.threads.virtual.enabled=true`.
- AC-034: `RequestContextFilter` clears MDC in a `finally` block, including when the filter chain throws — `RequestContextFilterTest.clearsMdcEvenIfChainThrowsException` continues to pass.

**No regression**
- AC-035: `gradle clean build` (including `integration`) passes; PMD and JaCoCo are green at existing, unmodified thresholds; CodeQL and the secrets scanner are clean.
- AC-036: No RAG response field is dropped or transformed by any change to the ingestion or answer-serving flow (CLAUDE.md hard rule); `doc_id` and `llm_input` continue to be persisted and served unaltered.
- AC-037: The diff introduces no PII, case content, court reference number or `CJSCPPUID` into code, config, tests or fixtures; correlation values in tests and WireMock stubs are synthetic.
- AC-038: No existing OpenAPI field name or type changes; `api-cp-crime-caseadmin-case-document-knowledge` stays at 0.0.11 and `version.cdk` is untouched.

---

## Candidate Sub-Stories (preview for Stage 3)

Indicative breakdown; each needs its own Jira sub-ticket before Test Specs, per the CLAUDE.md rule
that every story has a linked ticket. **Story 1 must land first** — it fixes the resolution and the
MDC-destruction bug that Stories 2–4 all build on. Stories 5 and 6 are independent of the rest and
can be done in parallel.

1. **Story 1 — Unify inbound resolution and stop destroying MDC.** One documented header plus aliases and precedence; one MDC key; response echo; rewrite `CorrelationIdInterceptor` to save/restore rather than remove. Covers FR-001 – FR-003, FR-005, AC-001 – AC-007, AC-010, AC-034. Requires OQ-002, OQ-003 resolved.
2. **Story 2 — Propagate correlation on every outbound RestClient call.** The epic's highest-value fix: real propagation to RAG/Progression/Hearing, plus the no-inbound-context fallback. Covers FR-004, FR-006, AC-008, AC-009, AC-011, AC-012. Requires OQ-004.
3. **Story 3 — Restore correlation into MDC for JobManager async execution.** Mechanism across the 7 tasks (per-task versus aspect versus library hook — OQ-006), `JobManagerKeys` constant consolidation, seed from the inbound ID at the 4 dispatch sites. Covers FR-007 – FR-009, AC-013 – AC-017. Requires OQ-005, OQ-006.
4. **Story 4 — Populate `ErrorResponse.traceId` with a usable value.** Includes correcting the empty-string behaviour and choosing the value's source. Covers FR-010, FR-011, AC-018 – AC-021. Requires OQ-007, OQ-008.
5. **Story 5 — Business identifiers as structured JSON log fields.** `caseId`/`docId`/`transactionId` into MDC across services, tasks, schedulers and clients; both `RagAnswerAsyncServiceImpl` completion lines. Covers FR-012 – FR-015, AC-022 – AC-026. Requires OQ-009.
6. **Story 6 — Fix OTLP tracing configuration.** Migrate to the real Boot 4 property keys, split `OTEL_TRACES_ENABLED` from `OTEL_METRICS_ENABLED`, `/v1/*` spec paths, make `management.tracing.enabled` configurable, capture collector evidence. Covers FR-016 – FR-018, AC-027 – AC-031. Requires OQ-010, OQ-011.
7. **Story 7 — MDC leak assurance and test coverage.** Cross-request isolation tests including under the virtual-threads toggle; rewrite the four `CorrelationIdInterceptorTest` methods that pin the broken behaviour; integration coverage that a sent correlation ID reaches a WireMock downstream request and appears as a JSON log field. Covers FR-019, FR-020, NFR-006, AC-032, AC-033, AC-035 – AC-038. Requires OQ-012.

Explicitly **not** a story here: custom span creation, cross-service W3C trace propagation, enabling
virtual threads or production tracing, log-index or alerting configuration.

---

## Open Questions

- **OQ-001 (the "three mechanisms" premise is wrong — confirm the intended scope):** the ticket names three. Verified: there are at least **six** in five identifier namespaces (see the Context table), and the ticket's third — `CorrelationIdInterceptor` "reads `X-Request-ID`" — is a mischaracterisation: it reads that header off the *outbound* request CDKS is building, nothing ever sets it there, so the branch is dead and the value is always a fresh UUID. It is an outbound writer, not an inbound convention. Confirm the unification is meant to cover all six (in particular items 4 and 5 below), or is deliberately scoped to the three named. — Owner: requester · Due: before Stage 2.
- **OQ-002 (which header wins, and what are the aliases?):** FR-001 requires "a single documented inbound header ... with the others accepted as aliases", but the ticket does not say which one is canonical. Four candidates are in play: `X-Correlation-Id` (current `RequestContextFilter`), bare `traceId` (current `TracingFilter`, non-standard and collides with W3C tracing vocabulary), `CPPCLIENTCORRELATIONID` (**already consumed by `cp-audit-filter-springboot` 1.0.5** — so arguably the CPP platform convention already, and unmentioned by the ticket), and W3C `traceparent` (standards-aligned, propagates natively, but a different shape). Also settle: the **outbound** and **response** header names (FR-003, FR-004) — same name or different? — and whether `TracingFilter`'s `traceId`/`spanId` response headers are retained, since withdrawing them is a breaking change for anything reading them. Platform/SRE must confirm what the log pipeline and other CPP services expect. — Owner: requester + platform/SRE · Due: Stage 2, **ADR required**.
- **OQ-003 (the MDC key, and the API-contract constraint on renaming it):** "one documented key" is unnamed. `correlationId` is used today by `RequestContextFilter`, `CorrelationIdInterceptor`, `StalledWorkMetrics` and `MdcCopyingTaskDecorator`'s consumers — and, critically, `DiscoverySchedulerController` reads `MDC.get("correlationId")` straight into the OpenAPI response field `DiscoveryTriggerResponse.correlationId`, asserted by `DiscoverySchedulerTriggerHttpLiveTest`. Renaming the key silently nulls a published API field. Recommend keeping `correlationId`; confirm, and confirm what happens to the now-redundant `traceId`/`spanId`/`applicationName` MDC keys `TracingFilter` sets. — Owner: requester + platform/SRE · Due: Stage 2.
- **OQ-004 (where does the outbound value come from?):** `CorrelationIdInterceptor` is a `ClientHttpRequestInterceptor` with no request-scope injection, so it must read the ambient MDC. That works on the request thread and on threads covered by `MdcCopyingTaskDecorator`, but **not** on a JobManager worker thread until OQ-006 is solved — making Story 2 partly dependent on Story 3 for the async paths. Confirm MDC is the agreed source, or whether a `ThreadLocal`/`ScopedValue` correlation-context holder should be introduced instead. — Owner: requester's design reviewers · Due: Stage 2, ADR recommended.
- **OQ-005 (reuse `requestId` or add a new `jobData` key?):** the AC says "a `JobManagerKeys` constant is added for the key rather than an inline string literal" — but `JobManagerKeys.Params.REQUEST_ID = "requestId"` **already exists**, is already seeded at all four dispatch sites, and already survives the whole task chain via `createObjectBuilder(jobData)`. So the plumbing is built; what is missing is seeding it from the inbound correlation ID and putting it into MDC. Decide: (a) reuse `requestId` as *the* correlation key (least churn, but the name diverges from the FR-002 MDC key), or (b) add a distinct correlation key alongside it (two identifiers per job, needing a documented relationship). Either way, the surviving inline literals at `RetrieveMaterialAndUploadTask:79` and `JobManagerService:59` are fixed. — Owner: requester · Due: Stage 2, **ADR required**.
- **OQ-006 (how to restore MDC across 7 tasks without 7 copy-pastes?):** each task is a `@Task`-annotated `ExecutableTask` invoked by `task-manager-service` 1.0.10; there is **no central execution hook in CDKS**. Options: repeat a try/finally in all 7 `execute()` methods (simple, duplicated, easy to forget on task #8); a Spring AOP aspect around `ExecutableTask.execute` (DRY but adds AOP, currently unused in this repo); a decorator/base class; or an extension point in `task-manager-service` if one exists (needs investigation, and a library change is outside this repo). Note `MdcCopyingTaskDecorator` does **not** help here — it is wired only to `DiscoveryTriggerConfig`'s executor. — Owner: requester's design reviewers · Due: Stage 2, **ADR required**.
- **OQ-007 (the ticket's `null` claim is wrong — `""` — so restate the AC):** `GlobalExceptionHandler.traceId()` returns the **empty string**, not `null`. Chain: `management.tracing.enabled: false` → `NoopTracerAutoConfiguration` supplies `Tracer.NOOP` → `currentSpan()` returns `Span.NOOP`, which is non-null so `Objects.requireNonNull` never throws → `.context()` is `TraceContext.NOOP` → `.traceId()` is a hard-coded `""`. Two consequences: the AC's "populated with a non-null value" is **not a valid test oracle** (it passes today against the bug), and consumers currently receive `"traceId": ""` rather than an absent field. Confirm the AC is restated as **non-blank**, and confirm whether an absent trace should serialise as omitted or as an explicit value. Also note the bare `catch (Exception ignored)` at `GlobalExceptionHandler:40` silently swallows any real tracer failure and should be reconsidered. — Owner: requester · Due: Stage 2.
- **OQ-008 (what value goes in `traceId`?):** FR-010 needs a non-blank value with tracing disabled, but the ticket does not say what. Options: (a) the unified correlation ID — satisfies AC-020's "single value" goal directly and needs no tracing, but the field is named `traceId`, so the name would no longer mean a trace ID; (b) the real span trace ID when tracing is on and the correlation ID as fallback — accurate but environment-dependent, so support engineers get different value shapes in different environments; (c) enable tracing everywhere so a genuine trace ID always exists — cost and platform implications (OQ-011). Note that (a) makes `ErrorResponse.traceId` and `DiscoveryTriggerResponse.correlationId` carry the same value under different field names — confirm that is acceptable, or whether `ErrorResponse` should gain a `correlationId` field instead (which *would* be an API change, currently out of scope). — Owner: requester + platform/SRE · Due: Stage 2, **ADR required**.
- **OQ-009 (scope of "every operational log line"):** FR-012 says MDC contains `caseId` and, where applicable, `docId` and `transactionId` for any log from "a service, task, scheduler or client class". Taken literally that is a very large diff across `services/`, `jobmanager/`, `scheduler/` and `clients/`, and some units of work legitimately have no `caseId` (`/queries` list, `/query-catalogue`, the discovery schedulers, `StalledWorkMetrics`). Define: which classes are in scope; what "where applicable" means concretely; whether a missing `caseId` is acceptable or must be an explicit sentinel; and which `transactionId` is meant (`CTX_RAG_TRANSACTION_ID` / `ragTransactionId`, or something else). Also note the ticket's claim that `RagAnswerAsyncServiceImpl`'s completion messages "currently carry no identifier at all" is only true of `answerUserQueryAsync` (`:61`) — `answerUserQueryStatus` (`:104`) already logs a sanitised `transactionId`. — Owner: requester · Due: Stage 2.
- **OQ-010 (the OTLP config is dead, not merely mis-bound — confirm the target keys):** the ticket says `management.otlp.tracing.enabled` is "bound to `${OTEL_METRICS_ENABLED:false}`", which is literally what line 40 says — but **that property does not exist in Spring Boot 4.0.6**, not even as a deprecated alias, so the line is inert. Line 41's `management.otlp.tracing.endpoint` is worse: Boot 4.0.0 removed it at deprecation level **`error`**, replaced by `management.opentelemetry.tracing.export.otlp.endpoint`. Verified from `spring-boot-micrometer-tracing-opentelemetry-4.0.6`'s configuration metadata. So the fix is a **property migration**, not a one-line env-var swap: `enabled` → `management.tracing.export.otlp.enabled`, `endpoint` → `management.opentelemetry.tracing.export.otlp.endpoint`. (The metrics half, `management.otlp.metrics.export.{enabled,url}`, *is* valid and needs only the `/v1/metrics` path change.) Confirm the target keys with platform, and consider adding `spring-boot-properties-migrator` to CI temporarily to catch any other dead keys — nothing currently fails the build on one. — Owner: requester + platform/SRE · Due: Stage 2, **ADR required**.
- **OQ-011 (`management.tracing.enabled` is the real master switch — who owns flipping it?):** it is hard-coded `false` at `application-server-management.yml:34`, not env-var bound. While it is `false`, no tracer is created, no spans exist, and fixing the OTLP export keys (FR-016) produces **nothing** — so AC-030's collector screenshot is unachievable without also making this configurable. Confirm: (a) it becomes env-var bound (e.g. `TRACING_ENABLED`); (b) which non-production environment hosts the demonstration and who provides the collector; (c) whether it will ever be `true` in production, since OQ-008 option (b) makes `ErrorResponse.traceId` environment-dependent otherwise. Note `management.tracing.sampling.probability` is set to `${TRACING_SAMPLER_PROBABILITY:1.0}` — 100% sampling — against a Boot default of 0.1; that is likely wrong for production volume and cost. — Owner: requester + platform/SRE · Due: Stage 2, **ADR required**.
- **OQ-012 (virtual threads are not enabled — what is FR-020 actually asking for?):** the scenario says "when correlation handling is extended to virtual threads (`spring.threads.virtual.enabled`)", but `application-other.yml:22` binds it to `${VIRTUAL_THREADS:false}` and `VIRTUAL_THREADS` is set **nowhere** in the repo — no compose file, no CI workflow, no profile. So nothing is running on virtual threads today. Clarify whether the deliverable is (a) enable virtual threads and prove correlation survives (a significant, riskier change well beyond correlation handling), or (b) leave the toggle off and add a test that runs with it forced on, proving no leak if it is ever flipped. Recommend (b). Worth noting for Design that virtual threads generally *reduce* MDC-leak risk — one virtual thread per task, no pooling and therefore no reuse — so the realistic leak surface is pooled platform-thread executors such as `MdcCopyingTaskDecorator`'s and the JobManager workers, not virtual threads. — Owner: requester · Due: Stage 2.
- **OQ-013 (source of truth):** Jira DD-43183 was not fetched in this session — no Jira/Atlassian MCP tool is available — so this document is grounded solely in the pasted text at `00-input-brief.md`, and **no summary comment has been posted to the ticket**. Confirm the pasted brief is the complete and current ticket text (no later comments, no revised ACs) and post the Stage-1 summary manually. — Owner: requester · Due: before Stage 2.
- **OQ-014 (context-doc drift, minor):** `.claude/context/cdks-context.md` states `caseflow/` holds "5 multi-defendant tasks"; there are **4** (`CheckAllDocumentsIngestionStatusTask`, `CheckIdpcAvailabilityAllDefendantsTask`, `CheckIngestionStatusForAllDefendantsTask`, `RetrieveMaterialAndUploadTask`), giving 7 JobManager tasks in total, not 8. `tech-stack.md` also records Spring Boot 4.0.5 while `build.gradle` pins 4.0.6. Neither affects this ticket's scope, but FR-007's "every JobManager task" needs the correct count, and both context docs should be corrected. — Owner: CDKS engineers · Due: housekeeping, non-blocking.
