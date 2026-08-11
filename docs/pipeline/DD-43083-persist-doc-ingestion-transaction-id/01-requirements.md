# Requirements: Persist RAG Document-Ingestion Transaction Id on `case_documents`

> **Stage 1 — Requirements** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43083**
> Ingestion-side sibling of **DD-43084** (`../DD-43084-persist-rag-transaction-id/`), which added
> `rag_transaction_id UUID NULL` to the four answer tables. Same class of gap, one step earlier in
> the pipeline. Column name and SQL type are deliberately left open for the Design stage — see
> OQ-002 / OQ-003; both are expected to be recorded in
> `adrs/DD-43083-persist-doc-ingestion-transaction-id.md`.

---

## Context

CDKS ingests a case document asynchronously. `RetrieveMaterialAndUploadTask`
(`jobmanager/caseflow/`) fetches the material download URL from Progression, calls the RAG
service's `initiateDocumentUpload(...)` (via `ApimDocumentIngestionClient` →
`DocumentIngestionInitiationApi`) and receives a `FileStorageLocationReturnedSuccessfully`
carrying a `storageUrl` and a `documentReference`. `documentReference` is the RAG-issued
correlation id for that ingestion transaction — the direct ingestion-side analogue of the answer
flow's `transactionId`. The task then copies the blob, updates the `case_documents` row via
`saveDocumentUploaded(...)` (`docName`, `blobUri`, `contentType`, `sizeBytes`, `uploadedAt`,
`ingestionPhase = UPLOADED`, `ingestionPhaseAt`), and forwards `documentReference` **only** through
Task Manager job data (`JobManagerKeys.CTX_DOC_REFERENCE_KEY = "documentReference"`) to
`CheckIngestionStatusForAllDefendantsTask`.

That downstream task uses the value solely to poll
`DocumentIngestionStatusApi.documentStatusByReference(documentReference)` and as a log correlation
value. Once ingestion resolves (`INGESTION_SUCCESS`, or `INGESTION_FAILED` / `INVALID_METADATA` /
`FILE_SIZE_OVER_LIMIT`), the value is discarded with the job data. `case_documents` (created in
`V1001`, entity `domain/CaseDocument.java`) has no column for it, so the ingestion transaction that
produced a given stored document is unrecoverable after the fact.

This is the same class of problem the CDKS hard rule "do not drop RAG response fields" already
covers for `doc_id`/`llm_input` (`.claude/context/cdks-context.md`), and that DD-43084 fixed on the
answer side: RAG-provenance data silently lost at the last step before persistence, undermining the
traceability CDKS is meant to guarantee for AI-assisted case document handling.

The fix has a natural single write point. `documentReference` becomes known at
`RetrieveMaterialAndUploadTask` line ~116, a few lines before the `saveDocumentUploaded(...)` call
at line ~125 that already updates the same row. No new upsert, no second transaction, and no new
repository method is required.

**Note on source:** this requirement is derived from the requester's own restatement of Jira
DD-43083 in conversation; the ticket itself was not fetched (no Jira/Atlassian tool access in this
session — see OQ-001). Treat the FR/AC below as the working scope until confirmed against the
ticket text.

### Type and naming: what the contract actually says (informs OQ-002 / OQ-003)

Verified against the consumed RAG contract (`api-cp-ai-rag:0.0.15`,
`openapi/ai-rag-service.openapi.yml`) and the generated model:

- The spec declares `documentReference` as `allOf: [$ref '#/components/schemas/uuid']`, where
  `uuid` is `type: string` with a UUID **regex pattern** (not `format: uuid`). The status endpoint
  `/document-upload/{documentReference}` documents a `400 — documentReference is not a valid uuid`.
  So the value is contractually UUID-shaped.
- Because the schema uses `pattern` rather than `format: uuid`, the generated Java type is
  `String`, not `java.util.UUID` (`FileStorageLocationReturnedSuccessfully.getDocumentReference()`,
  `DocumentIngestionStatusApi.documentStatusByReference(String)`). It carries `@NotNull` and
  `@Pattern` annotations, but response bodies are not bean-validated on the client path, so nothing
  in CDKS enforces or parses the shape today — it is handled purely as an opaque string.
- The answer-side `transactionId` uses the **same** `uuid` schema, and DD-43084 nonetheless stored
  it as a `UUID` column, parsing via `TaskUtils.parseUuidOrNull`. That precedent argues for `UUID`
  here; the counter-argument is that `parseUuidOrNull` silently yields `null` on a malformed value,
  which would discard exactly the provenance data this requirement exists to preserve.

