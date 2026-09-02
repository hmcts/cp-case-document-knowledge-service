# Test Specs: Persist the RAG Document-Ingestion Reference on `case_documents`

> **Stage 4 — Test Specs** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43083** · Stories: [`03-stories.md`](./03-stories.md) · Design: [`02-design.md`](./02-design.md) ·
> Requirements: [`01-requirements.md`](./01-requirements.md) ·
> ADRs: [`adrs/DD-43083-persist-doc-ingestion-transaction-id.md`](../adrs/DD-43083-persist-doc-ingestion-transaction-id.md)
> (ADR-001 `rag_document_reference`, ADR-002 `TEXT` verbatim — both **Accepted**, not reopened here).
>
> **Written prospectively — no implementation exists yet.** Unlike the sibling
> [`DD-43084/04-test-specs.md`](../DD-43084-persist-rag-transaction-id/04-test-specs.md), which was
> written retrospectively against merged code and could name already-green tests as "Proof", every
> scenario below states **"To be proven by:"** — a *plan* for a test to write, not a report of a
> test that passed. Nothing in this document should be read as evidence of coverage. `V1013`, the
> `CaseDocument.ragDocumentReference` field, and the `saveDocumentUploaded(...)` signature change
> do not exist on this branch yet, so none of the named tests can compile until Stage 5 lands the
> production change described in design §1–§4.
>
> **Test-authoring order is Story 1 → Story 2 → Story 3**, matching the story dependency chain
> (`03-stories.md` §Summary). Within Story 2, unit tests can be written first (A-TDD, red) because
> the seam — `ArgumentCaptor<CaseDocument>` on `CaseDocumentRepository.saveAndFlush(...)` — already
> exists in `RetrieveMaterialAndUploadTaskTest` (line ~82); only the new getter/setter and the
> fifth `saveDocumentUploaded(...)` parameter are missing.

---

## Test inventory — files to create or extend

| Tier | File | New / extend | Story |
|---|---|---|---|
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/repo/CaseDocumentRepositoryTest.java` | extend | 1 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/jobmanager/caseflow/RetrieveMaterialAndUploadTaskTest.java` | extend | 2 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTaskTest.java` | extend | 2 |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/services/IdpcAvailabilityServiceTest.java` | extend | 2 |
| Integration | `src/integrationTest/java/uk/gov/hmcts/cp/cdk/jobmanager/caseflow/RetrieveMaterialAndUploadRagDocumentReferenceLiveTest.java` | **new** | 3 |
| Integration | `IngestionProcessByCaseHttpLiveTest`, `IngestionProcessHttpLiveTest`, `IngestionStatusHttpLiveTest`, `DocumentHttpLiveTest`, `AnswersHttpLiveTest` | **unmodified — run as regression** | 3 |

No new WireMock static mapping file, no new `__files` fixture, no new `pactVerificationTest` class
(no contract change — FR-009). The new IT registers its own stub programmatically (see Scenario 3.1)
rather than adding a mapping to the shared `wiremock/mappings/` directory, precisely to avoid making
the existing two-stub collision a three-stub collision.

**Naming convention:** `should<Outcome>_when<Condition>` for unit tests (matching the existing
`shouldSaveDocumentUploadedWhenCopyUrlSuccessful` / `shouldComplete_whenDocIdMissing` house style),
and `<task>_<behaviour>` for live tests (matching
`checkStatusTask_persistsRagTransactionId_forDefaultLevelAnswer`).

---

## Story 1 — Add `rag_document_reference` column to `case_documents` (DD-43136)

**Scenario 1.1 — Migration is additive, metadata-only, and idempotent**
- **Given** a database already migrated to `V1012` (the last shipped migration, added by DD-43084)
- **When** Flyway applies
  `V1013__add_rag_document_reference_to_case_documents.sql`
- **Then** `case_documents` gains exactly one new nullable `TEXT` column named
  `rag_document_reference`; no existing column, constraint (`cd_blob_uri_not_blank`,
  `cd_source_not_blank`, `cd_size_nonneg`, `cd_sha256_shape`), index
  (`idx_cd_case_uploaded_desc`, `idx_cd_case_phase`, `idx_cd_phase`), view
  (`v_case_ingestion_status`), foreign key (`fk_cqs_doc`, `fk_cllda_doc`, `fk_def_doc`) or the
  `document_ingestion_phase_enum` type is altered; no row is rewritten; and because the statement is
  `ADD COLUMN IF NOT EXISTS`, re-running it is a no-op rather than an error.
- **To be proven by:** the existing Testcontainers-backed Spring context tests, which run the whole
  Flyway chain against a fresh `postgres:16-alpine` on every `gradle test` —
  `CaseDocumentRepositoryTest`, `QueryVersionRepositoryTest`, `IngestionStatusViewRepositoryTest`,
  `QueriesAsOfRepositoryTest`, `DiscoverySchedulerConfigurationRepositoryTest`,
  `JobManagerConfigTest`. A malformed `V1013` fails context startup for all of them, so this is a
  broad (if implicit) guard. **No new test file** — but the author must confirm the run is genuinely
  green *after* adding `V1013`, not assume it.
- **Additional deliberate check:** a **new** `CaseDocumentRepositoryTest.shouldRoundTripRagDocumentReference_whenSavedThroughTheEntity`
  (see 1.3) is the first assertion that names the column explicitly rather than relying on
  "the context booted".

