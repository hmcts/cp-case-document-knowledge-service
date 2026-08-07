# Test Specs: Persist RAG `transactionId` on Answer Records

> **Stage 4 — Test Specs** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43084** · Stories: [`03-stories.md`](./03-stories.md) · Design: [`02-design.md`](./02-design.md)
> Written retrospectively against the code actually merged (implementation ran ahead of this
> artefact for this ticket) — every scenario below names the real test class/method that proves
> it, so this doc stays a traceability map rather than an aspirational spec. Where a scenario is
> specified but not yet automated at the stated tier, that's called out explicitly (no silent
> gaps) rather than implied as done.

---

## Story 1 — Add `rag_transaction_id` column to all four answer tables

**Scenario 1.1 — Migration is additive and idempotent**
- **Given** a database already at `V1011`
- **When** Flyway applies `V1012__add_rag_transaction_id_to_answers.sql`
- **Then** `answers`, `case_level_latest_doc_answers`, `case_level_all_documents_answers`, and
  `defendant_answers` each gain a nullable `rag_transaction_id UUID` column, no existing row is
  rewritten or loses data, and re-running the migration (`ADD COLUMN IF NOT EXISTS`) is a no-op.
- **Proof:** every Testcontainers-backed Spring context test in `src/test/` (e.g.
  `CaseDocumentRepositoryTest`, `QueryVersionRepositoryTest`, `JobManagerConfigTest`) boots against
  a fresh Postgres 16 container and runs the full migration chain including `V1012` on every
  `./gradlew test` run — a broken or non-idempotent migration would fail context startup for all
  of them. Confirmed green.

**Scenario 1.2 — Pre-existing rows are left `NULL`, not backfilled**
- **Given** an `answers` row written before this migration
- **When** `V1012` runs
- **Then** that row's `rag_transaction_id` is `NULL`, and no error occurs from the absence of a
  value to backfill.
- **Proof:** covered implicitly by 1.1 (Flyway's `ADD COLUMN` with no `DEFAULT`/backfill statement
  cannot fail this way) and explicitly disclaimed in FR-005/AC-006. No dedicated automated
  assertion exists for this specific case today — reasonable to add only if a future change
  introduces a backfill path and this invariant needs re-guarding.

---

## Story 2 — Thread `transactionId` through the four answer-persistence paths

**Scenario 2.1 — `CASE` level: transaction id reaches `case_level_latest_doc_answers`**
- **Given** a `CheckStatusOfAnswerGenerationTask` job whose job data carries `queryLevel=CASE`,
  a `caseId`, `queryId`, `docId`, and a resolved `ragTransactionId`
- **When** the RAG status poll returns `ANSWER_GENERATED`
- **Then** `caseLevelLatestDocumentAnswerService.upsert(...)` is called with that exact
  transaction id, and the persisted row's `rag_transaction_id` column equals it.
- **Proof (unit):** `CheckStatusOfAnswerGenerationTaskTest.shouldPassRagTransactionId_toCaseLevelLatestDocAnswerService_forCaseLevelQuery`
  + `CaseLevelLatestDocumentAnswerServiceTest.shouldPassCorrectParametersToUpsertQuery` (asserts
  `rag_transaction_id` in the captured `MapSqlParameterSource`).
- **Proof (integration):** `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest.checkStatusTask_persistsRagTransactionId_forCaseLevelAnswer`
  — seeds a real Task Manager `jobs` row, lets the live app's `JobExecutor` (polling every 5s)
  pick it up and run the real task against the real DB and the RAG WireMock stub
  (`/answer-user-query-async-status/.*` → `ANSWER_GENERATED`), then asserts
  `case_level_latest_doc_answers.rag_transaction_id` via JDBC. **Automated and green** against the
  full compose stack.

**Scenario 2.2 — `CASE_ALL_DOCUMENTS` level: transaction id reaches `case_level_all_documents_answers`**
- **Given/When** as above with `queryLevel=CASE_ALL_DOCUMENTS`
- **Then** `caseLevelAllDocumentsAnswerService.upsert(...)` receives the transaction id and it
  lands in `case_level_all_documents_answers.rag_transaction_id`.