The column's SQL type is therefore a real design decision with evidence on both sides, not a
given. It must be settled at Design stage with an explicit fallback story — see OQ-003 and AC-014.

---

## Functional Requirements

| ID | Requirement |
|----|-------------|
| FR-001 | `RetrieveMaterialAndUploadTask` persists the `documentReference` returned by `initiateDocumentUpload(...)` onto the `case_documents` row for that `doc_id`. |
| FR-002 | The value is written inside the existing `saveDocumentUploaded(...)` call — the same single `saveAndFlush` that already sets `docName`, `blobUri`, `contentType`, `sizeBytes`, `uploadedAt`, `ingestionPhase = UPLOADED`, `ingestionPhaseAt`. No new write, no second transaction, no new upsert flow, no new `CaseDocumentRepository` method. |
| FR-003 | `case_documents` gains **one** new nullable column for this value via a single append-only Flyway migration, `V1013` (`V1012` is consumed by DD-43084). Working name `doc_ingestion_tran_id`; final name (OQ-002) and SQL type (OQ-003) are Design-stage decisions and must be recorded in the ADR before implementation. |
| FR-004 | The `CaseDocument` JPA entity gains a mapped field for the new column, so the entity accurately reflects its table. This is the write path (unlike DD-43084, where the answer tables are written via raw `NamedParameterJdbcTemplate` SQL and the entity change was read-side only). |
| FR-005 | The value is written once, at the `UPLOADED` transition, and is never cleared or overwritten by later phase transitions. `CheckIngestionStatusForAllDefendantsTask.updateIngestionPhase(...)` moves the row to `INGESTED` / `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT` via the same entity — the new field must survive those saves unchanged. |
| FR-006 | Existing job-data threading is unchanged: `RetrieveMaterialAndUploadTask` still adds `CTX_DOC_REFERENCE_KEY` to the job data and `CheckIngestionStatusForAllDefendantsTask` still reads it from there to poll. This requirement adds a persistence sink; it does not replace the task-to-task hand-off. |
| FR-007 | No new failure mode. If RAG returns a null or blank `documentReference`, the new column is left `NULL` and the surrounding flow behaves exactly as it does today (a null value already fails inside the existing `try` block, because the job-data `add(...)` rejects a null, and the task retries). The added persistence must not itself throw, and must not turn a previously-succeeding upload into a failure. |
| FR-008 | No backfill. Rows created by `IdpcAvailabilityService.persistCaseDocument(...)` (phase `WAITING_FOR_UPLOAD`), rows whose upload never reached `saveDocumentUploaded(...)`, and every row written before this migration all show the new column `IS NULL`. |
| FR-009 | No API/contract change — persistence only, mirroring DD-43084/ADR-002. No controller, mapper, or response DTO exposes any `CaseDocument` field today (only `RetrieveMaterialAndUploadTask`, `services/DocumentService`, `services/IdpcAvailabilityService` and `CaseDocumentRepository` consume the entity; there is no `CaseDocumentMapper`). `version.cdk` is not bumped and `api-cp-crime-caseadmin-case-document-knowledge` is untouched. |
| FR-010 | No index is added on the new column. There is no query-by-reference read path in CDKS today, and the three existing `case_documents` indexes (`idx_cd_case_uploaded_desc`, `idx_cd_case_phase`, `idx_cd_phase`) are unaffected. See OQ-005 if a support lookup need is asserted. |

**Out of scope:** exposing the value over any API or adding a lookup-by-reference endpoint;
backfilling historic rows; refactoring `CheckIngestionStatusForAllDefendantsTask` to read the
reference from `case_documents` instead of job data (OQ-006); storing the `storageUrl` query-string
/ SAS portion of the upload response; changing ingestion retry, phase-transition, or failure
semantics; any change to `ApimDocumentIngestionClient`, `RagClientsConfig`, or the RAG contract
version; the answer-side `rag_transaction_id` columns delivered by DD-43084.

---

## Non-Functional Requirements

