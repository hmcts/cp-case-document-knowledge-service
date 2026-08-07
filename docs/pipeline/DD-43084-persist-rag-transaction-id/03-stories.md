# User Stories: Persist RAG `transactionId` on Answer Records

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43084** — each story below has its own sub-ticket, raised as DD-43104/DD-43105/DD-43106.
> Acceptance Criteria text below is embedded verbatim from [`01-requirements.md`](./01-requirements.md)
> so each story is ready to paste into its Jira ticket; ADR-001/ADR-002
> ([`adrs/DD-43084-persist-rag-transaction-id.md`](../adrs/DD-43084-persist-rag-transaction-id.md)) are locked decisions, not reopened here.

**Standard DoD (applies to every story unless noted)**: code reviewed & approved · all ACs
covered by automated tests (unit + integration) · no critical/high Snyk findings introduced ·
deployed to and verified on sandbox · Jira ticket updated with test evidence. Deltas only, below.

---

## Story 1 — Add `rag_transaction_id` column to all four answer tables
**Jira: [DD-43104](https://tools.hmcts.net/jira/browse/DD-43104)**
As a **CDKS developer**, I want **a new, append-only Flyway migration (`V1012`) that adds a
nullable `rag_transaction_id UUID` column to `answers`, `case_level_latest_doc_answers`,
`case_level_all_documents_answers`, and `defendant_answers`**, so that **the application layer has
somewhere to persist the RAG transaction id, with zero risk to existing data or existing reads**.

**Acceptance Criteria**
- AC-006: Rows that predate this migration, or any failed-generation attempt, show `rag_transaction_id IS NULL` — no error, no backfill attempted.
- AC-009: `V1012__*.sql` only adds nullable columns (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS rag_transaction_id UUID`) across the four tables — no rename, no drop, no data rewrite — and is reviewed by `migration-reviewer`.

- NFR: NFR-002 (migration safety), NFR-006 (migration governance)
- Dependency: blocks Story 2 (nothing to write to until the column exists); independently
  deployable/reversible on its own — a no-op until Story 2 lands
- DoD delta: `migration-reviewer` review completed for `V1012__add_rag_transaction_id_to_answers.sql`

## Story 2 — Thread `transactionId` through the four answer-persistence paths
**Jira: [DD-43105](https://tools.hmcts.net/jira/browse/DD-43105)**
As a **support/ops engineer investigating an AI-generated answer**, I want **every successfully
generated answer to record the RAG `transactionId` that produced it, for every query level**, so
that **I can trace any stored answer back to the exact upstream RAG call, closing the "RAG
response field silently dropped" gap this ticket exists to fix**.

Reuses the `transactionId` `CheckStatusOfAnswerGenerationTask` already resolves today (design
§3–§4); no new RAG call, no new job-data key, no change to `GenerateAnswerForQueryTask`.

**Acceptance Criteria**
- AC-001: A successfully generated `CASE`-level answer persists `rag_transaction_id` equal to the polled transaction id, in `case_level_latest_doc_answers`.
- AC-002: Same for `CASE_ALL_DOCUMENTS` → `case_level_all_documents_answers`.
- AC-003: Same for `DEFENDANT` → `defendant_answers`.
- AC-004: Same for `null`/default level → `answers`.
- AC-005: Re-upserting an existing `(case_id, query_id[, defendant_id], version)` row updates `rag_transaction_id` to the new value via `EXCLUDED.rag_transaction_id`, never leaving a stale id from a prior transaction.
- AC-010: `AnswerGenerationServiceTest`, `CaseLevelLatestDocumentAnswerServiceTest`, `CaseLevelAllDocumentsAnswerServiceTest`, `DefendantAnswerServiceTest`, `CheckStatusOfAnswerGenerationTaskTest` compile and pass with updated signatures.
- AC-011: New/updated unit test assertions capture the `MapSqlParameterSource` passed to each `UPSERT_SQL` and confirm `rag_transaction_id` equals the transaction id supplied by the caller.
- AC-012: `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green at existing thresholds.
- AC-013: Diff introduces no PII, case content, court reference, or `CJSCPPUID` into migrations, tests, or fixtures.

- Out of scope: exposing the value via any API response, a lookup-by-transaction-id endpoint,
  backfilling historic rows, any change to `case_query_status` or `GenerateAnswerForQueryTask`
  (design §1, §3, ADR-001)
- NFR: NFR-001, NFR-003, NFR-004, NFR-005, NFR-007
- Dependency: needs Story 1's column to exist first; ships together with Story 1 in practice since
  neither is independently useful
- DoD delta: integration test extended against the existing async-RAG WireMock stub
  (`answer_user_query_async_response.json`) asserting the persisted column value end-to-end
  (design Testing items 1–3); `AnswersHttpLiveTest` re-run unmodified to confirm no response-shape
  regression (AC-007)

## Story 3 — Keep the read-side JPA entities in sync with the new column
**Jira: [DD-43106](https://tools.hmcts.net/jira/browse/DD-43106)**
As a **CDKS developer**, I want **`Answer`, `BaseAnswer` (and its subclasses) to expose a mapped
`ragTransactionId` field**, so that **the entity model doesn't silently drift from the table it
maps, ready for a future API-exposure change without another schema audit**.

**Acceptance Criteria**
- AC-007: `GET /cases/{caseId}/queries/{queryId}/answers/with-llm`, `.../answers/list`, and the `/v2/...` answers endpoints return an unchanged response shape — no new field, confirmed by the existing `AnswersHttpLiveTest` assertions passing unmodified.
- AC-008: `AnswerMapper` output and `version.cdk` are unchanged.

- Out of scope: any mapper/DTO/contract change (ADR-002) — this story is entity-only
- NFR: NFR-003 (backward compatibility)
- Dependency: needs Story 1's column; independent of Story 2 (read-side vs. write-side), can ship
  in the same PR as Story 2 for review convenience but is logically separable
- DoD delta: confirm via `AnswersHttpLiveTest` (unmodified) that adding the entity field alone,
  with no mapper change, produces zero response-body diff
