# Test Specs: Persist Query Prompt Version (DD-43822 / DD-43848)

> Minimal-fix scope — one story, no NFR-coverage matrix. Scenarios are grouped: automated (already
> implemented as unit tests, referenced by exact class/method) and manual (what the testing team
> verifies directly, because it can't be exercised through the normal JPA/Testcontainers unit-test
> path — see "Why the backfill is manual" below).

---

## Automated — already implemented, run via `gradle test`

**Scenario 1 — First prompt load for a query gets `V1`** *(AC-001)*
- **Given** a query with zero existing `query_versions` rows (a fresh/mocked `QueryVersionRepository`
  with `countVersions(queryId)` unstubbed, i.e. returning `0`).
- **When** `QueryService.upsertDefinitions(...)` is called for that query.
- **Then** the saved `QueryVersion.versionLabel` is `"V1"`.
- **Proven by:** `QueryServiceTest.upsert_success` (extended).

**Scenario 2 — Nth prompt load gets `V(N+1)`** *(AC-002)*
- **Given** a query with 2 existing `query_versions` rows (`countVersions(queryId)` stubbed to `2`).
- **When** `QueryService.upsertDefinitions(...)` is called.
- **Then** the saved `QueryVersion.versionLabel` is `"V3"`.
- **Proven by:** `QueryServiceTest.upsert_computes_next_version_label_when_prior_versions_exist` (new).

**Scenario 3 — The version read at generation-start is what's carried through, for every `QueryLevel`** *(AC-004)*
- **Given** `QueryDefinitionLatest.versionLabel` is `"V2"` for the query being answered.
- **When** `GenerateAnswerForQueryTask` builds the RAG request and schedules
  `CHECK_STATUS_OF_ANSWER_GENERATION`.
- **Then** the next task's job data carries `CTX_QUERY_PROMPT_VERSION = "V2"`.
- **Proven by:** `GenerateAnswerForQueryTaskTest.shouldStartAsyncRagAndScheduleNextTask` (extended).

- **Given** that job-data value is present when `CheckStatusOfAnswerGenerationTask` runs.
- **When** the answer is generated for each `QueryLevel` (`CASE`, `CASE_ALL_DOCUMENTS`, `DEFENDANT`,
  and the default/legacy path with no level set).
- **Then** the corresponding service (`caseLevelLatestDocumentAnswerService`,
  `caseLevelAllDocumentsAnswerService`, `defendantAnswerService`, `answerGenerationService`
  respectively) is called with that same version string as its last argument.
- **Proven by:** `CheckStatusOfAnswerGenerationTaskTest` — the four
  `shouldPass...` / `shouldSaveAnswerToCdkDatabase...` tests (extended).

**Scenario 4 — `query_prompt_version` is persisted and distinct from the answer-regeneration `version` column** *(AC-004, AC-005)*
- **Given** an answer is upserted via each of the four services.
- **When** the `INSERT ... ON CONFLICT DO UPDATE` SQL runs.
- **Then** the captured `MapSqlParameterSource` contains `query_prompt_version` equal to the value
  passed in, independently of `version` (the pre-existing `COALESCE(MAX(version), 0) + 1` counter,
  unchanged).
- **Proven by:** `DefendantAnswerServiceTest.shouldUseCorrectParametersForUpsert`,
  `CaseLevelAllDocumentsAnswerServiceTest.shouldPassCorrectParametersToUpsertQuery`,
  `CaseLevelLatestDocumentAnswerServiceTest.shouldPassCorrectParametersToUpsertQuery`,
  `AnswerGenerationServiceTest.shouldPassCorrectParamsToUpsert` (all extended).

---

## Manual — for the testing team to verify directly

**Why the backfill is manual, not automated:** `gradle test`'s Testcontainers Postgres always runs
Flyway to the latest migration before any test starts. There is no "pre-`V1015`" moment inside a
normal test run to seed unlabelled `query_versions` rows and then observe the backfill happen — by
the time a test can query the table, `V1015` has already run against whatever was (or wasn't) there.
So the backfill's correctness has to be checked against a **real pre-existing dataset**, not a
freshly-created one.

**Scenario 5 — Backfill labels existing prod/sit rows correctly, in `effective_at` order** *(AC-003)*
- **Given** a staging/sit environment with `query_versions` rows that predate this change (multiple
  rows for at least one `query_id`, per the "multiple versions already loaded without a physical
  version" situation this ticket was raised for).
- **When** migration `V1015` is applied.
- **Then**, for a query with N pre-existing rows, running
  `SELECT query_id, effective_at, version_label FROM query_versions WHERE query_id = '<id>' ORDER BY effective_at;`
  shows `version_label` as `V1, V2, ..., VN` in that exact order, with no gaps, nulls or duplicates
  across the whole table (`SELECT version_label, COUNT(*) FROM query_versions GROUP BY version_label HAVING COUNT(*) <> ... ` style check, or simpler: `SELECT COUNT(*) FROM query_versions WHERE version_label IS NULL;` returns `0`).
- **How to execute:** run against a sit/staging snapshot before this ships to an environment anyone
  relies on, not a disposable test container.

**Scenario 6 — Loading a new prompt after the migration continues the existing sequence, not `V1`** *(AC-002, cross-check with Scenario 5)*
- **Given** a query that already has pre-existing rows backfilled to e.g. `V1`–`V3` (Scenario 5).
- **When** a new prompt is loaded for that query via the real `/queries` upsert endpoint (not a unit
  test — an actual HTTP call against a running instance, sit or local).
- **Then** the new row's `version_label` is `V4` — continuing from the backfilled sequence, not
  restarting at `V1`.
- **Why this matters:** this is the one place a unit test (mocked `countVersions`) can't substitute
  for an end-to-end check — it proves the backfill and the live-computation path agree with each
  other against the same real data.

**Scenario 7 — A newly-generated answer, inspected directly in the DB, shows the correct version** *(AC-004)*
- **Given** a query whose prompt has just been updated (new `version_label`, e.g. `V4`) in a sit/dev
  environment.
- **When** an answer is generated end-to-end for a case against that query (through the real
  ingestion/answer-generation flow, not mocked).
- **Then** `SELECT query_prompt_version FROM <answers table> WHERE case_id = '<id>' AND query_id = '<id>' ORDER BY version DESC LIMIT 1;`
  returns `V4` — confirming the value survived the full async RAG round-trip in a real deployment,
  not just in the job-data-threading unit tests above.
- **Which table to check** depends on the query's level: `case_level_latest_doc_answers` (`CASE`),
  `case_level_all_documents_answers` (`CASE_ALL_DOCUMENTS`), `defendant_answers` (`DEFENDANT`), or
  `answers` (default/legacy).

---

## Out of scope for this test pass

- Historic answer rows: no backfill, so no test asserting a value there (none exists to assert).
- Any API/OpenAPI-level test exposing `version_label` on `QuerySummary`/`QueryVersionSummary` — not
  changed by this story.

---

## Stage gate

**Stage 4 (Test Specs) complete.** Automated scenarios 1–4 are implemented and passing
(`gradle test` — see `03-stories.md` Definition of Done). Manual scenarios 5–7 are for the testing
team to run against a sit/staging environment before this reaches anything production-adjacent.