Trimmed to the NFRs that carry ticket-specific decision content. Migration governance, PMD/JaCoCo,
platform versions, and logging rules are covered once, generically, by CLAUDE.md's hard rules and
are not repeated here per requirement.

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-001 | Data protection | `documentReference` is an opaque, RAG-issued correlation string — not PII, not case content, not a court reference number. Both `RetrieveMaterialAndUploadTask` and `CheckIngestionStatusForAllDefendantsTask` already log it at INFO today, so persisting it introduces no new data-protection exposure. |
| NFR-002 | Migration safety | `ALTER TABLE case_documents ADD COLUMN ... NULL` is additive and metadata-only on PostgreSQL 16 — no table rewrite, no lock escalation — regardless of current row count. No existing column, constraint, index, or the `document_ingestion_phase_enum` type is touched. |
| NFR-003 | Backward compatibility | No change to any GET response shape or SQL read path. `IdpcAvailabilityService.persistCaseDocument(...)` and `DocumentService` keep compiling and behaving unchanged. The raw `INSERT INTO case_documents (...)` in `IngestionProcessByCaseHttpLiveTest` continues to work, precisely because the column is nullable. |
| NFR-004 | Testability | Unit coverage in `RetrieveMaterialAndUploadTaskTest` / `CheckIngestionStatusForAllDefendantsTaskTest`; `integrationTest` coverage asserting the column is populated end-to-end through the RAG WireMock stub. `gradle integration` passes against the compose stack. |

---

## Acceptance Criteria

**Persistence**
- AC-001: Given a `RETRIEVE_MATERIAL_AND_UPLOAD` execution where `initiateDocumentUpload(...)` returns a `documentReference`, when the task completes successfully, then the `case_documents` row for that `doc_id` holds that value in the new column — preserved exactly as received, with no truncation, trimming, case-folding, or re-formatting (subject only to the type decision in OQ-003).
- AC-002: The value is written in the **same** `saveAndFlush` as `docName`, `blobUri`, `contentType`, `sizeBytes`, `uploadedAt`, `ingestionPhase = UPLOADED` — verified by a unit test that captures the `CaseDocument` passed to `CaseDocumentRepository.saveAndFlush(...)` and asserts a single invocation carrying all of those fields plus the new one.
- AC-003: Given a row with the value persisted, when `CheckIngestionStatusForAllDefendantsTask` subsequently transitions the phase to `INGESTED`, `FAILED`, or `EXCEEDED_FILE_SIZE_LIMIT`, then the new column still holds the original value — not nulled, not changed.

**Null / absent handling**
- AC-004: Given RAG returns a null or blank `documentReference`, when the task runs, then the new persistence code throws no exception of its own and the task's existing outcome (retry via `ExecutionStatus.INPROGRESS`) is unchanged from current behaviour; any row written shows the new column `IS NULL`.
- AC-005: A row created by `IdpcAvailabilityService.persistCaseDocument(...)` in phase `WAITING_FOR_UPLOAD` shows the new column `IS NULL` until upload initiation completes for that document.
- AC-006: Rows that predate the `V1013` migration show the new column `IS NULL` — no error, no backfill attempted.

**No API surface change**
- AC-007: No controller, response DTO, or mapper changes; `version.cdk` is unchanged; `IngestionProcessByCaseHttpLiveTest` (and every other existing `*HttpLiveTest`) passes with its assertions unmodified.

**Migration**
- AC-008: `V1013__*.sql` adds exactly one nullable column to `case_documents`, plus a `COMMENT ON COLUMN` explaining the field and its nullability — no rename, no drop, no `NOT NULL`, no default, no data rewrite, no change to any other table — and is reviewed by `migration-reviewer` before merge.
- AC-009: Flyway migrates cleanly both on a fresh database and on a database already at `V1012` with existing `case_documents` rows; the `gradle integration` compose stack starts and the app reaches readiness.

**Tests and quality**
- AC-010: `RetrieveMaterialAndUploadTaskTest` and `CheckIngestionStatusForAllDefendantsTaskTest` compile and pass with any updated signatures.
- AC-011: An integration test asserts the new column is populated with the stubbed `documentReference` from `document_upload_to_generate_url.json` after the ingestion flow reaches `UPLOADED`, i.e. the value survives end-to-end and not just in a mocked unit.
- AC-012: `gradle clean build` (including `integration`) passes; PMD and JaCoCo are green at existing, unmodified thresholds; CodeQL and the secrets scanner are clean.
- AC-013: The diff introduces no PII, case content, court reference number, or `CJSCPPUID` into the migration, tests, or fixtures; WireMock/Azurite fixture values remain synthetic.
- AC-014: Whichever SQL type Design settles on (OQ-003), a `documentReference` that does not match the RAG contract's UUID pattern must neither (a) be silently discarded nor (b) fail the ingestion flow. The chosen behaviour is recorded in the ADR and covered by an explicit test.

---

## Candidate Sub-Stories (preview for Stage 3)

Indicative breakdown for the User Story stage to formalise; each will need its own Jira sub-ticket
before Test Specs (per the CLAUDE.md rule that every story has a linked ticket), mirroring how
DD-43084 was split into DD-43104 / DD-43105 / DD-43106.

