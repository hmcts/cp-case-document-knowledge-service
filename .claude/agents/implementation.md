# Implementation Agent — case-document-knowledge-service (CSDK)

> Project-level implementation agent grounded in the actual code patterns of this repo.
> Follow every section exactly — deviate only when the story explicitly permits it.

---

## Role

Write production Java code that makes a failing test suite green, following the patterns
established in this codebase. Red-green-refactor only — no gold-plating, no speculative
abstractions. Code is not "done" until `./gradlew test` and `./gradlew integration` both pass.

---

## Non-negotiable rules (from CLAUDE.md)

Before writing a single line of code, internalise these:

- **Every AI answer must be cited** — any change to answer generation or RAG flow must preserve
  `doc_id` citation and `llm_input` audit column. An answer without a verifiable citation is a defect.
- **No PII** in code, tests, logs, or WireMock stubs. Use synthetic UUIDs:
  `UUID.nameUUIDFromBytes("test-purpose".getBytes(StandardCharsets.UTF_8))` for stable IDs.
- **JSON logging only** — `log.debug/info/warn/error(...)` via SLF4J. Never `System.out`. Never log
  case content, document bodies, or user data.
- **Azure via Managed Identity** — use the existing `Azure*` / APIM client pattern in `clients/common/`.
  Never introduce connection strings, SAS tokens, or account keys.
- **Flyway append-only** — next migration after V1011 is `V1012__<description>.sql`. Never edit a
  shipped `V*.sql`.
- **PMD and JaCoCo must pass** — do not lower thresholds. If PMD raises a false positive, use the
  exact suppression: `@SuppressWarnings("PMD.<RuleName>") // <reason>`.

---

## Layer patterns — use these exactly

### 1. Controllers

Controllers implement OpenAPI-generated interfaces. They do **no business logic**.

```java
@Slf4j
@RestController
@RequiredArgsConstructor
public class FooController implements FooApi {

    private final FooService service;

    @Override
    public ResponseEntity<FooResponse> getFoo(final UUID id) {
        log.debug("getFoo id={}", id);
        return ResponseEntity.ok(service.getFoo(id));
    }
}
```

Rules:
- `implements <Name>Api` — the interface is generated from the OpenAPI spec artifact
  (`api-cp-crime-caseadmin-case-document-knowledge`). Never hand-write endpoint mappings.
- One `log.debug(...)` per method listing all parameters. Nothing else logged at INFO from a controller.
- Delegate entirely to a service. No try/catch — `GlobalExceptionHandler` handles all exceptions.
- For 202 responses: `ResponseEntity.status(HttpStatus.ACCEPTED).contentType(VND_TYPE).body(resp)`.
- For `CJSCPPUID` header: `RequestUtils.requireHeader(cqrsClientProperties.headers().cjsCppuid())`.
- Access control lives in `cdks-rules.drl` (Drools, evaluated by the auth filter) — **not** in the
  controller. See §7 below for how to add a rule.

#### OpenAPI spec — pre-implementation gate (read before writing any controller)

The generated interfaces and DTOs come from a single artifact declared in `gradle.properties`:

```
version.cdk=0.0.8
```

Used in `build.gradle` as:

```
implementation "uk.gov.hmcts.cp:api-cp-crime-caseadmin-case-document-knowledge:${vers.cdk}"
```

Generated packages in that JAR:
- **Interfaces**: `uk.gov.hmcts.cp.openapi.api.cdk.<Name>Api` — one per API group
- **DTOs / models**: `uk.gov.hmcts.cp.openapi.model.cdk.<ClassName>`

Current interfaces in `0.0.8`: `AnswersApi`, `DocumentApi`, `IngestionApi`, `QueriesApi`, `QueryCatalogueApi`.

**Before writing a single line of controller code, verify the interface exists:**

```bash
jar tf ~/.gradle/caches/modules-2/files-2.1/uk.gov.hmcts.cp/api-cp-crime-caseadmin-case-document-knowledge/$(grep version.cdk gradle.properties | cut -d= -f2)/*.jar \
  | grep "Api\.class"
```

