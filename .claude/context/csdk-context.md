# CSDK Project Context

**cp-case-document-knowledge-service** — AI/RAG-powered answers for Crime Common Platform case documents.
Every answer must be cited, auditable, and free of PII. Classified OFFICIAL-SENSITIVE.

---

## Stack

- **Java 21 · Spring Boot 4.0.5 · Gradle 9** — base package `uk.gov.hmcts.cp.cdk`, port 8082, context path `/casedocumentknowledge-service`
- **PostgreSQL 16** + **Flyway** (migrations `V1000–V1010`, append-only)
- **Azure Blob Storage** (`azure-storage-blob` 12.32.0) — authenticated via **Managed Identity only** (`AzureIdentityConfig` → `AzureTokenService` → `ApimAuthHeaderService`)
- **ActiveMQ Artemis 2.31.2** — audit event publishing only (via `cp-audit-filter-springboot`); no `@JmsListener` in service code
- **ShedLock** (JDBC, `V1010`) — guards `IntradayDiscoveryScheduler`

---

## Key packages

| Package | Purpose |
|---------|---------|
| `controllers/` | REST API: `Answers`, `Document`, `Ingestion`, `Queries`, `QueryCatalogue` + `GlobalExceptionHandler` |
| `services/` | Business logic: answer generation, query management, document discovery, ingestion orchestration |
| `domain/` | 22 JPA entities: `Query`, `QueryVersion`, `CaseDocument`, `CaseQueryStatus`, answer variants, `DocumentVerificationTask`, `ScheduledIngestionRequest` |
| `repo/` | 13 JPA repositories |
| `jobmanager/` | Long-running task orchestration via Task Manager service: `caseflow/`, `queryflow/`, `hearing/` |
| `scheduler/` | `IntradayDiscoveryScheduler` — every 10 min, Mon–Fri 07:00–19:50, ShedLock-guarded |
| `clients/` | External integrations: `rag/` (AI), `hearing/`, `progression/`, `common/` (Azure auth + APIM) |
| `storage/` | `AzureBlobStorageService` — all blob operations go here |
| `filters/tracing/` | OpenTelemetry tracing filter |
| `http/` | `CorrelationIdInterceptor`, `DebugLoggingInterceptor`, `RestClientFactoryConfig` |

---

## External integrations

| Service | Client class | Auth | Timeout |
|---------|-------------|------|---------|
| RAG (AI) | `ApimDocumentIngestionClient`, `RagAnswerServiceImpl` | APIM / AAD token | 180 s read |
| Hearing API | `HearingClientImpl` | APIM / AAD token | 15 s read |
| Progression API | `ProgressionClientImpl` | APIM / AAD token | 15 s read |
| Azure Blob | `AzureBlobStorageService` | Managed Identity | — |

All APIM calls: `RestClientFactoryConfig` → `CorrelationIdInterceptor` → `ApimAuthHeaderService`.
**Never bypass this chain.**

---

## API surface

| Endpoint | Method |
|----------|--------|
| `/cases/{caseId}/queries/{queryId}/answers` | GET |
| `/cases/{caseId}/queries/{queryId}/answers/with-llm` | GET |
| `/cases/{caseId}/queries/{queryId}/answers/list` | GET |
| `/documents/{docId}/material-content-url` | GET |
| `/ingestion/process` | POST |
| `/ingestion/status/{caseId}` | GET |
| `/queries`, `/queries/{caseId}`, `/queries/{queryId}/versions` | GET |
| `/queries` | POST |
| `/query-catalogue`, `/query-catalogue/{queryId}` | GET |
| `/query-catalogue/{queryId}/label` | PATCH |

---

## Access control

- Framework: `cp-auth-rules-filter` (Drools, `acl/cdks-rules.drl`)
- All endpoints require `"AI search"` permission or System Users group
- User context header: `CJSCPPUID`
- Permission constant: `PermissionConstants.INTELLIGENCE_ACCESS`

---

## Test structure

| Layer | Source set | Frameworks |
|-------|------------|-----------|
| Unit | `src/test/` | JUnit 5, Mockito, AssertJ, Spring Boot Test |
| Integration | `src/integrationTest/` | REST Assured, WireMock, Docker Compose stack (Postgres, Artemis, Azurite, WireMock, App) |
| Contract | `src/pactVerificationTest/` | Pact (consumer-driven) |

`gradle integration` is **not optional** — `build` and `check` depend on it.

---

## Hard rules

1. **No PII / case content in logs, tests, or artefacts** — use synthetic data; Azurite seed and WireMock stubs must be non-real.
2. **Azure via Managed Identity only** — no connection strings, SAS tokens, or account keys anywhere.
3. **Flyway migrations are append-only** — never edit a shipped `V*.sql`; add the next version.
4. **Every answer must cite its source** — changes to RAG/answer flow must preserve the citation chain.
5. **JSON logging to stdout only** — `logback-spring.xml`; never log document content, answer text, or CJSCPPUID values.
6. **PMD + JaCoCo must pass** — do not lower thresholds.

---

## Build commands

```bash
./gradlew clean build          # full build + all tests
./gradlew test                 # unit tests only
./gradlew integration          # integration tests (requires Docker)
./gradlew pmdMain pmdTest      # static analysis
./gradlew jacocoTestReport     # coverage report
```
