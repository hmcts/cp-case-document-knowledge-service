# Design: Persist the RAG Document-Ingestion Reference on `case_documents`

> **Stage 2 — Architecture & Design** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43083** · Requirements: [`01-requirements.md`](./01-requirements.md) · ADRs: [`adrs/DD-43083-persist-doc-ingestion-transaction-id.md`](../adrs/DD-43083-persist-doc-ingestion-transaction-id.md)
> Add `rag_document_reference TEXT NULL` to `case_documents` (`V1013`); persist the
> `documentReference` that `RetrieveMaterialAndUploadTask` already receives from
> `initiateDocumentUpload(...)` inside the existing `saveDocumentUploaded(...)` write.
> No API/contract change, no new endpoint, no ACL change, no new repository method, no index.
>
> **Two Stage 1 open questions are resolved here and recorded as ADRs — both Accepted, confirmed
> by the requester after design review on 2026-08-11:**
> **OQ-002 (column name) → ADR-001: `rag_document_reference`** (not `doc_ingestion_tran_id`).
> **OQ-003 (SQL type) → ADR-002: `TEXT`, stored verbatim, no parse, no CHECK** (diverging from
> DD-43084's `UUID` for reasons set out in the ADR). §1–§4 below are locked design, not proposals.

---

## Detailed Design

### 1. Migration (this repo — no external contract dependency)

**File:** `src/main/resources/db/migration/V1013__add_rag_document_reference_to_case_documents.sql`
(next version after `V1012`, which DD-43084 consumed).

```sql
-- ----------------------------------------------------------------------------
-- Persist the RAG-issued documentReference for the document-ingestion upload
-- transaction that produced this row (ingestion-side analogue of the answer
-- tables' rag_transaction_id, added in V1012).
-- Nullable, additive: rows written before this migration keep NULL, no backfill.
-- Stored verbatim as TEXT — no parse, no shape CHECK — see ADR-002 (DD-43083).
-- ----------------------------------------------------------------------------
ALTER TABLE case_documents
    ADD COLUMN IF NOT EXISTS rag_document_reference TEXT NULL;
COMMENT ON COLUMN case_documents.rag_document_reference IS
'RAG-issued documentReference returned by POST /document-upload — the ingestion-transaction correlation id for this document (ingestion-side analogue of answers.rag_transaction_id). Stored verbatim as received; nullable — NULL for rows in WAITING_FOR_UPLOAD, for uploads that never reached the UPLOADED transition, and for rows written before this column existed (not backfilled).';
```

- Plain `TEXT NULL` — no `NOT NULL`, no `DEFAULT`, no `CHECK`, no index. `ADD COLUMN` of a
  nullable column with no default is metadata-only on PostgreSQL 16 (no table rewrite, no lock
  escalation), matching NFR-003.
- **No `CHECK` constraint deliberately.** The table does have a shape check on a comparable opaque
  string — `cd_sha256_shape CHECK (sha256_hex IS NULL OR sha256_hex ~ '^[0-9a-fA-F]{64}$')`
  (`V1001__case_documents_ai_schema.sql`) — and it would be easy to add the RAG UUID pattern here
  by analogy. It is rejected: a failing CHECK raises on `saveAndFlush`, which would turn a
  malformed-but-otherwise-successful upload into a task failure and violate FR-007 ("must not turn
  a previously-succeeding upload into a failure"). Shape validation is the RAG contract's
  responsibility, not CDKS's storage layer's — see ADR-002.
- **No index** (FR-010). No lookup-by-reference read path exists in CDKS; the three existing
  indexes (`idx_cd_case_uploaded_desc`, `idx_cd_case_phase`, `idx_cd_phase`) are untouched. A
  btree index on a `TEXT` column is an independent additive migration later if OQ-005 turns out to
  have a real ops need.
- No existing column, constraint (`cd_blob_uri_not_blank`, `cd_source_not_blank`,
  `cd_size_nonneg`, `cd_sha256_shape`), index, view (`v_case_ingestion_status`), FK
  (`fk_cqs_doc`, `fk_cllda_doc`, `fk_def_doc` all reference `case_documents.doc_id`) or the
  `document_ingestion_phase_enum` type is touched.
- Route through `migration-reviewer` per CLAUDE.md's hard rule for any `db/migration` change
  (AC-008, NFR-006). Shipped `V1000`–`V1012` are not edited.

### 2. Files touched

| File | Change |
|---|---|
| `db/migration/V1013__add_rag_document_reference_to_case_documents.sql` *(new)* | Adds one nullable `TEXT` column + `COMMENT ON COLUMN`. |
| `domain/CaseDocument.java` | New `@Column(name = "rag_document_reference") private String ragDocumentReference;`. |
| `jobmanager/caseflow/RetrieveMaterialAndUploadTask.java` | Extract `documentReference` into a local (line ~116); pass it into `saveDocumentUploaded(...)` (line ~125); set it on the entity in `saveDocumentUploaded(...)` (lines ~227–236). |

**No new constant is required.** The job-data key constant already exists
(`jobmanager/support/JobManagerKeys.java:7` — `CTX_DOC_REFERENCE_KEY = "documentReference"`) and is
unchanged; the column name lives only in the entity's `@Column` annotation, matching how every
other `CaseDocument` column is declared. No `TaskUtils` helper is needed either — unlike DD-43084,
there is no parse step (ADR-002) and no `MapSqlParameterSource` on this path.

**Not changed:**
- `repo/CaseDocumentRepository.java` — **confirmed no change needed.** It is a
  `JpaRepository<CaseDocument, UUID>`; `saveAndFlush(...)` and `findById(...)` are inherited and
  persist/hydrate whatever the entity maps. Its two `@Query(nativeQuery = true)`
  `findSupersededDocuments(...)` methods select `distinct(cd.doc_id)` only, so neither is affected
  by a new column, and `findFirstByCaseIdOrderByUploadedAtDesc` is a derived query over unchanged
  fields.
- `jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTask.java` — see §5; the value survives
  its writes with no code change.
- `services/IdpcAvailabilityService.java` (`persistCaseDocument(...)`, lines ~109–122) — leaves the
  new field unset, so the row is created with `rag_document_reference IS NULL` in phase
  `WAITING_FOR_UPLOAD` (AC-005). No compile or behaviour change.
- `services/DocumentService.java`, every controller, every mapper, every OpenAPI model,
  `version.cdk`, `acl/cdks-rules.drl`, `resources/acl/*`, any existing migration. There is no
  `CaseDocumentMapper` and no response DTO exposes a `CaseDocument` field, so FR-009 holds with
  zero edits (AC-007).

### 3. Entity change (`CaseDocument`)

One field, appended after `createdAt` (line ~85) to keep the declaration order roughly
column-order:

```java
@Column(name = "rag_document_reference")
private String ragDocumentReference;
```

- Lombok `@Getter`/`@Setter` on the class generate `getRagDocumentReference()` /
  `setRagDocumentReference(String)` — no hand-written accessors, consistent with every other field.
- `String` → `TEXT` is the mapping the app already uses for `source`, `doc_name`, `blob_uri`,
  `content_type` and `sha256_hex`, and it satisfies `spring.jpa.hibernate.ddl-auto: validate`
  (`application-datasource.yml:22`) without a `columnDefinition` override. This is a hard
  constraint on the type decision, not a preference: Hibernate's startup schema validation
  compares column types, so a `String` field over a Postgres `uuid` column fails validation at
  boot — a `UUID` column would force the Java field (and therefore the whole call path) to
  `java.util.UUID` and reintroduce the parse step ADR-002 rejects.
- **Known consequence:** the class-level `@EqualsAndHashCode` has no exclusions, so the new field
  joins `equals`/`hashCode`. Left as-is for consistency with all 14 existing fields; nothing in
  `src/test/` or `src/integrationTest/` compares whole `CaseDocument` instances by equality (the
  existing unit test asserts captured *fields*, see §Testing), so this is inert today. Adding
  `@EqualsAndHashCode.Exclude` for just this field would be an inconsistent special case; fixing
  the broader JPA-entity-equality smell is out of scope for this ticket.

### 4. `RetrieveMaterialAndUploadTask` — threading and persisting the value

`documentReference` is already in hand at line ~116, nine lines before the save at ~124–125. Three
small edits, all inside the existing `try` block, no new call, no new transaction:

```java
final FileStorageLocationReturnedSuccessfully fileStorageLocation =
        initiateDocumentUpload(documentId, materialName, documentMetadata, supersededDocumentList);
final String documentReference = fileStorageLocation.getDocumentReference();   // (1) new local
log.info("downloadUrl generated: {}, destinationUrl: {} ", downloadUrl, fileStorageLocation.getStorageUrl());

// ... blob copy unchanged ...

caseDocumentRepository.findById(documentId).ifPresent(doc ->
        saveDocumentUploaded(doc, blobName, blobUrl, sizeBytes, documentReference));   // (2) pass it in

// ... unchanged: still forwarded through job data to the next task (FR-006)
updatedJobData.add(CTX_DOC_REFERENCE_KEY, documentReference);   // (3) reuse the local, same value
```

Edit (3) is a readability-only substitution — `updatedJobData.add(CTX_DOC_REFERENCE_KEY, ...)`
receives exactly the same expression value as today, including the same
`NullPointerException`-on-null behaviour from `JsonObjectBuilder.add(String, String)` (see §6).
The local is effectively final, so the lambda capture in (2) compiles.

The write itself:

```java
private void saveDocumentUploaded(final CaseDocument doc, final String blobName, final String blobUrl,
                                  final long sizeBytes, final String documentReference) {
    doc.setDocName(blobName);
    doc.setBlobUri(blobUrl);
    doc.setContentType(uploadProperties.contentType());
    doc.setSizeBytes(sizeBytes);
    doc.setUploadedAt(utcNow());
    doc.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
    doc.setIngestionPhaseAt(utcNow());
    doc.setRagDocumentReference(isBlank(documentReference) ? null : documentReference);
    caseDocumentRepository.saveAndFlush(doc);
}
```

- **One `saveAndFlush`, unchanged** — the new field rides the same single write as `docName`,
  `blobUri`, `contentType`, `sizeBytes`, `uploadedAt`, `ingestionPhase = UPLOADED`,
  `ingestionPhaseAt` (FR-002, AC-002).
- **Stored verbatim.** No trim, no case-fold, no `UUID.fromString`, no `TaskUtils.normalise(...)`
  truncation, no re-formatting (AC-001). The only mapping applied is blank → `NULL`, so that
  `rag_document_reference IS NULL` is the single, unambiguous predicate for "no reference
  recorded" across AC-004/AC-005/AC-006 — a zero-length string carries no provenance and would
  just create a second "absent" representation.
- `isBlank` is `io.micrometer.common.util.StringUtils.isBlank`, already statically imported in this
  file (line 3); it is null-safe, so no separate null guard is needed.
- **Fifth parameter is fine for PMD.** `.github/pmd-ruleset.xml` includes only the `bestpractices`,
  `codestyle`, `errorprone`, `performance` and `security` categories — the `design` category (which
  owns `ExcessiveParameterList`, default threshold 10) is not enabled, so NFR-007's contingency
  does not bite. An explicit `String documentReference` parameter is preferred over passing
  `FileStorageLocationReturnedSuccessfully` into the persistence helper, which would couple a
  private DB-write method to a generated RAG API model. If this parameter list grows again, the
  right refactor is a small local record (e.g. `UploadedBlob(blobName, blobUrl, sizeBytes)`), not a
  PMD suppression.

### 5. Survival across later phase transitions and task retries (FR-005, FR-006)

`CheckIngestionStatusForAllDefendantsTask.updateIngestionPhase(...)` (lines ~239–245) re-reads the
row and mutates only the two phase fields before saving:

```java
caseDocumentRepository.findById(documentId).ifPresent(doc -> {
    doc.setIngestionPhase(phase);
    doc.setIngestionPhaseAt(utcNow());
    caseDocumentRepository.saveAndFlush(doc);
});
```

Because the entity is hydrated from the row (not constructed fresh), `ragDocumentReference` is
loaded and written back unchanged for every transition to `INGESTED`, `FAILED` and
`EXCEEDED_FILE_SIZE_LIMIT` (AC-003). **No change is required in that task**, and its job-data read
of `CTX_DOC_REFERENCE_KEY` (line ~79) and status polling are untouched (FR-006).

**One clarification on FR-005's "written once, never overwritten".** That holds for *phase
transitions*, which is what FR-005 is about. It does not hold — and should not — for a **retry of
`RETRIEVE_MATERIAL_AND_UPLOAD` itself**: a retry calls `initiateDocumentUpload(...)` again, gets a
new `documentReference`, and overwrites the column. That is correct behaviour: the stored reference
must identify the ingestion transaction that produced the blob currently recorded in `blob_uri`,
and the retry has just replaced it. Deliberately **not** using a write-once guard (e.g.
`COALESCE`-style "only set if currently null"), which would leave the row pointing at a superseded,
abandoned ingestion transaction. `CaseDocument` has no `@Version` field, so this is plain
last-write-wins, consistent with how every other field on this row already behaves. Worth an
explicit assertion at Test Specs stage so the semantics are pinned rather than incidental.

### 6. Error handling

No new error path, and no new `try`/`catch`.

| Input from RAG | Behaviour |
|---|---|
| Valid UUID-shaped `documentReference` | Stored verbatim; flow unchanged. |
| Non-UUID-shaped, non-blank (e.g. `"not-a-uuid"`) | **Stored verbatim.** No exception, no null, no truncation. The same value is then forwarded to job data and later passed to `documentStatusByReference(...)`, where RAG's documented `400 — documentReference is not a valid uuid` is handled by `CheckIngestionStatusForAllDefendantsTask`'s existing error handling exactly as it would be today. CDKS records what it was given; validating it is the contract owner's job. This is the AC-014 behaviour (ADR-002). |
| `null` or blank | Column left `NULL`. `saveDocumentUploaded(...)` completes normally — the added line cannot throw, because `isBlank(...)` is null-safe and `setRagDocumentReference(null)` is a plain field assignment. The **pre-existing** behaviour then takes over unchanged: `updatedJobData.add(CTX_DOC_REFERENCE_KEY, null)` throws `NullPointerException` per the `jakarta.json.JsonObjectBuilder` contract, which the method-level `catch (Exception ex)` converts into `ExecutionStatus.INPROGRESS` + `withShouldRetry(true)` (lines ~150–158). Net effect, identical to today apart from the new column: the row is left at `UPLOADED` with `rag_document_reference IS NULL` and the task retries (FR-007, AC-004). |
| `initiateDocumentUpload(...)` returns a `null` body | Already fails **before** the save — `fileStorageLocation.getStorageUrl()` is dereferenced at line ~117 — and is caught into the same retry. Unchanged. |

The `try`-block-then-NPE sequence in the null case (row already saved as `UPLOADED`, then retry) is
a pre-existing wart in this task, not introduced or worsened here; fixing it is out of scope and
should not be smuggled into this change.

### 7. Open questions: status after this design

| OQ | Status |
|---|---|
| OQ-002 (column name) | **Resolved — ADR-001:** `rag_document_reference`. |
| OQ-003 (SQL type + fallback) | **Resolved — ADR-002:** `TEXT`, verbatim, no parse, no CHECK; malformed values stored as-is. |
| OQ-005 (index) | **Design position: no index** (FR-010). Nothing queries by reference. If an ops lookup need is confirmed, it is a separate additive migration; `TEXT` does not constrain that option. |
| OQ-001, OQ-004, OQ-006, OQ-007 | **Carried forward unchanged** with their Stage 1 owners and due dates. None of them alters the design in §1–§6; OQ-001 (ticket text) and OQ-006 (does the ticket actually want the status task to read from the row instead of job data?) are the two that could reopen scope, and both are due before Stage 3. |

---

## Testing

Scoping only — Test Specs stage owns the actual scenarios.

**Unit (`src/test/`)**

| Target | Covers |
|---|---|
| `RetrieveMaterialAndUploadTaskTest` (extend) | The existing `ArgumentCaptor<CaseDocument> caseDocumentCaptor` (already declared, line ~82) and `verify(caseDocumentRepository).saveAndFlush(caseDocumentCaptor.capture())` (line ~222) are the seam. Assert **one** `saveAndFlush` whose captured entity carries `ragDocumentReference` equal to the stubbed `documentReference` *plus* the existing `docName`/`blobUri`/`contentType`/`sizeBytes`/`uploadedAt`/`ingestionPhase = UPLOADED` values (AC-001, AC-002). |
| `RetrieveMaterialAndUploadTaskTest` (new cases) | (a) `documentReference` = a deliberately non-UUID string → captured entity holds that exact string, unmodified, and no exception escapes the persistence step (**AC-014** — the explicit test the AC demands). (b) `documentReference` = `null`, and (c) = `""` → captured entity's `ragDocumentReference` is `null`, `saveAndFlush` still invoked once, and the returned `ExecutionInfo` is the unchanged `INPROGRESS`/`shouldRetry` retry outcome (AC-004, FR-007). |
| `CheckIngestionStatusForAllDefendantsTaskTest` (extend) | For at least the `INGESTED` and one failure transition, stub `findById` to return an entity that already has `ragDocumentReference` set and assert the captured saved entity still carries the original value (AC-003, FR-005). Also confirm the suite compiles/passes unchanged otherwise (AC-010). |

No `TaskUtils` test is needed — nothing is added there (contrast DD-43084, which extended
`buildAnswerParams`).

**Integration (`src/integrationTest/`)** — asserts the value survives end-to-end and not just in a
mocked unit (AC-011, NFR-005).

Recommended shape: a dedicated live test mirroring DD-43084's
`jobmanager/queryflow/CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` — seed a `jobs` row
addressed to `RETRIEVE_MATERIAL_AND_UPLOAD` with the job-data shape its predecessor produces, let
the live app's `JobExecutor` run the real task against the real DB, Azurite and the RAG WireMock
stub, then assert `rag_document_reference` via JDBC with Awaitility. That class already establishes
the whole pattern (job seeding, `openConnection()`, `Awaitility.await()`, `finally` cleanup),
including the `case_documents` seeding idiom available in
`IngestionProcessByCaseHttpLiveTest` (raw `INSERT INTO case_documents`, line ~136).

**Stub caveat to resolve at Test Specs stage — do not assume the fixed fixture value is what the
app sees.** There are *two* stubs on `POST /document-upload`:

1. the static mapping `wiremock/mappings/document_upload_to_generate_url.json`, with a **fixed**
   `documentReference` of `b181b0b0-628e-4491-9ccd-2ea93d70cb2f`; and
2. the programmatic scenario stub `DocumentIngestionInitiationApiStub.stubInitiateDocumentUpload(...)`,
   registered by `IngestionProcessHttpLiveTest` (lines ~228, ~343) and
   `IngestionProcessByCaseHttpLiveTest` (line ~78), which returns `randomUUID().toString()` per
   response.

WireMock is a shared container for the whole `integration` run and later-registered stubs of equal
priority win, and the scenario's final stub keeps matching once its state settles — so if either
`IngestionProcess*HttpLiveTest` has already run, the fixed-value mapping is shadowed and a
hard-coded expectation of `b181b0b0-…` will flake on test ordering. Pick one of:

- **(preferred)** have the new IT register its own `POST /document-upload` stub with an explicit
  higher `withPriority(...)` and a known fixed `documentReference`, then assert exact equality; or
- assert against the value WireMock actually served, read back from its request/response journal; or
- assert only that the column is non-null and equals the value the flow also placed in job data.

Also in scope for the IT pass, both assertion-only:

- `IngestionProcessByCaseHttpLiveTest`, `IngestionProcessHttpLiveTest`, `IngestionStatusHttpLiveTest`,
  `DocumentHttpLiveTest` run **unmodified and green** — no response body gains a field (AC-007), and
  the raw `INSERT INTO case_documents (...)` in `IngestionProcessByCaseHttpLiveTest` keeps working
  precisely because the column is nullable (NFR-004).
- App startup succeeds against a database migrated from `V1012` with pre-existing
  `case_documents` rows — which is also the live proof that `ddl-auto: validate` accepts the new
  `String` → `TEXT` mapping (AC-006, AC-009).

**Contract tests:** none — no contract change (FR-009); `pactVerificationTest` unaffected, and the
consumed `api-cp-ai-rag` / `api-cp-crime-caseadmin-case-document-knowledge` artefact versions are
untouched.

**Quality gates:** `gradle clean build` (including `integration`), PMD and JaCoCo green at existing
unmodified thresholds, CodeQL and secrets scanner clean (AC-012). All new fixtures/values synthetic;
no PII, case content, court reference number or real `CJSCPPUID` (AC-013).
