# Event Flow — Document Ingestion (CSDK)

*Demo artifact. Traced from source in `case-document-knowledge-service`. This is the kind of output the
`event-flow-mapper` agent produces; captured here so it can be reviewed and shared.*

**Scope:** two entry points both feed the same async task chain — (A) the HTTP `POST .../ingestion` endpoint (on-demand) and (B) the `IntradayDiscoveryScheduler` (intraday, cron-triggered). Both paths converge at task ① `GET_CASES_FOR_HEARING`.

---

## Entry points

### A — On-demand (HTTP-triggered)
`POST .../ingestion/start` → `IngestionController` → `JobManagerService` → enqueues ① `GET_CASES_FOR_HEARING`.
Returns `202 ACCEPTED` immediately. See the flow diagram below.

### B — Intraday discovery (scheduler-triggered)
`IntradayDiscoveryScheduler` fires on cron `0 0/10 7-19 * * MON-FRI` (every 10 min, Mon–Fri, 07:00–19:50).
Guarded by **ShedLock** (`lockAtLeastFor=PT8M`, `lockAtMostFor=PT9M`) so only one instance runs cluster-wide.
Calls `DiscoveryService.runIntradayDiscovery()`:
1. Reads `scheduled_ingestion_request` WHERE `hearing_date = today`.
2. For each row, builds a `jobData` JSON (same shape as the HTTP path: `cppuid`, `requestId`, `courtCentreId`, `roomId`, `date`).
3. Calls `JobManagerService.dispatchCaseDocumentIngestionTasks(jobData)` — enters the same task chain at ①.

**Purpose:** catches late-arriving IDPCs, schedule changes, and late list additions during court hours.

---

## Flow diagram

```
ENTRY A — HTTP (sync)                ASYNC TASK CHAIN (CPP taskmanager / ExecutionService)
─────────────────────                ─────────────────────────────────────────────────────
POST .../ingestion/start
  │ requires CJS-CPPUID header
  ▼
IngestionController                  ① GET_CASES_FOR_HEARING            (hearing/GetCasesForHearingTask)
  .startIngestionProcess()                       │ enqueues
  │ → 202 ACCEPTED (phase=STARTED)               ▼
  ▼                                  ② CHECK_CASE_ELIGIBILITY           (caseflow/CheckCaseEligibilityTask)
IngestionProcessor (interface)                   │ enqueues
  └─ JobManagerService                           ▼
       builds jobData{cppuid,        ③ CHECK_IDPC_AVAILABILITY[_ALL_DEFENDANTS]
       requestId,courtCentreId,                  │ enqueues
       roomId,date}                              ▼
       executor.executeWith(        ④ RETRIEVE_FROM_MATERIAL / RETRIEVE_MATERIAL_AND_UPLOAD
         GET_CASES_FOR_HEARING) ─────►              │  → ApimDocumentIngestionClient.initiateDocumentUpload()
                                                    │     → APIM → RAG service (upload)    [Managed Identity auth]
                                                    │ enqueues
                                                    ▼
                                     ⑤ CHECK_DOCUMENT_INGESTION_STATUS  (caseflow/CheckDocumentIngestionStatusTask)
                                          │  polls ApimDocumentIngestionStatusApi.documentStatusByReference()
                                          │     ⇄ APIM → RAG status         [Managed Identity auth]
                                          │
                                          ├─ INGESTION_SUCCESS → CaseDocument.ingestionPhase = INGESTED (saveAndFlush)
                                          │        └─ for each resolved Query → enqueue ⑥ GENERATE_ANSWER_FOR_QUERY
                                          ├─ INGESTION_FAILED  → CaseDocument.ingestionPhase = FAILED
                                          └─ otherwise         → retry w/ backoff (JobManagerRetryProperties)
                                                                        │
                                                                        ▼
                                     ⑥ GENERATE_ANSWER_FOR_QUERY ─► ⑦ CHECK_STATUS_OF_ANSWER_GENERATION (poll loop)

STATUS READ PATH (sync, separate):
GET .../ingestion/{caseId} → IngestionController.getIngestionStatus()
   → IngestionService.getStatus() → IngestionStatusViewRepository.findByCaseId()
   → phase: INGESTED | FAILED | NOT_FOUND
```

---

## Stage-by-stage