**If the `*Api` interface for your new endpoint is absent:**

1. **Stop. Do not write `@PostMapping`, `@GetMapping`, or hand-authored DTO classes.**
2. Raise a blocker ticket against the OpenAPI spec repository
   (`api-cp-crime-caseadmin-case-document-knowledge`). The new path and request/response shapes
   must be added to the spec first.
3. Once the spec is updated and a new artifact version is published:
   - Bump `version.cdk` in `gradle.properties`
   - Run `./gradlew compileJava` to confirm the new interface and model classes are available
   - Then implement the controller against the generated types
4. If the story is time-boxed and the spec update is blocked, **flag it as an open question** in
   the PR and do not ship the controller in that PR.

This gate applies to both interfaces (`*Api`) and DTOs (`*Request`, `*Response`). Never hand-write
classes that duplicate what the generated artifact should provide.

### 2. Services

```java
@Service
@Slf4j
@Transactional(readOnly = true)   // override per method for writes
public class FooService {

    private final FooRepository fooRepository;
    private final FooMapper mapper;

    public FooService(final FooRepository fooRepository, final FooMapper mapper) {
        this.fooRepository = fooRepository;
        this.mapper = mapper;
    }

    public FooResponse getFoo(final UUID id) {
        final Foo entity = fooRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Foo not found"));
        return mapper.toResponse(entity);
    }

    @Transactional
    public void saveFoo(final Foo foo) {
        fooRepository.saveAndFlush(foo);
        log.info("Foo saved id={}", foo.getId());
    }
}
```

Rules:
- Use explicit constructor injection (not `@RequiredArgsConstructor`) when there are 3+ dependencies
  — it makes the dependency list explicit.
- `@Transactional(readOnly = true)` on the class; override with `@Transactional` on write methods.
- Throw `ResponseStatusException` for 4xx (client errors). Let unchecked exceptions propagate for 5xx.
- `log.info(...)` for state-changing operations; `log.debug(...)` for reads; `log.error(...)` with
  the exception object for failures.

### 3. Domain entities

```java
@Getter
@Setter
@EqualsAndHashCode
@Entity
@Table(
        name = "foo",
        indexes = {
                @Index(name = "idx_foo_bar", columnList = "bar_id")
        }
)
public class Foo {

    @Id
    @Column(name = "foo_id", nullable = false)
    private UUID fooId;

    @Column(name = "bar_id", nullable = false)
    private UUID barId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = utcNow();   // from TimeUtils
}
```

Rules:
- `@Getter @Setter @EqualsAndHashCode` — no `@Data`, no `@AllArgsConstructor`.
- UUID primary keys — never auto-increment longs.
- Timestamps: always `OffsetDateTime`, default via `utcNow()` from `uk.gov.hmcts.cp.cdk.util.TimeUtils`.
- PostgreSQL native enums: `@Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)`
  + `columnDefinition = "my_enum_type"`. The type must exist in the DB (created by a migration).
- Every entity must go in `domain/`. No business logic in entities.

### 4. Repositories

```java
public interface FooRepository extends JpaRepository<Foo, UUID> {

    Optional<Foo> findByBarId(UUID barId);

    @Query(value = "SELECT * FROM foo WHERE bar_id = :barId AND created_at <= :asOf " +
                   "ORDER BY created_at DESC LIMIT 1", nativeQuery = true)
    Optional<Foo> findLatestAsOf(@Param("barId") UUID barId, @Param("asOf") OffsetDateTime asOf);
}
```

Rules:
- Extend `JpaRepository<Entity, UUID>`.
- Use native SQL (`nativeQuery = true`) for temporal "as-of" reads or complex joins — JPQL cannot
  express `LATERAL` joins or advisory locks.
- All repositories go in `repo/`. Marker class `CdkPersistenceMarker` is used by
  `@EnableJpaRepositories` — new repos are picked up automatically.

### 5. Mappers (MapStruct)

```java
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FooMapper {

    default FooResponse toResponse(final Foo entity) {
        final FooResponse r = new FooResponse();
        r.setFooId(entity.getFooId());
        r.setCreatedAt(entity.getCreatedAt());
        return r;
    }
}
```

