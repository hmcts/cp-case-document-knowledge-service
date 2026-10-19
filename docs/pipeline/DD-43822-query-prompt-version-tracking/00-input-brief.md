# 00 — Input Brief

**Jira ticket:** [DD-43822](https://hmcts.atlassian.net/browse/DD-43822)
**Sub-task (implementation):** [DD-43848](https://hmcts.atlassian.net/browse/DD-43848)

---

## Raw brief, as given

> We have one new ticket raised, need to update more details in ticket. The issue is: whenever a
> new version of prompts is loaded into `query_versions`, we are creating a new version with
> `effective_at`, but no physical version is getting added — if no previous version exists, start
> with `V1` and then append `V2` etc. So that whenever we persist answers tables (all defendant,
> case-level-all-docs etc, all answers tables) we need to persist `query_prompt_version` like `V1`,
> `V2` etc, so that we know when an answer was generated against which version.
>
> First create Jira content, then create a new branch from main and give design docs as per the
> HMCTS plugin. Once design is approved we can do implementation. Don't give NFRs etc — just give a
> very minimal design fix (adding a new version column etc).

## Jira content drafted for DD-43822 (this session — paste into the ticket)

**Summary:** Persist the prompt version used to generate each answer; add an explicit version label
to `query_versions`

**Description:** Today, `query_versions` rows are inserted with only a `query_id` + `effective_at`
timestamp — no explicit version number or label. When a new prompt is loaded for an existing query
(via `QueryService.upsertDefinitions`), we get a new timestamped row, but nothing marks it as "the
2nd version of this query" vs "the 5th." Separately, when an answer is generated and persisted
(across `answers`, `defendant_answers`, `case_level_all_documents_answers`,
`case_level_latest_doc_answers`), we don't record which version of the query's prompt was actually
used to produce it — so we can't later tell "this answer came from prompt V2" vs "V3," which matters
once a prompt is corrected and we need to know which existing answers were generated against the old
wording.

**Acceptance criteria:**
- `query_versions` gets an explicit, ordered version label per `query_id`: no prior version ⇒ `V1`;
  each subsequent row for the same `query_id` increments (`V2`, `V3`, …), ordered by `effective_at`.
- All four answer-generation paths (`CASE`, `CASE_ALL_DOCUMENTS`, `DEFENDANT`, default/legacy) persist
  which query-prompt version was actually used, on the answer row itself.
- The version recorded against an answer is the version genuinely used to build that specific
  generation's RAG prompt — not just "whatever is latest" read at some unrelated later point.
- Existing `query_versions` rows get a retrofitted label; existing answer rows are **not** backfilled
  (they don't currently record this at all — nothing to backfill from).

**Out of scope:** backfilling historic answer rows; any new API/OpenAPI surface exposing the version
label; automatic re-generation of answers when a prompt version changes.

---

## Note on source

No Atlassian/Jira MCP tool is available in this session, so DD-43822 itself was not fetched — this
document is grounded in the brief as given above plus a full read of the current CDKS codebase (see
`02-design.md`'s evidence trail). The Jira content above should be pasted into the ticket manually.