**Scenario 1.2 — Pre-existing rows and column-less inserts leave `NULL`, with no backfill**
- **Given** `case_documents` rows written before `V1013` — including rows created by
  `IdpcAvailabilityService.persistCaseDocument(...)` in phase `WAITING_FOR_UPLOAD`, and rows whose
  upload never reached the `UPLOADED` transition
- **When** `V1013` runs, and afterwards when a row is inserted by SQL that does not mention the new
  column at all
- **Then** `rag_document_reference IS NULL` for every such row; no error is raised; no backfill is
  attempted; `IS NULL` is therefore the single unambiguous predicate for "no reference recorded"
  (design §4).
- **To be proven by:** `CaseDocumentRepositoryTest.shouldLeaveRagDocumentReferenceNull_whenRowInsertedWithoutTheColumn`
  (new) — reuse the class's existing private `persist(...)` JDBC helper (line ~106), whose
  `INSERT INTO case_documents (...)` column list deliberately stays unchanged, then assert
  `SELECT rag_document_reference` returns `null` for that `doc_id`.
- **Reinforced by (integration):** `IngestionProcessByCaseHttpLiveTest.seedExistingCaseDocument(...)`
  (line ~136) performs exactly this kind of column-less raw insert and must keep working
  **unmodified** — the live proof that the column's nullability preserves backward compatibility
  (NFR-003). Covered as Scenario 3.2.

**Scenario 1.3 — The entity mapping matches the column, and `TEXT` stores the value byte-for-byte**
- **Given** the `CaseDocument` entity with its new `@Column(name = "rag_document_reference") private String ragDocumentReference`
- **When** an entity carrying a value is saved via `repository.saveAndFlush(...)`, the persistence
  context is cleared, and the row is re-read via `findById(...)`
- **Then** the value returned is identical to the value written — no trim, no case-fold, no
  canonicalisation (which is the concrete behavioural difference ADR-002 chose `TEXT` to get; a
  Postgres `uuid` column would canonicalise mixed-case and brace/unhyphenated input, breaching
  AC-001).
- **To be proven by:** `CaseDocumentRepositoryTest.shouldRoundTripRagDocumentReference_whenSavedThroughTheEntity`
  (new) — save with a mixed-case synthetic UUID-shaped string, `em.flush()` / `em.clear()`, re-read,
  assert `isEqualTo` on the exact original string (not `equalToIgnoringCase`).
- **Deliberate scope note:** this test does **not** prove `spring.jpa.hibernate.ddl-auto: validate`
  accepts the mapping. `application-datasource.yml` (where `ddl-auto: validate` lives) is **not**
  imported by `src/test/resources/application.yml`, so `@DataJpaTest` runs with Boot's default
  `ddl-auto` for a non-embedded, Flyway-managed datasource — i.e. schema validation is not
  exercised at the unit tier. The `validate` check is an integration-tier proof only (Scenario 3.3).
  Do not claim AC-009 from `gradle test`.

---

## Story 2 — Persist and preserve the RAG `documentReference` (DD-43137)

All scenarios in this story target
`jobmanager/caseflow/RetrieveMaterialAndUploadTask` and
`jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTask` per design §4–§5.

**Shared Given for 2.1–2.5** (the existing `RetrieveMaterialAndUploadTaskTest` fixture, unchanged):
a `RETRIEVE_MATERIAL_AND_UPLOAD` `ExecutionInfo` whose job data carries `caseId`, `defendantId`,
`materialId`, `docId`, `materialName`, `CJSCPPUID` and `requestId`; `progressionClient` returns a
download URL; `storageService.copyFromUrl(...)` returns a `DocumentBlobMetadata`; and
`caseDocumentRepository.findById(documentId)` returns a `CaseDocument`.

---

**Scenario 2.1 — The reference is persisted in the *same single* `saveAndFlush` as every other upload field** *(AC-001, AC-002, FR-002)*
- **Given** `initiateDocumentUpload(...)` returns a `FileStorageLocationReturnedSuccessfully` whose
  `documentReference` is a known synthetic UUID-shaped string
- **When** the task executes successfully
- **Then** `CaseDocumentRepository.saveAndFlush(...)` is invoked **exactly once**, and the captured
  `CaseDocument` carries, in that one write: `ragDocumentReference` equal to the returned value
  *verbatim*, plus `docName`, `blobUri`, `contentType`, `sizeBytes`, a non-null `uploadedAt`,
  `ingestionPhase = UPLOADED` and a non-null `ingestionPhaseAt`. The task returns `COMPLETED`.
- **To be proven by:** `RetrieveMaterialAndUploadTaskTest.shouldPersistRagDocumentReferenceInTheSameSaveAndFlush_whenUploadSucceeds`
  (new; may instead be delivered as a strengthening of the existing
  `shouldSaveDocumentUploadedWhenCopyUrlSuccessful`, which already captures the entity and asserts
  `ingestionPhase`/`blobUri` — extending it is acceptable, but the **`times(1)`** assertion is the
  part AC-002 actually demands and is currently absent). Use the class's existing
  `@Captor ArgumentCaptor<CaseDocument> caseDocumentCaptor` (line ~82) with
  `verify(caseDocumentRepository, times(1)).saveAndFlush(caseDocumentCaptor.capture())`.
