# Architecture Decision Records — Persist the RAG Document-Ingestion Reference on `case_documents`

> Service: `cp-case-document-knowledge-service` (CDKS) · Jira: DD-43083 · Taken at Stage 2 (Architecture & Design), resolving Stage 1 open questions OQ-002 and OQ-003.
> Requirement: [`../DD-43083-persist-doc-ingestion-transaction-id/`](../DD-43083-persist-doc-ingestion-transaction-id/) ·
> Requirements: [`01-requirements.md`](../DD-43083-persist-doc-ingestion-transaction-id/01-requirements.md) ·
> Design: [`02-design.md`](../DD-43083-persist-doc-ingestion-transaction-id/02-design.md) ·
> Sibling ADRs (answer side): [`DD-43084-persist-rag-transaction-id.md`](./DD-43084-persist-rag-transaction-id.md)
>
> Both ADRs below are **Accepted** — confirmed by the requester after design review with their
> designers on 2026-08-11. `rag_document_reference TEXT NULL` is the locked column
> name/type for `V1013` and is not reopened by later stages.

---

## ADR-001: Name the column `rag_document_reference`, not `doc_ingestion_tran_id`

- **Status:** Accepted · **Date:** 2026-08-11 · **Jira:** DD-43083 · **Resolves:** OQ-002
- **Artefacts:** `01-requirements.md` (FR-003, OQ-002) · `02-design.md` (§1–§3)

### Context

The input brief asks for "a similarly meaningful column name … something in the spirit of
`doc_ingestion_tran_id`" — i.e. the requester's stated goal is a name consistent with what
DD-43084 did, with `doc_ingestion_tran_id` offered as an illustration rather than a fixed token.
Stage 1 recorded three candidates and left the choice to Design (OQ-002).

Three facts constrain the answer:

1. **The upstream contract does not call this a transaction id.** In `api-cp-ai-rag:0.0.15`
   (`openapi/ai-rag-service.openapi.yml`) the field is `documentReference` on
   `FileStorageLocationReturnedSuccessfully`, and the status path is
   `GET /document-upload/{documentReference}`. CDKS's own job-data key already mirrors that
   vocabulary: `JobManagerKeys.CTX_DOC_REFERENCE_KEY = "documentReference"`. "Transaction id" is
   *our* interpretation of the field's role, not the contract's word for it — and whether the
   reference is strictly per-upload or is a durable handle for the document inside RAG is not
   something this repo can prove from the spec.
2. **DD-43084 established a naming family, and it is the `rag_` prefix that carries the meaning.**
   `V1012` added `rag_transaction_id` to four tables. The transferable part of that convention is
   "prefix RAG-provenance correlation columns with `rag_` so they are greppable as a family and
   never confused with CDKS's own identifiers" — not "call every RAG correlation id a transaction
   id regardless of what RAG calls it".
3. **This schema does not abbreviate.** Across `V1000`–`V1012` there is no `tran`, no `txn`, no
   truncated word: `ingestion_phase_at`, `last_answer_version`, `scheduled_ingestion_request`,
   `rag_transaction_id`, `court_centre_id`. PostgreSQL's identifier limit is 63 characters, so
   abbreviation buys nothing here.

### Decision

