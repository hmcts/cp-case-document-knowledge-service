# CSDK Project Context

**cp-case-document-knowledge-service** — persists and surfaces AI/RAG-generated answers for Crime Common Platform case documents. The service orchestrates document ingestion into the RAG pipeline, stores the responses it receives, and serves them back via REST API. Answer generation and citation production are the responsibility of the upstream RAG service; CSDK must not drop or alter RAG response fields (e.g. `doc_id`, `llm_input`) when persisting or mapping. Classified OFFICIAL-SENSITIVE; no PII in data, logs, or artefacts.

---

## Stack

- **Java 25 · Spring Boot 4.0.5 · Gradle 9** — base package `uk.gov.hmcts.cp.cdk`, port 8082, context path `/casedocumentknowledge-service`
- **PostgreSQL 16** + **Flyway** (migrations `V1000–V1011`, append-only)
- **Azure Blob Storage** (`azure-storage-blob` 12.32.0) — authenticated via **Managed Identity only** (`AzureIdentityConfig` → `AzureTokenService` → `ApimAuthHeaderService`)
- **ActiveMQ Artemis 2.31.2** — audit event publishing only (via `cp-audit-filter-springboot`); no `@JmsListener` in service code
- **ShedLock** (JDBC, `V1010`) — guards `IntradayDiscoveryScheduler`

---

## Key packages

| Package | Purpose |
|---------|---------|
| `controllers/` | REST API: `Answers`, `Document`, `Ingestion`, `Queries`, `QueryCatalogue`, `DiscoveryScheduler` + `GlobalExceptionHandler` |
| `services/` | Business logic: answer generation, query management, document discovery, ingestion orchestration, discovery scheduler configuration |
| `domain/` | 23 JPA entities: `Query`, `QueryVersion`, `CaseDocument`, `CaseQueryStatus`, answer variants, `DocumentVerificationTask`, `ScheduledIngestionRequest`, `DiscoverySchedulerConfiguration` |
| `repo/` | 14 JPA repositories |
| `jobmanager/` | Long-running task orchestration via Task Manager service: `caseflow/` (5 multi-defendant tasks), `queryflow/`, `hearing/` |
| `scheduler/` | `IntradayDiscoveryScheduler` — every 10 min, Mon–Fri 07:00–19:50, ShedLock-guarded |
| `clients/` | External integrations: `rag/` (AI), `hearing/`, `progression/`, `common/` (Azure auth + APIM) |
| `storage/` | `AzureBlobStorageService` — all blob operations go here |
| `filters/tracing/` | OpenTelemetry tracing filter |
| `http/` | `CorrelationIdInterceptor`, `DebugLoggingInterceptor`, `RestClientFactoryConfig` |

---

## External integrations

| Service | Client class | Auth | Timeout |
|---------|-------------|------|---------|
| RAG (AI) | `ApimDocumentIngestionClient`, `ApimDocumentIngestionStatusClient`, `RagAnswerServiceImpl`, `RagAnswerAsyncServiceImpl` | APIM / AAD token | 180 s read |
| Hearing API | `HearingClientImpl` | APIM / AAD token | 15 s read |
| Progression API | `ProgressionClientImpl` | APIM / AAD token | 15 s read |
| Azure Blob | `AzureBlobStorageService` | Managed Identity | — |

All APIM calls: `RestClientFactoryConfig` → `CorrelationIdInterceptor` → `ApimAuthHeaderService`.
**Never bypass this chain.**

---

## API surface

| Endpoint | Method |
|----------|--------|
| `/cases/{caseId}/queries/{queryId}/answers/with-llm` | GET |
| `/cases/{caseId}/queries/{queryId}/answers/list` | GET |
| `/documents/{docId}/material-content-url` | GET |
| `/ingestions/start` | POST |
| `/ingestions/start-by-case` | POST |
| `/ingestions/status` | GET |
| `/queries`, `/queries/{caseId}`, `/queries/{queryId}/versions` | GET |
| `/queries` | POST |
| `/query-catalogue`, `/query-catalogue/{queryId}` | GET |
| `/query-catalogue/{queryId}/label` | PATCH |
| `/discovery-scheduler/configurations` | POST |

`/ingestions/start` (scheduled, multi-case via `courtCentreId`/`roomId`/`date`) is fire-and-forget:
it dispatches `GET_CASES_FOR_HEARING`, which dispatches `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS`
directly per case, and returns `202 ACCEPTED` immediately. `/ingestions/start-by-case`
(manual, single `caseId` — triggered by the "Process IDPC" button on the AI Search page) is
synchronous: `IngestionProcessorByCaseService` calls `IdpcAvailabilityService` inline on the request
thread and only returns `200 OK` once the outcome is known (`STARTED` if a newer IDPC was found and
the remaining workflow was dispatched, `NOT_REQUIRED` if not — this also covers a case that doesn't
exist or has no defendants, since the availability check simply finds nothing to ingest either way —
`FAILED` on error). The scheduled flow's `CheckIdpcAvailabilityAllDefendantsTask` JobManager task
calls the same service, and both entry points build their `RETRIEVE_MATERIAL_AND_UPLOAD` job data via
the shared `RetrieveMaterialAndUploadJobDataService` — so the IDPC-availability rule and its
job-data shape are each defined exactly once and reused by both entry points. There is no separate
case-eligibility step or task. The manual flow's JobManager tasks run at `JobPriority.HIGH`
(`jobmanager/support/JobPriority`); the scheduled flow is unaffected and stays at the task-manager
default priority.

---

## Access control

- Framework: `cp-auth-rules-filter` (Drools, `acl/cdks-rules.drl`)
- Most endpoints require `"AI search"` permission or System Users group
- Exception: `/discovery-scheduler/configurations` (action `casedocumentknowledge-service.discovery-scheduler-configuration`) is **System Users group only** — no `"AI search"` fallback, since it's a backend config write, not an end-user action
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
3. **Flyway migrations are append-only** — never edit a shipped `V*.sql`; add the next version. Current highest: `V1011`; next is `V1012`.
4. **Do not drop RAG response fields** — changes to the ingestion or answer-serving flow must preserve all fields returned by the RAG service (e.g. `doc_id`, `llm_input`). Citation production is upstream's responsibility; CSDK's responsibility is not to lose that data.
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
