# Test Specs: Manual Discovery Scheduler Trigger Endpoint

> **Stage 4 — Test Specs** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43036** · Requirements: [`01-requirements.md`](./01-requirements.md) ·
> Design: [`02-design.md`](./02-design.md) · Stories: [`03-stories.md`](./03-stories.md) ·
> ADRs: [`adrs/DD-43036-manual-scheduler-trigger.md`](../adrs/DD-43036-manual-scheduler-trigger.md)
>
> Scenarios for `src/integrationTest/`, grouped by story. **Story 1 (DD-43060)** is contract-only
> (no runtime behaviour here — no scenarios).

---

## Story 2 — System-Users-only ACL rule for the trigger endpoint (DD-43061)

Covers **AC-008 – AC-011**, NFR-001 (AuthZ).

### Reference material

| Asset | Path | Use |
|---|---|---|
| ACL journey template | `.../http/DiscoverySchedulerConfigurationHttpLiveTest.java` (lines 100–108) | `extends AbstractHttpLiveTest`, vendor media type, `CJSCPPUID` header, `try/fail/catch HttpClientErrorException` idiom for 4xx |
| Both-persona pattern | `.../http/QueriesHttpLiveTest.java` (~line 138) | `@ParameterizedTest` over both personas — Story 2 inverts it: outcomes must now *differ* |
| Base infra | `testsupport/AbstractHttpLiveTest.java`, `util/UtilHttp.java`, `util/UtilConstants.java` | `baseUrl`, shared `RestTemplate` |
| Caller fixtures | `wiremock/mappings/user_group_query_api*.json` | Stubbed `usersgroups` identities |
| Rule template | `src/main/resources/acl/cdks-rules.drl` (lines 81–88), `"Allow LA – discovery-scheduler-configuration"` | Shape the new rule must match (AC-011) |
| Filter config | `application-other.yml` `authz.http` | `enabled/deny-when-no-rules/reload-on-each-request: true`; `/discovery-scheduler` not excluded → filter-protected |

**New IT class:** `http/DiscoverySchedulerTriggerAclHttpLiveTest.java` — ACL only. Functional trigger
scenarios belong to Story 3's `DiscoverySchedulerTriggerHttpLiveTest`.

**Caller fixtures — reuse only, do not add new stubs:**

| Persona | Constant | Identity | Covers |
|---|---|---|---|
| System User | `USER_WITH_SYSTEM_USERS_GROUPS` | group `"System Users"` | AC-008 |
| Permission-only | `USER_WITH_PERMISSIONS` | no groups, `AI search/View` permission | AC-009 |

> Names don't signal the split — use `@DisplayName` to state intent.

### Scenarios

**S2.1 — System User authorised (AC-008).** Given the new rule exists and caller is
`USER_WITH_SYSTEM_USERS_GROUPS`, when they `POST /discovery-scheduler/trigger` (vendor media type,
valid `discoveryOperation`), then response is not 401/403, and is `202 Accepted` with vendor
content type. Run for both `INTRADAY` and `NIGHTLY` (`@ParameterizedTest`). Assert authorisation
only — full body contract is Story 3's.

