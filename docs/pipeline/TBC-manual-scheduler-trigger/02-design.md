# Design: Manual Discovery Scheduler Trigger Endpoint

> **Stage 2 — Architecture & Design** · Service: `cp-case-document-knowledge-service` (CSDK)
> **Jira: TBC** · Requirements: [`01-requirements.md`](./01-requirements.md) · ADRs: [`adrs.md`](./adrs.md)
> `POST /discovery-scheduler/trigger` — enum discriminator (`INTRADAY`/`NIGHTLY`), returns `202 Accepted` immediately, dispatches the existing `DiscoveryService` operation onto a dedicated bounded executor. `DiscoveryService` itself is not touched.

---

## Detailed Design

### 1. Contract change (external repo — do this first)

**Repo:** `api-cp-crime-caseadmin-case-document-knowledge` · `0.0.11` → new version, then bump `version.cdk` (`gradle.properties:2`). Nothing here compiles until the operation exists in the jar.

Add under the existing `Discovery Scheduler` tag (keeps it on the existing generated `DiscoverySchedulerApi`):

```yaml
  /discovery-scheduler/trigger:
    post:
      tags: [Discovery Scheduler]
      operationId: triggerDiscovery
      requestBody:
        required: true
        content:
          application/vnd.casedocumentknowledge-service.discovery-scheduler-trigger+json:
            schema: {$ref: '#/components/schemas/DiscoveryTriggerRequest'}
      responses:
        '202':
          content:
            application/vnd.casedocumentknowledge-service.discovery-scheduler-trigger+json:
              schema: {$ref: '#/components/schemas/DiscoveryTriggerResponse'}
        '400':
          content:
            application/vnd.casedocumentknowledge-service.discovery-scheduler-trigger+json:
              schema: {$ref: '#/components/schemas/ErrorResponse'}
```

```yaml
    DiscoveryOperation:
      type: string
      enum: [INTRADAY, NIGHTLY]
    DiscoveryTriggerRequest:
      type: object
      required: [discoveryOperation]
      properties:
        discoveryOperation: {$ref: '#/components/schemas/DiscoveryOperation'}
    DiscoveryTriggerResponse:
      type: object
      required: [discoveryOperation, message]
      properties:
        discoveryOperation: {$ref: '#/components/schemas/DiscoveryOperation'}
        message: {type: string, example: "Discovery run dispatched."}
        correlationId: {type: string, description: "Correlation id the dispatched run logs under."}
```

- **Vendor media type is load-bearing.** `cp-auth-rules-filter` derives the Drools action name from it: `...discovery-scheduler-trigger+json` → action `casedocumentknowledge-service.discovery-scheduler-trigger`. Change one, change both.
- `required: [discoveryOperation]` turns a missing field into a clean 400 rather than a 500.
- One enum, not two paths — one ACL rule; leaves the door open to split `INTRADAY`/`NIGHTLY` permissions later.
- No `409`/`429` response — not implemented.

### 2. Files touched

| File | Change |
|---|---|
| `controllers/DiscoverySchedulerController.java` | Add `@Override triggerDiscovery(...)`. |
| `services/DiscoveryTriggerService.java` *(new)* | Maps enum → `DiscoveryService` method, submits to executor, owns start/finish/failure logging. |
| `config/DiscoveryTriggerConfig.java` *(new)* | `discoveryTriggerExecutor` bean; binds properties. |
| `config/MdcCopyingTaskDecorator.java` *(new)* | Captures submitting thread's MDC onto the worker thread. |
| `config/DiscoveryTriggerProperties.java` *(new)* | `cp.cdk.discovery-trigger.*` (queue-capacity, await-termination-seconds). |
| `acl/cdks-rules.drl` | Append one rule. |
| `gradle.properties` | Bump `version.cdk`. |
| `application-cdk.yml` | Add `cp.cdk.discovery-trigger.*`. |

**Not changed:** `DiscoveryService.java`, both scheduler classes, `ShedLockConfig.java`, `GlobalExceptionHandler.java`, `db/migration/**`, any existing `.drl` rule or endpoint — verifiable from the diff's file list alone.