- **Note:** asserting `times(1)` is what pins FR-002's "no new write, no second transaction". A
  future refactor that split the reference into its own `save` would still satisfy a bare `verify`,
  so the explicit invocation count is load-bearing, not decorative.

**Scenario 2.2 — A non-UUID-shaped reference is stored verbatim: neither discarded nor fatal** *(AC-014, ADR-002)*
- **Given** `initiateDocumentUpload(...)` returns a deliberately non-UUID-shaped, non-blank
  `documentReference` (e.g. `"not-a-uuid"`)
- **When** the task executes
- **Then** the captured `CaseDocument.ragDocumentReference` holds that exact string — not `null`,
  not truncated, not case-folded, not parsed — **and** no exception escapes the persistence step:
  the task still returns `COMPLETED` and still schedules
  `CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS` with the same value in job data. This is the "neither
  (a) silently discarded nor (b) failing the ingestion flow" behaviour AC-014 requires an explicit
  test for.
- **To be proven by:** `RetrieveMaterialAndUploadTaskTest.shouldStoreRagDocumentReferenceVerbatim_whenReferenceIsNotUuidShaped`
  (new — this is the single test AC-014 names, and it must be a dedicated, self-documenting case).
- **Pre-existing corroboration worth recording:** the current fixtures already use non-UUID strings
  — `shouldUploadDocumentAndScheduleNextTask` stubs `getDocumentReference()` as `"document-id"`,
  and `shouldSaveDocumentUploadedWhenCopyUrlSuccessful` /
  `shouldHandleNullBlobMetadata` / `shouldFallbackToCaseLevelSupersededDocs` construct
  `new FileStorageLocationReturnedSuccessfully("storage-url", "doc-ref")`. Under a `UUID` column
  those four fixtures would have had to change (or would have silently null-ed); under ADR-002's
  `TEXT` they keep passing untouched. That is a useful incidental signal, but it is **not** a
  substitute for the dedicated test — none of them asserts anything about the new field.

**Scenario 2.3 — A `null` reference leaves the column `NULL` and does not change the task outcome** *(AC-004, FR-007)*
- **Given** `initiateDocumentUpload(...)` returns a body whose `documentReference` is `null` (and a
  non-null `storageUrl`, so the pre-existing line ~117 dereference still succeeds)
- **When** the task executes
- **Then** `saveAndFlush(...)` is still invoked once and the captured entity's `ragDocumentReference`
  is `null` (via the null-safe `isBlank(...)` guard — the added line cannot throw); and the task's
  **existing, unchanged** outcome follows: `JsonObjectBuilder.add(CTX_DOC_REFERENCE_KEY, null)`
  throws `NullPointerException`, the method-level `catch (Exception ex)` converts it to
  `ExecutionStatus.INPROGRESS` with `isShouldRetry() == true`, and no next task is scheduled.
- **To be proven by:** `RetrieveMaterialAndUploadTaskTest.shouldLeaveRagDocumentReferenceNullAndRetry_whenReferenceIsNull`
  (new) — assert the captured entity's field is `null`, assert `INPROGRESS` + `shouldRetry`, and
  `verifyNoInteractions(executionService)` (or `verify(executionService, never()).executeWith(any())`).
- **Explicitly asserting the pre-existing wart, not fixing it.** Design §6 records that the row is
  saved as `UPLOADED` *before* the NPE, so the task retries over an already-`UPLOADED` row. That is
  current behaviour and out of scope; the test pins it so a later well-meaning "fix" is a visible,
  deliberate change rather than a silent one.

**Scenario 2.4 — A blank (`""`) reference behaves identically to `null`** *(AC-004, design §4)*
- **Given** `documentReference` is the empty string
- **When** the task executes
- **Then** the captured entity's `ragDocumentReference` is **`null`, not `""`** — the one and only
  mapping the design applies, so that `IS NULL` remains the single "absent" representation and a
  zero-length string never becomes a second, indistinguishable one. Task outcome as 2.3 (the
  `add(..., "")` call itself does not throw, so confirm at implementation time whether the
  observable outcome is `COMPLETED`-with-a-blank-job-data-value rather than a retry, and assert
  whatever the *unchanged* pre-existing behaviour actually is — do not "improve" it in this ticket).
- **To be proven by:** `RetrieveMaterialAndUploadTaskTest.shouldLeaveRagDocumentReferenceNull_whenReferenceIsBlank`
  (new).
- **Open point for the implementer:** 2.3 and 2.4 diverge in task outcome (`null` → NPE → retry;
  `""` → no NPE). Design §6's table only spells out the `null` path. Assert the blank path's real
  behaviour empirically and, if it differs from the `null` path, note it on the ticket rather than
  forcing symmetry.

**Scenario 2.5 — A retry of `RETRIEVE_MATERIAL_AND_UPLOAD` overwrites the previous reference (last-write-wins)** *(design §5, story DoD delta)*
- **Given** a `CaseDocument` that already carries a `ragDocumentReference` from an earlier attempt
  (stub `findById` to return an entity pre-populated with reference `A`)
- **When** the task runs again and `initiateDocumentUpload(...)` returns a **different** reference
  `B` — the real retry path, since a retry re-initiates the upload
