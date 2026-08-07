# Requirements: Persist RAG `transactionId` on Answer Records

> **Stage 1 — Requirements** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43084**
> Gate decisions (column placement, nullability, API scope) are folded in below — see
> `02-design.md` and [`adrs/DD-43084-persist-rag-transaction-id.md`](../adrs/DD-43084-persist-rag-transaction-id.md) (ADR-001/002).

---

## Context

CDKS generates an answer asynchronously: `GenerateAnswerForQueryTask` calls the RAG service's
`answerUserQueryAsync` endpoint and receives a `transactionId`; that id is forwarded via Task
Manager job data to `CheckStatusOfAnswerGenerationTask`, which polls RAG's
`answerUserQueryStatus(transactionId, ...)` until the answer is ready, then persists it into one of
four tables depending on `QueryLevel` — `answers` (default), `case_level_latest_doc_answers`
(`CASE`), `case_level_all_documents_answers` (`CASE_ALL_DOCUMENTS`), `defendant_answers`
(`DEFENDANT`). Today the `transactionId` is used only to poll and to correlate log lines; it is
discarded before any of the four persistence calls, and none of the four tables have a column for
it.

This is the same class of problem the CDKS hard rule "do not drop RAG response fields" already
covers for `doc_id`/`llm_input` (`.claude/context/cdks-context.md`) — `transactionId` is
RAG-provenance data being silently lost at the last step before persistence, undermining the
traceability CDKS is meant to guarantee for AI-generated answers.

**Note on source:** this requirement is derived from the requester's own restatement of Jira
DD-43084 in conversation; the ticket itself was not fetched (no Jira access in this session — see
OQ-001). Treat FR/AC below as the working scope until confirmed against the ticket text.

---

## Functional Requirements

| ID | Requirement |
|----|-------------|
| FR-001 | `CheckStatusOfAnswerGenerationTask` passes its already-resolved `transactionId` into whichever of the four upsert calls it makes for a successfully generated answer (`ANSWER_GENERATED`). |
| FR-002 | Each of `answers`, `case_level_latest_doc_answers`, `case_level_all_documents_answers`, `defendant_answers` gains a `rag_transaction_id UUID NULL` column via one new, append-only Flyway migration (`V1012`). |
| FR-003 | Each of the four upsert methods (`AnswerGenerationService.upsertAnswer`, `CaseLevelLatestDocumentAnswerService.upsert`, `CaseLevelAllDocumentsAnswerService.upsert`, `DefendantAnswerService.upsert`) accepts the transaction id and writes it on both `INSERT` and `ON CONFLICT ... DO UPDATE SET` — a re-upsert of an existing version always reflects the transaction that most recently produced it, never a stale one. |
| FR-004 | `TaskUtils.buildAnswerParams` (shared by `AnswerGenerationService`) is extended with the new parameter; no duplicate parameter-building logic is introduced. |
| FR-005 | No backfill: rows written before this change, and any `ANSWER_GENERATION_FAILED` attempt (never persisted, unchanged by this ticket), show `rag_transaction_id IS NULL`. |
| FR-006 | No API/contract change: `AnswerResponse`, `AnswerWithLlmResponse`, `AnswersResponse`, `AnswerMapper`, and `version.cdk` are unchanged (ADR-002) — persistence only. |
| FR-007 | The read-side JPA entities that map these tables (`Answer`; `BaseAnswer`, inherited by `CaseLevelAllDocumentsAnswer` and — via `DocumentAnswer` — `CaseLevelLatestDocumentAnswer`/`DefendantAnswer`) gain a mapped `ragTransactionId` field so each entity accurately reflects its table, even though no mapper surfaces it externally yet. |
| FR-008 | `case_query_status` is untouched — it is a current-state pointer per `(case_id, query_id)`, not a per-version record, and is out of scope (ADR-001). |

**Out of scope:** exposing `transactionId` via any GET response, a new lookup-by-transaction-id
endpoint, backfilling historic rows, changing `case_query_status` or its trigger, changing the
retry/failure path, any change to `GenerateAnswerForQueryTask` itself (it already forwards the
value correctly today — the gap is entirely downstream).

---