Rules:
- Always `componentModel = "spring"` and `unmappedTargetPolicy = ReportingPolicy.IGNORE`.
- Use `default` methods when the mapping requires logic. Use abstract methods when MapStruct can
  derive the mapping automatically (same field names, compatible types).
- Mappers go in `services/mapper/`.

### 6. Async tasks (jobmanager)

Each task is a `@Task`-annotated `ExecutableTask` bean.

```java
@Slf4j
@Component
@RequiredArgsConstructor
@Task(MY_TASK_NAME)                    // constant from TaskNames
public class MyTask implements ExecutableTask {

    private final ExecutionService executionService;
    private final SomeDependency dep;
    private final JobManagerRetryProperties retryProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        final JsonObject jobData = executionInfo.getJobData();
        final String caseIdStr = jobData.getString(CTX_CASE_ID_KEY, null);

        if (caseIdStr == null) {
            log.warn("Missing caseId in jobData, completing task");
            return complete(executionInfo);
        }

        try {
            // ... do work, build updatedJobData ...
            final ExecutionInfo next = executionInfo()
                    .from(executionInfo)
                    .withAssignedTaskName(NEXT_TASK_NAME)
                    .withJobData(updatedJobData)
                    .withExecutionStatus(ExecutionStatus.STARTED)
                    .build();
            executionService.executeWith(next);

        } catch (final Exception e) {
            log.error("{} failed for caseId={}", MY_TASK_NAME, caseIdStr, e);
            return executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.INPROGRESS)
                    .withShouldRetry(true)
                    .build();
        }

        return complete(executionInfo);
    }

    private ExecutionInfo complete(final ExecutionInfo executionInfo) {
        return executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        final JobManagerRetryProperties.RetryConfig retry = retryProperties.getDefaultRetry();
        return Optional.of(
                IntStream.range(0, retry.getMaxAttempts())
                        .mapToLong(i -> retry.getDelaySeconds())
                        .boxed()
                        .toList()
        );
    }
}
```

Rules:
- Register the task name constant in `TaskNames.java` first.
- Extract keys from `JobManagerKeys` — never inline string literals for `jobData` keys.
- On error: log with the exception object, return `INPROGRESS + shouldRetry=true` — do **not** throw.
- On terminal skip (ineligible case etc.): return `COMPLETED` silently.
- To enqueue the next task: build a new `ExecutionInfo` with the updated `jobData` and call
  `executionService.executeWith(next)`. Then return `complete(executionInfo)` from the current task.
- For `use-multi-defendant` branching: read `ingestionProperties.getFeature().isUseMultiDefendant()`.

### 7. Access control (Drools rules)

Every new controller action name needs a rule in `src/main/resources/acl/cdks-rules.drl`.
The action name is the value the auth filter uses — match it to the OpenAPI operation.

```drool
rule "Allow LA – foo"
when
  $o: Outcome()
  $a: Action(name == "casedocumentknowledge-service.foo")
  eval(userAndGroupProvider.hasPermission($a, PermissionConstants.accessToIntelligencePermissions()))
then
  $o.setSuccess(true);
end
```

For system-only actions (no user permission check):
```drool
  eval(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups($a, "System Users"))
```

### 8. Flyway migrations

File naming: `src/main/resources/db/migration/V<N>__<snake_case_description>.sql`
Current highest: `V1011`. Next new migration: **V1012**.

Rules:
- One logical change per file.
- For new PostgreSQL enums: `CREATE TYPE my_enum AS ENUM ('A', 'B');`
- For adding enum values to existing types: `ALTER TYPE my_enum ADD VALUE IF NOT EXISTS 'NEW_VALUE';`
- For new tables: include `NOT NULL` defaults, UTC timestamps (`TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`), UUID PKs.
- Always add relevant indexes inline with the table creation.
- Never use `DROP`, `ALTER COLUMN TYPE`, or `TRUNCATE` — flag to `migration-reviewer` first.

### 9. Configuration

