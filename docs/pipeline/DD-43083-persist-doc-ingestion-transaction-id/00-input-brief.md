# 00 — Input Brief

> Raw, unstructured input as given by the requester. Jira ticket: **DD-43083**
> (https://tools.hmcts.net/jira/browse/DD-43083).

## Brief (verbatim, lightly reformatted from conversation)

As per DD-43083: we need to persist the RAG transaction id for **document ingestion** in the
`case_documents` table. We already store document reference, blob link, etc., but it would be
better to also store the RAG transaction id for document ingestion. We recently implemented a RAG
transaction id for **answer generation** (DD-43084) — use a similarly meaningful column name here,
something in the spirit of `doc_ingestion_tran_id`.

Requested sequence: produce the Requirements stage document first, with the requirement broken
down into sub-stories, following the HMCTS SDLC orchestrator plugin pipeline. The requester will
then review the Design stage with their own designers before stories are formally created and
implementation begins. A Test Specs step must remain part of the plan.

## Source

Jira ticket DD-43083 (title/description not fetched — no Jira/Atlassian MCP tool access in this
session; the brief above is the requester's own restatement of the ticket in conversation). The
ticket's literal text should be verified before this requirement is taken to the Story stage — see
Open Question OQ-001 in `01-requirements.md`.

## Ground truth gathered from the codebase before drafting requirements

- **This is the ingestion-side sibling of DD-43084** (`../DD-43084-persist-rag-transaction-id/`),
  which added `rag_transaction_id UUID NULL` to the four answer tables. The same class of gap
  exists one step earlier in the pipeline, on document ingestion rather than answer generation.
- `RetrieveMaterialAndUploadTask` (`jobmanager/caseflow/RetrieveMaterialAndUploadTask.java`) calls
  `DocumentIngestionInitiationApi.initiateDocumentUpload(...)` (RAG service, via
  `ApimDocumentIngestionClient`) and gets back a `FileStorageLocationReturnedSuccessfully` with
  `storageUrl` and `documentReference`. `documentReference` is the RAG-issued correlation id for
  this ingestion transaction — the direct ingestion-side analogue of the answer flow's
  `transactionId`.
- That task forwards `documentReference` only via Task Manager job data
  (`JobManagerKeys.CTX_DOC_REFERENCE_KEY = "documentReference"`) to the next task,
  `CheckIngestionStatusForAllDefendantsTask`, which uses it solely to poll
  `DocumentIngestionStatusApi.documentStatusByReference(documentReference)` and as a log
  correlation value. Once ingestion status resolves (`INGESTION_SUCCESS` or a failure status), the
  value is dropped — never written onto the `CaseDocument` row it correlates to.
- `case_documents` (`V1001__case_documents_ai_schema.sql`, entity `domain/CaseDocument.java`) has
  no column for this today. The row for a given `doc_id` is created earlier in the flow (before
  `RetrieveMaterialAndUploadTask` runs) and is next updated by
  `RetrieveMaterialAndUploadTask.saveDocumentUploaded(...)` (sets `docName`, `blobUri`,
  `contentType`, `sizeBytes`, `uploadedAt`, `ingestionPhase=UPLOADED`) — this is the natural place
  to also persist the new column, since `documentReference` becomes known in the same method
  (`initiateDocumentUpload(...)`, line ~116) just before that save call.
- **Type note (differs from the DD-43084 precedent):** the answer-flow `transactionId` is parsed
  and stored as `UUID` (`TaskUtils.parseUuidOrNull`) end-to-end. `documentReference` here is typed
  `String` throughout the generated RAG API model
  (`FileStorageLocationReturnedSuccessfully.documentReference`,
  `DocumentIngestionStatusApi.documentStatusByReference(String)`) and is never parsed/validated as
  a UUID anywhere in this codebase — it is treated as an opaque correlation string. The new
  column's SQL type is therefore an open design decision (`TEXT` vs `UUID`), not a given — see
  OQ-002 / ADR candidate in Design stage.
- This is the same category of problem the CDKS hard rule "do not drop RAG response fields" names
  for `doc_id`/`llm_input` (`.claude/context/cdks-context.md`) and that DD-43084 already fixed on
  the answer side — `documentReference` is RAG-provenance data being silently discarded before the
  last persistence step on the ingestion side.
- Next available Flyway version is `V1013` (`V1012` was consumed by DD-43084's
  `rag_transaction_id` migration on the answer tables).
- No controller, mapper, or API response currently exposes any `CaseDocument` field externally
  (`grep` found only `RetrieveMaterialAndUploadTask`, `services/DocumentService`,
  `services/IdpcAvailabilityService` as consumers of the entity, and no `CaseDocumentMapper`
  exists) — so, mirroring DD-43084/ADR-002, this looks like a persist-only change with no API
  surface impact, pending confirmation at Design stage.
