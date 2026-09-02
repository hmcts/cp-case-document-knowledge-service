# Design: Persist RAG `transactionId` on Answer Records

> **Stage 2 — Architecture & Design** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43084** · Requirements: [`01-requirements.md`](./01-requirements.md) · ADRs: [`adrs/DD-43084-persist-rag-transaction-id.md`](../adrs/DD-43084-persist-rag-transaction-id.md)
> Add `rag_transaction_id UUID NULL` to all four answer tables (`V1012`); thread the value
> `CheckStatusOfAnswerGenerationTask` already resolves through to each of the four upsert calls.
> No API/contract change, no new endpoint, no ACL change.

---

## Detailed Design

### 1. Migration (this repo — no external contract dependency)

**File:** `src/main/resources/db/migration/V1012__add_rag_transaction_id_to_answers.sql` (next
version after `V1011`).

```sql
ALTER TABLE answers
    ADD COLUMN IF NOT EXISTS rag_transaction_id UUID NULL;
COMMENT ON COLUMN answers.rag_transaction_id IS
'RAG service transactionId that produced this answer version (nullable — not backfilled for rows written before this column existed).';

ALTER TABLE case_level_latest_doc_answers
    ADD COLUMN IF NOT EXISTS rag_transaction_id UUID NULL;
COMMENT ON COLUMN case_level_latest_doc_answers.rag_transaction_id IS
'RAG service transactionId that produced this answer version.';

ALTER TABLE case_level_all_documents_answers
    ADD COLUMN IF NOT EXISTS rag_transaction_id UUID NULL;
COMMENT ON COLUMN case_level_all_documents_answers.rag_transaction_id IS
'RAG service transactionId that produced this answer version.';

ALTER TABLE defendant_answers
    ADD COLUMN IF NOT EXISTS rag_transaction_id UUID NULL;
COMMENT ON COLUMN defendant_answers.rag_transaction_id IS
'RAG service transactionId that produced this answer version.';
```

- Plain `UUID NULL` — no `NOT NULL`, no `DEFAULT`, no index. `ADD COLUMN` of a nullable column
  with no default is a metadata-only operation on PostgreSQL 16 (no table rewrite), matching
  NFR-002.
- No index added: this ticket's driver is traceability-on-write, not a query pattern of
  "look up an answer by transaction id" — nothing in this repo needs that access path today (see
  `01-requirements.md` out-of-scope). Adding one later is a separate, independent, additive
  migration if a lookup need materialises.
- `case_query_status` is not touched (ADR-001) — it is a current-pointer table, not a
  per-version one.
- Route through `migration-reviewer` per CLAUDE.md's hard rule for any `db/migration` change
  (AC-009).

### 2. Files touched