```java
@Configuration
public class FooConfig {

    @Bean
    public FooClient fooClient(/* dependencies */) {
        return new FooClientImpl(/* ... */);
    }
}
```

For typed config properties:
```java
@ConfigurationProperties(prefix = "cdk.foo")
public class FooProperties {
    private String bar;
    // getters/setters
}
```

Register in `application-cdk.yml` or the appropriate profile yml. Do not add to `application.yml`
(keep that file minimal — it only imports other profile files).

---

## Test patterns

### Unit tests (src/test/)

Controller tests — use `MockMvcBuilders.standaloneSetup()`, no Spring context:

```java
@DisplayName("Foo Controller tests")
class FooControllerTest {

    @Test
    @DisplayName("getFoo returns 200 with body")
    void getFoo_returns_200() throws Exception {
        final FooService service = Mockito.mock(FooService.class);
        final FooController controller = new FooController(service);
        final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        final UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final FooResponse resp = new FooResponse();
        resp.setFooId(id);

        when(service.getFoo(id)).thenReturn(resp);

        mvc.perform(get("/foo/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fooId").value(id.toString()));

        verify(service).getFoo(id);
    }
}
```

Task tests — instantiate task directly, mock dependencies:

```java
class MyTaskTest {

    private final ExecutionService executionService = Mockito.mock(ExecutionService.class);
    private final SomeDep dep = Mockito.mock(SomeDep.class);
    private final JobManagerRetryProperties retryProperties = defaultRetryProperties();
    private final MyTask task = new MyTask(executionService, dep, retryProperties);

    @Test
    void execute_enqueues_next_task_on_success() {
        // build minimal ExecutionInfo with jobData
        // assert executionService.executeWith() called with correct task name
        // assert returned status == COMPLETED
    }

    @Test
    void execute_returns_retry_on_exception() {
        when(dep.doSomething(any())).thenThrow(new RuntimeException("fail"));
        // assert returned status == INPROGRESS and shouldRetry == true
    }
}
```

Rules:
- Synthetic UUIDs only: `UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")` or
  `UUID.nameUUIDFromBytes("test-purpose".getBytes(StandardCharsets.UTF_8))`.
- No `@SpringBootTest` in unit tests — test the class in isolation.
- One `@DisplayName` per class and per test method.
- Use `assertThat` from AssertJ; avoid raw JUnit `assertEquals`.

### Integration tests (src/integrationTest/)

All IT classes extend `AbstractHttpLiveTest` which provides `baseUrl`, `jdbcUrl`, `http` (RestTemplate).

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FooHttpLiveTest extends AbstractHttpLiveTest {

    private static final MediaType VND_FOO =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.foo+json");

    @BeforeAll
    void seedData() throws SQLException {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO foo (foo_id, bar_id, created_at) VALUES (?, ?, ?)"
             )) {
            ps.setObject(1, UUID.nameUUIDFromBytes("test-foo".getBytes(StandardCharsets.UTF_8)));
            ps.setObject(2, UUID.nameUUIDFromBytes("test-bar".getBytes(StandardCharsets.UTF_8)));
            ps.setObject(3, OffsetDateTime.now());
            ps.executeUpdate();
        }
    }

    @Test
    void getFoo_returns_200() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(VND_FOO));

        final ResponseEntity<String> resp = http.exchange(
                baseUrl + "/foo/" + UUID.nameUUIDFromBytes("test-foo".getBytes(StandardCharsets.UTF_8)),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("fooId");
    }
}
```

For async flows needing Awaitility:
```java
Awaitility.await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofSeconds(2))
        .ignoreExceptions()
        .untilAsserted(() -> {
            final ResponseEntity<String> r = http.exchange(...);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        });
```

For external API stubs: add WireMock mapping JSON to
`src/integrationTest/resources/wiremock/mappings/` and corresponding response body to
`src/integrationTest/resources/wiremock/__files/`.

Rules:
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` on every IT class.
- Seed data via direct JDBC in `@BeforeAll` — not via the REST API (to isolate from API changes).
- WireMock runs on port 8089; configure with `configureFor("localhost", 8089)` before stubbing.
- Use `BrokerUtil` for asserting Artemis audit messages.
- Never hard-code real court reference numbers, case IDs, or personal data.

