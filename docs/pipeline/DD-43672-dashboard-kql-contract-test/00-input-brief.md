# Input Brief: DD-43672 — Contract test tying dashboard KQL markers to their source log lines

**Jira ticket:** https://hmcts.atlassian.net/browse/DD-43672

**Background:** DD-43432 added two Azure Monitor dashboard tiles (Tile 1 — ingestion phase
breakdown, Tile 2 — answer generation outcomes), each driven by KQL queries in
`support/dashboard-kql/*.kql` that match on specific log-line message prefixes emitted by CDKS.
FR-006 in `docs/pipeline/DD-43432-structured-logging-dashboard-tiles/01-requirements.md` declares
these log lines a "consumed interface" — changing the text, level, field names or emission point of
any of them requires updating the corresponding `.kql` file in the same change — but this was always
a **documented convention, not a build-enforced one** (Stage 1's OQ-007, resolved at Stage 2 as "no
automated enforcement is expected").

DD-43672 is the follow-up raised against that resolution: a reviewer on PR #231 (DD-43470/DD-43471,
2026-09-24) pointed out that of the 7 log-line markers the two tiles depend on, only one
(`WAITING_FOR_UPLOAD`) has any test at all — and even that test duplicates the marker string by hand
in the test file rather than reading it from the `.kql` file it's meant to protect. Tracked as
**OQ-013** in DD-43432's `01-requirements.md`, `02-design.md`, `03-stories.md` and `04-test-specs.md`.

## Raw request (verbatim, as given by the reviewer on PR #231)

> another comment on PR #231 (DD-43470/DD-43471): the dashboard tiles depend on 7 specific log
> lines, but only the new WAITING_FOR_UPLOAD line has a test, and even that test uses a hardcoded
> copy of the string rather than reading it from the .kql file. If someone rewords or re-levels any
> of those log statements, CI stays green and the tile quietly shows 0. I'm going to suggest a small
> contract test that reads the markers from support/dashboard-kql/*.kql and checks that each one is
> actually logged. Happy for it to be a follow-up ticket, but I'd like it tracked before Story 3
> wires the tiles up.

## The 7 markers this ticket needs to protect

Verified against `support/dashboard-kql/*.kql` on the DD-43470/DD-43471 branch (PR #231):

| # | Tile | Segment | `.kql` file | Marker (`startswith_cs` / `contains`) | Source log statement |
|---|------|---------|-------------|-----------------------------------------|-----------------------|
| 1 | 1 | `WAITING_FOR_UPLOAD` | `ingestion-phase-counts.kql` | `'Saved CaseDocument placeholder docId='` | `IdpcAvailabilityService.persistCaseDocument(...)` |
| 2 | 1 | `UPLOADED` | `ingestion-phase-counts.kql` | `'Saved CaseDocument docId='` | `RetrieveMaterialAndUploadTask.java` |
| 3 | 1 | `INGESTED` | `ingestion-phase-counts.kql` | `'INGESTION SUCCESS identifier='` | `CheckIngestionStatusForAllDefendantsTask.java` |
| 4 | 1 | `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT` | `ingestion-phase-counts.kql` | `'ingestion FAILED for identifier='`, split by `contains "reason='FILE_SIZE_OVER_LIMIT'"` | `CheckIngestionStatusForAllDefendantsTask.java` |
| 5 | 2 | Total | `answer-generation-outcomes.kql` | `'Async RAG started for caseId='` | `GenerateAnswerForQueryTask.java` |
| 6 | 2 | Succeeded | `answer-generation-outcomes.kql` | `'Answer Generation updated in the DB for caseId='` | `CheckStatusOfAnswerGenerationTask.java` |
| 7 | 2 | Failed | `answer-generation-outcomes.kql` | `'Answer Generation Failed for caseId='` | `CheckStatusOfAnswerGenerationTask.java` |

**Current test coverage, verified:**
- Marker #1 (`WAITING_FOR_UPLOAD`): tested in `IdpcAvailabilityServiceTest` (4 tests, added on
  DD-43470) — but via `WAITING_FOR_UPLOAD_LOG_PREFIX`, a string literal hardcoded in the test file,
  **not** read from `support/dashboard-kql/ingestion-phase-counts.kql`.
- Markers #2–#7: **zero** test coverage of the log line itself. DD-43470's AC-006/AC-012 only assert
  `git diff develop -- src/main/java/...` is empty for the files containing these lines — a
  point-in-time diff check against this one PR, not a standing regression guard.

## Scope guardrails carried over from DD-43432

- This is a small, contained follow-up — a test, not a feature. No dashboard/tile behaviour changes.
- Does not touch Method A/Method B KQL-behaviour verification (AC-007/AC-008/AC-009/AC-010/AC-011/AC-014
  in DD-43432's `04-test-specs.md`) — those remain manual, real-workspace verification, unrelated gap.
- Does not fix the Tile-1 `FAILED` undercount via retry exhaustion (DD-43432's OQ-011) — separate,
  already-tracked gap, not this ticket's concern.
- Does not touch Story 3 / the `cp-amp-terraform-az-dashboard` repo.
- Must close **before Story 3** (DD-43472) wires the tiles up live, per the reviewer's stated
  priority — a silent drift should not ship to a live dashboard before something can catch it.

## Code locations verified against `develop` (as of DD-43470/DD-43471's PR #231)

Same 7 source files as the marker table above, plus:
- `src/test/java/uk/gov/hmcts/cp/cdk/services/IdpcAvailabilityServiceTest.java` — the one existing
  test with the hardcoded-copy problem (`WAITING_FOR_UPLOAD_LOG_PREFIX`, line 43).
- `support/dashboard-kql/ingestion-phase-counts.kql`, `support/dashboard-kql/answer-generation-outcomes.kql`
  — the two files whose markers need to become the single source of truth for any new test.
