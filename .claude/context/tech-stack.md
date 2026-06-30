# Tech Stack — cp-case-document-knowledge-service (CSDK)

> Ground truth for this repo. Agents must not invent dependencies, versions, or patterns
> not listed here. When in doubt, read `build.gradle`.

---

## Runtime

| Component | Version / Detail |
|-----------|-----------------|
| Java | 25 |
| Spring Boot | 4.0.5 |
| Spring MVC | via Spring Boot |
| Spring Data JPA | via Spring Boot |
| Spring Batch | on classpath; `job.enabled=false` (disabled at runtime) |
| Gradle | 9 (wrapper: `./gradlew`) |
| Base package | `uk.gov.hmcts.cp.cdk` |
| Context path | `/casedocumentknowledge-service` |
| Default port | 8082 (`SERVER_PORT`) |
| Management port | same as server port |

---

## Database

| Component | Version / Detail |
|-----------|-----------------|
| PostgreSQL | 16 (production), 16-alpine (Docker Compose) |
| Flyway | 7.x — migrations in `src/main/resources/db/migration/` |
| Migration prefix | `V1000` – `V1010` (append-only; never edit shipped versions) |
| HikariCP | max 20 connections, min 5, 300 s timeout |
| JPA DDL | `validate` — Flyway owns schema; Hibernate must not auto-create |
| Timezone | UTC enforced via JPA/JDBC properties |
| Distributed lock | ShedLock (`shedlock-spring` + JDBC provider); table `shedlock` (`V1010`) |

### Migration inventory

| File | What it creates / alters |
|------|--------------------------|
| `V1000__spring_batch_metadata.sql` | Spring Batch metadata tables |
| `V1001__case_documents_ai_schema.sql` | Core schema: `queries`, `query_versions`, `case_documents`, `case_query_status`, answer tables, views |
| `V1002__case_documents_queries_updateschema.sql` | Query versioning / lifecycle updates |
| `V1003__document_verification_task.sql` | `document_verification_task` table + indexes |
| `V1004-V1006__case_documents_repo_updateschema.sql` | Iterative case-document schema refinements |
| `V1007__case_documents_queries_activeflag.sql` | Active flag on queries |
| `V1008__alter_document_ingestion_phase_enum_file_size_limit.sql` | Ingestion phase enum + file size tracking |
| `V1009__case_documents_scheduled_ingestion_request.sql` | `scheduled_ingestion_request` table |
| `V1010__create_shedlock_table.sql` | `shedlock` table for distributed scheduler lock |

---

## Messaging

| Component | Version / Detail |
|-----------|-----------------|
| ActiveMQ Artemis | 2.31.2 |
| Protocol | JMS (port 61616, SSL enabled) |
| Usage | Audit event publishing only (via `cp-audit-filter-springboot`); no `@JmsListener` in service code |
| Reconnect | Infinite (-1), 10 initial attempts, 2 s retry interval |

---

## Azure integrations

| Component | Library | Auth |
|-----------|---------|------|
| Blob Storage | `azure-storage-blob` 12.32.0 | Managed Identity (or Azurite locally) |
| Azure Identity | `azure-identity` 1.14.1 | `DefaultAzureCredential` |
| APIM (RAG, Hearing, Progression) | `ApimAuthHeaderService` / `AzureTokenService` | Managed Identity AAD token |

Storage modes (configured via `cp.cdk.storage.mode`):
- `managed-identity` — production
- `connection-string` — not permitted in production
- `azurite` — local dev / integration tests

---

## HTTP clients

| Component | Version | Used for |
|-----------|---------|---------|
| Apache HttpComponents | 5.5.1 | Primary HTTP client factory (`RestClientFactoryConfig`) |
| OkHttp | 5.3.0 | MockWebServer in tests only |

Outbound clients:
- **RAG service** — `ApimDocumentIngestionClient`, `ApimDocumentIngestionStatusClient`, `RagAnswerServiceImpl`, `RagAnswerAsyncServiceImpl` (base URL: `CP_CDK_RAG_URL`; 3 s connect, 180 s read)
- **Hearing service** — `HearingClientImpl` (path: `/hearing-query-api/...`; 3 s connect, 15 s read)
- **Progression service** — `ProgressionClientImpl` (path: `/progression-query-api/...`; 3 s connect, 15 s read)

All APIM calls carry a subscription key or AAD token injected by `ApimAuthHeaderService`.

---

## HMCTS internal libraries

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `api-cp-crime-caseadmin-case-document-knowledge` | 0.0.9 | OpenAPI models for CSDK own API |
| `api-cp-ai-rag` | 0.0.12 | RAG service API models |
| `cp-auth-rules-filter` | 1.0.7 | Drools-based HTTP authorization |
| `cp-audit-filter-springboot` | 1.0.5 | Audit event filter (publishes to Artemis) |
| `task-manager-service` | 1.0.10 | Job/task orchestration client |

---

## Scheduling

| Component | Detail |
|-----------|--------|
| Scheduler class | `IntradayDiscoveryScheduler` |
| Cron | `0 0/10 7-19 * * MON-FRI` (every 10 min, 07:00–19:50, weekdays) |
| Lock name | `IntradayDiscoveryScheduler` |
| Lock duration | at-least 8 min, at-most 9 min (ShedLock JDBC) |