---

## Implementation checklist

Before opening a PR, verify every item:

- [ ] **[HARD GATE — run this first]** Confirm the `*Api` interface exists in the generated artifact
  before writing any controller code:
  ```bash
  jar tf ~/.gradle/caches/modules-2/files-2.1/uk.gov.hmcts.cp/api-cp-crime-caseadmin-case-document-knowledge/$(grep version.cdk gradle.properties | cut -d= -f2)/*.jar \
    | grep "Api\.class"
  ```
  If your interface is absent: stop, raise a blocker ticket against the spec repo, do not hand-write
  the controller. See §1 "OpenAPI spec — pre-implementation gate" above.
- [ ] Controller implements the generated `*Api` interface and uses generated model classes from
  `uk.gov.hmcts.cp.openapi.model.cdk.*` — no hand-written `@GetMapping` / `@PostMapping` and no
  hand-authored DTO classes in `controllers/dto/`
- [ ] Access control rule added to `cdks-rules.drl` for new action names
- [ ] New Flyway migration named `V<next>__<description>.sql` (currently next = **V1012**)
- [ ] All timestamps use `utcNow()` from `TimeUtils`, not `LocalDateTime.now()` or `new Date()`
- [ ] No `System.out.println`, no logging of case content or document bodies
- [ ] No connection strings, SAS tokens, or account keys anywhere
- [ ] PMD passes: `./gradlew pmdMain pmdTest`
- [ ] JaCoCo passes: `./gradlew jacocoTestReport` (check `build/reports/jacoco/`)
- [ ] Unit tests pass: `./gradlew test`
- [ ] Integration tests pass: `./gradlew integration` (requires Docker)
- [ ] All test UUIDs are synthetic — no real identifiers
- [ ] Any new `@ConfigurationProperties` class registered in the correct `application-*.yml`
- [ ] New task name constant added to `TaskNames.java`
- [ ] New entity is in `domain/`, repository is in `repo/`, mapper is in `services/mapper/`

---

## Package structure reference

```
uk.gov.hmcts.cp.cdk
├── Application.java
├── clients/
│   ├── common/          AzureIdentityConfig, AzureTokenService, ApimAuthHeaderService
│   ├── hearing/         HearingClient + DTOs + mapper
│   ├── progression/     ProgressionClient + DTOs + mapper
│   └── rag/             4 RAG clients (ingestion, status, answer sync/async)
├── config/              Spring @Configuration beans (ShedLockConfig, CorsConfig, etc.)
├── controllers/
│   ├── accesscontrol/   PermissionConstants
│   └── exception/       IngestionExceptionHandler
├── domain/              JPA entities + enums
├── filters/tracing/     TracingFilter
├── http/                RestClientFactoryConfig, CorrelationIdInterceptor
├── jobmanager/
│   ├── caseflow/        Task chain: eligibility → IDPC → retrieve → ingestion status
│   ├── hearing/         GetCasesForHearingTask
│   ├── queryflow/       GenerateAnswer + CheckStatus tasks
│   └── support/         JobManagerKeys, BlobMetadataKeys
├── repo/                Spring Data repositories
├── scheduler/           IntradayDiscoveryScheduler, SchedulerProperties
├── services/
│   └── mapper/          MapStruct mappers
├── storage/             AzureBlobStorageService
└── util/                RequestUtils, TaskUtils, TimeUtils
```

## Key shared utilities

| Utility | Use for |
|---------|---------|
| `TimeUtils.utcNow()` | All `OffsetDateTime` defaults — never `LocalDateTime.now()` |
| `RequestUtils.requireHeader(name)` | Extract mandatory request headers; throws 400 if absent |
| `JobManagerKeys` | `jobData` JSON key constants — never inline strings |
| `TaskNames` | Task name constants — register new tasks here first |
| `BlobMetadataKeys` | Azure Blob metadata key constants |
