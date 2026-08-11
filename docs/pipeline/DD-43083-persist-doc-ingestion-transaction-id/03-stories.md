# User Stories: Persist RAG Document-Ingestion Reference on `case_documents`

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43083** — each story below has its own sub-ticket:
> [DD-43136](https://tools.hmcts.net/jira/browse/DD-43136) (Story 1),
> [DD-43137](https://tools.hmcts.net/jira/browse/DD-43137) (Story 2),
> [DD-43138](https://tools.hmcts.net/jira/browse/DD-43138) (Story 3).
>
> Acceptance Criteria text below is embedded verbatim from [`01-requirements.md`](./01-requirements.md)
> so each story is ready to paste into its Jira ticket; ADR-001 (column name
> `rag_document_reference`) and ADR-002 (SQL type `TEXT`, verbatim, no parse, no CHECK) —
> [`adrs/DD-43083-persist-doc-ingestion-transaction-id.md`](../adrs/DD-43083-persist-doc-ingestion-transaction-id.md)
> — are both **Accepted** and are locked decisions, not reopened here.
>
> **Structure note.** This ticket does not mirror DD-43084's four-way split. DD-43084 needed a
> separate "keep read-side entities in sync" story because its write path was raw
> `NamedParameterJdbcTemplate` upsert SQL, independent of the JPA entity. Here there is a single
> entity (`CaseDocument`), a single write point (`saveDocumentUploaded(...)`'s existing
> `saveAndFlush`), and no upsert — the entity change *is* the write-path change, so Stage 1's
> candidate Story 2 (entity + persistence) and Story 3 (survival across phase transitions) are
> merged below into one story, exactly as Stage 1 flagged as an option ("assertion-only, with no
> production change of its own beyond what Story 2 delivers"). Net result: **three stories**, not
> four — schema, persistence (incl. survival across later phases), and end-to-end test coverage.

**Standard DoD (applies to every story unless noted)**: code reviewed & approved · all ACs
covered by automated tests (unit + integration) · no critical/high Snyk findings introduced ·
deployed to and verified on sandbox · Jira ticket updated with test evidence. Deltas only, below.

---

## Story 1 — Add `rag_document_reference` column to `case_documents`
**Jira: [DD-43136](https://tools.hmcts.net/jira/browse/DD-43136)**

As a **CDKS developer**,
I want **a new, append-only Flyway migration (`V1013`) that adds a nullable
`rag_document_reference TEXT` column to `case_documents`, with a column comment**,
so that **the application layer has somewhere to persist the RAG-issued ingestion
`documentReference`, with zero risk to existing rows, constraints, or reads**.

## Background
Column name and SQL type were the two open questions carried from Stage 1 (OQ-002, OQ-003) and
are now settled and Accepted in ADR-001/ADR-002: `rag_document_reference`, typed `TEXT`, stored
verbatim, no `CHECK`, no index. This story ships only the schema change — no code reads or writes
the column yet, so it is a safe, independently deployable no-op until Story 2 lands.

## Acceptance criteria
- [ ] AC-006: Rows that predate the `V1013` migration show the new column `IS NULL` — no error, no backfill attempted.
- [ ] AC-008: `V1013__*.sql` adds exactly one nullable column to `case_documents`, plus a `COMMENT ON COLUMN` explaining the field and its nullability — no rename, no drop, no `NOT NULL`, no default, no data rewrite, no change to any other table — and is reviewed by `migration-reviewer` before merge.
- [ ] AC-009: Flyway migrates cleanly both on a fresh database and on a database already at `V1012` with existing `case_documents` rows; the `gradle integration` compose stack starts and the app reaches readiness.

## NFR links
- NFR-003 (Migration safety): `ALTER TABLE ... ADD COLUMN ... NULL` is additive and metadata-only on PostgreSQL 16 — no table rewrite, no lock escalation, no default backfill.
- NFR-006 (Migration governance): routed through `migration-reviewer`; shipped `V1000`–`V1012` are never edited.

## Out of scope for this story
- Any code that reads or writes the new column (Story 2)
- An index on the new column (FR-010; no query-by-reference read path exists today — see OQ-005, deferred)
- Backfilling historic rows (FR-008)

## Definition of done
- [ ] Code reviewed and approved
- [ ] All ACs covered by automated tests (unit + integration)
- [ ] Accessibility audit passed (axe-core + manual check) — **N/A, no UI in this change**
- [ ] No critical or high Snyk findings introduced
- [ ] Deployed to and verified on sandbox
- [ ] Jira ticket updated with test evidence
- [ ] **DoD delta:** `migration-reviewer` review completed and evidenced on the ticket for `V1013__add_rag_document_reference_to_case_documents.sql`; `gradle integration` confirmed green against a database already at `V1012` with existing rows (not just a fresh DB)

## Dependencies
- Blocks Story 2 (nothing to write to until the column exists)
- Independently deployable/reversible on its own — a no-op until Story 2 lands

## Notes / open questions
- OQ-004 (external consumers of `case_documents`) and OQ-005 (support/ops lookup-by-reference need,
  which would require a separate additive index migration) are carried forward unresolved; neither
  blocks this story.

---

## Story 2 — Persist and preserve the RAG `documentReference` on `case_documents`
**Jira: [DD-43137](https://tools.hmcts.net/jira/browse/DD-43137)**

As a **support/ops engineer investigating a document ingestion**,
I want **every uploaded document row to record the exact RAG `documentReference` that produced it,
and to keep that value unchanged through every later ingestion-status transition**,
so that **I can trace any stored document back to the exact upstream RAG ingestion transaction,
closing the ingestion-side half of the traceability gap DD-43084 closed on the answer side**.

## Background
`documentReference` is already resolved in `RetrieveMaterialAndUploadTask` a few lines before the
existing `saveDocumentUploaded(...)` call that updates the same `case_documents` row (design §2,
§4). This story adds the mapped `CaseDocument` field and threads the value into that single
existing `saveAndFlush` — no new write, no second transaction, no new repository method, no new
`TaskUtils` helper (ADR-002 rules out a parse step). Because `CaseDocument` is hydrated from the
row rather than constructed fresh, `CheckIngestionStatusForAllDefendantsTask`'s later phase-
transition saves (`INGESTED` / `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT`) carry the value forward
automatically with **no code change in that task** (design §5) — this is asserted, not built, so
it is folded into this story rather than split out, per Stage 1's explicit note that it "may be
folded into Story 2 if reviewers prefer."

The one exception to "written once, never changed" is a **retry** of
`RETRIEVE_MATERIAL_AND_UPLOAD` itself: a retry calls `initiateDocumentUpload(...)` again and the
new reference correctly overwrites the old one, last-write-wins, because it must identify the
ingestion transaction that produced the blob currently recorded in `blob_uri` (design §5). This is
deliberate and should be pinned by a test, not treated as a regression.

## Acceptance criteria
- [ ] AC-001: Given a `RETRIEVE_MATERIAL_AND_UPLOAD` execution where `initiateDocumentUpload(...)` returns a `documentReference`, when the task completes successfully, then the `case_documents` row for that `doc_id` holds that value in the new column — preserved exactly as received, with no truncation, trimming, case-folding, or re-formatting.
- [ ] AC-002: The value is written in the **same** `saveAndFlush` as `docName`, `blobUri`, `contentType`, `sizeBytes`, `uploadedAt`, `ingestionPhase = UPLOADED` — verified by a unit test that captures the `CaseDocument` passed to `CaseDocumentRepository.saveAndFlush(...)` and asserts a single invocation carrying all of those fields plus the new one.
- [ ] AC-003: Given a row with the value persisted, when `CheckIngestionStatusForAllDefendantsTask` subsequently transitions the phase to `INGESTED`, `FAILED`, or `EXCEEDED_FILE_SIZE_LIMIT`, then the new column still holds the original value — not nulled, not changed.
- [ ] AC-004: Given RAG returns a null or blank `documentReference`, when the task runs, then the new persistence code throws no exception of its own and the task's existing outcome (retry via `ExecutionStatus.INPROGRESS`) is unchanged from current behaviour; any row written shows the new column `IS NULL`.
- [ ] AC-005: A row created by `IdpcAvailabilityService.persistCaseDocument(...)` in phase `WAITING_FOR_UPLOAD` shows the new column `IS NULL` until upload initiation completes for that document.
- [ ] AC-010: `RetrieveMaterialAndUploadTaskTest` and `CheckIngestionStatusForAllDefendantsTaskTest` compile and pass with any updated signatures.
- [ ] AC-014: Whichever SQL type Design settled on (`TEXT`, per ADR-002), a `documentReference` that does not match the RAG contract's UUID pattern must neither (a) be silently discarded nor (b) fail the ingestion flow. The chosen behaviour is recorded in the ADR and covered by an explicit test.

## NFR links
- NFR-001 (Data protection): `documentReference` is an opaque, RAG-issued correlation string, already logged at INFO by both tasks today — persisting it introduces no new data-protection exposure.
- NFR-004 (Backward compatibility): no change to any GET response shape or SQL read path; `IdpcAvailabilityService` and `DocumentService` keep compiling and behaving unchanged.
- NFR-007 (Code quality): PMD/JaCoCo pass at existing thresholds with no new suppressions; the extra `saveDocumentUploaded(...)` parameter stays within the enabled PMD rule categories (design §4).
- NFR-008 (Platform): the existing Spring Data JPA `saveAndFlush` pattern is preserved — no raw SQL introduced on this path.
- NFR-009 (Logging): no new log statement required; JSON-to-stdout logging and no-document-body-logged rules unchanged.

## Out of scope for this story
- Any API/contract exposure of the field, or a lookup-by-reference endpoint (FR-009, out of scope for the whole requirement)
- An index on the new column (FR-010)
- Refactoring `CheckIngestionStatusForAllDefendantsTask` to read the reference from `case_documents` instead of job data (OQ-006) — job-data threading via `CTX_DOC_REFERENCE_KEY` is explicitly unchanged (FR-006)
- Backfilling historic rows (FR-008)
- A write-once guard against retry overwrites — last-write-wins is the intended behaviour (design §5)

## Definition of done
- [ ] Code reviewed and approved
- [ ] All ACs covered by automated tests (unit + integration)
- [ ] Accessibility audit passed (axe-core + manual check) — **N/A, no UI in this change**
- [ ] No critical or high Snyk findings introduced
- [ ] Deployed to and verified on sandbox
- [ ] Jira ticket updated with test evidence
- [ ] **DoD delta:** `RetrieveMaterialAndUploadTaskTest` extended with explicit non-UUID-shaped-string, `null`, and `""` cases against the captured `CaseDocument` (AC-014, AC-004); `CheckIngestionStatusForAllDefendantsTaskTest` extended to stub an already-populated `ragDocumentReference` and assert it survives at least the `INGESTED` and one failure transition (AC-003); a retry-of-`RETRIEVE_MATERIAL_AND_UPLOAD` overwrite case is asserted so the last-write-wins semantic is pinned, not incidental (design §5)

## Dependencies
- Needs Story 1's column to exist first
- Supersedes Stage 1's candidate "Story 3" (no regression on the ingestion status path) — folded in here because it is assertion-only with no production change of its own beyond what this story already delivers (`CheckIngestionStatusForAllDefendantsTask` requires **zero code changes**)

## Notes / open questions
- OQ-001 (confirm the restated scope matches the literal Jira ticket text) and OQ-006 (confirm the
  ticket doesn't actually want the status task to read the reference from the row instead of job
  data) are both still open per Stage 1 and are due before implementation of this story begins —
  if either resolves differently, this story's scope may need revisiting.
- OQ-007 (retention/purge — assumed the column simply inherits the parent row's policy) is still
  open; flag to data-protection review before merge.

---

## Story 3 — End-to-end test coverage for `rag_document_reference`
**Jira: [DD-43138](https://tools.hmcts.net/jira/browse/DD-43138)**

As a **CDKS developer**,
I want **an integration test proving `rag_document_reference` is populated through the real
ingestion flow (real DB, Azurite, and the RAG WireMock stub), not just asserted against a mocked
repository call**,
so that **persistence of the RAG ingestion reference is proven end-to-end, and existing API/response
behaviour is confirmed unaffected**.

## Background
Stage 1 requires the value to be proven live, not just unit-mocked (NFR-005), and requires proof
that no API surface changed (AC-007). Design flags a real ordering hazard to resolve here rather
than assume away: `POST /document-upload` has **two** WireMock stubs in the shared `integration`
compose container — a static mapping with a fixed `documentReference`
(`wiremock/mappings/document_upload_to_generate_url.json`) and a programmatic scenario stub
(`DocumentIngestionInitiationApiStub`) used by the `IngestionProcess*HttpLiveTest` suites that
returns a fresh `randomUUID()` per response and can shadow the static mapping depending on test
run order (design §Testing, "Stub caveat"). This story must pick one of the design's three
mitigations (dedicated higher-priority stub with a known fixed value; assert against WireMock's
own request/response journal; or assert only that the column is non-null and matches the value
also placed in job data) rather than hard-coding the static mapping's fixed UUID and risking a
flake on suite ordering.

## Acceptance criteria
- [ ] AC-007: No controller, response DTO, or mapper changes; `version.cdk` is unchanged; `IngestionProcessByCaseHttpLiveTest` (and every other existing `*HttpLiveTest`) passes with its assertions unmodified.
- [ ] AC-011: An integration test asserts the new column is populated with the stubbed `documentReference` from `document_upload_to_generate_url.json` after the ingestion flow reaches `UPLOADED`, i.e. the value survives end-to-end and not just in a mocked unit.
- [ ] AC-012: `gradle clean build` (including `integration`) passes; PMD and JaCoCo are green at existing, unmodified thresholds; CodeQL and the secrets scanner are clean.
- [ ] AC-013: The diff introduces no PII, case content, court reference number, or `CJSCPPUID` into the migration, tests, or fixtures; WireMock/Azurite fixture values remain synthetic.

## NFR links
- NFR-005 (Testability): dedicated `integrationTest` coverage against the existing WireMock stub, run as part of `gradle integration` against the full compose stack.
- NFR-004 (Backward compatibility): existing `*HttpLiveTest` suites (`IngestionProcessByCaseHttpLiveTest`, `IngestionProcessHttpLiveTest`, `IngestionStatusHttpLiveTest`, `DocumentHttpLiveTest`) confirmed unmodified and green, and the raw `INSERT INTO case_documents (...)` fixture in `IngestionProcessByCaseHttpLiveTest` keeps working because the column is nullable.

## Out of scope for this story
- Any production code change — this story is test-only, layered on top of Story 1 and Story 2
- Contract tests — no contract change (FR-009); `pactVerificationTest` is unaffected and out of scope here

## Definition of done
- [ ] Code reviewed and approved
- [ ] All ACs covered by automated tests (unit + integration)
- [ ] Accessibility audit passed (axe-core + manual check) — **N/A, no UI in this change**
- [ ] No critical or high Snyk findings introduced
- [ ] Deployed to and verified on sandbox
- [ ] Jira ticket updated with test evidence
- [ ] **DoD delta:** new live test (mirroring the pattern of `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` from DD-43084 — job seeding, real `JobExecutor`, `openConnection()` + `Awaitility.await()`, `finally` cleanup) added under `src/integrationTest/`, resolving the two-stub ordering hazard explicitly (design §Testing "Stub caveat") rather than assuming the static mapping wins; app-startup-against-a-`V1012`-database-with-existing-rows confirmed as the live proof that `ddl-auto: validate` accepts the new `String`→`TEXT` mapping (AC-006/AC-009, cross-checked against Story 1)

## Dependencies
- Needs Story 1 (column must exist) and Story 2 (persistence code must exist) to be meaningful; can be scoped and drafted in parallel but cannot pass CI until both have merged
- Closes out NFR-005 and the remaining untested ACs (AC-007, AC-011, AC-012, AC-013) left after Stories 1–2

## Notes / open questions
- The WireMock stub-ordering hazard (design §Testing) is a genuine implementation risk, not a
  documentation nicety — flag to the assigned developer before they write the IT, so they choose
  one of the three documented mitigations deliberately rather than discovering the flake later.

---

## Summary for review

| Story | Jira | Covers | Depends on |
|---|---|---|---|
| 1 — Schema migration | [DD-43136](https://tools.hmcts.net/jira/browse/DD-43136) | FR-003, AC-006, AC-008, AC-009 | none |
| 2 — Persist + preserve across phase transitions | [DD-43137](https://tools.hmcts.net/jira/browse/DD-43137) | FR-001, FR-002, FR-004, FR-005, FR-006, FR-007, FR-008, AC-001–AC-005, AC-010, AC-014 | Story 1 |
| 3 — End-to-end test coverage | [DD-43138](https://tools.hmcts.net/jira/browse/DD-43138) | NFR-005, AC-007, AC-011, AC-012, AC-013 | Stories 1 & 2 |

**Not a story in this requirement** (per Stage 1, unchanged): any API exposure of the field, a
lookup-by-reference endpoint, or a backfill job.