| # | Task (`@Task` name) | Class | Does | Enqueues next |
|---|---------------------|-------|------|---------------|
| — | *(HTTP entry)* | `IngestionController.startIngestionProcess` | Requires `CJS-CPPUID` header; returns `202 ACCEPTED`, phase `STARTED` | delegates to processor |
| — | *(orchestration entry)* | `JobManagerService` (`implements IngestionProcessor`) | Builds `jobData{cppuid, requestId, courtCentreId, roomId, date}`; submits first task | `GET_CASES_FOR_HEARING` |
| ① | `GET_CASES_FOR_HEARING` | `hearing/GetCasesForHearingTask` | Resolves cases for the hearing | `CHECK_CASE_ELIGIBILITY` |
| ② | `CHECK_CASE_ELIGIBILITY` | `caseflow/CheckCaseEligibilityTask` | Eligibility check | `CHECK_IDPC_AVAILABILITY[_ALL_DEFENDANTS]` |
| ③ | `CHECK_IDPC_AVAILABILITY` / `…_ALL_DEFENDANTS` | `caseflow/CheckIdpcAvailability*Task` | IDPC availability | `RETRIEVE_FROM_MATERIAL` / `RETRIEVE_MATERIAL_AND_UPLOAD` |
| ④ | `RETRIEVE_FROM_MATERIAL` / `RETRIEVE_MATERIAL_AND_UPLOAD` | `caseflow/RetrieveFromMaterialAndUploadTask` / `RetrieveMaterialAndUploadTask` | Fetches material; **uploads to RAG** via `ApimDocumentIngestionClient.initiateDocumentUpload()` → APIM | `CHECK_DOCUMENT_INGESTION_STATUS` / `CHECK_INGESTION_STATUS_FOR_DOCUMENT` |
| ⑤ | `CHECK_DOCUMENT_INGESTION_STATUS` | `caseflow/CheckDocumentIngestionStatusTask` | Polls `documentStatusByReference()` (APIM RAG status). On success sets `INGESTED` and fans out per query; on failure sets `FAILED`; else retries | `GENERATE_ANSWER_FOR_QUERY` (per query) |
| ⑥ | `GENERATE_ANSWER_FOR_QUERY` | `queryflow/GenerateAnswerForQueryTask` | Kicks off answer generation | `CHECK_STATUS_OF_ANSWER_GENERATION` |
| ⑦ | `CHECK_STATUS_OF_ANSWER_GENERATION` | `queryflow/CheckStatusOfAnswerGenerationTask` | Polls answer-gen status (may re-enqueue ⑥) | — |

*Scope variants:* single-document vs. case-wide use parallel tasks — `CHECK_INGESTION_STATUS_FOR_DOCUMENT`,
`CHECK_ALL_DOCUMENTS_INGESTION_STATUS`, `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS`.

---

## Key observations

- **Fire-and-forget entry.** The controller returns `202 ACCEPTED` immediately; all real work runs as an
  **async task chain** orchestrated by the CPP `taskmanager` (`ExecutionService.executeWith`, each `@Task`
  enqueues the next). Artemis (in the compose / integration stack) is the likely transport.
- **Two outbound RAG calls via APIM**, both authenticated with **Managed Identity / APIM headers**
  (`ApimAuthHeaderService`) — no keys or connection strings, per the CSDK `CLAUDE.md` rule:
  `ApimDocumentIngestionClient` (upload) and `ApimDocumentIngestionStatusClient` (status).
- **State machine** on `CaseDocument.ingestionPhase` (`…→ INGESTED / FAILED`), surfaced read-side via
  `IngestionStatusViewRepository` and the `GET .../ingestion/{caseId}` endpoint.
- **Success fans out** into per-query answer generation — ingestion hands off to the query/answer flow.
- **Retries with backoff** are configured via `JobManagerRetryProperties` (e.g. `getVerifyDocumentStatus`).
- **Two entry points, one task chain.** The HTTP `POST /ingestions/start` (on-demand) and the `IntradayDiscoveryScheduler` (cron, every 10 min Mon–Fri during court hours) both call `JobManagerService.dispatchCaseDocumentIngestionTasks()` and enter the same `GET_CASES_FOR_HEARING` task chain. The scheduler path reads from the `scheduled_ingestion_request` table (`V1009`), guarded by ShedLock (`V1010`) so exactly one node fires cluster-wide.

---

## Source references

- `controllers/IngestionController.java` — HTTP entry + status read
- `services/JobManagerService.java` — `IngestionProcessor` impl; submits first task
- `services/IngestionService.java` + `repo/IngestionStatusViewRepository.java` — status read path
- `jobmanager/caseflow/*` — the task chain (eligibility → IDPC → retrieve/upload → status)
- `jobmanager/queryflow/*` — answer-generation hand-off
- `clients/rag/ApimDocumentIngestionClient.java`, `ApimDocumentIngestionStatusClient.java` — APIM/RAG calls
- `jobmanager/TaskNames.java` — task-name constants
- `domain/DocumentIngestionPhase.java` — phase enum
- `scheduler/IntradayDiscoveryScheduler.java` — cron-triggered intraday discovery
- `services/DiscoveryService.java` — reads `scheduled_ingestion_request`, dispatches tasks
- `domain/ScheduledIngestionRequest.java` + `repo/ScheduledIngestionRequestRepository.java` — persistence
- `config/ShedLockConfig.java` — PROXY_METHOD ShedLock configuration
- `src/main/resources/db/migration/V1009__case_documents_scheduled_ingestion_request.sql` — scheduler table
- `src/main/resources/db/migration/V1010__create_shedlock_table.sql` — ShedLock table