### 3. Controller

One method on the existing `DiscoverySchedulerController` (already `implements DiscoverySchedulerApi`) — same resource namespace and actor as the configuration endpoint. No mapping annotation of its own; path comes from the generated interface.

```
ResponseEntity<DiscoveryTriggerResponse> triggerDiscovery(@RequestBody @Valid DiscoveryTriggerRequest request)
  -> log.debug(...)
  -> discoveryTriggerService.trigger(request.getDiscoveryOperation())
  -> return ResponseEntity.status(ACCEPTED).contentType(VND_DISCOVERY_SCHEDULER_TRIGGER).body(response)
```

DTOs are contract-generated (`uk.gov.hmcts.cp.openapi.model.cdk`) — no hand-rolled enum, deserialiser, or mapper.

### 4. Access control

```drl
rule "Allow LA – discovery-scheduler-trigger"
when
  $o: Outcome()
  $a: Action(name == "casedocumentknowledge-service.discovery-scheduler-trigger")
  eval(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups($a, "System Users"))
then
  $o.setSuccess(true);
end
```

Denial (fixed inside `cp-auth-rules-filter`): no/blank `CJSCPPUID` → 401; no matching rule / non-matching caller → 403. A wrong or missing vendor media type also denies (fail-closed), never leaves the endpoint unprotected.

Route this ACL change through the `rbac-auditor` agent (CLAUDE.md rule for `resources/acl/`).

### 5. Service layer

New `DiscoveryTriggerService`, not a method on `DiscoveryService`:

```
DiscoveryTriggerService
  ├── DiscoveryService discoveryService       (existing, untouched)
  └── TaskExecutor discoveryTriggerExecutor   (new)

  public void trigger(DiscoveryOperation operation)
      switch (operation) { INTRADAY -> discoveryService::runIntradayDiscovery
                            NIGHTLY  -> discoveryService::runNightlyDiscovery }  // exhaustive, no default
      -> wrap in logging/timing decorator -> executor.execute(wrapped) -> return
```

Separate class because `DiscoveryService`'s constructor already takes 9 dependencies — adding a `TaskExecutor` there would touch every `DiscoveryServiceTest` construction site. Exhaustive switch (no `default`) turns a future third enum value into a compile error, not a silent no-op.

### 6. Fire-and-forget dispatch

Dedicated, bounded `ThreadPoolTaskExecutor` in `config/DiscoveryTriggerConfig.java` (not `ShedLockConfig` — unrelated to locking):

```
@Bean("discoveryTriggerExecutor")
ThreadPoolTaskExecutor discoveryTriggerExecutor(MdcCopyingTaskDecorator decorator)
    corePoolSize = 1, maxPoolSize = 1
    queueCapacity = ${cp.cdk.discovery-trigger.queue-capacity:10}
    threadNamePrefix = "discovery-trigger-"
    taskDecorator = decorator
    waitForTasksToCompleteOnShutdown = true
    awaitTerminationSeconds = ${cp.cdk.discovery-trigger.await-termination-seconds:30}
```

- **Explicit `execute(...)`, not `@Async`.** CSDK has zero `@Async` usage; `@EnableAsync` would add proxying service-wide for one call site, and self-invocation would silently make it synchronous again. Explicit injection is trivially unit-testable.
- **Not virtual threads.** `spring.threads.virtual.enabled` defaults `false` and is unset in this repo — behaviour must not depend on that toggle.
- **Boundedness is the only back-pressure.** One worker thread per pod caps concurrent manual runs per pod — per-JVM only, not a distributed guarantee (consistent with ADR-001).
- **Rejection, deliberately rough:** queue full → `TaskRejectedException` → `GlobalExceptionHandler` catch-all → 500. A 429/503 is out of scope. `queue-capacity` is externalised.

### 7. Correlation and MDC

`RequestContextFilter` clears MDC in `finally`; under a 202 the filter unwinds before the worker runs, so a naive implementation silently loses `correlationId`.

