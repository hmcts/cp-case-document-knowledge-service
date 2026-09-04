# Architecture Decision Records — Operational Metrics Instrumentation (Micrometer)

> Service: `cp-case-document-knowledge-service` (CDKS) · Jira: DD-43182 · Taken at Stage 2
> (Architecture & Design), resolving Stage 1 open questions OQ-002 – OQ-017.
> Requirement: [`../DD-43182-operational-metrics-instrumentation/`](../DD-43182-operational-metrics-instrumentation/) ·
> Requirements: [`01-requirements.md`](../DD-43182-operational-metrics-instrumentation/01-requirements.md) ·
> Design: [`02-design.md`](../DD-43182-operational-metrics-instrumentation/02-design.md)
>
> **Status of this file: Stage-2 human gate cleared on 2026-09-03. All ten ADRs are `Accepted`.**
> All six gate items in `02-design.md` §14 (GATE-1 – GATE-6) are **accepted as designed**,
> including GATE-6's re-scoping of AC-024. The cross-ticket coordination item with DD-43183
> (`JobCorrelationAspect` ordered outermost of `TaskRetryMetricsAspect` on `ExecutableTask.execute`;
> this ADR's "`cdk.metrics.enabled=false` removes the proxying entirely" claim is corrected — see
> the note at ADR-006/§10) is also accepted. Two items remain outside Design's control and are
> carried forward as follow-ups, not blockers: OQ-018's second half (platform/SRE confirmation of
> the `cdk_` prefix for these eight new names) and OQ-019 (the owning team for alert rules).
>
> **This is the second metrics ticket in CDKS.** DD-43185's ADR-001 already settled the house
> convention (lowercase dot-separated Micrometer registration names, no `.total` segment on
> counters, no snake-casing on this classpath, tag values mirroring their source-of-truth token,
> every name/key/value a `CdkMeters` constant) and DD-43185 ADR-006 settled that a metric's
> identity must be a compile-time constant, never a runtime-configurable string. **Those decisions
> are reused verbatim and are not re-derived here.** ADR-001 below records only the *extensions*
> DD-43182 needs, principally the Timer naming rule that DD-43185 had no occasion to settle
> because CDKS had no `Timer` at all.
>
> **Sequencing (OQ-018), verified at Design:** DD-43185 is merged on `develop`
> (`885357e`, "[DD-43185] Implement stalled-work gauges & scheduler heartbeat observability
> (#224)", which is `origin/develop`'s head). `origin/main` is still at `ae2205e` and has **no
> `metrics` package**. `src/main/java/uk/gov/hmcts/cp/cdk/metrics/` therefore exists on `develop`
> with `CdkMeters`, `SchedulerMetrics`, `StalledWorkMetrics` and `StalledWorkMetricsRefreshJob`
> present, and `V1014` is on disk (next free migration is `V1015`, though this ticket needs none).
> **DD-43182 branches from `develop` and extends the existing `CdkMeters`.** No parallel constants
> class. OQ-018's first half is closed by evidence; its second half (SRE confirmation of the
> `cdk_` prefix) is still open and is inherited, unchanged, from DD-43185 ADR-001.

---

## ADR-001: Extend `CdkMeters` under DD-43185's convention — and add one new rule: a Timer's registered name never carries `.seconds`

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Extends:** DD-43185 ADR-001 · **Resolves:** NFR-008, and the naming half of every FR
- **Artefacts:** `01-requirements.md` (NFR-008, FR-001, FR-003, FR-005, FR-008 – FR-011, AC-002) · `02-design.md` (§2)

### Context

DD-43185 ADR-001 established the naming convention from decompiled evidence on this exact
classpath (Micrometer 1.16.5 + Prometheus Java client 1.x): `PrometheusNamingConvention.name(...)`
escapes invalid characters, **preserves case**, and `_total` is appended by the exposition writer,
not the naming convention. That ruling stands and is not revisited.

What DD-43185 could not settle is the Timer case, because **CDKS had no `Timer` and no histogram
anywhere** — its six meters are five gauges and one counter. DD-43182 introduces the first two
Timers, and the same decompiled `PrometheusNamingConvention.name(...)` contains a second
conditional the gauge/counter cases never exercised: for `Meter.Type.TIMER` /
`Meter.Type.LONG_TASK_TIMER` it appends `_seconds`, guarded by an `endsWith` check, before
`sanitizeMetricName(...)` runs.

Two consequences:

- Registering `cdk.external.call.duration` as a `Timer` renders as
  `cdk_external_call_duration_seconds` — exactly the name the ticket asks for.
- Registering `cdk.external.call.duration.seconds` would render **identically**, but only because
  of the `endsWith` guard. That is the same class of reliance DD-43185 ADR-001 rejected for
  `.total` ("relying on a sanitizer's strip step is not a contract"), so it must be rejected here
  for the same reason and by the same argument.

`sanitizeMetricName` also strips `_bucket` as a reserved suffix, so no meter name may end in
`.bucket` either. Nothing in this ticket does; recorded so the next ticket does not have to
rediscover it.

### Decision

1. **A Timer's registered Micrometer name carries no `.seconds` segment and no `baseUnit`.** The
   Prometheus convention appends `_seconds`. So:
   `cdk.document.ingestion.duration` → `cdk_document_ingestion_duration_seconds`;
   `cdk.external.call.duration` → `cdk_external_call_duration_seconds`.
   This is the exact analogue of DD-43185's `.total` rule and is stated so the pair reads as one
   rule: *never encode in the registered name what the exposition layer appends.*
2. **Counters keep DD-43185's rule.** `cdk.document.ingestion.phase`, `cdk.answer.generation`,
   `cdk.task.retry` and `cdk.task.retry.exhausted` — no `.total`.
3. **Extend the existing `uk.gov.hmcts.cp.cdk.metrics.CdkMeters`.** No second constants class, no
   per-area constants class. Its Javadoc mapping table gains one row per new meter, and gains the
   Timer rule as prose. The class stays `final` with a throwing private constructor.
4. **The full name mapping this ticket registers:**

   | Ticket's Prometheus name | Micrometer meter name | Type | Ticket-specific tags |
   |---|---|---|---|
   | `cdk_document_ingestion_phase_total` | `cdk.document.ingestion.phase` | Counter | `phase`, `source` |
   | `cdk_document_ingestion_duration_seconds` | `cdk.document.ingestion.duration` | Timer | `phase` |
   | `cdk_external_call_duration_seconds` | `cdk.external.call.duration` | Timer | `dependency`, `operation`, `outcome` |
   | `cdk_answer_generation_total` | `cdk.answer.generation` | Counter | `outcome`, `query_level` |
   | `cdk_task_retry_total` | `cdk.task.retry` | Counter | `task_name`, `retry_policy` |
   | `cdk_task_retry_exhausted_total` | `cdk.task.retry.exhausted` | Counter | `task_name`, `retry_policy` |
   | `cdk_http_pool_connections_leased` | `cdk.http.pool.connections.leased` | Gauge | — |

5. **Tag-value casing follows DD-43185 ADR-001's rule with no exceptions.** Applied to the new tag
   sets:
   - `phase` — `DocumentIngestionPhase` enum constants verbatim (`WAITING_FOR_UPLOAD`, `UPLOADED`,
     `INGESTED`, `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT`). Database enum → verbatim.
   - `query_level` — `QueryLevel` enum constants verbatim (`CASE`, `DEFENDANT`,
     `CASE_ALL_DOCUMENTS`), plus the CDKS-invented `unknown` (ADR-007).
   - `task_name` — `TaskNames` constants verbatim (they are already UPPER_SNAKE and are the
     persisted `jobs.assigned_task_name` values).
   - `outcome` — the ticket's literals as stated (`success`, `client_error`, `server_error`,
     `timeout`; `succeeded`, `failed`, `timed_out`), plus the CDKS-invented `error` (ADR-003).
     **Note the deliberate inconsistency the ticket itself creates:** `cdk_external_call_duration_seconds`
     uses `success` while `cdk_answer_generation_total` uses `succeeded`. Design keeps both as the
     ticket states them rather than harmonising, because renaming a tag value after alert rules
     exist is the cross-repo change DD-43185 ADR-001 warned about, and neither value is wrong.
     This is called out for the gate rather than silently smoothed over.
   - `dependency` — the ticket's literals (`rag`, `progression`, `hearing`, `azure_blob`).
   - `operation` — CDKS-invented, lowercase kebab-case (ADR-004).
   - `retry_policy` — CDKS-invented, lowercase kebab-case matching the configuration key
     (`default-retry`, `verify-document-status`, `questions-retry`, `none`) — ADR-006.
   - `source` — the entity's value, membership-checked against a fixed allow-list (ADR-009).

### Alternatives considered

- **`cdk.document.ingestion.duration.seconds` / `.baseUnit("seconds")`.** Rejected per (1): it
  renders correctly only through the `endsWith` guard and the reserved-suffix stripper. Same
  argument DD-43185 used against `cdk.scheduler.runs.total`; consistency matters more here than
  the marginal readability of a self-describing constant.
- **A `DD43182Meters` or per-area constants class.** Rejected. NFR-008 requires the existing
  `CdkMeters`, and the whole point of a single definition site is that a rename is one edit.
- **Harmonising `succeeded` → `success` across the two counters.** Rejected as a Design decision;
  raised as a **GATE** item instead. It is a functional change to a tag value the ticket states
  literally, and it is the requirements owner's call, not Design's.
- **Registering all eight `DocumentIngestionPhase` values on the phase counter** (DD-43185 ADR-004's
  future-proofing argument). Rejected here — see ADR-009 — because the argument does not transfer:
  DD-43185's zero series guarded against an *undetected stall*, whereas a transition counter for a
  phase no code can write is not a missed-failure risk, and the series appears automatically on the
  first real increment if the phase model is ever repaired.

### Consequences

- **Positive:** every rendered name matches the ticket character for character, and the naming rule
  is now complete for all three meter types CDKS uses. The next ticket adds meters without
  re-deriving anything.
- **Positive:** one constants class, one Javadoc mapping table, one place a rename happens.
- **Accepted:** DD-43185 ADR-001's downside deepens — grepping the codebase for
  `cdk_external_call_duration_seconds` finds only the integration test. Mitigated the same way, by
  the `CdkMeters` Javadoc table.
- **Accepted:** the `success`/`succeeded` split ships as ticketed unless the gate says otherwise.
- **Reversibility:** poor once alert rules exist in another repository, exactly as DD-43185
  ADR-001 recorded. Settle all eight names and all tag-value spellings at this gate.

---

## ADR-002: Measure ingestion duration as `Timer.record(Duration)` computed from two persisted timestamps — `case_documents.created_at` to the terminal `ingestion_phase_at` — never a `Timer.Sample`

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Resolves:** OQ-004 (blocking)
- **Artefacts:** `01-requirements.md` (FR-003, FR-004, AC-005 – AC-007, OQ-004) · `02-design.md` (§4)

### Context

The ticket's scenario is "Given a document enters phase `UPLOADING`, when it reaches `INGESTED` or
`FAILED`, then record the elapsed time". Stage 1 found the start trigger does not exist. Design
re-verified every write of `CaseDocument.ingestionPhase` in `src/main/java` — there are exactly
three:

| Site | Phase written | Timestamp written |
|---|---|---|
| `services/IdpcAvailabilityService.persistCaseDocument(...)` ~line 117 | `WAITING_FOR_UPLOAD` | `created_at` set explicitly to `utcNow()` (line 116); `ingestion_phase_at` left at the `CaseDocument` field initialiser `utcNow()` — the same instant |
| `jobmanager/caseflow/RetrieveMaterialAndUploadTask.saveDocumentUploaded(...)` ~line 235 | `UPLOADED` | `ingestion_phase_at = utcNow()` |
| `jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTask.updateIngestionPhase(...)` ~line 241 | `INGESTED` / `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT` | `ingestion_phase_at = utcNow()` |

