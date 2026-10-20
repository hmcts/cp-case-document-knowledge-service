# User Stories: Persist Query Prompt Version (DD-43822)

> One story — the fix is small and contained enough not to split further. Per instruction: low-level
> task detail instead of NFRs/alternatives, so implementation can be picked up directly.

---

## Story 1 — Version `query_versions` and persist the version used on every generated answer

**Jira:** [DD-43848](https://hmcts.atlassian.net/browse/DD-43848), sub-task of
[DD-43822](https://hmcts.atlassian.net/browse/DD-43822).

### Background

See `02-design.md` for the full evidence trail. In short: `query_versions` has no version
number/label today; `QueryService.upsertDefinitions(...)` creates new rows with no versioning logic
at all; the answer-generation flow reads the current prompt but never carries forward which version
it used, so persisted answers (across all four answer tables) can't be traced back to the prompt
version that produced them.

### Acceptance criteria

- **AC-001:** Given a query with no existing `query_versions` rows, when a prompt is first loaded for
  it via `QueryService.upsertDefinitions(...)`, then the new row's `version_label` is `V1`.
- **AC-002:** Given a query with N existing `query_versions` rows, when a new prompt is loaded for it,
  then the new row's `version_label` is `V(N+1)`.
- **AC-003:** Given existing `query_versions` rows at migration time, when `V1015` runs, then every
  row is retrofitted with a `version_label` ordered by `effective_at` ascending per `query_id`
  (`V1` = oldest), and the column is `NOT NULL` once backfilled.
- **AC-004:** Given an answer is generated for `QueryLevel.CASE` (latest-document), `CASE_ALL_DOCUMENTS`,
  or `DEFENDANT`, or via the default/legacy path, when it is persisted, then the corresponding row in
  `case_level_latest_doc_answers`, `case_level_all_documents_answers`, `defendant_answers`, or
  `answers` respectively carries a `query_prompt_version` equal to the `version_label` that was read
  from `v_query_definitions_latest` at the point `GenerateAnswerForQueryTask` built the RAG request —
  not whatever happens to be "latest" at persistence time (the two can differ if the prompt is
  re-edited while a generation is still in flight).
- **AC-005:** Given the existing `version` integer column on every answer table (the answer-
  regeneration counter), when `query_prompt_version` is added, then it is a distinct column with no
  shared meaning, naming, or computation with `version`.

### Low-level implementation tasks

**1. Flyway migration — `src/main/resources/db/migration/V1015__add_query_prompt_version_tracking.sql`**
```sql
ALTER TABLE query_versions ADD COLUMN version_label TEXT;

UPDATE query_versions v
   SET version_label = 'V' || sub.rn
  FROM (
    SELECT query_id, effective_at,
           ROW_NUMBER() OVER (PARTITION BY query_id ORDER BY effective_at ASC) AS rn
      FROM query_versions
  ) sub
 WHERE v.query_id = sub.query_id AND v.effective_at = sub.effective_at;

ALTER TABLE query_versions ALTER COLUMN version_label SET NOT NULL;

ALTER TABLE answers ADD COLUMN query_prompt_version TEXT;
ALTER TABLE defendant_answers ADD COLUMN query_prompt_version TEXT;
ALTER TABLE case_level_all_documents_answers ADD COLUMN query_prompt_version TEXT;
ALTER TABLE case_level_latest_doc_answers ADD COLUMN query_prompt_version TEXT;

CREATE OR REPLACE VIEW v_query_definitions_latest AS
SELECT DISTINCT ON (q.query_id)
  q.query_id,
  q.label,
  qv.effective_at,
  qv.version_label,
  qv.user_query,
  qv.query_prompt
FROM queries q
JOIN query_versions qv ON qv.query_id = q.query_id
ORDER BY q.query_id, qv.effective_at DESC;
```

**2. `QueryVersion.java`** — add field:
```java
@Column(name = "version_label", nullable = false)
private String versionLabel;
```

**3. `QueryVersionRepository.java`** — add a next-version-number query, same shape as the existing
`NEXT_VERSION_SQL` pattern in the four answer services:
```java
@Query(value = "SELECT COUNT(*) FROM query_versions WHERE query_id = :queryId", nativeQuery = true)
long countVersions(UUID queryId);
```

**4. `QueryService.java`** (`upsertDefinitions`, around line 218) — compute the label before building
the row:
```java
final long nextOrdinal = queryVersionRepository.countVersions(queryId) + 1;
final QueryVersion version = new QueryVersion();
version.setQuery(query);
version.setQueryVersionId(new QueryVersionId(queryId, effectiveAt));
version.setUserQuery(userQuery);
version.setQueryPrompt(queryPrompt);
version.setVersionLabel("V" + nextOrdinal);
version.setLevel(uk.gov.hmcts.cp.cdk.domain.QueryLevel.valueOf(item.getLevel().name()));
queryVersionRepository.save(version);
```

**5. `QueryDefinitionLatest.java`** — add field:
```java
@Column(name = "version_label", nullable = false)
private String versionLabel;
```

**6. `JobManagerKeys.java`** — add constant next to `CTX_RAG_TRANSACTION_ID`:
```java
public static final String CTX_QUERY_PROMPT_VERSION = "queryPromptVersion";
```

**7. `GenerateAnswerForQueryTask.java`** (around line 90, where `nextJobData` is built) — add the
version label read from `qdl`:
```java
final JsonObjectBuilder nextJobData = createObjectBuilder(jobData)
        .add(CTX_RAG_TRANSACTION_ID, transactionId)
        .add(CTX_QUERY_PROMPT_VERSION, qdl.getVersionLabel());
```

**8. `CheckStatusOfAnswerGenerationTask.java`** (around line 97, alongside the other job-data reads)
— read it and pass it into all four upsert call sites:
```java
final String queryPromptVersion = jobData.getString(CTX_QUERY_PROMPT_VERSION, null);
```
then add `queryPromptVersion` as the last argument to each of
`caseLevelLatestDocumentAnswerService.upsert(...)`,
`caseLevelAllDocumentsAnswerService.upsert(...)`,
`defendantAnswerService.upsert(...)`,
`answerGenerationService.upsertAnswer(...)`.

**9. Four answer services** — `DefendantAnswerService.java`, `CaseLevelAllDocumentsAnswerService.java`,
`CaseLevelLatestDocumentAnswerService.java`, `AnswerGenerationService.java`:
- Add `queryPromptVersion` parameter to `upsert(...)`/`upsertAnswer(...)`.
- Add `query_prompt_version` to the `INSERT` column list, `VALUES`, and `ON CONFLICT ... DO UPDATE SET`
  clause (same shape as the existing `rag_transaction_id` column in each).
- Add `.addValue("query_prompt_version", queryPromptVersion)` to the `MapSqlParameterSource`.

**10. Tests to add/update** (per `cpp-test-authoring` convention, JUnit 5 + Testcontainers Postgres
where these services are already integration-tested):
- `QueryServiceTest` — V1 on first load, V(N+1) on subsequent loads for the same `query_id`.
- A migration/repository-level test (or extend an existing one) asserting the backfill produces
  `V1..VN` ordered by `effective_at` for a query seeded with multiple pre-existing rows.
- `DefendantAnswerServiceTest`, `CaseLevelAllDocumentsAnswerServiceTest`,
  `CaseLevelLatestDocumentAnswerServiceTest`, and the equivalent for `AnswerGenerationService` —
  assert `query_prompt_version` is persisted and survives the `ON CONFLICT` upsert path.
- `GenerateAnswerForQueryTaskTest` — asserts `CTX_QUERY_PROMPT_VERSION` is added to the job data
  handed to `CHECK_STATUS_OF_ANSWER_GENERATION`.
- `CheckStatusOfAnswerGenerationTaskTest` — asserts the value is read from job data and passed through
  to the correct upsert call for each `QueryLevel`.

### Out of scope for this story

- Backfilling `query_prompt_version` onto historic answer rows (nothing to derive it from).
- Any API/OpenAPI change exposing `version_label` on `QuerySummary`/`QueryVersionSummary`.
- Automatic re-generation of answers when a prompt version changes.

### Definition of done

- Migration `V1015` applies cleanly and the backfill is verified (spot-check a query with multiple
  pre-existing rows gets `V1..VN` in `effective_at` order).
- All four answer tables carry `query_prompt_version` on every newly-generated answer.
- Unit tests above pass; `gradle test` and `gradle integration` both green.
- PMD/JaCoCo thresholds unaffected.

### Notes / open questions

- None blocking. The only open question from `01`/`02` (whether to expose `version_label` via the
  API) is explicitly deferred, not decided against — raise as a separate ticket if wanted later.

---

## Stage gate

**Stage 3 (User Story) — ready for implementation.** Design (Stage 2) was approved 2026-10-19,
including the backfill decision. Proceed to Stage 5 (Code) against this story.