**Design:** `MdcCopyingTaskDecorator implements TaskDecorator` — captures `MDC.getCopyOfContextMap()` on the request thread at submit time (before `finally` runs), installs it on the worker, runs the delegate, clears MDC in its own `finally`. Both start/completion log records then carry the HTTP request's `correlationId`, also returned in the 202 body.

### 8. Logging

| Where | Level | Message |
|---|---|---|
| Controller, on accept | `debug` | `Discovery trigger accepted discoveryOperation={}` |
| Worker, start | `info` | `Manual discovery run starting discoveryOperation={} trigger=manual` |
| Worker, success | `info` | `Manual discovery run finished discoveryOperation={} trigger=manual durationMs={}` |
| Worker, exception | `error` | `Manual discovery run failed discoveryOperation={} trigger=manual durationMs={}` + exception |

`trigger=manual` and `discoveryOperation` go into MDC on the worker (promoted to JSON fields by `LogstashEncoder`) — the explicit discriminator vs. the cron path's differently-worded lines. The `error` branch is required: a failure in the surrounding code (not just per-item dispatch, which `DiscoveryService` already swallows) can still escape and must not leave the start/finish pair unterminated. Only the enum name is logged — no `CJSCPPUID`, case/court ids, hearing dates, or content.

### 9. Error handling

| Input | Exception | Status |
|---|---|---|
| Discriminator absent/null | `MethodArgumentNotValidException` (`@NotNull` + `@Valid`) | 400 |
| Unrecognised value / malformed JSON | `HttpMessageNotReadableException` | 400 |
| Wrong HTTP method | `HttpRequestMethodNotSupportedException` | 405 |

All flow through the existing `GlobalExceptionHandler` — no new exception-handling code. The one new handling point is the worker-thread `catch (Exception)` in `DiscoveryTriggerService`: once the 202 is sent there's no response left to write to, so a post-response failure is observable only in logs — the accepted trade-off of fire-and-forget, accept-and-log.

### 10. Configuration

```yaml
cp:
  cdk:
    discovery-trigger:
      queue-capacity: ${CP_CDK_DISCOVERY_TRIGGER_QUEUE_CAPACITY:10}
      await-termination-seconds: ${CP_CDK_DISCOVERY_TRIGGER_AWAIT_TERMINATION_SECONDS:30}
```

Not under `scheduler.*` — the trigger is not a scheduler.

---

## Testing

Scoping only — Test Specs stage owns the actual scenarios.

**Unit (`src/test/`)**

| Target | Covers |
|---|---|
| `DiscoveryTriggerServiceTest` (new) | Enum routes to the correct `DiscoveryService` method; submission delegates to the injected executor (request thread doesn't run the work); an escaping exception is caught and logged, not rethrown. |
| `DiscoverySchedulerControllerTest` (extend) | New method returns 202 + correct vendor content type, delegates once; existing tests unaffected. |
| `MdcCopyingTaskDecoratorTest` (new) | MDC captured at decorate-time is visible in the worker; cleared after (no leakage between pooled tasks); null map handled. |
| `DiscoveryServiceTest` (existing) | Must not change. |

**Integration (`src/integrationTest/`)** — new `DiscoverySchedulerTriggerHttpLiveTest`:

1. Authorised trigger → 202, body echoes the operation.
2. `"AI search"`-only caller → 403 (the key proof the `.drl` rule binds to the right action name).
3. No `CJSCPPUID` → 401.
4. Invalid discriminator / missing field / malformed JSON → 400, body carries no caller-supplied value.
5. Non-blocking: delay the WireMock hearing stub, assert 202 returns well before that delay elapses.
6. Audit event emitted (`BrokerUtil`).
7. Wrong/absent vendor media type → 403.

**Testability obstacle:** the compose stack runs both discovery crons every 30s, which confounds any IT asserting dispatch *counts*. Assert on discriminators instead (`trigger=manual` + correlation id + the 202 contract) — no compose change needed, existing scheduler live tests stay intact.

**Contract tests:** none expected — no known consumer yet.

**Verify empirically:** unknown-JSON-field behaviour (CSDK's `@Primary ObjectMapper` may not match Boot's default), and JaCoCo coverage on the new `@Configuration`/`TaskDecorator` classes (easy to leave uncovered).