`UPLOADING` is only the Java field initialiser (`CaseDocument` line 73) and the `V1001` column
default, both overwritten before insert. So the timer as specified can never start.

Two further verified facts constrain the mechanism:

- **`case_documents.created_at` is written once and never mutated.** Design grepped every
  `setCreatedAt` in `src/main/java`: `IdpcAvailabilityService:116` (this entity),
  `JobManagerService:88` (a different entity, `ScheduledIngestionRequest`), and three
  answer-response/DTO mappers. Nothing re-stamps a `CaseDocument`. It is the only durable start
  anchor; `ingestion_phase_at` is overwritten on every transition, so by the time the terminal
  write happens it holds the *`UPLOADED`* instant, not the start.
- **Start and end are in different processes.** The `WAITING_FOR_UPLOAD` write happens either on an
  HTTP request thread (`/ingestions/start-by-case` → `IngestionProcessorByCaseService` →
  `IdpcAvailabilityService`, inline) or inside `CheckIdpcAvailabilityAllDefendantsTask`; the
  terminal write happens inside `CheckIngestionStatusForAllDefendantsTask`, minutes to hours later,
  after up to 50 retries at 5 s (`CDK_JOBMANAGER_RETRY_VERIFY_DOC_MAX_ATTEMPTS:50`), on whichever
  pod won the job. **An in-process `Timer.Sample` cannot span that.** There is no shared in-memory
  state, no affinity, and a pod restart would lose every open sample.

### Decision

**`cdk.document.ingestion.duration` is a `Timer` whose observations are recorded with
`Timer.record(Duration)` from a duration computed at the terminal transition out of two persisted
timestamps. No `Timer.Sample`, no `start()`/`stop()`, no in-memory pending state, no new column, no
migration.**

1. **The interval measured is `created_at` → the terminal `ingestion_phase_at`.** Stated plainly so
   nobody reads more into the metric than it carries: this is *from the moment CDKS first learned a
   new IDPC document existed and persisted a row for it, to the moment CDKS learned RAG's terminal
   answer for it.* It spans the Progression material lookup, the blob copy, the RAG upload
   initiation, and the whole RAG ingestion poll loop, plus all JobManager queue-and-retry latency
   in between. It is the closest available thing to FR-003's "end to end", and it is genuinely
   end-to-end from CDKS's point of view.
2. **Three terminal stops, not two** (OQ-004(b)): `INGESTED`, `FAILED` **and**
   `EXCEEDED_FILE_SIZE_LIMIT`. All three are written by the same `updateIngestionPhase(...)` call
   site, `EXCEEDED_FILE_SIZE_LIMIT` is a real terminal phase the scenario merely omits, and
   excluding it would silently drop the observations for oversized documents — a population whose
   latency profile is *different* and therefore worth having. Tagged `phase` with the terminal
   phase, so the three are separable.
3. **One observation per document, at the single terminal write site.** Recorded immediately after
   the `saveAndFlush(doc)` inside `updateIngestionPhase(...)`, from the same loaded entity, so the
   start anchor is read from the row that was just written (AC-007). A document in a non-terminal
   phase contributes nothing.
4. **Duration is computed as `Duration.between(doc.getCreatedAt(), terminalAt)` and clamped at
   zero.** Both timestamps are wall-clock `OffsetDateTime`s from `TimeUtils.utcNow()`, written by
   potentially different pods, so clock skew can make the computed value negative. A negative
   `Timer.record(...)` would corrupt `_sum`. The value is clamped to `Duration.ZERO` and a
   throttled WARN is emitted (ADR-010). This is the one place in this ticket that cannot use a
   monotonic clock; every other timer does (ADR-003).
5. **The metric is documented as success-biased and complementary to DD-43185's stall gauge**
   (OQ-004(d)). A document that never reaches a terminal phase contributes **no observation ever** —
   so this timer's `_count` is *completed* ingestions, not *started* ones, and it structurally
   cannot detect the stall case. The detector for that is `cdk_documents_stalled{phase="UPLOADED"}`
   from DD-43185 (ADR-004 there added exactly that phase for exactly this failure). The two must be
   read together, and the `CdkMeters` Javadoc says so.
6. **The phase-population defect is not repaired here.** DD-43185's follow-up (populate or remove
   `UPLOADING`/`INGESTING`) stands. If it is ever done, this ADR's start anchor does not change —
   `created_at` remains correct — so the fix and this metric are independent.

### Alternatives considered

- **`Timer.Sample` started at the `WAITING_FOR_UPLOAD` write, stopped at the terminal write.**
  Rejected on the cross-process finding above. It is not merely awkward; it is unimplementable
  without a new persistence mechanism, and it would silently under-report on every pod restart.
- **A `DistributionSummary` recorded from a computed `Duration`** (Stage-1's suggested shape).
  Considered carefully and rejected in favour of `Timer.record(Duration)`, which is the same idea
  with three concrete advantages and no disadvantage: (i) the Prometheus convention appends
  `_seconds` automatically, so the ticket's `cdk_document_ingestion_duration_seconds` falls out
  rather than needing `baseUnit("seconds")` — see ADR-001; (ii) SLO boundaries are declared as
  `Duration`s, both in code (`Timer.Builder.serviceLevelObjectives(Duration...)`) and in
  configuration (`management.metrics.distribution.slo.<name>: 30s,1m,…`), whereas a
  `DistributionSummary` needs raw doubles whose unit is a comment; (iii) the exposition shape is
  identical, so nothing is gained by the substitution. **`Timer.record(Duration)` is not a
  `Timer.Sample`** — the concern behind the suggestion is fully addressed by (1) above, which
  forbids samples explicitly.
- **`ingestion_phase_at` as the start anchor.** Rejected as the *primary* interval: at the terminal
  write it holds the `UPLOADED` instant, so it measures the RAG-ingestion leg only, not end-to-end.
  Recorded as a **follow-up worth having**: because that value is in the same loaded entity, a
  second timer (`cdk.document.rag.ingestion.duration`, tagged `phase`) would split "our leg" from
  "RAG's leg" for free, which is the first question any incident asks. Not added here because it is
  a meter the ticket does not request (scope creep), and it would add ~36 series.
- **A new `ingestion_started_at` column.** Rejected: `created_at` already is that column, and the
  ticket's out-of-scope list forbids a Flyway migration.
- **Recording an observation for documents that time out, from a scheduled sweep** (so the timer is
  not success-biased). Rejected: it invents a terminal event that the domain does not have, it
  would need the ShedLock machinery DD-43185 built for a different purpose, and DD-43185's stall
  gauge already answers the question correctly. Documented as complementary instead.

### Consequences

- **Positive:** works across pods, tasks, restarts and retries with zero new state, because both
  endpoints of the interval are already durably persisted. Nothing to leak, nothing to lose.
- **Positive:** the interval is the widest genuinely-available one, and `EXCEEDED_FILE_SIZE_LIMIT`
  being a third stop means no completed ingestion is invisible.
- **Accepted:** the interval includes JobManager queueing and retry delay, so the number is not
  "RAG's latency" and must not be read as an upstream SLO. Stated in the Javadoc and in §4.
- **Accepted:** the timer cannot see stalls. This is a real hole in the ticket's implied coverage
  and the reason DD-43185's gauge is named as its complement rather than left implicit.
- **Accepted:** clock skew between pods is absorbed by a clamp, not corrected. Sub-second skew on a
  minutes-to-hours interval is noise; a clamp firing at all is itself a signal, hence the WARN.
- **Reversibility:** total. One method call at one site, plus the meter registration.

---

## ADR-003: Derive `outcome` by walking the exception cause chain; leave `RagClientException` untouched; add a fifth `outcome=error`; instrument each outbound call site explicitly

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Resolves:** OQ-005 (blocking), OQ-013 (mechanism), OQ-014
- **Artefacts:** `01-requirements.md` (FR-005, FR-006, FR-007, NFR-006, AC-008 – AC-012, AC-014, OQ-005, OQ-013, OQ-014) · `02-design.md` (§5)

### Context

**OQ-005.** The ticket says a `RagClientException` is recorded as `outcome=server_error`, in the
same scenario that enumerates `client_error` and `timeout`. Design re-verified all four RAG client
classes (`ApimDocumentIngestionClient`, `ApimDocumentIngestionStatusClient`,
`RagAnswerServiceImpl`, `RagAnswerAsyncServiceImpl`): each method has the identical two-catch
shape —

```java
} catch (final HttpStatusCodeException exception) {          // 4xx AND 5xx
    ...
    throw new RagClientException(message, exception);
} catch (final Exception exception) {                        // timeouts, parse failures, anything
    ...
    throw new RagClientException(message, exception);
}
```

So `RagClientException` is genuinely unclassifiable *by type*. Blanket-mapping it to `server_error`
makes `client_error` and `timeout` permanently unreachable for `dependency=rag`, which is four of
the eleven operations and the only dependency whose latency anyone is worried about.

**The finding that unblocks it:** `RagClientException` is a two-argument
`RuntimeException(message, cause)` and **both throw sites pass the original exception as the
cause**. The information the ticket wants is therefore already present at the metrics-recording
call site — it just is not in the exception's *type*. Nothing needs to change in the exception
hierarchy, its constructors, its message, or its public contract.

Hearing and Progression need no such treatment: `HearingClientImpl` and `ProgressionClientImpl`
have **no try/catch at all** — `HttpStatusCodeException` and `ResourceAccessException` propagate
raw from `RestClient`.

**OQ-014.** `AzureBlobStorageService.copyFromUrl(...)` is a different shape again, and one detail
matters: on poll timeout it throws `new IllegalStateException(message)` — **with no cause**. The
`TimeoutException` that identifies it as a timeout is discarded at that line. So a classifier
outside the method sees an `IllegalStateException` with an empty cause chain and cannot tell a
timeout from an aborted copy. Inside the method, the check
`runtimeException.getCause() instanceof TimeoutException` already exists.

**OQ-013.** Three mechanisms were on the table. Two are eliminated by facts, not preference:

- **A fourth `ClientHttpRequestInterceptor`** cannot produce the `operation` tag.
  `ProgressionClientImpl.getCourtDocuments(...)` and
  `ProgressionClientImpl.getCourtDocumentsForAllDefendants(...)` build the **same URI** from the
  same `courtDocsPath` with the same `caseId` query parameter — they are indistinguishable at the
  HTTP layer. It also cannot see Azure Blob at all, and `PATH_DOCUMENT_STATUS_BY_REFERENCE` /
  `PATH_ANSWER_USER_QUERY_STATUS` carry `{...}` segments whose *expanded* form is a RAG document
  reference and a RAG transaction id — precisely the NFR-001 leak the requirements forbid.
- **Spring Boot's `http.client.requests`** is inactive today because
  `RestClientFactory.build(...)` calls the static `RestClient.builder()` rather than injecting the
  auto-configured `RestClient.Builder`, so no `ObservationRegistry` is attached. Adopting it would
  change the construction path for every client (NFR-005 risk), tag on `uri` (the same leak), and
  still give no `operation` and no Azure coverage. See ADR-008.

### Decision

**1 — Classify `outcome` by walking the exception cause chain, to a bounded depth, at the
recording call site.** One `OutcomeClassifier`, used by every dependency:

| Observed, anywhere in the cause chain (depth ≤ 5, cycle-guarded) | `outcome` |
|---|---|
| no exception, call returned | `success` |
| `org.springframework.web.client.HttpStatusCodeException` with a 4xx status | `client_error` |
| `HttpStatusCodeException` with a 5xx status | `server_error` |
| `com.azure.core.exception.HttpResponseException` with a 4xx / 5xx response status | `client_error` / `server_error` |
| `java.net.SocketTimeoutException`, `org.apache.hc.client5.http.ConnectTimeoutException`, `org.apache.hc.core5.concurrent.CancellableDependency`-style `ConnectionRequestTimeoutException`, `java.util.concurrent.TimeoutException` | `timeout` |
| anything else (JSON parse failure, `IllegalStateException` from an aborted blob copy, `NullPointerException` in mapping) | `error` |

This reaches through `RagClientException` (cause = `HttpStatusCodeException` or the raw failure)
and through Spring's `ResourceAccessException` (cause = `SocketTimeoutException` for a read
timeout, `ConnectTimeoutException` for a connect timeout) with one implementation. It applies
uniformly to `dependency=rag` including the two classes the ticket does not name
(`ApimDocumentIngestionStatusClient`, `RagAnswerServiceImpl`) — OQ-005's second question,
answered yes.