---

## Observability

| Concern | Mechanism |
|---------|-----------|
| Metrics | Micrometer + Prometheus (`/actuator/prometheus`) |
| Tracing | OpenTelemetry (OTLP; disabled by default, `TRACING_SAMPLER_PROBABILITY`) |
| Logs | Structured JSON to stdout (`logback-spring.xml` + Logstash encoder 9.0) |
| Health | Spring Actuator (`/actuator/health`, liveness/readiness) |
| Auditing | `cp-audit-filter-springboot` → Artemis |

---

## Build & quality

| Command | What it runs |
|---------|-------------|
| `./gradlew clean build` | Full build incl. unit + integration tests |
| `./gradlew test` | Unit tests only (failFast) |
| `./gradlew integration` | Integration tests (spins up Docker Compose: db, artemis, azurite, azurite-seed, wiremock, app) |
| `./gradlew pmdMain pmdTest` | PMD static analysis |
| `./gradlew jacocoTestReport` | JaCoCo coverage report (`build/reports/jacoco`) |
| `./gradlew dependencyInsight --dependency <group>` | Dependency graph inspection |

Quality thresholds: PMD + JaCoCo must pass. Do not lower thresholds to go green.

---

## Test frameworks

| Layer | Source set | Framework |
|-------|------------|-----------|
| Unit | `src/test/` | JUnit 5, Mockito, AssertJ, Spring Boot Test, Testcontainers (PostgreSQL) |
| Integration | `src/integrationTest/` | REST Assured 5.5.6, WireMock 3.0.1, Docker Compose-backed stack, `AbstractHttpLiveTest` base class |
| Contract | `src/pactVerificationTest/` | Pact (`au.com.dius.pact` plugin) — consumer-driven contract tests |

Integration test compose stack:
- PostgreSQL 16-alpine (port 55432)
- Artemis JMS
- Azurite (Azure Blob emulator)
- azurite-seed (pre-populates containers)
- WireMock (stubs for Hearing + RAG APIs)
- App (port 8082)

---

## Mapping & serialization

| Library | Version | Usage |
|---------|---------|-------|
| MapStruct | 1.5.5.Final | Bean mapping (`QueryMapper`, `AnswerMapper`, DTO mappers) |
| Jackson | 2.18.1 | JSON serialization + Kotlin support |
| Lombok | 1.18.42 | Annotation processing (`@Data`, `@Builder`, etc.) |
| Swagger Parser | 2.1.35 | OpenAPI spec parsing |

---

## Authorization

- Framework: `cp-auth-rules-filter` (HTTP Drools)
- Rules: `src/main/resources/acl/cdks-rules.drl`
- Identity endpoint: `CP_CDK_BASE_URL/usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions`
- User context header: `CJSCPPUID`
- Permission constant: `"AI search"` (`PermissionConstants.INTELLIGENCE_ACCESS`)
- Excluded from auth: `/usersgroups-query-api/`, `/actuator`, `/error`

All endpoints require `"AI search"` permission or System Users group membership.

---

## Docker Compose (local dev)

| Service | Image | Port |
|---------|-------|------|
| `db` | postgres:16-alpine | 55432:5432 |
| `app` | Custom (Dockerfile) | 8082:8082 |

Integration testing adds: `artemis`, `azurite`, `azurite-seed`, `wiremock`.

---

## API surface

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/cases/{caseId}/queries/{queryId}/answers/with-llm` | GET | Answer with LLM metadata |
| `/cases/{caseId}/queries/{queryId}/answers/list` | GET | Multiple answers |
| `/documents/{docId}/material-content-url` | GET | SAS-signed Azure Blob URI |
| `/ingestion/process` | POST | Submit ingestion workflow |
| `/ingestion/status/{caseId}` | GET | Poll ingestion status |
| `/queries` | GET | List queries (no caseId) |
| `/queries/{caseId}` | GET | List queries for case |
| `/queries` | POST | Upsert query definition |
| `/queries/{queryId}/{caseId}` | GET | Single query summary |
| `/queries/{queryId}/versions` | GET | Query version history |
| `/query-catalogue` | GET | Browse query catalogue |
| `/query-catalogue/{queryId}` | GET | Single catalogue entry |
| `/query-catalogue/{queryId}/label` | PATCH | Update label/order |

---

## Package map

```
uk.gov.hmcts.cp.cdk
├── controllers/          REST controllers + exception handler + accesscontrol/
├── services/             Business logic (Answer, Query, Document, Discovery, Ingestion)
├── domain/               JPA entities (22 classes) + enums
├── repo/                 JPA repositories (13) + helpers
├── jobmanager/           Task orchestration: caseflow/, queryflow/, hearing/, support/
├── scheduler/            IntradayDiscoveryScheduler + SchedulerProperties
├── clients/              External clients: rag/, hearing/, progression/, common/, config/
├── storage/              Azure Blob: StorageService, AzureBlobStorageService
├── config/               Spring config beans (JPA, ShedLock, OpenAPI, CORS, JobManager)
├── http/                 HTTP factory, CorrelationIdInterceptor, DebugLoggingInterceptor
├── filters/tracing/      TracingFilter (OTel)
└── util/                 TimeUtils, RequestUtils, TaskUtils
```