Name the column **`rag_document_reference`**, mapped as `ragDocumentReference` on `CaseDocument`,
with a `COMMENT ON COLUMN` that states both the source field and its role ("RAG-issued
documentReference returned by `POST /document-upload` — the ingestion-transaction correlation id
for this document (ingestion-side analogue of `answers.rag_transaction_id`)").

Keep the *local variable and method parameter* in `RetrieveMaterialAndUploadTask` named
`documentReference`, matching the RAG model and the existing `CTX_DOC_REFERENCE_KEY`; the `rag_`
prefix exists to disambiguate provenance inside CDKS's schema, where a bare `document_reference`
sitting next to `doc_id`, `doc_name` and `courtdoc_id` would read as one of CDKS's own identifiers.

### Alternatives considered

- **`doc_ingestion_tran_id`** (the brief's illustrative name) — rejected on three counts, any one
  of which is minor but which compound: (a) `tran` is an abbreviation with no precedent anywhere in
  this schema; (b) the `doc_` prefix collides conceptually with the existing `doc_id` / `doc_name`
  family on the very same table, implying it belongs to CDKS's document identifiers when it is an
  external system's value; (c) `_tran_id` asserts a semantic ("this is a transaction id") that the
  RAG contract itself does not assert, so it is the one candidate that could become an outright
  misnomer. Renaming a shipped column under an append-only Flyway policy is the most expensive
  mistake available here, so the name that cannot go stale wins.
- **`rag_ingestion_transaction_id`** — the strongest runner-up, and the right choice if reviewers
  weight sibling symmetry with `rag_transaction_id` above fidelity to the source field. Rejected
  as primary because it inherits exactly the risk in (c) above while being 10 characters longer
  than the literal name, and because someone debugging with a RAG support ticket in hand is
  searching for `documentReference`, not for a transaction id.
- **`document_reference`** (no prefix) — rejected: loses the `rag_` provenance marker DD-43084
  established, and reads as a CDKS-owned identifier alongside `doc_id`/`courtdoc_id`.
- **Reusing the name `rag_transaction_id` on `case_documents`** — rejected: identical column names
  holding values issued by two different RAG endpoints, with different semantics and lifetimes,
  would make cross-table reasoning and any future data-export mapping actively misleading.

### Consequences

- **Positive:** the column name is a lossless pointer back to the exact upstream field, so
  ingestion traceability is greppable in both directions (`documentReference` in RAG's logs and
  contract, `rag_document_reference` in CDKS's schema). It stays correct whatever RAG later
  clarifies about the reference's lifetime.
- **Accepted:** the two RAG-provenance columns are not name-identical across tables
  (`answers.rag_transaction_id` vs `case_documents.rag_document_reference`). They remain a
  discoverable family via the shared `rag_` prefix, and each column comment cross-references the
  other. This is the deliberate trade of visual symmetry for semantic accuracy.
- **Accepted:** this recommendation diverges from the name in the input brief and therefore needs
  an explicit accept/override at the Stage 2 gate before Story 1 (the migration) is written.
- **Reversibility:** poor once shipped, which is precisely why it is being settled at Design.
  Flyway is append-only, so a later rename means a new migration plus an entity change plus
  coordination with any external reader of `case_documents` (OQ-004). Pick the name once.

---

## ADR-002: Store the reference as `TEXT`, verbatim — no UUID parse, no shape `CHECK`

- **Status:** Accepted · **Date:** 2026-08-11 · **Jira:** DD-43083 · **Resolves:** OQ-003, satisfies AC-014
- **Artefacts:** `01-requirements.md` (FR-003, FR-007, AC-001, AC-004, AC-014, OQ-003) · `02-design.md` (§1, §3, §4, §6)

### Context

Stage 1 left the SQL type genuinely open, with evidence both ways:

**For `UUID`:** the RAG spec constrains `documentReference` via `allOf: [$ref '#/components/schemas/uuid']`,
where `uuid` is a `string` with a UUID **regex pattern**; `GET /document-upload/{documentReference}`
documents `400 — documentReference is not a valid uuid`; and DD-43084 stored the *same* `uuid`
schema's `transactionId` as a `UUID` column.

**For `TEXT`:** because the schema uses `pattern` rather than `format: uuid`, the generated Java
type is `String` — `FileStorageLocationReturnedSuccessfully.getDocumentReference()`,
`DocumentIngestionStatusApi.documentStatusByReference(String)` — and nothing in CDKS parses or
validates it. Response bodies are not bean-validated on the client path, so the `@Pattern`
annotation is decorative here; the value is threaded through job data as a string and handed
straight back to RAG.

Two further pieces of evidence, gathered at Design stage, decide it:

1. **`spring.jpa.hibernate.ddl-auto: validate`** (`application-datasource.yml:22`). Hibernate
   validates column types at startup, so a `String` entity field over a Postgres `uuid` column
   fails boot. Choosing `UUID` is therefore not just a storage choice — it *forces* the entity
   field, and hence the whole call path, to `java.util.UUID`, which forces a parse of a value the
   application otherwise never parses.
2. **The only parse helper in this repo is lossy by design.** `TaskUtils.parseUuidOrNull(...)`
   logs a warning and returns `null` on a malformed value. Using it here would silently discard the
   precise provenance data this ticket exists to preserve, in direct tension with CDKS's hard rule
   "do not drop RAG response fields" (`.claude/context/cdks-context.md`).

There is also a strong in-table precedent: every non-key string column on `case_documents` is
`TEXT` — `source`, `doc_name`, `blob_uri`, `content_type`, and notably `sha256_hex`, a strictly
shape-constrained hex string stored as `TEXT` with a `CHECK` rather than in a specialised type
(`V1001__case_documents_ai_schema.sql`).

### Decision

Add **`rag_document_reference TEXT NULL`**, mapped to a `String` field, and store the value exactly
as received from RAG.

**Fallback behaviour, explicit and testable (AC-014):**

| Input | Behaviour |
|---|---|
| Non-UUID-shaped, non-blank value | **Stored verbatim.** Not parsed, not rejected, not truncated, not null-ed, not case-folded. No exception. No `CHECK` constraint to violate. The same value continues to job data and to `documentStatusByReference(...)`, where RAG's own documented `400` is handled by the existing `CheckIngestionStatusForAllDefendantsTask` error path exactly as today. Optionally observable via existing INFO logging of the reference; **no new WARN is added**, because a malformed reference is an upstream contract breach that CDKS is recording, not diagnosing. |
| `null` or blank | Column left `NULL`. The added persistence line cannot throw. Pre-existing behaviour then applies unchanged: `JsonObjectBuilder.add(CTX_DOC_REFERENCE_KEY, null)` throws NPE, the existing `catch` returns `INPROGRESS` + `shouldRetry`, and the row is left at `UPLOADED` with a `NULL` reference (FR-007, AC-004). |
| Retry of `RETRIEVE_MATERIAL_AND_UPLOAD` | New reference **overwrites** the old one — last-write-wins, no write-once guard. The stored reference must identify the ingestion transaction that produced the blob currently in `blob_uri`. |

Explicitly **no** `CHECK (rag_document_reference IS NULL OR rag_document_reference ~ '<uuid pattern>')`,
by analogy with `cd_sha256_shape`: a failing CHECK raises on `saveAndFlush` and would turn a
malformed-but-otherwise-successful upload into a task failure, breaching FR-007.

This diverges from DD-43084's `UUID` column, and the divergence is principled rather than
inconsistent: **both decisions store the value as the type the application actually holds.** On the
answer side, `CheckStatusOfAnswerGenerationTask` had already parsed `transactionId` to a `UUID` for
its own status-poll call, so a `UUID` column added no transformation. Here the in-memory type is
`String` throughout, so `TEXT` adds no transformation. Persistence should not be the layer that
introduces a parse.

### Alternatives considered

- **`UUID` column, parsing via `TaskUtils.parseUuidOrNull(...)`** — rejected: silently converts a
  malformed reference to `NULL`, destroying the exact provenance the ticket exists to keep, and
  makes "RAG never gave us one" and "RAG gave us something odd" indistinguishable in the data.
  Directly at odds with CDKS's "do not drop RAG response fields" rule.
- **`UUID` column, throwing on a malformed value** — rejected: promotes an upstream contract breach
  into a failed ingestion and a retry loop over a value that will never parse, breaching FR-007
  ("must not turn a previously-succeeding upload into a failure").
- **`UUID` column plus a shadow `TEXT` column for unparseable values** (one of Stage 1's suggested
  fallbacks) — rejected: two columns, two nullability states and a documented "check the other
  column" rule, to model a single scalar. Strictly worse than one honest `TEXT` column.
- **`UUID` column with normalisation-on-write** — rejected: Postgres canonicalises `uuid` input
  (case-folding, accepting brace and unhyphenated forms), so the stored value can differ textually
  from what RAG sent. That breaches AC-001's "preserved exactly as received … no case-folding, no
  re-formatting" for any non-canonical input, and would make a byte-for-byte comparison against a
  RAG-side log or support ticket unreliable.
- **`TEXT` with a UUID-pattern `CHECK`** — rejected per the Decision: it is the CHECK-raises-on-save
  problem in a different costume, and the shape contract belongs to RAG.
- **`VARCHAR(36)`** — rejected: no advantage over `TEXT` in PostgreSQL (identical storage and
  performance), the schema uses `TEXT` throughout, and a length cap would truncate or reject a
  longer malformed value instead of recording it.

### Consequences

- **Positive:** the persisted value is byte-for-byte what RAG returned, so it can be pasted
  straight into a RAG support query or matched against RAG-side logs with no normalisation caveat.
  Zero new failure modes, zero new parse code, zero new helper, and no `MapSqlParameterSource`
  plumbing (contrast DD-43084's seven-file change).
- **Positive:** `String` → `TEXT` satisfies `ddl-auto: validate` with no `columnDefinition`
  override or `@JdbcTypeCode`, matching the four existing `String` columns on this table. The
  integration suite's successful app startup is itself the regression test for this.
- **Accepted:** no database-level guarantee that the column contains a UUID. Consumers must treat
  it as an opaque correlation string — which is exactly how every consumer, including RAG's own
  status endpoint, treats it today. Anything requiring UUID semantics should validate at the point
  of use, not rely on the column type.
- **Accepted:** ~21 extra bytes per row versus a 16-byte `uuid`, on a table that already stores full
  blob URIs as `TEXT`. Immaterial, and no index depends on it (FR-010).
- **Accepted:** the two sibling columns have different SQL types (`answers.rag_transaction_id UUID`
  vs `case_documents.rag_document_reference TEXT`). Any future join or export across them needs an
  explicit cast. Judged cheaper than a lossy write path; the column comments record why.
- **Reversible in the useful direction:** if UUID semantics are ever genuinely required,
  `ALTER TABLE case_documents ALTER COLUMN rag_document_reference TYPE uuid USING rag_document_reference::uuid`
  is a single additive migration — and it fails loudly if any malformed rows exist, which is
  precisely the signal you would want before tightening the type. The reverse move (`uuid` → `TEXT`
  after values have been silently null-ed by `parseUuidOrNull`) recovers nothing, because the
  original strings were never stored. Choosing `TEXT` first is the one-way door avoided.