- **Then** the captured entity's `ragDocumentReference` is `B`, not `A`, and not both — no
  write-once / `COALESCE`-style guard. This is **correct and intended**: the stored reference must
  identify the ingestion transaction that produced the blob currently recorded in `blob_uri`, and
  the retry has just replaced that blob. `CaseDocument` has no `@Version` field, so this is plain
  last-write-wins, exactly as every other field on the row already behaves.
- **To be proven by:** `RetrieveMaterialAndUploadTaskTest.shouldOverwriteRagDocumentReference_whenTaskRetriesWithANewReference`
  (new).
- **Why this scenario exists:** design §5 flags that FR-005's "written once, never overwritten"
  holds for *phase transitions only*, and asks for the retry semantic to be "pinned rather than
  incidental". Without this test, a future reviewer reading FR-005 could add a write-once guard in
  good faith and leave rows pointing at abandoned ingestion transactions. The test is the guard
  against the guard.

---

**Shared Given for 2.6–2.8** (the existing `CheckIngestionStatusForAllDefendantsTaskTest` fixture):
job data carrying `docId`, `blobName`, `caseId`, `CTX_DOC_REFERENCE_KEY`, `CTX_LATEST_DEFENDANT`,
`defendantId`; `documentIngestionStatusApi.documentStatusByReference(...)` stubbed with the relevant
`DocumentIngestionStatus`; and — **the change from today** — `caseDocumentRepository.findById(docId)`
returning a `CaseDocument` whose `ragDocumentReference` is **already set** to a known value
(currently these tests return a bare `new CaseDocument()`).

**Scenario 2.6 — The reference survives the transition to `INGESTED`** *(AC-003, FR-005)*
- **Given** a persisted row whose `ragDocumentReference` is a known synthetic value, and a RAG status
  poll returning `INGESTION_SUCCESS`
- **When** `CheckIngestionStatusForAllDefendantsTask` transitions the phase
- **Then** the saved entity has `ingestionPhase = INGESTED` **and** `ragDocumentReference` unchanged
  — not nulled, not altered. `updateIngestionPhase(...)` mutates only the two phase fields on an
  entity hydrated from the row, so this must hold with **zero production changes** in that task;
  the test's job is to prove that claim rather than assume it.
- **To be proven by:** `CheckIngestionStatusForAllDefendantsTaskTest.shouldPreserveRagDocumentReference_whenTransitioningToIngested`
  (new) — extend/mirror the existing `shouldUpdateAndTriggerAllQueryTypes_whenIngestionSuccess_andLatestDefendant`,
  which already asserts on the same `doc` instance via `verify(caseDocumentRepository).saveAndFlush(doc)`.

**Scenario 2.7 — The reference survives the transition to `EXCEEDED_FILE_SIZE_LIMIT`** *(AC-003)*
- **Given** as 2.6 but the poll returns `FILE_SIZE_OVER_LIMIT`
- **When** the phase is transitioned
- **Then** `ingestionPhase = EXCEEDED_FILE_SIZE_LIMIT` and `ragDocumentReference` is unchanged.
- **To be proven by:** `CheckIngestionStatusForAllDefendantsTaskTest.shouldPreserveRagDocumentReference_whenTransitioningToExceededFileSizeLimit`
  (new) — extend/mirror the existing
  `shouldUpdateIngestionPhase_whenIngestionFailedDueToFileExceedingSizeLimit`.

**Scenario 2.8 — The reference survives the transition to `FAILED`** *(AC-003)*
- **Given** as 2.6 but the poll returns a failure status that maps to `DocumentIngestionPhase.FAILED`
  (`INGESTION_FAILED` or `INVALID_METADATA` — confirm the exact mapping in the task before writing)
- **When** the phase is transitioned
- **Then** `ingestionPhase = FAILED` and `ragDocumentReference` is unchanged.
- **To be proven by:** `CheckIngestionStatusForAllDefendantsTaskTest.shouldPreserveRagDocumentReference_whenTransitioningToFailed`
  (new). **Note this is a genuinely new test case, not an extension** — the suite has no
  `FAILED`-transition test today (it covers `INGESTION_SUCCESS`, `FILE_SIZE_OVER_LIMIT`, a missing
  `docId`, and an API exception). AC-003 names all three phases, so all three need a case; this is
  the one that adds coverage the suite currently lacks entirely.

**Scenario 2.9 — Job-data threading to the status task is unchanged** *(FR-006)*
- **Given** a successful `RETRIEVE_MATERIAL_AND_UPLOAD` execution
- **When** the next task is scheduled
- **Then** the scheduled `ExecutionInfo` for `CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS` still
  carries `CTX_DOC_REFERENCE_KEY` with **the same value** that was persisted to the row — persistence
  is an added sink, not a replacement for the hand-off. (Design edit (3) substitutes the new local
  for the inline `fileStorageLocation.getDocumentReference()` expression; this test is what proves
  that substitution is behaviour-preserving.)
- **To be proven by:** extending the existing
  `RetrieveMaterialAndUploadTaskTest.shouldUploadDocumentAndScheduleNextTask` with an assertion that
  `nextTask.getJobData().getString(CTX_DOC_REFERENCE_KEY)` equals both the stubbed value and the
  captured entity's `ragDocumentReference` (the suite currently asserts only on `CTX_DOC_ID_KEY` and
  the presence of `CTX_BLOB_NAME_KEY`).