**S2.2 — Permission-only caller denied (AC-009, runtime half of AC-011).** Given caller is
`USER_WITH_PERMISSIONS` (no groups), when they `POST` the same request, then `403 Forbidden`
(`HttpClientErrorException.Forbidden`), and body has no `CJSCPPUID`/case/court/document data.
(Response `Content-Type` is not a useful discriminator here: `GlobalExceptionHandler` negotiates
it from the request's `Accept` header regardless of which layer produced the error, so a 403 can
still carry the trigger vendor type — empirically confirmed, don't assert on it.) This caller *is*
authorised on other endpoints with an
`or hasPermission` branch — a 403 here proves the new rule has no such fallback. Pair with S2.1 to
also catch a wrong Drools action name (which would 403 both personas).

**S2.3 — No `CJSCPPUID` header denied (AC-010).** Given a well-formed request with no `CJSCPPUID`
at all, when `POST`ed, then `401 Unauthorized`, and no call reaches the stubbed `usersgroups`
endpoint (verify via WireMock admin `findAll(getRequestedFor(...))` count before/after).
Also assert blank/whitespace `CJSCPPUID` → `401`.

> **Blocker:** `UtilHttp.newClient()` auto-injects `CJSCPPUID: USER_WITH_PERMISSIONS` when absent
> (`UtilHttp.java:19–26`), so the inherited client can't send a header-less request. Add
> `UtilHttp.newClientWithoutDefaultUser()` (same client, no interceptor) for this scenario only —
> don't touch the existing interceptor, ~a dozen ITs depend on it.

**S2.4 — Rule matches `discovery-scheduler-configuration` shape (AC-011, static half).**
Structural, not an IT. New unit test `controllers/accesscontrol/CdksAclRulesTest.java` (no such
class exists yet): given `classpath:/acl/cdks-rules.drl`, isolate the block whose action is
`casedocumentknowledge-service.discovery-scheduler-trigger`, then assert exactly one `eval(...)`
(`isMemberOfAnyOfTheSuppliedGroups($a, "System Users")`), no `hasPermission`, no `or`, and
structural parity with the existing LA block (lines 81–88). Behavioural corroboration = S2.1 + S2.2.
Complements, doesn't replace, the mandatory `rbac-auditor` review.

**S2.5 — Existing ACL rules unaffected (regression, NFR-001).** Given the new rule is appended to
the shared `.drl`, the existing suite (`DiscoverySchedulerConfigurationHttpLiveTest`,
`QueriesHttpLiveTest`/`IngestionProcessHttpLiveTest` permission paths) still passes unchanged. No
new test — a malformed append + `deny-when-no-rules: true` would silently 403 everything, so this
is a "must run" statement, not a new file.

### Coverage map

| AC | Scenario(s) | Layer |
|---|---|---|
| AC-008 | S2.1 (× `INTRADAY`, `NIGHTLY`) | Integration |
| AC-009 | S2.2 | Integration |
| AC-010 | S2.3 (+ blank-header variant) | Integration |
| AC-011 | S2.4 static + S2.1/S2.2 behavioural | Unit + Integration |
| NFR-001 | S2.1–S2.5 collectively | Integration |

### Constraints and open questions

1. **"Nothing dispatched" isn't directly assertable at IT level** — compose overrides both crons to
   `0/30 * * * * *`, so counting dispatches is confounded by concurrent scheduled runs. Proxy: a
   401/403 means the controller was never entered. True "no dispatch" assertion belongs at unit
   level in Story 3. Open: should a log-scraping test helper be added to assert absence of a
   `trigger=manual` record? Not built today — likely Story 4.
2. **These ITs can't run until Stories 1 and 3 land** — before then, every request 403s
   (`deny-when-no-rules`) regardless of persona, so S2.2/S2.3 would pass for the wrong reason. Write
   now (A-TDD), but treat green S2.2/S2.3 as meaningless until S2.1 is also green.
3. **`action-required: false` + wrong/missing vendor media type** — design says this should still
   deny, but `action-required: false` on its face doesn't reject for a non-derivable action; may
   still be caught by `deny-when-no-rules`. Not one of Story 2's ACs — needs empirical confirmation
   and an `rbac-auditor` flag before Story 3 writes its item-7 IT.
4. **Group aliasing** — `authz.http.group-aliases` doesn't cover `"System Users"`; it's matched
   literally, consistent with the existing rule. No new alias needed.
5. **No negative-authorisation IT exists in this repo today** — current suite only asserts
   OK/ACCEPTED/CONFLICT/NOT_FOUND. S2.2/S2.3 are the first 401/403 assertions; pick one idiom
   (`try/fail/catch` or no-op `ResponseErrorHandler`) and use it consistently.
6. **Test data** — synthetic WireMock fixtures and `UUID.randomUUID()` only; no real case/court/user
   identifiers.

---

## Story 3 — On-demand discovery trigger endpoint (fire-and-forget dispatch) (DD-43062)

Covers **AC-001, AC-002, AC-004–AC-007, AC-012–AC-017, AC-021–AC-025**. NFR-004/005/006/008/009/012.

**New IT class:** `http/DiscoverySchedulerTriggerHttpLiveTest.java` — functional/dispatch only;
ACL is Story 2's `DiscoverySchedulerTriggerAclHttpLiveTest`, not repeated here.

**Auditing (AC-023) and `correlationId` need no new plumbing:**
`cp-audit-filter-springboot` audits every OpenAPI-documented operation automatically (no
`AuditService` call in application code anywhere in this repo — confirmed by grep); `triggerDiscovery`
gets audited for free once it's a real operation. `RequestContextFilter` already puts the inbound
request's id into MDC key `correlationId` before the controller runs, so the controller can read
`MDC.get("correlationId")` directly for the response field — no interceptor/decorator needed for
this part (worker-thread MDC propagation, i.e. `MdcCopyingTaskDecorator`, is Story 4's job).

**AC-012/013/014 need no new exception-handling code either:** the generated
`DiscoveryTriggerRequest.discoveryOperation` is already `@NotNull @Valid` and `DiscoveryOperation`'s
`@JsonCreator fromValue(...)` already throws on an unrecognised string — both route through the
existing `GlobalExceptionHandler` (`onValidation` / `onUnreadable`) to `400`, same as every other
endpoint. Nothing to add beyond the controller method itself.

### Scenarios

**S3.1 — Authorised trigger dispatches and returns 202 (AC-001, AC-004–006, AC-017).** Given a
System User POSTs a valid `discoveryOperation` (`INTRADAY` or `NIGHTLY`), then `202 Accepted`,
vendor content type, body echoes `discoveryOperation` + `message` + a non-blank `correlationId`;
the response returns well before a `NIGHTLY` run could complete (assert wall-clock only, not
completion). `@ParameterizedTest` over both enum values.

**S3.2 — Non-blocking dispatch (design §Testing item 5).** Given the WireMock hearing stub carries
a `fixedDelay` (e.g. 5s), when triggered, then the 202 returns well under that delay — proves
dispatch runs off the request thread, not inline.

**S3.3 — Wrong HTTP method → 405 (AC-002).** Given a `GET` to `/discovery-scheduler/trigger`, then
`405`, nothing dispatched.

**S3.4 — Missing/unrecognised/malformed body → 400 (AC-012–014, AC-015).** Three sub-cases: missing
`discoveryOperation`; unrecognised enum string; malformed JSON (message is exactly
`"Malformed request body"`, per the existing `onUnreadable` handler). Body carries no caller-supplied
value in any case.

**S3.5 — Audit event emitted (AC-023).** Given a successful trigger, when dispatched, then an audit
event exists identifying the action and its time — assert via `BrokerUtil.getMessageMatching(...)`
against the `jms.topic.auditing.event` topic (same pattern as `IngestionProcessHttpLiveTest`).

**S3.6 — Existing suites pass unmodified (AC-007, regression).** `DiscoveryServiceTest` and both
scheduler live tests pass with no changes required by this story — no new test, a "must still pass"
statement.

Not separately IT-tested (unit-level or non-test per 03-stories.md): **AC-021** (documented
accepted-risk, not enforced — no test), **AC-022** (per-item dispatch failure isolation — unit-level
in `DiscoveryTriggerServiceTest`, since `DiscoveryService` already swallows per-item errors),
**AC-024/025** (build-level / diff-review, not a test class).

### Unit specs (`src/test/`)

| Target | Covers |
|---|---|
| `DiscoveryTriggerServiceTest` (new) | Enum routes to the correct `DiscoveryService` method (exhaustive switch expression, no `default` — a third enum value fails to compile); submission delegates to the injected executor, not the calling thread; an escaping exception is caught and logged, not rethrown (AC-022). |
| `DiscoverySchedulerControllerTest` (extend) | New method returns 202 + vendor content type, delegates once to `DiscoveryTriggerService`; existing tests unaffected. |
| `DiscoveryServiceTest` (existing) | Must not change (AC-007). |

### Coverage map

| AC | Scenario(s) | Layer |
|---|---|---|
| AC-001 | S3.1 | Integration |
| AC-002 | S3.3 | Integration |
| AC-004–006 | S3.1 + `DiscoveryTriggerServiceTest` | Integration + Unit |
| AC-007 | S3.6 | Integration (regression) |
| AC-012–015 | S3.4 | Integration |
| AC-017 | S3.1 | Integration |
| AC-021 | — | Documented, not tested (ADR-001) |
| AC-022 | `DiscoveryTriggerServiceTest` | Unit |
| AC-023 | S3.5 | Integration |
| AC-024/025 | — | Build / review |

### Constraints and open questions

1. **Config prefix deviates from design §10.** Design says `cp.cdk.discovery-trigger.*`, but no
   `cp.cdk.*` prefix exists anywhere else in this repo — the established convention is a flat
   `cdk.<feature>` prefix (`cdk.ingestion`, `cdk.storage.azure`). Using `cdk.discovery-trigger.*` for
   consistency with `IngestionProperties`/`StorageProperties`; flagging as a deliberate deviation from
   the design doc, not an oversight.
2. **`MdcCopyingTaskDecorator` is explicitly out of scope for this story** — the executor bean has no
   custom `TaskDecorator`; worker-thread logs won't carry `correlationId` until Story 4 adds it, per
   03-stories.md's own note that Story 4's classes are "implemented alongside Story 3's ... not
   independently deployable". The response body's `correlationId` (a Story 3 concern, part of the
   released contract) is unaffected — that's read from MDC synchronously on the request thread.
3. **AC-002 uncovered a pre-existing gap in `GlobalExceptionHandler`, unrelated to this endpoint.**
   `S3.3` (wrong method → 405) initially returned `500`: the class's catch-all
   `@ExceptionHandler(Exception.class)` intercepts `HttpRequestMethodNotSupportedException` before
   Spring's own 405 translation ever runs, for *every* endpoint in the service, not just this one —
   no existing test had exercised a wrong-method request anywhere in the repo. Fixed by adding an
   explicit `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` (→ 405) ahead of the
   catch-all, with unit coverage added to the existing `GlobalExceptionHandlerTest`. This is a
   deviation from design §2's "not changed" list for `GlobalExceptionHandler.java` — required for
   AC-002, not a scope choice.

---

## Story 4 — Structured logging and correlation for manual discovery runs (DD-43063)

Covers **AC-018, AC-019, AC-020**. NFR-002 (Logging), NFR-007 (Observability).

**Not independently deployable** — extends Story 3's `DiscoveryTriggerService`/`DiscoveryTriggerConfig`
classes in place; no new endpoint or ACL surface.

### What's new

- `config/MdcCopyingTaskDecorator.java` (new) — captures `MDC.getCopyOfContextMap()` on the request
  thread at submit time (before `RequestContextFilter`'s `finally` clears it), installs it on the
  worker thread, runs the delegate, clears MDC in its own `finally`. Wired into
  `discoveryTriggerExecutor` via `ThreadPoolTaskExecutor.setTaskDecorator(...)`.
- `DiscoveryTriggerService.runWithLogging(...)` now puts `trigger=manual` and `discoveryOperation`
  into MDC around the delegate call (cleared after) — promoted to top-level JSON fields by
  `LogstashEncoder`, the explicit discriminator AC-019 requires (not timestamp inference).

### Scenarios

**S4.1 — MDC captured at submit time is visible on the worker thread (unit,
`MdcCopyingTaskDecoratorTest`).** Given MDC has `correlationId` set on the calling thread, when
`decorate(...)`'s returned `Runnable` runs on a *different* thread, then that value is visible
inside it — even after the calling thread's MDC is cleared/mutated post-submit.

**S4.2 — MDC is cleared after the decorated runnable completes (unit).** Given a pooled thread runs
the decorated runnable, then that thread's MDC is empty afterward — proves no leakage into whatever
task that pooled thread picks up next.

**S4.3 — a null context map at decorate-time doesn't throw (unit).** Given no MDC is set when
`decorate(...)` is called, then the returned `Runnable` still runs without error.

**S4.4 — one correlated start/completion log pair per run, tagged `trigger=manual`, no PII
(AC-018–020, unit, extends `DiscoveryTriggerServiceTest`).** Given `correlationId` is set on MDC and
`trigger(...)` is invoked, when the captured `Runnable` is run (success and failure paths, each
asserted separately), then a Logback `ListAppender` attached to `DiscoveryTriggerService`'s logger
shows exactly one "starting" event and exactly one "finished"/"failed" event, both carrying
`correlationId` + `trigger=manual` + `discoveryOperation` in their MDC property map, and no event's
formatted message contains `CJSCPPUID`, a case/court identifier, or document content — trivially true
by construction (the log statements only ever interpolate the enum), asserted here so a future
edit can't silently add one.

*Why unit-level, not IT:* asserting real log output from an IT would need a docker-log-scraping
helper that doesn't exist in `testsupport/` today (flagged as an open question back in Story 2's
spec); the `ListAppender` approach gives full, deterministic coverage of AC-018–020 without it.

**S4.5 — non-blocking dispatch, delayed-stub proof (design §Testing item 5, IT, extends
`DiscoverySchedulerTriggerHttpLiveTest`).** Given `/hearing-cases-for-day` is stubbed with a
multi-second `fixedDelay` (`CP_CDK_HEARING_IS_HEARING_FOR_CASES_ENABLED=true` in the compose stack
means `NIGHTLY` calls this endpoint directly and synchronously, once per date in the
`nightly-discovery.days-ahead` window — no `scheduled_ingestion_request` seeding needed, unlike the
`INTRADAY` path), when a System User triggers `NIGHTLY`, then the `202` returns in well under the
stub's delay — proving the response is decoupled from the worker's total run time, not just from
one call's latency as Story 3's S3.1 already showed with an un-stubbed instant response.

### Coverage map

| AC | Scenario(s) | Layer |
|---|---|---|
| AC-018 | S4.4 | Unit |
| AC-019 | S4.4 | Unit |
| AC-020 | S4.4 | Unit (+ manual review of the two log statements) |
| (design item 5) | S4.5 | Integration |

### Constraints and open questions

1. **AC-018/019/020 are unit-level, not IT** — see S4.4's rationale above. The DoD's "manual +
   automated check confirms no PII/case data in any log record" is satisfied by S4.4 (automated) plus
   a one-time manual read of `DiscoveryTriggerService`'s two log statements (only `discoveryOperation`
   is ever interpolated) — not a coded manual-test step.
2. **S4.5 depends on the compose stack's `is-hearing-for-cases-enabled` toggle staying `true`.** If
   that default ever flips, `NIGHTLY` falls back to the per-active-configuration/job-manager path
   (indirect, async, harder to pin a delay to) and S4.5 would need reworking — flagging so a future
   toggle change doesn't silently break this test for an unrelated reason.
3. **Test data** — synthetic fixtures only; no case/court/user identifiers.