**2 — `RagClientException` is not modified in any way, and is never swallowed.** No new subclass,
no status field, no new constructor, no change to the `@ExceptionHandler(RagClientException.class)`
handlers in `RagAnswerServiceImpl` / `RagAnswerAsyncServiceImpl`. The recording helper's contract
is `record(...)` → observe, then rethrow *the same instance*. FR-006 and AC-009 are satisfied
structurally: the exception object is never reconstructed, so type, message, cause and stack trace
are identical by construction, not by inspection.

**3 — `outcome` has five values, not four. GATE.** `error` is added for "no HTTP status and not a
timeout". It is genuinely reachable — the RAG clients' bare `catch (Exception)` covers JSON parse
failures, and `AzureBlobStorageService` throws a status-less `IllegalStateException` when a copy
reports `ABORTED`/`FAILED`. The alternative is to fold these into `server_error`, which would make
`server_error` mean "5xx, or a bug in our own mapping code, or a failed blob copy" — a tag value
that lies. AC-011 and AC-016 say `outcome` takes "exactly one of" the ticket's four, so **this
needs the requirements owner's accept or reject.** Cost if accepted: at most 11 extra series
(§2). Cost if rejected: `server_error` becomes an unreliable alerting signal for `dependency=rag`.

**4 — Instrument explicitly at each of the eleven production call sites**, through one helper
`metrics/ExternalCallMetrics` with two entry points:

- `<T> T record(String dependency, String operation, ThrowingSupplier<T> call)` — times with
  `System.nanoTime()` (monotonic; wall-clock is used only where ADR-002 has no choice), records on
  both the return and the throw path, classifies per (1), and rethrows unchanged.
- `void recordOutcome(String dependency, String operation, long elapsedNanos, String outcome)` —
  explicit outcome, used by **exactly one** call site: `AzureBlobStorageService.copyFromUrl(...)`,
  where the `TimeoutException` cause is still in scope inside the existing try/catch and the
  outcome is therefore known precisely. This is OQ-014's answer: for `dependency=azure_blob`,
  `outcome=timeout` means "the copy poll exceeded `cp.cdk.storage.copy-timeout-seconds` (default
  120 s, **not** 180 s)"; `success` means the poller returned a non-`ABORTED`/`FAILED` status; an
  aborted or failed copy is `error`; and a 4xx/5xx from the Azure SDK's own HTTP stack is
  classified by (1) from `HttpResponseException`. `client_error`/`server_error` are therefore
  reachable but rare for this dependency, and the pool gauge (ADR-008) covers none of its traffic.

**5 — `AzureBlobStorageService`'s discarded cause is flagged, not fixed.** Changing
`throw new IllegalStateException(message)` to `throw new IllegalStateException(message, runtimeException)`
would let the generic classifier handle Azure too and would improve every stack trace in the
service. It is one word. It is nevertheless **out of scope**: FR-006 forbids this ticket from
altering any client's exception contract, and a cause is part of that contract. Recorded as a
separate one-line tidy-up ticket; the `recordOutcome` entry point makes it unnecessary either way.

### Alternatives considered

- **Add a `status`/`kind` field or subclasses to `RagClientException`** (OQ-005's option (a) read
  literally). Rejected: it is a public-contract change to an exception two `@RestController`
  classes already have `@ExceptionHandler`s for, it would touch ten throw sites across four
  classes, and it buys nothing the cause chain does not already provide. The cheapest correct
  change is no change.