**Scenario 2.10 — A `WAITING_FOR_UPLOAD` row is created with the column `NULL`** *(AC-005)*
- **Given** `IdpcAvailabilityService` discovering a new IDPC material for a case
- **When** `persistCaseDocument(...)` creates the `CaseDocument` (setting `docId`, `caseId`,
  `materialId`, `docName`, `blobUri`, `createdAt`, `ingestionPhase = WAITING_FOR_UPLOAD`,
  `defendantId`, `courtdocId`)
- **Then** the saved entity's `ragDocumentReference` is `null` — the field is simply left unset, with
  no compile or behaviour change in that service, until upload initiation completes for the document.
- **To be proven by:** `IdpcAvailabilityServiceTest.shouldLeaveRagDocumentReferenceNull_whenPersistingWaitingForUploadRow`
  (new) — requires **new plumbing**: the class has no `ArgumentCaptor<CaseDocument>` today, so add
  one and capture `caseDocumentRepository.saveAndFlush(...)` from one of the existing
  new-document paths (e.g. the fixture behind `returnsNewDocuments_forMultipleDefendants`).
- **Reinforced by:** Scenario 1.2 (the DB-level `IS NULL` on a column-less insert) and Scenario 3.1
  (the live test seeds precisely such a `WAITING_FOR_UPLOAD` row and asserts the column is `NULL`
  *before* the task runs).

**Scenario 2.11 — Both touched unit suites remain green end to end** *(AC-010)*
- **Given** the `saveDocumentUploaded(...)` signature gains a fifth parameter and `CaseDocument`
  gains a field that joins the class-level `@EqualsAndHashCode` (design §3, "Known consequence")
- **When** `gradle test` runs
- **Then** `RetrieveMaterialAndUploadTaskTest` (9 existing tests) and
  `CheckIngestionStatusForAllDefendantsTaskTest` (5 existing tests) compile and pass with their
  existing assertions unmodified, as do `IdpcAvailabilityServiceTest`, `DocumentServiceTest` and
  `CaseDocumentRepositoryTest`.