| File | Change |
|---|---|
| `db/migration/V1012__add_rag_transaction_id_to_answers.sql` *(new)* | Adds the column to all four tables. |
| `jobmanager/queryflow/CheckStatusOfAnswerGenerationTask.java` | Pass `transactionId` into each of the four upsert calls in the `switch (level)` block. |
| `services/AnswerGenerationService.java` | `upsertAnswer(...)` gains a `UUID transactionId` param; `SQL_UPSERT_ANSWER` inserts/updates `rag_transaction_id`. |
| `services/CaseLevelLatestDocumentAnswerService.java` | `upsert(...)` gains the param; `UPSERT_SQL` updated. |
| `services/CaseLevelAllDocumentsAnswerService.java` | `upsert(...)` gains the param; `UPSERT_SQL` updated. |
| `services/DefendantAnswerService.java` | `upsert(...)` gains the param; `UPSERT_SQL` updated. |
| `util/TaskUtils.java` | `buildAnswerParams(...)` gains the param and adds it to the returned `MapSqlParameterSource`. |
| `domain/Answer.java` | New `@Column(name = "rag_transaction_id") private UUID ragTransactionId;` (flat entity, doesn't extend `BaseAnswer`). |
| `domain/BaseAnswer.java` | New `@Column(name = "rag_transaction_id") protected UUID ragTransactionId;` (inherited by `CaseLevelAllDocumentsAnswer` directly, and by `DocumentAnswer` → `CaseLevelLatestDocumentAnswer`/`DefendantAnswer`). |

**Not changed:** `GenerateAnswerForQueryTask.java` (already forwards `transactionId` correctly —
the gap is entirely downstream), `case_query_status` / `trg_answers_after_insert`, any controller,
`AnswerMapper`, any OpenAPI model, `version.cdk`, `acl/cdks-rules.drl`, any existing migration.

### 3. `CheckStatusOfAnswerGenerationTask` — threading the value through

`transactionId` is already resolved as a `UUID` at the top of `execute(...)` (line 74 today). The
only change is passing it into the four existing calls inside the `switch (level)` block:

```java
switch (level) {
    case QueryLevel.CASE:
        caseLevelLatestDocumentAnswerService.upsert(
                caseId, queryId, answerResponseBody.getLlmResponse(), llmInputJson, documentId, transactionId);
        break;
    case QueryLevel.CASE_ALL_DOCUMENTS:
        caseLevelAllDocumentsAnswerService.upsert(
                caseId, queryId, answerResponseBody.getLlmResponse(), llmInputJson, transactionId);
        break;
    case QueryLevel.DEFENDANT:
        defendantAnswerService.upsert(
                caseId, queryId, defendantId, answerResponseBody.getLlmResponse(), llmInputJson, documentId, transactionId);
        break;
    case null, default:
        answerGenerationService.upsertAnswer(
                caseId, queryId, answerResponseBody.getLlmResponse(), llmInputJson, documentId, transactionId);
        break;
}
```

No other logic in this task changes — retry handling, the `ANSWER_GENERATION_FAILED` branch, and
the polling call are all untouched.

### 4. Service-layer changes (pattern is identical across all four)

Example — `CaseLevelAllDocumentsAnswerService` (the simplest of the four, no `doc_id`):

```java
private static final String UPSERT_SQL = """
    INSERT INTO case_level_all_documents_answers
    (case_id, query_id, version, created_at, answer, llm_input, rag_transaction_id)
    VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :rag_transaction_id)
    ON CONFLICT (case_id, query_id, version) DO UPDATE SET
        answer = EXCLUDED.answer,
        llm_input = EXCLUDED.llm_input,
        rag_transaction_id = EXCLUDED.rag_transaction_id,
        created_at = EXCLUDED.created_at
""";

@Transactional
public void upsert(final UUID caseId, final UUID queryId, final String answer,
                   final String llmInput, final UUID ragTransactionId) {
    final int version = getVersionNumber(caseId, queryId);
    final MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("case_id", caseId)
            .addValue("query_id", queryId)
            .addValue("version", version)
            .addValue("answer", answer)
            .addValue("llm_input", llmInput)
            .addValue("rag_transaction_id", ragTransactionId);
    jdbc.update(UPSERT_SQL, params);
    // ... case_query_status update unchanged
}
```

`CaseLevelLatestDocumentAnswerService` and `DefendantAnswerService` follow the same shape, adding
`rag_transaction_id` alongside their existing `doc_id` column. `AnswerGenerationService` instead
extends the shared `TaskUtils.buildAnswerParams(...)` helper:

```java
// TaskUtils.java
public static MapSqlParameterSource buildAnswerParams(final UUID caseId, final UUID queryId,
        final Integer version, final String answer, final String llmInput,
        final UUID documentId, final UUID ragTransactionId) {
    return new MapSqlParameterSource()
            .addValue("case_id", caseId)
            .addValue("query_id", queryId)
            .addValue("version", version)
            .addValue("answer", answer)
            .addValue("llm_input", llmInput)
            .addValue("doc_id", documentId)
            .addValue("rag_transaction_id", ragTransactionId);
}
```

`buildCaseStatusParams` / `GLOBAL_UPDATE_CASE_QUERY_STATUS` / `case_query_status` are **not**
touched — that table has no per-version transaction concept (ADR-001).

`SQL_UPSERT_ANSWER` in `AnswerGenerationService` gets the same `rag_transaction_id` column added
to its `INSERT`/`ON CONFLICT` clauses as the example above.

### 5. Read-side entities (JPA)

Two edits keep the four JPA entities in sync with their tables, even though nothing maps this
field out to the API yet (ADR-002):

```java
// BaseAnswer.java — inherited by CaseLevelAllDocumentsAnswer, DocumentAnswer
// (→ CaseLevelLatestDocumentAnswer, DefendantAnswer)
@Column(name = "rag_transaction_id")
protected UUID ragTransactionId;
```

```java
// Answer.java — flat entity, does not extend BaseAnswer
@Column(name = "rag_transaction_id")
private UUID ragTransactionId;
```

`AnswerMapper`, `AnswerResponse`, `AnswerWithLlmResponse`, `AnswersResponse` are all left
unchanged (ADR-002) — this is a read-side data-completeness change only, not an API change.

### 6. Error handling

No new error paths. If `transactionId` were ever `null` at the call site (it can't be today —
`CheckStatusOfAnswerGenerationTask` already dereferences it via `.toString()` for the status-poll
call earlier in the same method, so a `null` would already have thrown an NPE and been caught by
the task's existing `catch (Exception ex) { return retry(...); }` before reaching the upsert
calls), the `MapSqlParameterSource.addValue("rag_transaction_id", null)` path is safe — Postgres
just stores `NULL`, consistent with FR-005/FR-006's "no backfill" nullable design.

---

## Testing

Scoping only — Test Specs stage owns the actual scenarios.

**Unit (`src/test/`)**

| Target | Covers |
|---|---|
| `AnswerGenerationServiceTest` (extend) | `upsertAnswer(..., transactionId)` writes `rag_transaction_id` into the captured `MapSqlParameterSource`; existing version/status assertions unaffected. |
| `CaseLevelLatestDocumentAnswerServiceTest` / `CaseLevelAllDocumentsAnswerServiceTest` / `DefendantAnswerServiceTest` (extend) | Same pattern per table — capture `MapSqlParameterSource`, assert `rag_transaction_id` equals the value passed in. |
| `CheckStatusOfAnswerGenerationTaskTest` (extend) | For each `QueryLevel` branch (`CASE`, `CASE_ALL_DOCUMENTS`, `DEFENDANT`, `null`/default), verify the corresponding service's upsert method is invoked with the transaction id parsed from job data — not a null or hardcoded value. |
| `TaskUtilsTest` (new or extend, if one exists — none found; create `TaskUtilsTest` if this is its first dedicated test) | `buildAnswerParams(...)` includes `rag_transaction_id` in the returned `MapSqlParameterSource`. |

**Integration (`src/integrationTest/`)** — extend the existing async-answer WireMock flow (stubs
already present: `answer-user-query-async.json` → `answer_user_query_async_response.json`
containing a fixed `transactionId`; `retrieve_answers_for_transactionId.json` →
`answer_for_transaction_response.json` for the `ANSWER_GENERATED` status poll):

1. Drive a query through to a generated answer (however the existing suite triggers
   `GenerateAnswerForQueryTask`/`CheckStatusOfAnswerGenerationTask` today, if such an end-to-end
   trigger exists in `src/integrationTest/`; otherwise seed job data directly and invoke the task
   bean, mirroring how `AnswersHttpLiveTest` seeds `answers` directly via JDBC).
2. Query the relevant answer table directly via JDBC and assert `rag_transaction_id` equals the
   fixed transaction id from the WireMock fixture (`c44576d4-ee3a-436e-b042-e867747171da`).
3. Re-run the same (case, query, version) through the flow a second time (simulating an upsert)
   with a different WireMock-stubbed transaction id and assert the column is overwritten, not
   left stale (AC-005).
4. `AnswersHttpLiveTest` — run unmodified; assert its existing response-body assertions still pass
   with no new field appearing (AC-007), i.e. add no new assertions there, just confirm the
   existing suite is green.

**Contract tests:** none — no contract change (ADR-002); `pactVerificationTest` unaffected.

**Verify empirically:** confirm whether any test infrastructure already drives
`CheckStatusOfAnswerGenerationTask` end-to-end via HTTP/JobManager in `src/integrationTest`, or
whether the task needs to be invoked directly as a Spring bean in the new IT — this determines
whether step 1 above is a new IT class or an extension of an existing one, and should be resolved
at Test Specs stage before authoring tests.