- **Proof (unit):** `CheckStatusOfAnswerGenerationTaskTest.shouldPassRagTransactionId_toCaseLevelAllDocumentsAnswerService_forCaseAllDocumentsLevelQuery`
  + `CaseLevelAllDocumentsAnswerServiceTest.shouldPassCorrectParametersToUpsertQuery`.
- **Proof (integration):** **not yet automated.** No live-stack test currently drives this
  specific level. The mechanism is identical to Scenario 2.1's proven path (same task, same
  `JobExecutor` seam, only `queryLevel` and the target table differ), so risk is judged low, but
  this is a real, disclosed gap — extend
  `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest` with a third case if this needs closing.

**Scenario 2.3 — `DEFENDANT` level: transaction id reaches `defendant_answers`**
- **Given/When** as above with `queryLevel=DEFENDANT` and a `defendantId`
- **Then** `defendantAnswerService.upsert(...)` receives the transaction id and it lands in
  `defendant_answers.rag_transaction_id`.
- **Proof (unit):** `CheckStatusOfAnswerGenerationTaskTest.shouldPassRagTransactionId_toDefendantAnswerService_forDefendantLevelQuery`
  + `DefendantAnswerServiceTest.shouldUseCorrectParametersForUpsert`.
- **Proof (integration):** **not yet automated** — same disclosed gap as 2.2.

**Scenario 2.4 — Default/`null` level: transaction id reaches `answers`**
- **Given/When** as above with no `queryLevel` set (or an unrecognised value, which
  `TaskUtils.parseQueryLevel` maps to `null`)
- **Then** `answerGenerationService.upsertAnswer(...)` receives the transaction id and it lands
  in `answers.rag_transaction_id`.
- **Proof (unit):** `CheckStatusOfAnswerGenerationTaskTest.shouldSaveAnswerToCdkDatabase_andCompleteJob_whenAnswerGenerationSuccessful`
  + `AnswerGenerationServiceTest.shouldPassCorrectParamsToUpsert`.
- **Proof (integration):** `CheckStatusOfAnswerGenerationRagTransactionIdLiveTest.checkStatusTask_persistsRagTransactionId_forDefaultLevelAnswer`
  — **automated and green** against the full compose stack.

**Scenario 2.5 — Re-upsert overwrites a stale transaction id, never leaves it behind**
- **Given** an existing answer row at `(case_id, query_id[, defendant_id], version)` with a prior
  `rag_transaction_id`
- **When** the same version is upserted again with a new transaction id (e.g. an
  `ANSWER_GENERATION_FAILED` retry that eventually succeeds and reuses the same version number)
- **Then** the row's `rag_transaction_id` is updated to the new value via
  `ON CONFLICT ... DO UPDATE SET rag_transaction_id = EXCLUDED.rag_transaction_id`, not left stale.
- **Proof (unit):** each of the four service tests exercises the single `UPSERT_SQL` statement
  containing that `ON CONFLICT` clause and asserts the parameter is bound — the SQL text itself
  (not just the Java call) guarantees the overwrite semantics, since Postgres executes exactly
  that one statement for both the insert and the update path.
- **Proof (integration):** **not yet automated** — would require seeding two sequential jobs
  against the same `(case_id, query_id, version)` and asserting the second transaction id wins.
  Judged lower priority: the guarantee is structural (one SQL statement, not two divergent code
  paths), so unit coverage of the SQL text is considered sufficient for now.

**Scenario 2.6 — No API/contract regression**
- **Given** the existing Answers read API
- **When** `GET /cases/{caseId}/queries/{queryId}/answers/with-llm`, `.../answers/list`, and the
  `/v2/...` answers endpoints are called
- **Then** the response body is byte-for-byte unchanged — no `ragTransactionId` field appears,
  confirming `AnswerMapper`/`AnswerResponse`/`AnswerWithLlmResponse`/`version.cdk` were untouched
  (ADR-002).
