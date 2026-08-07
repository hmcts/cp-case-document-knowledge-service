# Architecture Decision Records — Persist RAG `transactionId` on Answer Records

> Service: `cp-case-document-knowledge-service` (CDKS) · Jira: DD-43084 · Taken at the Stage 1 → Stage 2 human gate.
> Requirement: [`../DD-43084-persist-rag-transaction-id/`](../DD-43084-persist-rag-transaction-id/) ·
> Requirements: [`01-requirements.md`](../DD-43084-persist-rag-transaction-id/01-requirements.md) ·
> Design: [`02-design.md`](../DD-43084-persist-rag-transaction-id/02-design.md)

---

## ADR-001: Add a nullable `rag_transaction_id` column to each of the four answer tables, not a new join table or a column on `case_query_status`

- **Status:** Accepted · **Date:** 2026-08-07 · **Jira:** DD-43084
- **Artefacts:** `01-requirements.md` (FR-001–FR-005, FR-008) · `02-design.md` (§1–§3)

### Context

`GenerateAnswerForQueryTask` receives a `transactionId` from the RAG service's async accept
response and forwards it via Task Manager job data (`ragTransactionId`) to
`CheckStatusOfAnswerGenerationTask`. That task already resolves it to a `UUID` local variable to
poll `answerUserQueryStatus(...)`, but drops it before calling any of the four persistence paths —
`AnswerGenerationService.upsertAnswer`, `CaseLevelLatestDocumentAnswerService.upsert`,
`CaseLevelAllDocumentsAnswerService.upsert`, `DefendantAnswerService.upsert` — none of which accept
it, and none of the four backing tables (`answers`, `case_level_latest_doc_answers`,
`case_level_all_documents_answers`, `defendant_answers`) have a column for it. This is the same
category of problem the CDKS hard rule "do not drop RAG response fields" already names for
`doc_id`/`llm_input` (`.claude/context/cdks-context.md`).

Each of the four tables is a versioned, append-on-upsert record per `(case_id, query_id[,
defendant_id], version)` — every version is a distinct RAG-generation outcome and therefore a
distinct `transactionId`. `case_query_status`, by contrast, is a single current-state pointer per
`(case_id, query_id)` — it already only tracks `last_answer_version`/`last_answer_at`, not history.

### Decision

Add `rag_transaction_id UUID NULL` to all four answer tables via one additive Flyway migration
(`V1012`), and thread the already-resolved `transactionId` from `CheckStatusOfAnswerGenerationTask`
into all four upsert calls, written on both `INSERT` and `ON CONFLICT ... DO UPDATE SET`.

### Alternatives considered

- **New `answer_rag_transactions` table** (case_id, query_id, version, transaction_id) —
  rejected: over-engineered for a 1:1 scalar; `llm_input` already sets the precedent of storing
  per-version RAG-derived metadata as a plain column on each answer table, not a side table.
- **Store only on `case_query_status`** — rejected: it is a latest-pointer, not a version history;
  storing there would lose the transaction id for every superseded version, defeating the
  traceability goal the ticket is about.
- **`NOT NULL` column** — rejected: would require a backfill value for any pre-existing rows that
  don't have one (none recoverable from RAG after the fact), and adds rigidity to a
  traceability/diagnostic field that is not a business key.

### Consequences

- **Positive:** every future successful answer generation is traceable back to the exact RAG
  transaction that produced it, per version, matching the granularity of the data it's attached
  to. `ADD COLUMN ... NULL` on Postgres is a metadata-only change (no table rewrite), so rollout
  risk on existing tables is low regardless of current row counts.
- **Accepted:** rows written before this change (and any `ANSWER_GENERATION_FAILED` attempt, which
  was never persisted before or after this change) show `rag_transaction_id IS NULL` — no
  backfill is attempted or possible.
- **Reversible:** the column can be dropped again in a future migration without touching
  application logic beyond reverting the parameter threading; adding it does not touch
  `case_query_status`, the trigger `trg_answers_after_insert`, or any other table.

---

## ADR-002: `rag_transaction_id` is persisted only — no API/contract change this iteration

- **Status:** Accepted · **Date:** 2026-08-07 · **Jira:** DD-43084
- **Artefacts:** `01-requirements.md` (FR-006, FR-007) · `02-design.md` (§4)

### Context

The brief asks specifically for the value to be *stored*, not surfaced. CDKS's Answers API
(`AnswersController` → `AnswerMapper` → `AnswerResponse`/`AnswerWithLlmResponse`/`AnswersResponse`,
generated from `api-cp-crime-caseadmin-case-document-knowledge`) has no stated consumer need for a
RAG-internal transaction id today, and exposing an upstream vendor system's correlation id over a
public contract is a distinct product decision from persisting it for internal traceability.

### Decision

Persist `rag_transaction_id` in the database and (read-side) JPA entities only. Do not add it to
`AnswerResponse`, `AnswerWithLlmResponse`, or `AnswersResponse`; do not touch `AnswerMapper`; do
not bump `version.cdk` or touch `api-cp-crime-caseadmin-case-document-knowledge`. Support/ops
traceability needs are met by querying the database directly (the stated driver behind this
ticket), not by a new API surface.

### Alternatives considered

- **Add `ragTransactionId` to `AnswerWithLlmResponse`** — rejected as out of scope: no named
  consumer, would require a contract-first change in the external API-spec repo (mirroring
  DD-43036's approach), and risks leaking upstream implementation detail to API consumers without
  a stated need.
- **New `GET .../answers/by-transaction/{transactionId}` lookup endpoint** — rejected as out of
  scope: not asked for; would need its own requirements/ACL pass.

### Consequences

- **Positive:** zero contract risk, zero `version.cdk` dependency, ships entirely within this
  repo; smaller, easier-to-review diff.
- **Reversible — additive later:** exposing the field via the API afterward is a strictly additive
  contract change (new optional response field) once the column already exists — no migration
  needed at that point, only a mapper change and a contract bump.
- **Open question carried to Requirements (OQ-001):** if the actual Jira ticket text (not directly
  fetched in this session — see `00-input-brief.md`) turns out to ask for API exposure too, this
  ADR's scope boundary needs revisiting before Story stage.
