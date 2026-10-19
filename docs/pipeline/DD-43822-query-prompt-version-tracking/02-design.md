# Design: Persist Query Prompt Version (DD-43822)

> **Stage 2 — Architecture & Design** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43822** · Branch: `DD-43822-query-prompt-version-tracking`
>
> Per explicit instruction: this is a minimal schema/traceability fix, not a feature redesign. No
> NFR section, no alternatives/trade-off analysis, no C4 diagrams — just the facts and the fix.
>
> **Decision confirmed (2026-10-19):** backfill existing `query_versions` rows (Fix A below) rather
> than leaving pre-existing, already-loaded prompt versions unlabelled. Rationale: `query_versions`
> rows are never deleted, and whichever row has the latest `effective_at` is read on every answer
> generation until a query's prompt is next edited — leaving existing rows unlabelled would mean any
> query not actively being re-edited keeps producing `query_prompt_version = NULL` answers
> indefinitely, not just for a one-off historical gap. Backfilling is a single, cheap, non-destructive
> `UPDATE` against a small prompt-catalogue table (not case data, not case-volume scale) and makes the
> fix take effect for every query immediately on deploy.

---

**Design approved (2026-10-19).** Proceeding to Stage 3 (User Story) and implementation.

## Problem, grounded in the current code

| # | Fact | Evidence |
|---|---|---|
| 1 | `query_versions`' primary key is `(query_id, effective_at)` only — no version number/label column exists today. | `V1001__case_documents_ai_schema.sql:31-40` |
| 2 | New rows are created by `QueryService.upsertDefinitions(...)`, which builds a `QueryVersion` keyed by `new QueryVersionId(queryId, effectiveAt)` and saves it — there is no versioning logic here at all today. | `QueryService.java:218-224`, reached via `QueriesController` |
| 3 | Every answer table (`answers`, `defendant_answers`, `case_level_all_documents_answers`, `case_level_latest_doc_answers`) already has its **own, unrelated** `version` integer column — an answer-regeneration counter, computed as `COALESCE(MAX(version), 0) + 1` per `(case_id, query_id[, defendant_id])`. This must not be confused or reused for the new concept. | `AnswerId.java`, `DefendantAnswerId.java`; `DefendantAnswerService.java:23-27`, `CaseLevelAllDocumentsAnswerService.java`, `CaseLevelLatestDocumentAnswerService.java`, `AnswerGenerationService.java:22-26` — all four share this identical pattern |
| 4 | The answer-generation flow reads the query's current prompt via `QueryDefinitionLatestRepository.findByQueryId(...)`, backed by the `v_query_definitions_latest` view, but never captures or carries forward *which* version of that prompt was used. Once an answer is persisted, it cannot be traced back to the prompt version that produced it. | `GenerateAnswerForQueryTask.java:78-88` (builds the RAG request from `qdl`); `v_query_definitions_latest` view, `V1001__case_documents_ai_schema.sql:44-56` |
| 5 | The async round trip already threads values through job data between tasks (e.g. `CTX_RAG_TRANSACTION_ID`) — the same mechanism is available for threading a version value from generation-start to persistence-time. | `GenerateAnswerForQueryTask.java:90-99` → `CheckStatusOfAnswerGenerationTask.java:77-79` |
| 6 | Four separate upsert code paths persist an answer, one per `QueryLevel` (`CASE`, `CASE_ALL_DOCUMENTS`, `DEFENDANT`, default/legacy) — all four need the same new column. | `CheckStatusOfAnswerGenerationTask.java:97-126` dispatches to `caseLevelLatestDocumentAnswerService`, `caseLevelAllDocumentsAnswerService`, `defendantAnswerService`, `answerGenerationService` respectively |

---

## Fix

### A. `query_versions` gets an explicit, ordered version label

- New Flyway migration adds `version_label TEXT` to `query_versions`.
- Backfill existing rows in the same migration: `ROW_NUMBER() OVER (PARTITION BY query_id ORDER BY effective_at)`, rendered as `'V' || row_number()`. Make the column `NOT NULL` once backfilled.
- Going forward, `QueryService.upsertDefinitions(...)` (`QueryService.java:218`) computes the next label the same way the four answer services already compute their own next version — a `SELECT COUNT(*) FROM query_versions WHERE query_id = :queryId` (or `MAX` of a companion ordinal) before building the new row, formatted as `'V' || (n + 1)`. No prior version ⇒ `V1`. This reuses the existing house pattern rather than introducing a new mechanism (no DB trigger, no sequence).

### B. The version actually used is threaded through to the answer

1. `v_query_definitions_latest` view (`V1001`) and the `QueryDefinitionLatest` entity: add `version_label`.
2. `GenerateAnswerForQueryTask` (`GenerateAnswerForQueryTask.java:78`, where `qdl` is read): add the version label into the job data handed to the next task — a new `CTX_QUERY_PROMPT_VERSION` key, alongside the existing `CTX_RAG_TRANSACTION_ID` — so it survives the async round-trip.
3. `CheckStatusOfAnswerGenerationTask` (`CheckStatusOfAnswerGenerationTask.java:97`): read that job-data key and pass it as a new argument into whichever of the four `.upsert(...)` calls applies for the resolved `QueryLevel`.
4. Each of `DefendantAnswerService`, `CaseLevelAllDocumentsAnswerService`, `CaseLevelLatestDocumentAnswerService`, `AnswerGenerationService`: add a `query_prompt_version TEXT` column (same migration) to their respective table, and thread it through as a new `upsert(...)` parameter into their `INSERT ... ON CONFLICT DO UPDATE` statement (same shape as the existing `rag_transaction_id` column in each).

### Naming — deliberately distinct from the existing `version` column

Every answer table already has an integer `version` column (the answer-regeneration counter, fact 3 above). The new column is named **`query_prompt_version`** (text, e.g. `"V3"`) specifically so it can never be confused with that existing column — they are two independent counters that happen to share the word "version."

---

## Out of scope

- Backfilling `query_prompt_version` onto historic answer rows — none of the four answer tables record this today, so there is nothing to backfill from.
- Any API/OpenAPI change exposing `version_label` on `QuerySummary`/`QueryVersionSummary` — a small, separate addition if wanted later (just another field in `QueryService`'s existing row-mapping helpers), not required for this fix.
- Automatic re-generation of answers when a prompt version changes.

---

## Files touched (indicative, for the implementation stage)

- `src/main/resources/db/migration/V1015__add_query_prompt_version_tracking.sql` (new)
- `QueryService.java` — compute and set `version_label` in `upsertDefinitions(...)`
- `QueryVersion.java`, `QueryDefinitionLatest.java` — new field
- `GenerateAnswerForQueryTask.java`, `CheckStatusOfAnswerGenerationTask.java` — thread the value through job data
- `DefendantAnswerService.java`, `CaseLevelAllDocumentsAnswerService.java`, `CaseLevelLatestDocumentAnswerService.java`, `AnswerGenerationService.java` — new parameter + column
- `JobManagerKeys` — new `CTX_QUERY_PROMPT_VERSION` constant

---

## Stage gate

**Stage 2 (Architecture & Design) is a Human gate.** Do not proceed to implementation until this is
explicitly approved.