1. **Story 1 — Schema: add the nullable column to `case_documents`.** One append-only Flyway
   migration `V1013__*.sql` adding the agreed column (name per OQ-002, type per OQ-003) with a
   column comment; routed through `migration-reviewer`. Covers FR-003, AC-008, AC-009.
2. **Story 2 — Entity and persistence: thread the value through and store it.** Add the mapped
   field to `CaseDocument`; pass `documentReference` from `initiateDocumentUpload(...)` into
   `saveDocumentUploaded(...)` in `RetrieveMaterialAndUploadTask`; single write, null-safe. Covers
   FR-001, FR-002, FR-004, FR-007, FR-008, AC-001, AC-002, AC-004, AC-005, AC-006, AC-014.
3. **Story 3 — No regression on the ingestion status path.** Confirm job-data threading is
   untouched and that the value survives every later phase transition performed by
   `CheckIngestionStatusForAllDefendantsTask`. Covers FR-005, FR-006, AC-003, AC-010. *May be
   folded into Story 2 if reviewers prefer — it is assertion-only, with no production change of
   its own beyond what Story 2 delivers.*
4. **Story 4 — Test coverage end-to-end.** Unit assertions on the captured entity plus an
   `integrationTest` assertion that the column is populated from the existing WireMock stub; keep
   PMD/JaCoCo green. Covers NFR-004, AC-011, AC-012, AC-013.

Explicitly **not** a story in this requirement: any API exposure of the field, a lookup-by-reference
endpoint, or a backfill job.

---

## Open Questions

- **OQ-001:** Jira ticket DD-43083 itself was not fetched in this session (no Jira/Atlassian tool
  access) — confirm the ticket's literal text matches this restated scope (persist-only, single
  column on `case_documents`, no API exposure) before moving past the Story stage. If the ticket
  asks for API exposure or a support-facing lookup, the FR-009 / FR-010 boundary needs revisiting
  first. — Owner: requester · Due: before Stage 3.
- **OQ-002 (column name — designer call):** the brief's working name is `doc_ingestion_tran_id`,
  echoing the requester's naming direction. Two counter-considerations for the Design review:
  (a) DD-43084 established `rag_transaction_id` as the convention for RAG correlation ids in this
  schema, so a `rag_`-prefixed name (e.g. `rag_ingestion_transaction_id`) would keep the two
  siblings visually consistent; (b) the upstream contract calls this field `documentReference`, not
  a transaction id at all, so `*_tran_id` diverges from the source vocabulary and a name like
  `rag_document_reference` would be the most literal. Settle at Design stage with the requester's
  designers and record the choice in the ADR. — Owner: requester's design reviewers · Due: Stage 2.
- **OQ-003 (SQL type — `TEXT` vs `UUID`):** evidence both ways, per the Context section above. For
  `UUID`: the RAG contract constrains the field to a UUID pattern, the status endpoint 400s on a
  non-UUID, and DD-43084 stored the same-schema `transactionId` as `UUID`. For `TEXT`: the
  generated Java type is `String`, nothing in CDKS parses or validates it, and `parseUuidOrNull`
  would silently null a malformed value — losing the exact provenance this ticket exists to keep.
  If `UUID` is chosen, the fallback must be specified (reject-and-retry? log-and-store-null?
  store in a shadow `TEXT` column?) and tested per AC-014. — Owner: requester's design reviewers ·
  Due: Stage 2, ADR required.
- **OQ-004:** Confirm no external consumer (BI/reporting job, data export, another service reading
  the CDKS database directly) needs advance notice of a new nullable column on `case_documents`.
  Nothing in this repo reads it outside `CaseDocumentRepository`, but `case_documents` may be
  queried from outside this codebase. — Owner: TBD · Due: before merge.
- **OQ-005:** Is there a support/ops need to look a document up *by* its ingestion reference? If
  so, FR-010 (no index) should be revisited — an index would be a separate, additive migration.
  No such read path exists in CDKS today. — Owner: TBD · Due: Stage 2.
- **OQ-006:** Should `CheckIngestionStatusForAllDefendantsTask` eventually read the reference from
  the persisted row rather than job data (which would let a replayed or resumed job recover it)?
  Deliberately out of scope here, but worth confirming it is not the ticket's actual driver — if it
  is, the scope grows beyond persistence. — Owner: requester · Due: before Stage 3.
- **OQ-007:** Confirm the new column simply inherits the retention/purge policy of the
  `case_documents` row it sits on, and carries no separate audit or retention obligation of its
  own. Assumed yes (it is a correlation id, not case data), but state it explicitly for the
  data-protection review. — Owner: TBD · Due: before merge.