## Non-Functional Requirements

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-001 | Data protection / traceability | `transactionId` is an opaque RAG-issued UUID, not PII; safe to persist and already safe to log today. Storing it strengthens, not weakens, CDKS's data-protection/traceability posture. |
| NFR-002 | Migration safety | `ADD COLUMN ... NULL` is additive and metadata-only on PostgreSQL — no table rewrite, no lock escalation, regardless of current row counts in `answers`/`defendant_answers`/etc. |
| NFR-003 | Backward compatibility | No change to any existing GET response shape, SQL read path, or the `answers_after_insert` trigger; existing consumers of the Answers API are unaffected. |
| NFR-004 | Testability | Unit test coverage for all four service classes, `TaskUtils`, and `CheckStatusOfAnswerGenerationTask`; `integrationTest` coverage asserting the column is actually populated end-to-end via the existing RAG WireMock stub. `gradle integration` passes. |
| NFR-005 | Code quality | PMD/JaCoCo unmodified thresholds; change is mechanical parameter-threading, no new suppressions expected. |
| NFR-006 | Migration governance | The new `V1012__*.sql` is routed through the `migration-reviewer` agent per CLAUDE.md's hard rule for any change under `db/migration`. |
| NFR-007 | Platform | Java 25 / Spring Boot 4.0.5 / Gradle 9; existing `NamedParameterJdbcTemplate` raw-SQL pattern in the four services is preserved, not replaced with JPA writes. |

---

## Acceptance Criteria

**Persistence — one per query level**
- AC-001: A successfully generated `CASE`-level answer persists `rag_transaction_id` equal to the polled transaction id, in `case_level_latest_doc_answers`.
- AC-002: Same for `CASE_ALL_DOCUMENTS` → `case_level_all_documents_answers`.
- AC-003: Same for `DEFENDANT` → `defendant_answers`.
- AC-004: Same for `null`/default level → `answers`.

**Upsert correctness**
- AC-005: Re-upserting an existing `(case_id, query_id[, defendant_id], version)` row updates `rag_transaction_id` to the new value via `EXCLUDED.rag_transaction_id`, never leaving a stale id from a prior transaction.
- AC-006: Rows that predate this migration, or any failed-generation attempt, show `rag_transaction_id IS NULL` — no error, no backfill attempted.

**No API surface change**
- AC-007: `GET /cases/{caseId}/queries/{queryId}/answers/with-llm`, `.../answers/list`, and the `/v2/...` answers endpoints return an unchanged response shape — no new field, confirmed by the existing `AnswersHttpLiveTest` assertions passing unmodified.
- AC-008: `AnswerMapper` output and `version.cdk` are unchanged.

**Migration & tests**
- AC-009: `V1012__*.sql` only adds nullable columns (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS rag_transaction_id UUID`) across the four tables — no rename, no drop, no data rewrite — and is reviewed by `migration-reviewer`.
- AC-010: `AnswerGenerationServiceTest`, `CaseLevelLatestDocumentAnswerServiceTest`, `CaseLevelAllDocumentsAnswerServiceTest`, `DefendantAnswerServiceTest`, `CheckStatusOfAnswerGenerationTaskTest` compile and pass with updated signatures.
- AC-011: New/updated unit test assertions capture the `MapSqlParameterSource` passed to each `UPSERT_SQL` and confirm `rag_transaction_id` equals the transaction id supplied by the caller.
- AC-012: `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green at existing thresholds.
- AC-013: Diff introduces no PII, case content, court reference, or `CJSCPPUID` into migrations, tests, or fixtures.

---

## Open Questions

- **OQ-001:** The Jira ticket DD-43084 itself was not fetched in this session (no Jira/Atlassian
  tool access) — confirm the ticket's literal text matches this restated scope (persist-only,
  all four answer tables, no API exposure) before moving past the Story stage. If the ticket asks
  for API exposure or a support-facing lookup endpoint, ADR-002's boundary needs revisiting.
- **OQ-002:** Confirm no other consumer (e.g. a reporting/BI job, a data export) reads these four
  tables directly and would need to be told about the new nullable column ahead of time — none
  found in this repo via `grep`, but CDKS's `answers` tables may be queried from outside this
  codebase.
