# User Stories: Persist RAG Document-Ingestion Reference on `case_documents`

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43083** — each story below has its own sub-ticket:
> [DD-43136](https://tools.hmcts.net/jira/browse/DD-43136) (Story 1),
> [DD-43137](https://tools.hmcts.net/jira/browse/DD-43137) (Story 2),
> [DD-43138](https://tools.hmcts.net/jira/browse/DD-43138) (Story 3).
>
> Acceptance Criteria embedded verbatim from [`01-requirements.md`](./01-requirements.md), which
> also holds the full NFR list — only cited here where a story's own testing depends on one. ADR-001
> (column name `rag_document_reference`) and ADR-002 (SQL type `TEXT`, verbatim, no parse, no
> CHECK) — [ADRs](../adrs/DD-43083-persist-doc-ingestion-transaction-id.md) — are **Accepted** and
> not reopened here.
>
> **Three stories, not four.** Unlike DD-43084 (raw-SQL upsert, so the JPA entity needed its own
> "keep read-side in sync" story), this ticket has a single entity and a single write point —
> the entity change *is* the persistence change — so Stage 1's candidate stories 2 and 3 are merged.

**Standard DoD (every story)**: code reviewed & approved · ACs covered by automated tests (unit +
integration) · no critical/high Snyk findings · deployed to and verified on sandbox · Jira updated
with test evidence.

---

## Story 1 — Add `rag_document_reference` column to `case_documents`
**Jira: [DD-43136](https://tools.hmcts.net/jira/browse/DD-43136)**

As a **CDKS developer**, I want **an append-only Flyway migration (`V1013`) adding a nullable
`rag_document_reference TEXT` column to `case_documents`**, so that **the app has somewhere to
persist the RAG ingestion reference, with zero risk to existing rows or reads**.

**Acceptance criteria**
- AC-006: Rows that predate `V1013` show the column `IS NULL` — no error, no backfill.
- AC-008: `V1013__*.sql` adds exactly one nullable column plus a `COMMENT ON COLUMN` — no rename, no drop, no `NOT NULL`, no default — reviewed by `migration-reviewer`.
- AC-009: Flyway migrates cleanly on a fresh DB and on a DB already at `V1012` with existing rows; `gradle integration` starts and the app reaches readiness.

**Notes:** schema-only, no code reads/writes it yet — a safe no-op until Story 2 lands, and blocks
it. `ADD COLUMN ... NULL` is metadata-only on PostgreSQL 16 (NFR-002).

---

## Story 2 — Persist and preserve the RAG `documentReference`
**Jira: [DD-43137](https://tools.hmcts.net/jira/browse/DD-43137)**

As a **support/ops engineer investigating a document ingestion**, I want **every uploaded document
row to record the exact RAG `documentReference` that produced it, unchanged through every later
ingestion-status transition**, so that **I can trace a stored document back to the upstream RAG
transaction — the ingestion-side half of what DD-43084 did for answers**.

**Acceptance criteria**
- AC-001: The value is preserved exactly as received — no truncation, trimming, case-folding, or re-formatting.
- AC-002: Written in the same `saveAndFlush` as `docName`/`blobUri`/`contentType`/`sizeBytes`/`uploadedAt`/`ingestionPhase = UPLOADED` — one write, not two.
- AC-003: Survives later transitions to `INGESTED`, `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT` — not nulled, not changed.
- AC-004: A `null`/blank reference leaves the column `NULL`; task outcome (retry) is unchanged from today.
- AC-005: A `WAITING_FOR_UPLOAD` row (from `IdpcAvailabilityService`) shows the column `IS NULL` until upload initiation completes.
- AC-010: `RetrieveMaterialAndUploadTaskTest` / `CheckIngestionStatusForAllDefendantsTaskTest` compile and pass with updated signatures.
- AC-014: A non-UUID-shaped reference is neither silently discarded nor allowed to fail the ingestion flow (ADR-002).

**Notes:** needs Story 1's column. Retries of `RETRIEVE_MATERIAL_AND_UPLOAD` overwrite the stored
reference (last-write-wins, intended — design §5), and `CheckIngestionStatusForAllDefendantsTask`
needs **no code change** since it re-saves the hydrated entity (folds in Stage 1's candidate Story
3). OQ-001/OQ-006 (scope vs. the literal ticket text) should be settled before this is picked up.

---

## Story 3 — End-to-end test coverage for `rag_document_reference`
**Jira: [DD-43138](https://tools.hmcts.net/jira/browse/DD-43138)**

As a **CDKS developer**, I want **an integration test proving the column is populated through the
real ingestion flow**, so that **persistence is proven end-to-end and existing API behaviour is
confirmed unaffected** (NFR-003).

**Acceptance criteria**
- AC-007: No controller/DTO/mapper change; `version.cdk` unchanged; existing `*HttpLiveTest` suites pass unmodified.
- AC-011: A live test asserts the column is populated from the RAG WireMock stub after the flow reaches `UPLOADED`.
- AC-012: `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo/CodeQL/secrets-scanner clean.
- AC-013: No PII/case content/court reference/`CJSCPPUID` in the diff; fixtures stay synthetic.

**Notes:** needs Stories 1 and 2 merged first. `POST /document-upload` has two colliding WireMock
stubs in the shared compose container (a fixed-value static mapping and a `randomUUID()`
programmatic stub used by other live tests) — the new test must register its own
higher-priority, narrowly-matched stub rather than assume the static mapping wins (design
§Testing "Stub caveat"). No contract tests — no contract change.

---

## Summary

| Story | Jira | Depends on |
|---|---|---|
| 1 — Schema migration | [DD-43136](https://tools.hmcts.net/jira/browse/DD-43136) | none |
| 2 — Persist + preserve | [DD-43137](https://tools.hmcts.net/jira/browse/DD-43137) | Story 1 |
| 3 — Test coverage | [DD-43138](https://tools.hmcts.net/jira/browse/DD-43138) | Stories 1 & 2 |

**Not a story here** (per Stage 1, unchanged): API exposure, a lookup-by-reference endpoint, or a backfill job.