- **To be proven by:** the full `gradle test` run — plus one specific manual check the author must
  make rather than trust: design §3 asserts the new `equals`/`hashCode` member is *inert* because
  nothing in `src/test/` or `src/integrationTest/` compares whole `CaseDocument` instances by
  equality. Verify that (grep for `CaseDocument` in assertion positions, and note that
  `CheckIngestionStatusForAllDefendantsTaskTest` uses `verify(...).saveAndFlush(doc)` — identity of
  the same instance, so Mockito's `equals`-based argument matching is satisfied trivially). If any
  suite does compare by value, that is a finding to raise, not to work around.

---

## Story 3 — End-to-end test coverage for `rag_document_reference` (DD-43138)

**Scenario 3.1 — The column is populated through the real ingestion flow, with the two-stub ordering hazard resolved** *(AC-011, NFR-004)*
- **Given** the `gradle integration` compose stack (app + PostgreSQL + Artemis + Azurite +
  azurite-seed + WireMock), a `case_documents` row seeded directly via JDBC in phase
  `WAITING_FOR_UPLOAD` with `rag_document_reference IS NULL`, and a Task Manager `jobs` row seeded
  and addressed to `RETRIEVE_MATERIAL_AND_UPLOAD` with the job-data shape its predecessor produces
  (`caseId`, `defendantId`, `materialId`, `docId`, `materialName`, `CJSCPPUID`, `requestId`)
- **When** the live app's scheduled `JobExecutor` picks the job up and runs the **real** task against
  the real database, the real Azurite blob copy (source `documents/source.pdf`, primed by
  `azurite-seed` and returned by the existing `material_content_api` Progression stub) and the RAG
  `POST /document-upload` stub
- **Then** within the Awaitility window the row reaches `ingestion_phase = 'UPLOADED'` **and**
  `rag_document_reference` equals the exact `documentReference` the stub served — read back via
  JDBC, proving the value survives the full JPA write path and not just a mocked repository call.
- **To be proven by:** `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest.retrieveMaterialAndUploadTask_persistsRagDocumentReference_onCaseDocumentsRow`
  (new), extending `AbstractHttpLiveTest` and mirroring
  `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` beat for beat: `INSERT INTO jobs (...)`
  seeding, `openConnection()`, `Awaitility.await().atMost(60s).pollInterval(2s)`, `finally` cleanup
  of the `jobs` and `case_documents` rows it created.
- **Resolution of the ordering hazard (design §Testing, "Stub caveat") — mitigation (a), sharpened.**
  Two stubs answer `POST /document-upload` in the shared WireMock container: the static mapping
  `wiremock/mappings/document_upload_to_generate_url.json` with a fixed
  `documentReference` of `b181b0b0-628e-4491-9ccd-2ea93d70cb2f`, and the programmatic scenario stub
  `DocumentIngestionInitiationApiStub.stubInitiateDocumentUpload(...)` registered by
  `IngestionProcessHttpLiveTest` and `IngestionProcessByCaseHttpLiveTest`, which serves a fresh
  `randomUUID()` and — once its scenario state settles on the final mapping — keeps matching. Later
  registrations of equal priority win, so a hard-coded expectation of `b181b0b0-…` will flake on
  suite ordering. The new IT must therefore register **its own** stub that is both
  higher-priority *and* narrowly matched:
  - `withPriority(1)` (lower number = higher precedence, so it beats both existing stubs, which are
    at the default 5), **and**
  - a body matcher on the unique document id this test generated —
    `matchingJsonPath("$.documentId", equalTo(docId.toString()))` — so the new stub can only ever
    answer *this* test's request and cannot shadow the other suites' `POST /document-upload` calls
    in either direction. Priority alone would be sufficient for correctness but would make this test
    the de-facto global handler for the whole `integration` run; the body matcher is what keeps the
    blast radius at zero.
  - the response must carry a **known fixed synthetic** `documentReference` (a hard-coded UUID-shaped
    string declared as a constant in the test, so the assertion is exact equality, not
    "non-null") **and** a genuinely valid `storageUrl` produced by the existing
    `AzureSasUtil.generateSasUrl("documents-new", "<unique-blob-name>")` helper. Do **not** reuse
    the static mapping's `storageUrl`: its SAS signature and `se=2026-04-09` expiry are baked into
    the fixture, so the Azurite copy would fail and the row would never reach `UPLOADED`. Use a
    per-run unique blob name so repeat runs and parallel suites do not collide.
  - **Not chosen, and why:** asserting against WireMock's request/response journal (mitigation b)
    couples the test to WireMock's admin API and reads back the value under test from the same
    system that produced it; asserting only "non-null and equal to the job-data value"
    (mitigation c) is the weakest of the three and would not catch a truncating or re-formatting
    write, which is exactly what AC-001 cares about.
- **Pre-condition assertion worth making explicit:** before seeding the job, assert the seeded row's
  `rag_document_reference IS NULL`. That turns the test into a genuine before/after and rules out a
  false pass from a leftover row (an `IS NULL` → value transition, not just "the value is there").
- **Optional stretch (not required by any AC, decide at implementation):** because the real task
  schedules `CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS` and the existing `GET /document-upload/.*`
  stub returns `INGESTION_SUCCESS`, the same test could go on to await
  `ingestion_phase = 'INGESTED'` and re-assert `rag_document_reference` unchanged — an
  integration-tier confirmation of Scenario 2.6. Attractive, but it lengthens the Awaitility window
  and adds a second asynchronous hop; if it proves flaky, drop it and rely on the unit coverage,
  recording the decision on the ticket rather than leaving a quarantined test behind.

**Scenario 3.2 — No API or response-shape regression across the existing live suites** *(AC-007, NFR-003)*
- **Given** the full set of existing `*HttpLiveTest` classes, **with their assertions unmodified**
- **When** `gradle integration` runs after `V1013`, the entity field and the task change have all
  landed
- **Then** every one of them passes unchanged:
  - `IngestionProcessByCaseHttpLiveTest` — the load-bearing one. Its `seedExistingCaseDocument(...)`
    raw `INSERT INTO case_documents (doc_id, case_id, material_id, source, doc_name, blob_uri,
    uploaded_at, ingestion_phase, ingestion_phase_at, defendant_id, courtdoc_id, created_at)` omits
    the new column entirely and must keep working — which it does *only* because the column is
    nullable with no default (NFR-003, and the practical consequence of ADR-002's "no `NOT NULL`").
  - `IngestionProcessHttpLiveTest`, `IngestionStatusHttpLiveTest`, `DocumentHttpLiveTest`,
    `AnswersHttpLiveTest`, `QueriesHttpLiveTest`, `QueryVersionsHttpLiveTest`,
    `ActuatorHttpLiveTest` — no response body gains a field, because no controller, mapper or DTO
    exposes any `CaseDocument` field (there is no `CaseDocumentMapper`).
- **To be proven by:** the existing suites, run as a regression gate, **plus a diff-level check**
  that is stronger than the runtime one: confirm from the PR diff that `src/main/.../controllers/`,
  `services/DocumentService.java`, `services/IdpcAvailabilityService.java`, every mapper, every
  OpenAPI model, `version.cdk`, `acl/cdks-rules.drl` and `resources/acl/*` are **untouched**, and
  that `repo/CaseDocumentRepository.java` is unchanged. Runtime green-ness is a weak negative signal
  here (most response assertions use `contains(...)`, so an added field would not necessarily fail
  them) — the empty diff is the real evidence, exactly as DD-43084's Scenario 2.6 noted about its
  own equivalent check. State both.

**Scenario 3.3 — The app boots against a database migrated from `V1012` with pre-existing rows, proving `ddl-auto: validate` accepts the mapping** *(AC-006, AC-009, cross-checked with Story 1)*
- **Given** a PostgreSQL 16 instance already at `V1012` containing `case_documents` rows written
  before this change
- **When** the compose stack starts and the app runs Flyway (`spring.flyway` locations
  `classpath:db/migration/postgresql` and `classpath:db/migration`, `baseline-on-migrate: true`,
  `out-of-order: true`) and then Hibernate schema validation with
  `spring.jpa.hibernate.ddl-auto: validate`
- **Then** `V1013` applies, the pre-existing rows show `rag_document_reference IS NULL`, Hibernate
  accepts the `String` → `TEXT` mapping with no `columnDefinition` override or `@JdbcTypeCode`, and
  the app reaches readiness — the live proof of the ADR-002 constraint that a `uuid` column would
  have failed boot for a `String` field.
- **To be proven by:** the `gradle integration` stack starting successfully (a
  `SchemaManagementException` at boot fails every IT, so the signal is unmissable) plus
  `ActuatorHttpLiveTest` reaching readiness. **This is the only tier at which `validate` is
  exercised** — see the scope note on Scenario 1.3.
- **Manual step the story DoD requires and CI does not give you for free:** the compose database is
  normally fresh, so "fresh DB" and "already at `V1012` with rows" are *not* the same run. Do the
  `V1012`-then-upgrade run deliberately once (start the stack on the pre-change commit, seed a few
  synthetic `case_documents` rows, then restart on the change commit) and record the evidence on
  DD-43136/DD-43138. Do not infer AC-009's second half from a green CI run.

**Scenario 3.4 — Quality gates** *(AC-012)*
- **Given** the complete change (migration + entity + task + tests)
- **When** `gradle clean build` runs (which includes `integration`, since `check`/`build` depend on
  it), followed by `gradle pmdMain pmdTest jacocoTestReport`, CodeQL and the secrets scanner
- **Then** all pass at existing, **unmodified** thresholds, with no new PMD suppressions and no
  lowered JaCoCo limits.
- **To be proven by:** the CI workflows (`ci-build-publish`, `code-analysis`, `codeql`,
  `secrets-scanner`) plus a local `gradle clean build`.
- **Specific things to confirm rather than assume:** (a) the fifth `saveDocumentUploaded(...)`
  parameter does not trip a PMD rule — design §4 establishes that `.github/pmd-ruleset.xml` enables
  only `bestpractices`, `codestyle`, `errorprone`, `performance` and `security`, and that
  `ExcessiveParameterList` lives in the *disabled* `design` category, so this should be a non-event;
  if it nonetheless fires, the fix is a small local record (e.g. `UploadedBlob(blobName, blobUrl,
  sizeBytes)`), **never** a suppression. (b) JaCoCo coverage on new lines: the change is ~4
  production lines, all on paths the new unit tests exercise directly, so line coverage on new code
  should comfortably clear the ≥80% standard — but the `null`/blank branches only count if
  Scenarios 2.3 and 2.4 are actually written.

**Scenario 3.5 — No PII, case data or real identifiers anywhere in the new test material** *(AC-013, CDKS hard rule)*
- **Given** the new migration, the new/extended unit tests, and the new live test
- **When** the diff is reviewed
- **Then** every value is synthetic: `UUID.randomUUID()` or hard-coded synthetic UUID-shaped
  constants for ids and references, no real court reference number, no real `CJSCPPUID`, no case
  content, no document body, and no real material name. The `COMMENT ON COLUMN` text describes the
  field's provenance and nullability only. The deliberately-malformed value used in Scenario 2.2
  must be obviously synthetic (`"not-a-uuid"`), not a redacted real one.
- **To be proven by:** the secrets scanner, the `block-pii` / `block-secrets` plugin hooks (which
  run on every `Write`/`Edit`, so a violation is blocked at authoring time rather than caught at
  review), and explicit reviewer sign-off at the Code Review stage.
- **One pre-existing item to raise, not to change here:** the integration-test helper
  `AzureSasUtil.generateSasUrl(...)` reads `AZURE_STORAGE_CONNECTION_STRING` from the environment.
  The new IT should **reuse that helper as-is** — it is the established Azurite-emulator pattern in
  this source set and is already used by `DocumentIngestionInitiationApiStub`. Do not introduce any
  new connection-string handling, and do not extend this pattern beyond the existing call shape.
  CDKS's Managed-Identity-only hard rule targets production Azure access; this test-side emulator
  credential predates this ticket. Flag it to the reviewer for a separate decision rather than
  widening or silently entrenching it.

---

## Coverage summary — **planned**, not achieved

Every cell below describes coverage *to be written*. No row is evidence of a passing test.

| AC | Scenario(s) | Unit (`src/test/`) | Integration (`src/integrationTest/`) | Planned test |
|---|---|---|---|---|
| AC-001 (stored verbatim, exactly as received) | 2.1, 2.2, 1.3 | planned | planned | `RetrieveMaterialAndUploadTaskTest.shouldPersistRagDocumentReferenceInTheSameSaveAndFlush_whenUploadSucceeds`; `CaseDocumentRepositoryTest.shouldRoundTripRagDocumentReference_whenSavedThroughTheEntity`; `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest` |
| AC-002 (single `saveAndFlush`, all fields together) | 2.1 | planned | — | as above, with `verify(..., times(1))` |
| AC-003 (survives `INGESTED` / `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT`) | 2.6, 2.7, 2.8 | planned (3 cases) | optional stretch only (3.1) | `CheckIngestionStatusForAllDefendantsTaskTest.shouldPreserveRagDocumentReference_whenTransitioningTo{Ingested,Failed,ExceededFileSizeLimit}` |
| AC-004 (null / blank → `NULL`, outcome unchanged) | 2.3, 2.4 | planned (2 cases) | — | `...shouldLeaveRagDocumentReferenceNullAndRetry_whenReferenceIsNull`; `...shouldLeaveRagDocumentReferenceNull_whenReferenceIsBlank` |
| AC-005 (`WAITING_FOR_UPLOAD` row is `NULL`) | 2.10, 1.2 | planned (new captor plumbing) | planned (3.1 pre-condition assertion) | `IdpcAvailabilityServiceTest.shouldLeaveRagDocumentReferenceNull_whenPersistingWaitingForUploadRow` |
| AC-006 (pre-`V1013` rows are `NULL`, no backfill) | 1.2, 3.3 | planned | planned (manual `V1012`-upgrade run) | `CaseDocumentRepositoryTest.shouldLeaveRagDocumentReferenceNull_whenRowInsertedWithoutTheColumn` |
| AC-007 (no API / DTO / mapper / `version.cdk` change) | 3.2 | — | planned (existing suites unmodified) + **diff-level check** | `IngestionProcessByCaseHttpLiveTest` et al., run as regression |
| AC-008 (migration shape; `migration-reviewer`) | 1.1 | planned (implicit — Testcontainers migration chain) | planned (app boots) | existing repository/context tests + `migration-reviewer` review evidence on DD-43136 |
| AC-009 (clean migrate: fresh **and** from `V1012` with rows) | 1.1, 3.3 | planned (fresh only) | planned (both — the `V1012` case needs a deliberate run) | compose-stack startup + `ActuatorHttpLiveTest` |
| AC-010 (both touched unit suites green) | 2.11 | planned (`gradle test`) | — | full `gradle test`, plus the `@EqualsAndHashCode` inertness check |
| AC-011 (populated end-to-end from a live stub) | 3.1 | — | planned (**the headline new test**) | `RetrieveMaterialAndUploadRagDocumentReferenceLiveTest.retrieveMaterialAndUploadTask_persistsRagDocumentReference_onCaseDocumentsRow` |
| AC-012 (build / PMD / JaCoCo / CodeQL / secrets green) | 3.4 | planned | planned | `gradle clean build` + CI workflows |
| AC-013 (no PII / case data / real identifiers) | 3.5 | planned | planned | secrets scanner, `block-pii` / `block-secrets` hooks, reviewer sign-off |
| AC-014 (non-UUID value neither discarded nor fatal) | 2.2 | planned (**dedicated, AC-mandated**) | — | `...shouldStoreRagDocumentReferenceVerbatim_whenReferenceIsNotUuidShaped` |
| design §5 (retry overwrite = last-write-wins) | 2.5 | planned | — | `...shouldOverwriteRagDocumentReference_whenTaskRetriesWithANewReference` |

**Tier notes**
- **Nothing is integration-only.** Every behavioural AC has a unit-tier plan, so a failure localises
  to a class rather than to "the compose stack".
- **AC-003 is deliberately unit-only** (with an optional integration stretch in 3.1). Driving three
  distinct terminal phases through the live stack would need three seeded jobs and three status-stub
  states for a behaviour whose mechanism — an entity hydrated from the row, with only two fields
  mutated — is fully observable at the unit tier. Recorded as a conscious tier choice, not a gap.
- **No contract tests.** No contract changes (FR-009); `pactVerificationTest` is untouched and the
  consumed `api-cp-ai-rag` / `api-cp-crime-caseadmin-case-document-knowledge` artefact versions are
  unchanged.
- **No accessibility tests.** CDKS is backend-only with no UI; the WCAG 2.1 AA hard rule applies to
  downstream consumers of CDKS's API, not to this change. No axe-core hooks, no E2E specs.

---

## Risks and open points carried into implementation

1. **The WireMock two-stub hazard is the single biggest flake risk** (Scenario 3.1). Brief the
   assigned developer before they write the IT. A test that hard-codes `b181b0b0-628e-4491-9ccd-2ea93d70cb2f`
   will pass locally in isolation and fail in CI depending on whether an `IngestionProcess*HttpLiveTest`
   ran first. The static mapping's baked-in, expired SAS `storageUrl` is a second, independent
   reason not to depend on it.
2. **Blank-vs-null task outcome asymmetry** (Scenario 2.4). Design §6's table documents only the
   `null` path's NPE-then-retry. Assert the blank path's actual pre-existing behaviour; do not
   normalise the two in this ticket.
3. **`@EqualsAndHashCode` gains a member** (Scenario 2.11). Believed inert; verify rather than trust,
   and do not add a one-field `@EqualsAndHashCode.Exclude` (design §3 rejects that as an
   inconsistent special case, and the broader JPA-entity-equality smell is out of scope).
4. **Story sequencing.** Story 3's IT cannot compile or pass until Stories 1 and 2 have merged. It
   can be drafted in parallel, but do not open it as a standalone PR against `main`.
5. **Upstream open questions — resolved at implementation.** **OQ-001** (ticket text never
   fetched — no Jira access in these sessions) and **OQ-006** (whether the ticket's actual driver
   is for `CheckIngestionStatusForAllDefendantsTask` to read the reference *from the row* instead
   of job data) are both closed per `01-requirements.md` §Open Questions: implementation matches
   the restated persist-only scope, and the status task deliberately still reads job data,
   unchanged. **OQ-004** (external readers of `case_documents`) and **OQ-007** (retention/purge)
   are likewise resolved there. **OQ-005** (index) stays deferred — no read path needs it.
6. **Jira sub-tickets are live.** Story 1 is [DD-43136](https://tools.hmcts.net/jira/browse/DD-43136),
   Story 2 is [DD-43137](https://tools.hmcts.net/jira/browse/DD-43137), Story 3 is
   [DD-43138](https://tools.hmcts.net/jira/browse/DD-43138) — CLAUDE.md's "linked Jira ticket per
   story" precondition is satisfied; attach test evidence to these tickets.