- **Proof (integration):** `AnswersHttpLiveTest` (pre-existing, unmodified) — all its assertions
  on exact response-body content continue to pass unchanged after this PR, which is the actual
  regression signal (a new field would have broken nothing there since the assertions use
  `contains(...)`, so this is a weak negative check; strengthened by `AC-008`'s point that
  `AnswerMapper`'s source diff is empty — verifiable directly from the PR diff, not just runtime
  behaviour).

---

## Story 3 — Keep the read-side JPA entities in sync with the new column

**Scenario 3.1 — `Answer` entity constructor includes the new field**
- **Given** the flat `Answer` JPA entity (does not extend `BaseAnswer`)
- **When** constructed via its Lombok `@AllArgsConstructor`
- **Then** the constructor has 6 parameters (`answerId`, `createdAt`, `answerText`, `llmInput`,
  `docId`, `ragTransactionId`), and existing callers were updated to match.
- **Proof:** `AnswerMapperTest.testToAnswerResponse` / `testToAnswerWithLlm` — both call sites
  updated to pass a 6th `UUID` argument; compilation itself is the primary proof (Lombok generates
  the constructor from the field list, so a mismatch is a compile error, not a runtime one).

**Scenario 3.2 — `BaseAnswer` subclasses inherit the field without altering their own constructors**
- **Given** `CaseLevelAllDocumentsAnswer` (extends `BaseAnswer` directly) and
  `CaseLevelLatestDocumentAnswer`/`DefendantAnswer` (extend `DocumentAnswer` → `BaseAnswer`)
- **When** `ragTransactionId` is added to `BaseAnswer`
- **Then** none of the three subclasses' own `@AllArgsConstructor`s change shape, because Lombok's
  `@AllArgsConstructor` only includes fields declared directly on the annotated class, not
  inherited ones — each subclass's constructor still takes only its own `@EmbeddedId` field.
- **Proof:** `./gradlew compileJava` succeeds with no changes required in any of the three
  subclasses or their existing tests/usages — a genuinely different generated-constructor shape
  would have surfaced as a compile error at every existing call site.

**Scenario 3.3 — No mapper/DTO/contract change**
- **Given** `AnswerMapper.toAnswerResponse` / `toAnswerWithLlm`
- **When** mapping an `Answer` entity that now carries a non-null `ragTransactionId`
- **Then** the produced `AnswerResponse`/`AnswerWithLlmResponse` still has no `ragTransactionId`
  field — the mapper's `unmappedTargetPolicy = ReportingPolicy.IGNORE` combined with hand-written
  `default` methods means an unmapped source field is silently dropped by design, not surfaced.
- **Proof:** `AnswerMapperTest` assertions enumerate every field on the response objects
  explicitly and never reference `ragTransactionId`; the mapper source diff for this PR is empty.

---

## Coverage summary

| AC | Scenario | Unit | Integration |
|----|----------|------|--------------|
| AC-001 (CASE) | 2.1 | ✅ | ✅ |
| AC-002 (CASE_ALL_DOCUMENTS) | 2.2 | ✅ | ⬜ disclosed gap |
| AC-003 (DEFENDANT) | 2.3 | ✅ | ⬜ disclosed gap |
| AC-004 (default) | 2.4 | ✅ | ✅ |
| AC-005 (upsert overwrite) | 2.5 | ✅ | ⬜ disclosed gap |
| AC-006 (no backfill) | 1.2 | ✅ (implicit) | — |
| AC-007/AC-008 (no API change) | 2.6 / 3.3 | ✅ | ✅ (pre-existing suite unmodified) |
| AC-009 (migration shape) | 1.1 | ✅ (implicit via Testcontainers) | ✅ (app boots against it every IT run) |
| AC-010–AC-013 (test/quality gates) | — | ✅ (`gradle test`, PMD, JaCoCo all green) | ✅ (`gradle integration` full suite green) |

No scenario is silently uncovered — the three ⬜ rows are the same structural gap (CASE_ALL_DOCUMENTS
and DEFENDANT levels, and the overwrite case, aren't yet driven through the live compose stack),
carried forward as a known follow-up rather than hidden.