- **Accept a narrower `outcome` set for `dependency=rag` only** (OQ-005's option (b)) — e.g.
  `{success, error}` for RAG and the full set elsewhere. Rejected: a tag whose domain varies by
  another tag's value is hostile to every alert rule and dashboard that will consume it, and it
  concedes a limitation that does not actually exist once the cause chain is read.
- **A `ClientHttpRequestInterceptor`** — rejected on the `getCourtDocuments` /
  `getCourtDocumentsForAllDefendants` collision, no Azure coverage, and the path-variable leak.
  Recorded because it is the option a reviewer will ask about: it is genuinely the cheapest
  mechanism and it is wrong for concrete, checkable reasons, not aesthetic ones.
- **Spring Boot's `http.client.requests`** — rejected; see ADR-008.
- **A Micrometer `@Timed` annotation plus `TimedAspect`.** Rejected: `@Timed` cannot express an
  outcome tag derived from an exception's cause, its `exception` tag is the exception's simple class
  name (which for RAG is always `RagClientException` — the exact information loss this ADR exists
  to avoid), and it still needs an aspect and a `TimedAspect` bean.
- **`MicrometerHttpRequestExecutor` / `ObservationExecChainHandler`** from
  `micrometer-core`'s `httpcomponents.hc5` package (already on the classpath). Rejected for the
  same reasons as the interceptor — URI-level tags, no `operation`, no Azure — and it would require
  changing the `HttpClients.custom()` build path in `RestClientFactoryConfig`, which OQ-015 shows
  is already fragile.

### Consequences

- **Positive:** `client_error`, `server_error` and `timeout` are all reachable for every
  dependency including RAG, which is what the ticket actually wanted, with **zero** change to the
  exception hierarchy.
- **Positive:** `operation` is exact everywhere, including the two Progression methods that share a
  URI and the one Azure operation that is not HTTP at all.
- **Positive:** monotonic timing for the ten in-process calls means the numbers survive NTP steps.
- **Accepted:** eleven call sites in seven classes are edited, and four of them are the RAG clients
  NFR-006 protects. Mitigated by the helper being a pure pass-through (`return call.get();` — the
  returned object is never inspected, copied or mapped) **and** by NFR-006's mandated
  response-field-parity test, which §12 makes a merge blocker rather than a nice-to-have.
- **Accepted:** `RagClientsConfig`'s four `@Bean` methods, `HearingClientImpl`,
  `ProgressionClientImpl` and `AzureBlobStorageService` each gain one constructor parameter, so
  their unit tests need a construction-site edit. Precedent: DD-43185 ADR-006 accepted exactly this
  for the two schedulers.
- **Accepted:** if the gate rejects the fifth `outcome` value, `error` folds into `server_error`
  and the `CdkMeters` Javadoc must state that `server_error` includes non-HTTP failures. One
  constant and one mapping line.
- **Reversibility:** good. The helper is one class; removing it is eleven one-line reverts.

---

## ADR-004: `operation` tag values are CDKS-invented lowercase kebab-case constants, one per production call site — never a method name, a URI, or an OpenAPI path

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Resolves:** OQ-006
- **Artefacts:** `01-requirements.md` (FR-002, FR-005, NFR-001, AC-011, OQ-006) · `02-design.md` (§5)

### Context

The ticket does not define `operation`. Stage 1 inventoried eleven live operations and raised two
hard constraints: the value must never interpolate a path variable (`PATH_DOCUMENT_STATUS_BY_REFERENCE`
and `PATH_ANSWER_USER_QUERY_STATUS` expand to a RAG document reference and a RAG transaction id —
NFR-001), and the three dead `StorageService` methods (`exists`, `getBlobSize`) need a ruling.

DD-43185 ADR-006 already settled the general form of this question for the `scheduler` tag and gave
two reasons that transfer directly: a class name was rejected because a routine rename would
silently rename a production series, and a property placeholder was rejected because an environment
override would silently rename it. A Java **method** name is in exactly the first category —
`answerUserQueryAsync` is a name an IDE will happily refactor with no reviewer thinking twice.

### Decision

**Eleven `public static final String` constants in `CdkMeters`, lowercase kebab-case, one per
production call site, structurally incapable of carrying request data.**

| `dependency` | `operation` | Class · method | Effective read timeout |
|---|---|---|---|
| `rag` | `initiate-document-upload` | `ApimDocumentIngestionClient.initiateDocumentUpload` | 180 s |
| `rag` | `document-status-by-reference` | `ApimDocumentIngestionStatusClient.documentStatusByReference` | 180 s |
| `rag` | `answer-user-query-async` | `RagAnswerAsyncServiceImpl.answerUserQueryAsync` | 180 s |
| `rag` | `answer-user-query-status` | `RagAnswerAsyncServiceImpl.answerUserQueryStatus` | 180 s |
| `rag` | `answer-user-query` | `RagAnswerServiceImpl.answerUserQuery` | 180 s |
| `progression` | `get-court-documents` | `ProgressionClientImpl.getCourtDocuments` | 15 s |
| `progression` | `get-court-documents-all-defendants` | `ProgressionClientImpl.getCourtDocumentsForAllDefendants` | 15 s |
| `progression` | `get-material-download-url` | `ProgressionClientImpl.getMaterialDownloadUrl` | 15 s |
| `hearing` | `get-hearings-and-cases` | `HearingClientImpl.getHearingsAndCases` | 15 s |
| `hearing` | `get-hearing-cases-for-day` | `HearingClientImpl.getHearingCasesForDay` | 15 s |
| `azure_blob` | `copy-from-url` | `AzureBlobStorageService.copyFromUrl` | 120 s poll timeout (not HTTP) |

1. **The constant is passed as a literal argument at the call site.** It is not derived at runtime
   from the method, the class, the URI, the `PATH_*` template, or anything reachable from request
   or job data. There is no code path by which a path variable could become a tag value — which is
   how AC-011 and NFR-001 are satisfied structurally rather than by review vigilance.
2. **`dependency` is functionally determined by `operation`.** The pair is therefore 11
   combinations, not 4 × 11. Both tags are still emitted, because `sum by (dependency)` is the
   first query anyone writes.
3. **`exists` and `getBlobSize` get no `operation` value and are not instrumented.** They have no
   production call site (verified: `StorageService` declares three methods, only `copyFromUrl` is
   called from `src/main/java`). Instrumenting dead code creates a permanently-zero series that
   implies a call path exists. Note this is the *opposite* ruling to DD-43185 ADR-004's
   "keep the unreachable phases" — deliberately, and for a different reason: there, a missing
   series risked an undetected stall; here, a present series would assert a call path that does not
   exist. If either method acquires a caller, adding its constant is a two-line change.
4. **Kebab-case, matching DD-43185 ADR-001's rule for CDKS-invented values** — and deliberately
   *decoupled* from the Java method name, so a method rename cannot silently rename a series.
   A comment on each constant names its call site so the two stay findable.

### Alternatives considered

- **The Java method name verbatim (`answerUserQueryAsync`).** Rejected on DD-43185 ADR-006's
  rename argument, plus mixed case in a Prometheus label value being awkward for alert authors.
  Defensible under ADR-001's "mirror the source-of-truth token" clause — a method name is arguably
  such a token — which is why it is recorded rather than dismissed.
- **The OpenAPI `operationId`.** Rejected: for the four RAG clients it is the method name (they
  implement generated interfaces), so it inherits the rename risk while adding a dependency on
  `api-cp-ai-rag`'s spec; and Hearing, Progression and Azure Blob have no `operationId` at all, so
  it cannot be the rule for all eleven.
- **A templated path (`/document-upload/{documentReference}`).** Rejected twice over: it would need
  runtime matching of an actual URI against a template set (fragile, and one bug away from emitting
  the expanded form), and it still cannot separate the two Progression methods, which share a path.
- **Omitting `operation` entirely and tagging only `dependency`.** This is OQ-007's cardinality
  escape hatch and it is not needed — §2 shows the budget holds comfortably. Rejected because
  "RAG is slow" without knowing *which* RAG call is slow is barely more useful than a log grep,
  and the async answer path (sub-second `answerUserQueryAsync`, then N × `answerUserQueryStatus`)
  would be averaged into meaninglessness.

### Consequences

- **Positive:** eleven fixed values, compile-time, greppable, and provably free of case data.
- **Positive:** the timer separates the two same-URI Progression calls and the non-HTTP Azure call,
  which no HTTP-layer mechanism can.
- **Accepted:** the tag value and the method name can drift apart. Mitigated by the per-constant
  comment and by the fact that drift is harmless (the series keeps its meaning) whereas the
  alternative's failure mode is a silently renamed series.
- **Reversibility:** poor once alert rules exist, same as every tag value. Settle at this gate.

---

## ADR-005: Bound histogram cardinality explicitly — SLO buckets on the ingestion timer only, `percentiles-histogram` off, no client-side percentiles, budget computed at 243 series worst case

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Resolves:** OQ-007 (blocking), OQ-016
- **Artefacts:** `01-requirements.md` (FR-004, NFR-002, NFR-003, NFR-009, AC-006, AC-024, OQ-007, OQ-016) · `02-design.md` (§2, §6, §12)

### Context

Stage 1's fear was well founded in mechanism and wrong in magnitude, and the difference matters
enough to state precisely.

`management.metrics.distribution.*` is **not configured at all** today, and CDKS has no `Timer`, so
DD-43182 makes every histogram decision for the first time. The failure mode Stage 1 named is real:
enabling `percentiles-histogram` on a Micrometer `Timer` makes it publish its **default** bucket
set, which for the Prometheus registry is a wide fixed ladder (tens of `_bucket` series per tag
combination unless clamped with `minimum-expected-value`/`maximum-expected-value`). Applied to
FR-005's 55 tag combinations that alone is thousands of series, and it would breach NFR-002 in the
same ticket that states it.

But FR-004 asks for histogram buckets on **one** meter — `cdk_document_ingestion_duration_seconds`,
"such that p50, p95 and p99 are queryable in Prometheus". FR-005 asks for no percentiles at all. The
budget problem dissolves once those two are not conflated.

Series arithmetic on this classpath, verified against `PrometheusMeterRegistry`:

- a `Timer` with **no** histogram → `_seconds_count`, `_seconds_sum`, `_seconds_max` = **3 series**;
- a `Timer` with **N** service-level objectives → N+1 `_seconds_bucket` series (the SLOs plus
  `le="+Inf"`), plus `_seconds_count`, `_seconds_sum`, `_seconds_max` = **N+4 series**.

### Decision

**1 — `percentiles-histogram` is explicitly `false` for the `cdk` namespace, and client-side
`percentiles` are never configured.** FR-004 requires server-side, aggregatable percentiles from
`_bucket` series; Micrometer's `percentiles` property pre-computes per-pod percentiles which
`histogram_quantile` cannot combine, so configuring it would satisfy the letter of "p99 is
available" while making the number wrong across pods. Both are stated in configuration rather than
left at their defaults, so a future global change is a visible diff.

**2 — Buckets on `cdk.document.ingestion.duration` only, as eight explicit SLO boundaries:**
`15s, 30s, 1m, 2m, 5m, 10m, 30m, 1h` → nine `_bucket` series + count + sum + max = **12 series per
`phase` value**, 3 phase values = **36 series**. The ladder is chosen against the observed shape of
the pipeline, not by taste: the blob copy is bounded at 120 s
(`cp.cdk.storage.copy-timeout-seconds`), the RAG poll at 50 × 5 s = 250 s
(`CDK_JOBMANAGER_RETRY_VERIFY_DOC_*`), so a healthy end-to-end ingestion lands between roughly
30 s and 5 minutes — which is where five of the eight boundaries sit, giving `histogram_quantile`
real resolution at p50 and p95 — while `30m` and `1h` keep p99 meaningful when JobManager queueing
stretches the tail.

**3 — SLO boundaries are declared in code, with the Boot property as an override lever.**
`Timer.builder(...).serviceLevelObjectives(Duration...)` from `CdkMeters` constants, **and**
`management.metrics.distribution.slo.cdk.document.ingestion.duration` documented as the runtime
override. Boot's `PropertiesMeterFilter` only applies a distribution setting when the corresponding
property is present, and merges otherwise — so the code default survives when nothing is
configured, and an operator can retune buckets without a rebuild (NFR-009). Declaring them in code
also removes a real risk: `management.metrics.distribution.*` is a Boot property path this
repository has never exercised on Spring Boot 4.0.5, and if it turned out inert the buckets would
silently not appear. AC-006's integration test asserts the `_bucket` series are actually on
`/actuator/prometheus`, which catches that either way.

**4 — `cdk.external.call.duration` publishes count, sum and max only — no buckets.** FR-005 does
not ask for percentiles. `rate(_seconds_sum) / rate(_seconds_count)` gives mean latency per
`dependency`/`operation`/`outcome`, `rate(_seconds_count)` gives call rate, and the `outcome`
dimension gives the error rate — which is the whole of what FR-005, FR-006 and FR-007 ask for.
`_seconds_max` covers "how bad did it get" without a single bucket.
**GATE:** if a p99 for RAG latency is later wanted, the additive change is SLO boundaries on this
timer, at N+1 extra series per combination — with 55 combinations and a 5-boundary ladder that is
+330 series, which the budget below still absorbs. Deliberately not done now.

**5 — The budget is computed, not assumed** (NFR-002, DD-43185 NFR-002's precedent):

| Meter | Type | Tag combinations | Series each | Registered at first scrape | Worst case |
|---|---|---|---|---|---|
| `cdk.document.ingestion.phase` | Counter | `phase`(5) × `source`(`IDPC`) | 1 | 5 | 5 |
| — latent `source="unknown"` (ADR-009) | Counter | ≤ 5 | 1 | 0 | ≤ 5 |
| `cdk.document.ingestion.duration` | Timer + 8 SLOs | `phase`(3) | 12 | 36 | 36 |
| `cdk.external.call.duration` | Timer, no buckets | 11 `(dependency,operation)` × `outcome`(5) | 3 | 33 (`success` only) | 165 |
| `httpcomponents.httpclient.pool.*` (ADR-008) | 4 Gauges | — | — | 5 | 5 |
| `cdk.http.pool.connections.leased` (ADR-008) | Gauge | — | 1 | 1 | 1 |
| `cdk.answer.generation` | Counter | `outcome`(3) × `query_level`(4) | 1 | 12 | 12 |
| `cdk.task.retry` | Counter | `task_name`(7) ⟨`retry_policy` determined⟩ | 1 | 7 | 7 |
| `cdk.task.retry.exhausted` | Counter | `task_name`(7) ⟨`retry_policy` determined⟩ | 1 | 7 | 7 |
| **DD-43182 total** | | | | **106** | **243** |

Plus DD-43185's 14 = **257 `cdk_*`/pool series worst case.** The `service`/`cluster`/`region`
common tags apply on top and add no series.

**6 — The 2,000-series budget is met, and how it is enforced (OQ-016).** The budget counts the
**whole endpoint**, framework series included, as the ticket's "per pod" implies. DD-43185 captured
`baseline-actuator-prometheus.md` — 76 metric families — but **no series count**, so no baseline
number exists. Design's deliverables:
   - a companion `baseline-series-count.md`, captured the same way as DD-43185's families baseline,
     recording the measured pre-DD-43182 series count on the compose stack;
   - a **merge-blocking** `integrationTest` assertion that the total series count on
     `/actuator/prometheus` is below a stated ceiling in the compose stack. Set the compose ceiling
     **tighter than 2,000** and say why: `http_server_requests_seconds_*` grows with distinct
     `uri` × `status` × `method` × `outcome` combinations, and the compose suite exercises fewer of
     them than production, so the compose number is a *lower* bound on production. Design proposes
     **1,200** once the real baseline is known, leaving explicit headroom, and — per DD-43185's
     `ADR-004`-style honesty — records that the exact ceiling must be set from the measured
     baseline, not guessed at Stage 2;
   - **GATE:** re-scope AC-024's "under 1 second" to a CI smoke bound (Design proposes 2 s in the
     compose stack) plus a one-off production timing captured in `deploy-notes.md`. A hard
     sub-second assertion on shared CI hardware is a flaky test, not a guarantee — the same
     argument DD-43185 §12 used for its 500 ms EXPLAIN bound.

**7 — Nothing is computed on scrape** (NFR-003). Every counter and timer is written on the
business path; the pool gauges read `ConnPoolControl.getTotalStats()`, an in-memory struct. No
database query, no remote call, no lock is touched by a scrape.

### Alternatives considered

- **`percentiles-histogram: true` on both timers** (the ticket's implied reading of FR-004).
  Rejected on the arithmetic: at 55 combinations on the external-call timer, the default ladder puts
  that one meter into the low thousands and breaks NFR-002 by itself. This is the option Stage 1
  correctly flagged.
- **`percentiles-histogram: true` clamped with `minimum-expected-value`/`maximum-expected-value`.**
  Considered — clamping does bound the ladder — and rejected as less predictable than SLOs: the
  resulting bucket count is an implementation detail of Micrometer's ladder generation, so the
  series budget would be a number nobody in this repository can state exactly. Explicit SLOs make
  the count arithmetic (N+4) and reviewable.
- **Client-side `percentiles: [0.5, 0.95, 0.99]`.** Rejected: three extra series per combination
  *and* the numbers cannot be aggregated across pods, which is precisely what FR-004 forbids.
  Cheaper and wrong.
- **Dropping or coarsening the `operation` tag** (OQ-007's suggestion). Rejected — see ADR-004 —
  because the budget does not require it. Kept on the record as the lever to pull first if a future
  ticket does add buckets to the external-call timer.
- **Raising the 2,000 budget with SRE** (OQ-007's suggestion). Not needed. Recorded so the gate
  knows Design did not quietly rely on it.
- **A single global histogram policy for all `cdk` timers.** Rejected: the two timers answer
  different questions on different time scales (a 15 s–1 h pipeline versus a sub-second-to-180 s
  HTTP call). One ladder would be wrong for both.

### Consequences

- **Positive:** NFR-002 is met with a computed number and roughly 8× headroom on the `cdk_*` side,
  rather than an assumption. OQ-007 is closed as "not breached", with the arithmetic shown.
- **Positive:** the histogram exists exactly where FR-004 asks and nowhere else, so no series is
  paid for a percentile nobody requested.
- **Positive:** buckets are code-owned (robust to a Boot property path change) *and*
  environment-tunable (NFR-009).
- **Accepted:** RAG p99 is not available after this ticket — only mean, rate and max. A deliberate,
  reversible, additive gap, flagged at the gate.
- **Accepted:** the compose-stack series ceiling is not production's. Stated in the test's own
  assertion message so nobody later mistakes one for the other.
- **Accepted:** the exact numeric ceiling cannot be fixed until the baseline is measured, which is
  a Story-8 deliverable. The *arithmetic* for DD-43182's own contribution (243) is fixed now.
- **Reversibility:** excellent. Bucket ladders and the ceiling are one constant and one assertion.

---

## ADR-006: Compute retry grants and retry exhaustion inside CDKS by replicating `TaskExecutor.canRetry`'s predicate from `ExecutionInfo.getRetryAttemptsRemaining()`, applied by one AOP aspect around `ExecutableTask.execute`

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Resolves:** OQ-008 (blocking), OQ-009, OQ-010 (blocking)
- **Artefacts:** `01-requirements.md` (FR-010, FR-011, FR-012, NFR-004, AC-018 – AC-021, OQ-008, OQ-009, OQ-010) · `02-design.md` (§7)

### Context

Stage 1 concluded that `cdk_task_retry_exhausted_total` "is not obtainable from CDKS" and that
`task-manager-service` 1.0.11 "exposes no event, callback or extension point at the point of
exhaustion". **The first half of that is wrong, and the evidence is decisive.** Design decompiled
the same jar
(`~/.gradle/caches/modules-2/files-2.1/uk.gov.hmcts.cp/task-manager-service/1.0.11/…`) and read the
bytecode of `TaskExecutor`, `TaskExecutor$1`, `ExecutionInfo`, `ExecutionService`, `TaskRegistry`
and `JobsRepository`.

Stage 1 is right that there is no hook. It missed that **the entire retry predicate is computable
from inputs the task already receives.**

1. `TaskExecutor$1.doInTransactionWithoutResult()` builds the `ExecutionInfo` handed to the task
   with `ExecutionInfo.executionInfo().fromJob(this.job).build()`. `ExecutionInfo` has a
   **public** `getRetryAttemptsRemaining()`, populated from the `Job` row.
2. `TaskExecutor.canRetry(ExecutableTask task, ExecutionInfo info)` returns
   `info.isShouldRetry() && Objects.nonNull(job.getRetryAttemptsRemaining()) && job.getRetryAttemptsRemaining() > 0 && task.getRetryDurationsInSecs().isPresent()`.
   `job.getRetryAttemptsRemaining()` is **the same value** the task was handed in (1) — the field is
   not mutated between `fromJob(...)` and `canRetry(...)` within one execution.
3. `task.getRetryDurationsInSecs()` is the task's **own** `ExecutableTask` override. The task knows
   it by definition.
4. `ExecutionService.executeWith(...)` seeds a new job's `retryAttemptsRemaining` from
   `taskRegistry.findRetryAttemptsRemainingFor(taskName)`, which is
   `getRetryDurationsInSecs().map(List::size).orElse(null)`. `TaskExecutor.performRetry(...)` then
   decrements it by one on each granted retry via `JobService.updateNextTaskRetryDetails(jobId, …, remaining - 1)`.
5. **Exhaustion is terminal and happens exactly once.** When `canRetry` is false and the status is
   `INPROGRESS`, `TaskExecutor` calls
   `updateNextTaskDetails(jobId, taskName, unchanged-start-time, 0)` then `releaseJob(jobId)` — it
   does **not** delete the job. And `JobsRepository`'s assignment query is
   `… WHERE worker_id IS NULL AND (retry_attempts_remaining IS NULL OR retry_attempts_remaining > 0) AND assigned_task_start_time <= :currentTime …`.
   With `retry_attempts_remaining = 0` the row is **never selected again**. So an exhausted job is
   abandoned exactly once and leaves a permanently orphaned `jobs` row — which is precisely the
   "work is being silently abandoned" event FR-012 wants a counter for, and it fires once, not in a
   loop.

Therefore, at the moment a CDKS task decides to return `INPROGRESS` + `shouldRetry=true`, it can
compute *with certainty* whether the library will grant the retry. `CheckIngestionStatusForAllDefendantsTask`
already does something of this kind (`final Integer latestRetryCount = executionInfo.getRetryAttemptsRemaining();`
line 75, then `if (latestRetryCount == LAST_RETRY_COUNT) { updateIngestionPhase(documentId, FAILED); }`
line 214) — so the mechanism is not novel to this codebase, it is already load-bearing in it.

**OQ-010's two sub-findings, both confirmed:**

- (a) `GenerateAnswerForQueryTask` does **not** override `getRetryDurationsInSecs()`, so
  `findRetryAttemptsRemainingFor("GENERATE_ANSWER_FOR_QUERY")` returns `null`, `canRetry` is false
  on the `isPresent()` clause, and `GENERATE_ANSWER_FOR_QUERY` **can never be retried** despite
  returning `shouldRetry=true` on every failure. A naive counter would report retries that do not
  happen.
- (b) A task that **throws** never reaches its own `retry(...)` helper:
  `TaskExecutor.executeTask` catches `Exception`, logs
  `"Error executing the task: {}; error message: {}; setting task executionStatus to INPROGRESS"`,
  and synthesises `INPROGRESS` with
  `shouldRetry = Objects.nonNull(job.getRetryAttemptsRemaining()) && > 0` — **outside CDKS code**.
  This path is live: `CheckAllDocumentsIngestionStatusTask.execute` is **unguarded** — it has no
  try/catch, and `UUID.fromString(v.toString().replace("\"",""))` on malformed job data or a
  throwing `documentIdResolver.findIngestionStatusForAllDocs(...)` propagates straight out. So a
  CDKS-side increment placed only in the seven `retry(...)` helpers would under-report.

**OQ-009** is confirmed: the retry budgets are keyed by config key, not task, and the mapping is
many-to-one. Design also found a related documentation trap worth recording: `application-cdk.yml`
declares the first budget under the key `cdk.jobmanager.retry.default`, but
`JobManagerRetryProperties` exposes `setDefaultRetry(...)`, i.e. the bindable name
`default-retry`. **The `default:` block therefore does not bind**, and `defaultRetry` keeps its Java
field defaults of 3 attempts / 20 s — which happen to be the identical values the YAML states, so
there is **no behavioural difference today**, but `CDK_JOBMANAGER_RETRY_DEFAULT_MAX_ATTEMPTS` and
`CDK_JOBMANAGER_RETRY_DEFAULT_DELAY_SECONDS` are **inert environment variables**. Flagged as a
separate defect; FR-012's documentation must state the *effective* numbers, not the YAML's.

### Decision

**1 — One shared predicate, `metrics/TaskRetryDecision.willBeRetried(ExecutionInfo, ExecutableTask)`,
that replicates `canRetry` exactly:**

```
shouldRetry(from the ExecutionInfo about to be returned, or true-by-TaskExecutor on the throw path)
  && info.getRetryAttemptsRemaining() != null
  && info.getRetryAttemptsRemaining() > 0
  && task.getRetryDurationsInSecs().isPresent()
```

A unit test asserts the predicate against the decompiled `canRetry` semantics, including the
`GENERATE_ANSWER_FOR_QUERY` case (empty `Optional` ⇒ false) and the `null`-remaining case. **If
`task-manager-service` ever changes `canRetry`, this predicate drifts** — that is the single risk
this decision carries, and it is mitigated in (5).

**2 — `cdk.task.retry` counts retries that will actually be *granted*; `cdk.task.retry.exhausted`
counts the one moment a task's budget runs out.** This closes OQ-010's "requested versus granted"
question in favour of **granted**, because granted is what happened and requested is a number about
CDKS's intentions. The two counters partition the `INPROGRESS` outcome exactly: every
`INPROGRESS`-returning execution increments exactly one of them.

**3 — Applied by a single Spring AOP aspect, `metrics/TaskRetryMetricsAspect`, around
`ExecutableTask.execute(..)` on `uk.gov.hmcts.cp.cdk.jobmanager..*`.** Not seven explicit call
sites. Three reasons, in order of weight:
   - it is the **only** mechanism that covers OQ-010(b)'s throw path, which is live in
     `CheckAllDocumentsIngestionStatusTask` and latent in every unguarded line of the other six;
   - it touches **zero** lines of task business logic, which is the strongest possible NFR-004 /
     NFR-005 position for a counter added to seven production task paths;
   - `task_name` is read from the target class's `@Task` annotation via
     `AopUtils.getTargetClass(...)` — a compile-time constant — and membership-checked against the
     seven `TaskNames` values before use, so AC-019 ("no other value can be emitted") is structural.

   Verified safe against the one thing that would break it: introducing the first `@Aspect` turns on
   auto-proxying, and the task beans become CGLIB proxies. **`TaskRegistry.autoRegisterTasks()`
   already calls `org.springframework.aop.support.AopUtils.getTargetClass(bean)` before
   `.getAnnotation(Task.class)`** (confirmed in the bytecode), so registration is already
   proxy-aware. `spring-aop` 7.0.7 and `aspectjweaver` 1.9.25.1 are already on the runtime classpath
   transitively (via `spring-aspects`, from `spring-boot-starter-data-jpa`), and
   `AopAutoConfiguration` ships in `spring-boot-autoconfigure` 4.0.6 — so **no new dependency.**
   The aspect declares `throws Throwable`, catches only `Exception` (`errorprone.AvoidCatchingThrowable`
   is enabled in `.github/pmd-ruleset.xml`; `design.AvoidCatchingGenericException` is not, so
   `catch (Exception)` passes PMD — same finding DD-43185 §5 recorded), and rethrows the identical
   instance. `Error`s propagate unrecorded, deliberately.

**4 — A second tag, `retry_policy`, at zero series cost. GATE.** It is functionally determined by
`task_name`, so it adds a label to the existing 7 series and no new series. It answers FR-012's
documentation requirement in the metric itself instead of in a table a reader has to find:

   | `task_name` | `retry_policy` | Effective budget | Config source |
   |---|---|---|---|
   | `GET_CASES_FOR_HEARING` | `default-retry` | 3 × 20 s | `JobManagerRetryProperties.defaultRetry` field defaults — the YAML `default:` key does not bind |
   | `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS` | `default-retry` | 3 × 20 s | as above |
   | `RETRIEVE_MATERIAL_AND_UPLOAD` | `default-retry` | 3 × 20 s | as above |
   | `CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS` | `verify-document-status` | 50 × 5 s | `CDK_JOBMANAGER_RETRY_VERIFY_DOC_*` |
   | `CHECK_ALL_DOCUMENTS_INGESTION_STATUS` | `verify-document-status` | 50 × 5 s | `CDK_JOBMANAGER_RETRY_VERIFY_DOC_*` |
   | `CHECK_STATUS_OF_ANSWER_GENERATION` | `questions-retry` | 100 × 10 s | `CDK_JOBMANAGER_RETRY_QUESTIONS_*` |
   | `GENERATE_ANSWER_FOR_QUERY` | `none` | **cannot be retried** | no `getRetryDurationsInSecs()` override |

   This is a tag the ticket does not specify, so the gate must accept or reject it. Rejecting it
   costs nothing but moves the table entirely into documentation.

**5 — The library-drift risk is bounded by a test, not a comment.** An `integrationTest` against
the compose stack drives a task to genuine exhaustion (`CDK_JOBMANAGER_RETRY_*` overridden to a
budget of 1–2 in compose) and asserts that `cdk_task_retry_exhausted_total` incremented **and**
that the corresponding `jobs` row has `retry_attempts_remaining = 0` and is not re-executed. That
ties the CDKS-side prediction to the library's actual behaviour, so a `task-manager-service` bump
that changes `canRetry` fails CI instead of silently making the counter wrong.

**6 — `GENERATE_ANSWER_FOR_QUERY`'s missing override is documented, not fixed** (per the ticket's
out-of-scope list). The consequence is stated plainly and is a *feature* of this design: the task
will emit `cdk_task_retry_exhausted_total{task_name="GENERATE_ANSWER_FOR_QUERY", retry_policy="none"}`
on every failure and **never** `cdk_task_retry_total`, which is exactly what is happening. The
metric surfaces the defect rather than papering over it. FR-012's in-repo documentation
(`CdkMeters` Javadoc — OQ-017(b)) says so.

### Alternatives considered

- **Raise a `task-manager-service` change to publish an exhaustion event** (OQ-008 option (a)).
  Rejected as unnecessary once (1) was found — it would add an external dependency with its own
  lead time to a ticket that can be delivered in-repo today. Still worth doing on its own merits
  (a first-class event is better than a replicated predicate), and recorded as a follow-up.
- **Poll `jobs` for `retry_attempts_remaining = 0`, ShedLock-guarded, DD-43185-style**
  (OQ-008 option (b)). Rejected as the primary mechanism: it reads another component's schema, it
  yields a *gauge of stranded rows* rather than FR-011's counter, and it inherits DD-43185 ADR-008's
  whole per-pod-staleness apparatus. Kept as a **strong follow-up recommendation** in its own
  right, because the query
  `SELECT count(*) FROM jobs WHERE retry_attempts_remaining = 0` measures something this ticket does
  **not**: the accumulated backlog of permanently abandoned jobs, including any abandoned before
  DD-43182 shipped. Counter and gauge answer different questions.
- **Count in CDKS job data, `CTX_ANSWER_RETRY_COUNT`-style** (OQ-008 option (c)). Rejected: as
  Stage 1 said, it covers one task of seven, and it needs a job-data schema change.
- **Seven explicit call sites in the tasks' `retry(...)` helpers, no aspect.** The fallback if the
  gate rejects AOP. **State the cost honestly: it silently under-reports both counters by every
  throw-path execution**, which is a live path in `CheckAllDocumentsIngestionStatusTask` and latent
  everywhere else. It is also seven edits to production task code instead of zero.
- **Counting *requested* retries** (`INPROGRESS && shouldRetry`, no predicate). Rejected: it would
  report retries for `GENERATE_ANSWER_FOR_QUERY` that provably never occur, and `cdk_task_retry_total`
  would then be an inflated number nobody could reconcile with observed behaviour — the exact class
  of "permanently misleading series" this ticket's requirements stage was written to prevent.
- **A second tag naming the retry policy *value* (`max_attempts="50"`)** instead of the policy key.
  Rejected: a numeric label value that changes when configuration changes creates a new series on
  every retune, and orphans the old one.

### Consequences

- **Positive:** OQ-008 is closed **in-repo**, exactly, with no library change, no cross-schema
  read, and no new failure mode. FR-011 and AC-020 become testable.
- **Positive:** the throw path is covered, so `cdk_task_retry_total` is not systematically low.
- **Positive:** zero lines of task business logic change — the strongest available NFR-005 story
  for Area D.
- **Positive:** `retry_policy` makes the metric self-describing at no cardinality cost.
- **Accepted:** CDKS now holds a *replica* of a library predicate. Mitigated by (5)'s
  behaviour-anchored integration test, and by a Javadoc pointer to the decompiled `canRetry`. This
  is the one genuine liability in this ADR and it should be read as such at the gate.
- **Accepted:** the first `@Aspect` in this codebase, so the seven task beans become CGLIB proxies.
  De-risked by the verified `AopUtils.getTargetClass` call in `TaskRegistry`, by
  `cdk.metrics.enabled=false` removing the aspect bean entirely (ADR-010), and by the existing
  caseflow/queryflow live tests, which exercise all seven tasks and must stay green unmodified.
- **Accepted:** `Error`s thrown by a task are not counted. Deliberate, consistent with
  DD-43185 §5's `catch (Exception)` ruling.
- **Reversibility:** excellent. Delete one aspect class; or set `CP_CDK_METRICS_ENABLED=false`,
  which also removes the proxying, with a restart and no deployment.

---

## ADR-007: `cdk_answer_generation_total` counts answer-generation transactions that ended — redefining `timed_out` as "abandoned while still `ANSWER_GENERATION_PENDING`", and adding `query_level="unknown"`

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Resolves:** OQ-011 (blocking); depends on ADR-006
- **Artefacts:** `01-requirements.md` (FR-009, AC-015 – AC-017, OQ-011, OQ-021) · `02-design.md` (§8)

### Context

Stage 1 found three mismatches in one scenario. All three are confirmed, and one of them turns out
to be solvable rather than merely acknowledgeable.

**(a) `timed_out` has no code path.** `CheckStatusOfAnswerGenerationTask` sees only
`ANSWER_GENERATED`, `ANSWER_GENERATION_FAILED` and `ANSWER_GENERATION_PENDING` (the last, along with
a null/non-2xx response, returns `retry(executionInfo)` at line 85). Nothing anywhere detects or
records a timeout.

**(b) `failed` is not terminal.** On `ANSWER_GENERATION_FAILED` (line 149) the task re-dispatches
`GENERATE_ANSWER_FOR_QUERY` while `CTX_ANSWER_RETRY_COUNT < questionsRetry.maxAttempts` (100), and
only logs `log.warn("Max retries reached for caseId={}, queryId={}, ragTransactionId={}", …)` when
the budget is spent. Counting on every `ANSWER_GENERATION_FAILED` would let one transaction
contribute up to 100 increments.

**(c) `query_level` can be null.** `TaskUtils.parseQueryLevel` returns `null` for missing or
invalid input, and the task has an explicit `case null, default:` branch that calls
`answerGenerationService.upsertAnswer(...)`. Micrometer rejects a null tag value outright.

Design adds three findings of its own:

**(d) There *is* a real timeout event — ADR-006 makes it observable.** The `ANSWER_GENERATION_PENDING`
path returns `INPROGRESS` + `shouldRetry=true` against the `questions-retry` budget (100 attempts
× 10 s ≈ 17 minutes of polling). When that budget runs out, the job is abandoned **while the answer
is still pending** and the `jobs` row is stranded with `retry_attempts_remaining = 0`, never
re-selected. That is a genuine "we gave up waiting" event, it is user-visible as a permanently
empty AI Search result, and ADR-006's `TaskRetryDecision.willBeRetried(...)` predicate detects it
in-task with certainty. `timed_out` is therefore **reachable**, not fictional — it just is not where
the ticket looked.

**(e) `GenerateAnswerForQueryTask` has two silent-abandonment paths the ticket does not mention.**
`return completed(executionInfo)` on missing identifiers (line 65) and on
`QueryDefinitionLatest` not found (line 83). Both end the transaction with no answer and no retry,
and both are currently invisible.

**(f) `GENERATE_ANSWER_FOR_QUERY`'s exception path is terminal in practice.** Per ADR-006/OQ-010(a)
it returns `INPROGRESS` + `shouldRetry=true` but can never be retried, so a RAG-start failure ends
the transaction there.

### Decision

**`cdk.answer.generation` counts answer-generation transactions that reached an end state, exactly
once each.** A "transaction" is one (case, query, document) answer attempt, spanning
`GENERATE_ANSWER_FOR_QUERY` → N × `CHECK_STATUS_OF_ANSWER_GENERATION` → up to 100 re-dispatch
cycles. Six increment points, in two classes:

| Class · condition | `outcome` |
|---|---|
| `CheckStatusOfAnswerGenerationTask` · `ANSWER_GENERATED`, after the answer is upserted | `succeeded` |
| `CheckStatusOfAnswerGenerationTask` · `ANSWER_GENERATION_FAILED` **and** `retryCount >= maxRetries` (the existing `log.warn("Max retries reached…")` branch) | `failed` |
| `CheckStatusOfAnswerGenerationTask` · `ANSWER_GENERATION_PENDING` / null / non-2xx **and** `!willBeRetried(...)` | `timed_out` |
| `CheckStatusOfAnswerGenerationTask` · `catch (Exception)` **and** `!willBeRetried(...)` | `failed` |
| `GenerateAnswerForQueryTask` · missing identifiers, or `QueryDefinitionLatest` not found | `failed` |
| `GenerateAnswerForQueryTask` · RAG start threw **and** `!willBeRetried(...)` (always, per (f)) | `failed` |

1. **`timed_out` is redefined and is reachable:** "the `questions-retry` polling budget was spent
   while RAG still reported `ANSWER_GENERATION_PENDING`". The `catch` path exhausting is `failed`,
   not `timed_out` — a dependency error and a give-up-waiting are different incidents and the tag
   should not conflate them. **GATE:** this is a redefinition of a ticket-specified tag value.
2. **`ANSWER_GENERATION_FAILED` increments only when the re-dispatch budget is spent** (OQ-011(b)),
   so one transaction contributes at most one increment. The condition is the existing `else`
   branch — no new logic, just a counter next to a `log.warn` that already marks the event.
3. **`query_level="unknown"`** for the null case (OQ-011(c)) — a `CdkMeters` constant, lowercase per
   DD-43185 ADR-001's rule for CDKS-invented values, deliberately distinguishable from the three
   verbatim enum constants. The increment is **never omitted**: omitting it would make the
   counter's total stop equalling the number of transactions that ended, which is the single
   property that makes the metric interpretable.
4. **`GenerateAnswerForQueryTask`'s abandonment paths are counted. GATE.** The ticket names only
   `CheckStatusOfAnswerGenerationTask`'s states. Including (e) and (f) is what makes
   `sum(cdk_answer_generation_total)` equal "transactions that ended" — and therefore what makes
   `succeeded / (succeeded + failed + timed_out)` a true success rate. Excluding them leaves an
   unbounded, invisible leak between "transactions started" and "transactions accounted for".
5. **Exactly-once is structural, not incidental** (AC-015). Verified by tracing all paths:
   `GenerateAnswerForQueryTask`'s success dispatches `CHECK_STATUS_OF_ANSWER_GENERATION` and returns
   `COMPLETED` **without** incrementing; `CheckStatusOfAnswerGenerationTask`'s `ANSWER_GENERATION_FAILED`
   re-dispatch increments nothing and hands the transaction back to `GenerateAnswerForQueryTask`.
   Every path through the pair ends at exactly one of the six rows above.
6. **One behaviour-neutral code move:** `levelStr`/`level` parsing (currently lines 94–95, inside
   the post-status region) is hoisted to the top of `CheckStatusOfAnswerGenerationTask.execute`, so
   the `timed_out` and `catch`-path increments have a `query_level` to tag. No logic changes; the
   parse has no side effects and cannot throw (`parseQueryLevel` returns `null` on bad input).
7. **OQ-021 confirmed: no answer-generation *duration* is added.** The ticket does not ask for one,
   and — unlike ADR-002's ingestion duration — it is **not** cheap: there is no persisted
   answer-generation start timestamp to compute from, so it would need either a new column or a new
   job-data field. Recorded as a follow-up with that cost attached, so the next ticket does not
   assume ADR-002's pattern transfers for free.

### Alternatives considered

- **Drop `timed_out` entirely** and ship `{succeeded, failed}`. The honest option before (d) was
  found. Rejected: the event is real, user-visible, currently undetectable, and now cheaply
  detectable. Dropping the tag value would also silently change a ticket-specified enumeration.
- **`timed_out` = "the `questions-retry` re-dispatch budget of 100 was spent"** (Stage 1's
  suggested reading). Rejected: that is the `ANSWER_GENERATION_FAILED` path, where RAG gave a
  definite negative answer 100 times. That is a failure, not a timeout. Mapping it to `timed_out`
  would leave the actual give-up-waiting event with no value at all.
- **Omit the increment when `query_level` is null.** Rejected per (3): a metric that silently
  drops a population is worse than one with an `unknown` bucket, and the `case null, default:`
  branch is a real production path that persists an answer.
- **Count only `CheckStatusOfAnswerGenerationTask`, exactly as ticketed.** Rejected per (4), and
  raised as a **GATE** item rather than taken unilaterally — CLAUDE.md's "never invent
  requirements" rule applies, and widening what a counter counts changes what an alert fires on.
- **Increment on every `ANSWER_GENERATION_FAILED`.** Rejected: up to 100 increments per
  transaction makes the counter a poll counter with a misleading name.

### Consequences

- **Positive:** the counter's total equals the number of answer-generation transactions that ended,
  so success rate, failure rate and give-up rate are all directly derivable.
- **Positive:** `timed_out` ships as a reachable value rather than a permanently-zero series, and it
  detects a user-visible failure (empty AI Search) that nothing detects today.
- **Positive:** two previously-invisible abandonment paths in `GenerateAnswerForQueryTask` become
  visible.
- **Accepted:** `cdk_answer_generation_total` and `cdk_task_retry_exhausted_total{task_name="CHECK_STATUS_OF_ANSWER_GENERATION"}`
  will both fire on the same underlying event. Deliberate and correct — they answer different
  questions ("did this answer ever arrive?" versus "is a task giving up?"). Stated in the Javadoc so
  nobody treats one as a duplicate of the other.
- **Accepted:** `timed_out` depends on ADR-006's replicated predicate, so ADR-006's library-drift
  risk propagates here. The same integration test covers both.
- **Accepted:** twelve pre-registered series, three of which (`timed_out` × `query_level`) should
  stay at zero in a healthy service. Pre-registering them is the DD-43185 AC-009 rule: a zero
  series is a signal, a missing one is ambiguous.
- **Reversibility:** total for the increments; the tag-value *set* is the usual one-way door once
  alert rules exist.

---

## ADR-008: Publish pool visibility from Micrometer's `PoolingHttpClientConnectionManagerMetricsBinder` plus one `cdk_http_pool_connections_leased` alias; leave `http.client.requests` inactive; flag the shared-connection-manager mutation as a separate defect

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Resolves:** OQ-012, OQ-013(a), OQ-015
- **Artefacts:** `01-requirements.md` (FR-007, FR-008, AC-013, AC-014, OQ-012, OQ-013, OQ-015) · `02-design.md` (§6)

### Context

**OQ-012.** Design re-verified the classpath finding by decompiling
`io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder`
from `micrometer-core-1.16.5.jar`. It is a `MeterBinder` over a `ConnPoolControl<HttpRoute>` and its
`registerTotalMetrics(...)` registers exactly four meters / **five series**, tagged
`httpclient=<name>` from the constructor's `String name`:

| Micrometer name | Prometheus name | Series |
|---|---|---|
| `httpcomponents.httpclient.pool.total.max` | `httpcomponents_httpclient_pool_total_max` | 1 |
| `httpcomponents.httpclient.pool.total.connections` | `httpcomponents_httpclient_pool_total_connections{state="available"\|"leased"}` | 2 |
| `httpcomponents.httpclient.pool.total.pending` | `httpcomponents_httpclient_pool_total_pending` | 1 |
| `httpcomponents.httpclient.pool.route.max.default` | `httpcomponents_httpclient_pool_route_max_default` | 1 |

That is precisely AC-013's requirement — leased **plus** its ceiling **plus** pending — for a
one-line `@Bean`. `RestClientFactoryConfig.httpClientConnectionManager()` is a single shared
`@Bean` (`setMaxConnTotal(200)`, `setMaxConnPerRoute(50)`, `setConnectionManagerShared(true)`), so
one binder covers all Apache-HttpClient traffic (AC-014). No `httpcomponents_*` series exist today.

The tension is naming: the ticket asks for `cdk_http_pool_connections_leased`, and DD-43185 ADR-001
made `cdk_` the house prefix — but only the `httpcomponents_` family carries the *ceiling*, without
which "leased" cannot express "exhausted".

**OQ-013(a).** Confirmed: `RestClientFactory.build(...)` calls the **static**
`RestClient.builder()`, not the auto-configured `RestClient.Builder` bean, so no
`ObservationRegistry` is attached and Spring Boot's `http.client.requests` is not active.

**OQ-015.** Confirmed, with one correction that matters for this ticket. `RestClientFactory.build(...)`
calls `connectionManager.setDefaultConnectionConfig(...)` on the **shared** connection-manager bean
on every invocation, so the effective **connect** timeout is whichever client was built last —
"last build wins". But the **response/read** timeout is set per client, on each client's own
`RequestConfig` (`RestClientFactoryConfig` lines 94–97), so the 180 s RAG and 15 s CQRS read
timeouts are correct and unaffected. And the connect timeouts do not actually differ:
`application-clients.yml` sets `CP_CDK_RAG_CONNECTION_TIMEOUT_MS:3000` and
`CP_CDK_CQRS_CONNECTION_TIMEOUT_MS:3000` — **both 3000 ms**, so the defect is currently **latent
and has no behavioural effect.**

### Decision

**1 — Register the framework binder as the source of truth.** One `@Bean
PoolingHttpClientConnectionManagerMetricsBinder` over the existing shared connection-manager bean,
named `cdk` (so the tag is `httpclient="cdk"`). Boot's `MeterRegistryPostProcessor` binds any
`MeterBinder` bean automatically. **Five series, zero new dependency, no reimplementation.**

**2 — Add one thin alias gauge, `cdk.http.pool.connections.leased`. GATE.** It reads
`connectionManager.getTotalStats().getLeased()` from the same in-memory struct, so the two names
cannot disagree. Cost: exactly one series. Reason: the ticket names
`cdk_http_pool_connections_leased` specifically, and DD-43185 ADR-001 recorded that renaming a
metric after alert rules exist elsewhere is a coordinated cross-repository change — so shipping the
ticketed name costs one series and removes that risk entirely.
   **The gate's actual choice, stated plainly:** *either* SRE accepts the `httpcomponents_*`
   family (in which case drop the alias, −1 series, and this becomes the cleanest option) *or* the
   alias ships. Design's recommendation is to **ship the alias** and let SRE drop it later, because
   the asymmetric cost is obvious: an unused series is free, a missing series is a silent alert.

**3 — Alert on the ratio, not the alias.** Documented in `CdkMeters`' Javadoc and handed to OQ-019's
owner: the alias alone cannot express exhaustion, so the recommended expression is
   ```promql
   max by (service, cluster) (httpcomponents_httpclient_pool_total_connections{state="leased"})
     / on (service, cluster)
   max by (service, cluster) (httpcomponents_httpclient_pool_total_max) > 0.8
   ```
   with `httpcomponents_httpclient_pool_total_pending > 0` as the sharper "already queueing" signal.

**4 — `http.client.requests` is deliberately left inactive.** Switching `RestClientFactory` to the
auto-configured `RestClient.Builder` is **out of scope**: it changes the construction path for
every client (NFR-005), its `uri` tag would carry expanded path variables — RAG document references
and transaction ids — straight into a label value (NFR-001), and it still would not produce an
`operation` tag or cover Azure Blob. `cdk.external.call.duration` (ADR-003, ADR-004) supersedes it.
Recorded so it is a decision rather than an omission.

**5 — Azure Blob is explicitly out of the pool gauge's scope** (AC-014, OQ-014).
`AzureBlobStorageService` uses the Azure SDK's own HTTP stack, not the shared
`PoolingHttpClientConnectionManager`. Stated in the Javadoc, because a reader will otherwise assume
"all outbound traffic".

**6 — The shared-connection-manager mutation is flagged, not fixed** (OQ-015, and already on the
ticket's out-of-scope list). Design confirms it should be a **separate defect ticket**, and records
the two facts that make it non-urgent-but-real: it is latent today (both connect timeouts are
3000 ms), and it is the *connect* timeout only. **The timer design is immune either way**, and this
is the load-bearing point: `outcome` is derived from the observed exception (ADR-003), never from a
configured timeout value — so if the connect timeouts ever diverge and last-build-wins starts
biting, `cdk_external_call_duration_seconds{outcome="timeout"}` reports what actually happened, at
whatever latency it actually happened. The metric will *reveal* the defect rather than be corrupted
by it. FR-007's documentation states the **per-dependency effective** timeouts (RAG 3 s connect /
180 s read; Hearing and Progression 3 s / 15 s; Azure Blob 120 s poll) and warns against any alert
or dashboard annotation that assumes a uniform 180 s.

### Alternatives considered

- **Hand-roll five `cdk.http.pool.*` gauges** over `getTotalStats()`. Genuinely attractive for
  namespace consistency, and it is only ~20 lines. Rejected: it reimplements a maintained binder,
  and it invents five more names for the gate to settle when four already have stable upstream
  ones. Recorded as the fallback if the gate insists on a pure `cdk_` surface.
- **Binder only, no alias.** The cleanest option and the one to prefer *if* SRE confirms the
  `httpcomponents_*` names are acceptable — see (2). Rejected as the default because it ships a
  name the ticket does not ask for and omits the one it does.
- **Alias only, no binder.** Rejected outright: a leased count with no ceiling cannot express
  "exhausted", which is FR-008's entire purpose and AC-013's explicit requirement.
- **Also register the binder's per-route metrics.** `PoolingHttpClientConnectionManagerMetricsBinder`'s
  route-level variant tags by `HttpRoute`, i.e. by target host. Rejected: it is a per-route
  cardinality source for a pool that serves three fixed hosts, the aggregate is what "exhausted"
  means for a shared 200-connection pool, and the total-level metrics already carry
  `route.max.default`.
- **Fixing OQ-015 here.** Rejected — it is on the ticket's out-of-scope list, and (6) shows the
  metric is unaffected. Fixing it correctly means giving each client its own connection manager or
  moving the connect timeout onto the per-client `RequestConfig`, which is a change to the HTTP
  construction path and needs its own test evidence.

### Consequences

- **Positive:** FR-008 and AC-013 are met with one `@Bean` plus one gauge — the smallest,
  lowest-risk change in the ticket, and the one that could ship first and alone.
- **Positive:** the ticketed name exists, so an alert rule written against either family resolves.
- **Positive:** OQ-015 is closed as *analysed, latent, separately ticketed*, with a reasoned
  argument that the metric is immune — not as "we noticed something".
- **Accepted:** two names for one number (6 series total). Documented as an alias, with the
  ratio-based expression pointing consumers at the family that carries the ceiling.
- **Accepted:** `dependency=azure_blob` has no pool visibility at all. Structural, documented.
- **Accepted:** `http.client.requests` stays absent, so there is no framework-standard client
  metric. Deliberate; the `uri`-tag leak makes adopting it a bad trade for this service.
- **Reversibility:** excellent. Both the binder and the alias are single-bean changes.

---

## ADR-009: `source` is a bounded allow-list checked against the entity value — presently single-valued `IDPC`; the trigger-origin dimension is deferred; only the five reachable phases are registered

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Resolves:** OQ-002, OQ-003
- **Artefacts:** `01-requirements.md` (FR-001, FR-002, NFR-001, AC-002 – AC-004, OQ-002, OQ-003) · `02-design.md` (§3)

### Context

**OQ-002.** Re-verified: `CaseDocument.source` is `@Column(name = "source", nullable = false)` with
the Java initialiser `private String source = "IDPC";`, backed by `V1001`'s
`TEXT NOT NULL DEFAULT 'IDPC'` with only a `CHECK (length(btrim(source)) > 0)` constraint. **No
production code calls `setSource`** — so it has exactly one value today, while being *unbounded by
type*. Reading it as-is into a tag would satisfy the ticket's wording and violate the ticket's own
cardinality rule (FR-002, NFR-001) the moment anyone writes anything else to that column.

Stage 1's most useful candidate — (b) the ingestion trigger origin, manual "Process IDPC" versus
scheduled discovery — is genuinely the dimension an operator wants. Design checked what it would
cost. The two entry points are distinguished today only by `JobPriority.HIGH` versus the
task-manager default, which is readable in a task via `ExecutionInfo.getPriority()`; but the
`WAITING_FOR_UPLOAD` write happens in `IdpcAvailabilityService.persistCaseDocument(...)`, which is
called **both** inline from `IngestionProcessorByCaseService` (manual, on the request thread) and
from `CheckIdpcAvailabilityAllDefendantsTask` (scheduled) and knows nothing about either. Threading
an origin flag to it means a new parameter through the shared
`RetrieveMaterialAndUploadJobDataService` path or a new job-data key — a change to ingestion
plumbing, in a ticket whose out-of-scope list says "only observability is added".

**OQ-003.** Re-verified and consistent with DD-43185 ADR-004: only `WAITING_FOR_UPLOAD`,
`UPLOADED`, `INGESTED`, `FAILED` and `EXCEEDED_FILE_SIZE_LIMIT` are ever persisted. `UPLOADING` is
the field initialiser and `V1001` column default, overwritten before insert; `INGESTING` appears
only in two test fixtures; `NOT_FOUND` is a response-only DTO value in `IngestionService`.

### Decision

**1 — `source` is read from the entity and membership-checked against a fixed allow-list.**
`CdkMeters.SOURCE_IDPC = "IDPC"` is the only member today; any other value maps to
`CdkMeters.SOURCE_UNKNOWN = "unknown"`. The tag is therefore **bounded by construction** — the
number of possible values is the allow-list's size plus one, regardless of what the column contains
(FR-002, NFR-001, AC-004) — while still tracking reality if the column ever gains a second real
value. Only `source="IDPC"` is pre-registered; `unknown` materialises only if actually emitted.

**2 — It ships single-valued, and the Javadoc says so.** `cdk_document_ingestion_phase_total` will
carry `source="IDPC"` on every series until something writes a different source. The tag is
informationally empty today. It is kept because the ticket specifies it and because it keeps the
series shape stable for when non-IDPC document sources arrive — at which point the metric follows
automatically with no code change.

**3 — The trigger-origin dimension is a documented follow-up, not a substitute.** The recommended
shape is an **additive** `trigger="manual"|"scheduled"` tag, not a redefinition of `source` — the
two mean different things and conflating them would make the metric unreadable later. Cost noted:
one new job-data key or one new parameter through `RetrieveMaterialAndUploadJobDataService`, plus
doubling this counter's series from 5 to 10.

**4 — Only the five reachable phases are pre-registered** (OQ-003). `UPLOADING`, `INGESTING` and
`NOT_FOUND` are excluded. **This is deliberately the opposite of DD-43185 ADR-004's ruling on the
stall gauge, and the reason the arguments do not transfer is worth stating:** there, a missing
series meant an *undetected stall* — a real failure hiding behind an absent metric, so a
permanently-zero series was cheap insurance. Here the meter is a *transition* counter, so a phase
no code can write is not a hidden failure; it is an event that cannot occur. And if the phase model
is ever repaired (DD-43185's recorded follow-up), the series appears on the first real increment
with no code change and no alert breakage. Three zero series avoided, no risk taken.

### Alternatives considered

- **`source` from the `case_documents.source` column, unchecked** (OQ-002 option (a)). Rejected:
  free-text by type, so it violates FR-002 and NFR-001 the first time anything writes to it.
- **`source` as the trigger origin** (option (b)). Rejected *for this ticket* on the plumbing cost
  in Context, and rejected *permanently as a meaning for `source`* because `source` already names a
  document-provenance concept in the schema; overloading it would make the eventual real second
  source value ambiguous.
- **`source` as the writing component** (option (c): `IdpcAvailabilityService` /
  `RetrieveMaterialAndUploadTask` / `CheckIngestionStatusForAllDefendantsTask`). Rejected: it is
  almost perfectly redundant with `phase` — each component writes a disjoint phase set — so it
  would add a dimension carrying no information, and it would couple a metric to class names
  (DD-43185 ADR-006's rename argument).
- **Dropping the `source` tag entirely.** Genuinely tempting, since it is informationally empty.
  Rejected: the ticket specifies it, dropping a specified tag is a functional change, and adding a
  tag later is *not* additive for a consumer — it changes every existing series' identity. Better
  to ship the shape now.
- **Registering all eight phases** (DD-43185 ADR-004's argument). Rejected per (4).

### Consequences

- **Positive:** the tag is provably bounded — five values, one source value, and one fallback —
  with no path from database or request content to a new label value.
- **Positive:** self-correcting. If the column ever gains a real second value, the metric follows.
- **Accepted:** `source` carries no information today. Stated in the Javadoc rather than left for a
  reader to discover from a dashboard.
- **Accepted:** the genuinely useful manual-versus-scheduled split is deferred. This is a real loss
  and should be raised as the immediate follow-up.
- **Reversibility:** total. One allow-list and one set of pre-registered combinations.

---

## ADR-010: Contain every metric failure at the call site with a shared `MetricsSafety` helper and a 60-second WARN throttle, behind one `cdk.metrics.enabled` kill switch

- **Status:** Accepted at Stage-2 gate (2026-09-03) · **Date:** 2026-09-03 · **Jira:** DD-43182 · **Resolves:** OQ-017, NFR-009
- **Artefacts:** `01-requirements.md` (FR-014, FR-015, NFR-004, NFR-009, AC-025 – AC-027, OQ-017) · `02-design.md` (§9, §10)

### Context

FR-014 requires that a metric failure never changes business behaviour; FR-015 requires a WARN at
most once per minute. OQ-017 found no log rate-limiting anywhere in this codebase —
`logback-spring.xml` is a `LogstashEncoder` `ConsoleAppender` behind an `AsyncAppender`, with no
`DuplicateMessageFilter` and no `TurboFilter` — and offered a third option: declare FR-015
satisfied by design, because Micrometer meter registration is idempotent and
`Counter.increment()` / `Timer.record()` do not throw in normal operation.

That third option is **half right, and the half it gets wrong is the important half.** With every
meter pre-registered at construction (DD-43185's precedent, carried forward), the recording call is
a map lookup plus an atomic add and cannot realistically throw. But DD-43182 does something DD-43185
did not: it **computes** tag values on the business path. The realistic failure modes are all in
that computation —

- `OutcomeClassifier` walking a cause chain (ADR-003) — a pathological or cyclic chain;
- `Duration.between(doc.getCreatedAt(), …)` when `created_at` is unexpectedly null (ADR-002);
- `AopUtils.getTargetClass(...).getAnnotation(Task.class)` returning null, or
  `getRetryDurationsInSecs()` itself throwing (ADR-006);
- `parseQueryLevel` and the `query_level` mapping (ADR-007);
- a registry `MeterFilter` denying a meter, so a lookup returns a no-op or null.

None of these may fail a JobManager task, alter an `ExecutionInfo`, change an HTTP response, or drop
a RAG response field. So FR-015 is genuinely about tag computation — OQ-017(a)'s closing suggestion,
confirmed — and the throttle belongs where that computation lives.

### Decision

**1 — One `metrics/MetricsSafety` helper, used by every recording site in this ticket.**
`runSafely(Runnable recording)` wraps the **whole** recording block — meter lookup, tag computation
and the `increment()`/`record(...)` call — in `catch (Exception)`. `Error`s propagate
(`errorprone.AvoidCatchingThrowable` is enabled in `.github/pmd-ruleset.xml`, and swallowing an
`OutOfMemoryError` behind a metric is the failure DD-43185 §5 already argued against). It returns
nothing and cannot throw.

**2 — The critical distinction, stated once because getting it wrong would be silent:**
`ExternalCallMetrics.record(dependency, operation, call)` wraps a **business** call. The safety net
applies **only to the recording**, never to `call.get()`. The business exception propagates
unchanged in type, message, cause and stack trace (ADR-003), and its propagation does not depend on
the recording succeeding. Structurally:

```
long t0 = System.nanoTime();
try {
    T result = call.get();                                  // business — never wrapped
    MetricsSafety.runSafely(() -> record(dep, op, t0, SUCCESS));
    return result;
} catch (final Exception e) {
    MetricsSafety.runSafely(() -> record(dep, op, t0, classify(e)));
    throw e;                                                // same instance
}
```

AC-025 and AC-027 are then testable with an injected throwing registry: the business outcome must be
byte-identical.

**3 — Throttled WARN, in code.** `MetricsSafety` holds a single `AtomicLong` of the last WARN's
epoch second and emits at most one WARN per 60 s across all sites, with a monotonically counted
suppressed total included in the line. The message names the metric area and carries the exception
object; it contains **no** case id, doc id, defendant id, material id, court reference,
`CJSCPPUID`, RAG transaction id, blob URI, document name or answer text (NFR-001, AC-026), and it
is emitted as structured JSON through the existing `logback-spring.xml`. No new appender, no
Logback filter, no `System.out`.
   A Logback `DuplicateMessageFilter`/`TurboFilter` was rejected: it is global, it would silently
   throttle unrelated logging across the service, and it is untestable at unit level.

**4 — One kill switch: `cdk.metrics.enabled`, default `true`,
`CP_CDK_METRICS_ENABLED`** — a new `config/MetricsProperties` (`@ConfigurationProperties("cdk.metrics")`),
in the style of DD-43185 ADR-002's `cdk.monitoring.*`. Per-area switches were rejected as four
knobs nobody will tune during an incident.
   - Default `true`, on DD-43185 ADR-002's reasoning: recording is in-process and side-effect-free,
     and defaulting it off would reproduce the failure this ticket exists to remove — a pod that
     looks healthy and publishes nothing. It also means the compose integration stack exercises the
     whole metric surface on every `gradle build`.
   - It gates **recording, never registration.** Every series exists from context refresh at value
     `0` whatever the flag says (DD-43185 ADR-002 point 6, AC-022).
   - **One deliberate exception:** `TaskRetryMetricsAspect` carries
     `@ConditionalOnProperty(name = "cdk.metrics.enabled", havingValue = "true", matchIfMissing = true)`,
     so with the flag off the aspect bean does not exist and the seven task beans are **not
     CGLIB-proxied at all**. That makes ADR-006's one structural risk reversible with a restart and
     no deployment, which is worth the small asymmetry. `cdk.task.retry` and
     `cdk.task.retry.exhausted` are still registered at `0` by `TaskRetryMetrics`, which is
     unconditional.

**5 — FR-012's documentation lives in `CdkMeters`' Javadoc** (OQ-017(b)), extending DD-43185's
precedent, and covers: `cdk_task_retry_exhausted_total` as the primary "work is being silently
abandoned" signal; the seven `task_name` values; the `retry_policy`/budget table from ADR-006 with
the *effective* numbers (including the inert `CDK_JOBMANAGER_RETRY_DEFAULT_*` finding); and the
statement that `GENERATE_ANSWER_FOR_QUERY` cannot be retried at all. Not a new `docs/` runbook page:
one file, next to the constants, that a Javadoc-reading engineer and a `CdkMeters`-grepping SRE both
find.

### Alternatives considered

- **Declare FR-015 satisfied by design and log nothing** (OQ-017(a) option 3). Rejected on the
  tag-computation analysis above: the throwing paths are real, and a metric that silently stops
  recording is exactly the failure class DD-43185's `cdk_monitoring_last_refresh_epoch_seconds` was
  invented to catch.
- **A Logback `DuplicateMessageFilter` or `TurboFilter`.** Rejected per (3) — global blast radius,
  and it would change logging behaviour for code this ticket does not touch.
- **One WARN per site per minute** instead of one WARN per minute globally. Rejected: with eleven
  external-call sites plus the aspect, a systemic registry failure would emit a dozen WARNs a minute
  — which is what FR-015 exists to prevent.
- **Per-area kill switches (`cdk.metrics.ingestion.enabled`, `…external-call.enabled`, …).**
  Rejected as four knobs for one blast radius. The aspect's `@ConditionalOnProperty` already gives
  the only per-area behaviour that matters, because it is the only one that changes bean topology.
- **No kill switch.** Rejected: an aspect wrapping every JobManager task execution needs an off
  switch that does not require a code change, on the same "gets rolled back rather than
  reconfigured during an incident" reasoning as DD-43185 ADR-002.
- **A separate `docs/` runbook page for FR-012.** Rejected: a second location to drift. The
  `CdkMeters` Javadoc is where DD-43185 put the name-mapping table and is where an engineer already
  looks.

### Consequences

- **Positive:** FR-014 / NFR-004 are structural, not aspirational — no recording site can throw
  into business code, and AC-027's throwing-registry test proves it.
- **Positive:** the business exception's propagation is independent of the recording, so NFR-006's
  RAG-field-parity guarantee does not depend on the metrics code working.
- **Positive:** one restartable switch reverses the whole ticket, including the aspect's proxying.
- **Accepted:** a global throttle can hide a second, unrelated metric failure occurring in the same
  minute. Mitigated by the suppressed-count in the WARN line, which is what tells an engineer to
  look wider.
- **Accepted:** `catch (Exception)` at every recording site is boilerplate. Centralised in one
  helper so it is one implementation and one test.
- **Accepted:** `Error`s are not contained. Deliberate and consistent with DD-43185.
- **Reversibility:** excellent. `CP_CDK_METRICS_ENABLED=false` needs a restart and no deployment;
  the series stay present at `0` and stop moving, which is honest.
