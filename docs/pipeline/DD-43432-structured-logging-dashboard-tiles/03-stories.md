# User Stories: Structured Logging for Azure Monitor Dashboard Tiles (KQL replacement for Prometheus)

> **Stage 3 — User Story** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43432.** Real sub-tickets created by the requester on 2026-09-21 and linked
> below: Story 1 → [DD-43470](https://hmcts.atlassian.net/browse/DD-43470), Story 2 →
> [DD-43471](https://hmcts.atlassian.net/browse/DD-43471), Story 3 →
> [DD-43472](https://hmcts.atlassian.net/browse/DD-43472). Per CLAUDE.md's hard rule ("every story
> needs a linked Jira ticket before the test stage"), Stage 4 (Test Specs) may now proceed.
>
> **Exactly three sub-stories, fixed by the requester at Stage 1 and unchanged through Stage 2 —
> not reinterpreted or further split here** (`01-requirements.md`'s "Fixed sub-story shape" table;
> `02-design.md` §0). Acceptance criteria below are **derived from, not duplicated verbatim from**,
> `01-requirements.md`'s AC-001–AC-017, reusing AC numbers rather than inventing new ones, and
> rescoped only where `02-design.md` made an FR concrete (e.g. FR-005 needing no Java change,
> confirmed by ADR-001/ADR-002). No new ADR is raised at this stage; ADR-001–ADR-004
> (`../adrs/DD-43432-structured-logging-dashboard-tiles.md`) are all **Accepted** and are not
> reopened here. **Updated 2026-09-22, during Stage 5 implementation:** ADR-004's namespace literal
> was revised (`ns-dev-ccm-03` → `ns-ste-ccm-29`) and ADR-005 was added (30-day `ago()` fallback
> filter in both `.kql` files, revising ADR-003) — this document's Story 2 section below reflects
> both; see the ADR file for full rationale.
>
> **NFRs are kept deliberately minimal, per the requester's own instruction** ("dont give too many
> NFR's"). Each story links only the NFRs from `01-requirements.md` that actually apply to its own
> slice — not the full NFR-001–NFR-004 set repeated on every story.

**Standard DoD (every story, per `hmcts-standards.md` and this repo's CLAUDE.md hard rules)**: code
reviewed & approved (Stories 1–2 only — Story 3 is a different repo, different review route) · all
ACs covered by automated tests where the AC is automatable (Story 1: unit tests; Story 2: manual
KQL execution evidence, per AC-014) · `gradle clean build` (incl. `integration`) passes · PMD/JaCoCo
green at existing thresholds · CodeQL and secrets-scanner clean · no PII/case content/court
reference/`CJSCPPUID` in code, config, tests or fixtures · Jira ticket updated with test evidence ·
`claude-generated` + `needs-review` labels applied, linked to parent epic DD-43432 · story has its
own linked Jira sub-ticket (DD-43470 / DD-43471 / DD-43472, below).

---

## Story 1 — Add the missing `WAITING_FOR_UPLOAD` log line (CDKS)
**Jira: [DD-43470](https://hmcts.atlassian.net/browse/DD-43470)**
**No dependency on Story 2 or Story 3.**

As a **production support engineer**,
I want **`IdpcAvailabilityService.persistCaseDocument(...)` to emit a structured log line when a
placeholder `CaseDocument` reaches `WAITING_FOR_UPLOAD`**,
so that **Tile 1's ingestion-phase breakdown on the Azure Portal dashboard includes the one phase
that is currently invisible, alongside the three phases (`UPLOADED`, `INGESTED`,
`FAILED`/`EXCEEDED_FILE_SIZE_LIMIT`) that already log correctly today**.

### Background
`IdpcAvailabilityService.persistCaseDocument(...)` sets `entity.setIngestionPhase(DocumentIngestionPhase.WAITING_FOR_UPLOAD)`
and calls `saveAndFlush(entity)` with no accompanying log statement anywhere in the method — a
confirmed gap (`01-requirements.md` fact 4). `02-design.md` §1.2 gives the exact one-statement fix:
a single `log.info(...)` inserted immediately after `saveAndFlush`, as the last statement in the
method, using the same `key=value` interpolation convention as the sibling `UPLOADED` line
(`RetrieveMaterialAndUploadTask.java:129-130`), with a `placeholder` token in the message prefix so
the two phases remain mutually exclusive under the KQL `startswith_cs` predicate Story 2 writes
(design §1.2's "why this exact wording" table). This is the **only** production-code change in the
whole ticket — FR-002–FR-005 require no Java change at all, and that is stated here explicitly
rather than inventing verification tasks that would look like code changes.

### Acceptance criteria
- [ ] AC-001: Given a case with a defendant whose latest IDPC material has no existing
      `case_documents` row, when `IdpcAvailabilityService.retrieveDocuments(...)` runs, then exactly
      one INFO log event is emitted per newly persisted placeholder document, and that event
      contains the `docId`, `caseId` and `materialId` of that document.
- [ ] AC-002: Given a defendant whose document already exists (the `existingDocUuid.isPresent()`
      branch), when the same method runs, then **no** `WAITING_FOR_UPLOAD` log event is emitted for
      that defendant — only the existing `Skipping defendantId=…` line.
- [ ] AC-003: Given `n` newer IDPC documents are persisted for one case, when the method completes,
      then exactly `n` `WAITING_FOR_UPLOAD` events appear (one-to-one with the `docId`s on the
      returned new documents).
- [ ] AC-004: The emitted JSON event contains no document name, material name, defendant name, court
      reference number, `CJSCPPUID`, or document content.
- [ ] AC-005: The return value of `retrieveDocuments(...)` and the persisted `CaseDocument` rows are
      byte-for-byte unchanged by this story; the existing seven `IdpcAvailabilityServiceTest` tests
      pass with their assertions unmodified.
- [ ] AC-006 (verify, no code change — FR-002/FR-003/FR-004): the three existing Tile-1 log
      statements at `RetrieveMaterialAndUploadTask.java:129-130`, `CheckIngestionStatusForAllDefendantsTask.java:116`
      and `:194-200` are present on the delivered branch with message text, log level and emission
      point **identical to `develop`** — `git diff develop -- src/main/java/uk/gov/hmcts/cp/cdk/jobmanager/`
      is empty. No task for this AC beyond confirming the diff is empty; it is not reworded,
      re-levelled, moved or re-ordered.
- [ ] AC-012 (verify, no code change — FR-005): existing retry behaviour in
      `GenerateAnswerForQueryTask` and `CheckStatusOfAnswerGenerationTask` (`maxAttempts`,
      `CTX_ANSWER_RETRY_COUNT`, the `GENERATE_ANSWER_FOR_QUERY` re-dispatch, every returned
      `ExecutionStatus`) is identical to `develop` — no Java change is required for Tile 2, per
      ADR-001/ADR-002; existing unit tests for both classes pass unmodified.
- [ ] AC-017: `logback-spring.xml` is unmodified, and no new logging dependency appears in
      `build.gradle`.

### NFR links
- NFR-001 (Data protection): only `docId`, `caseId`, `materialId` (internal UUIDs) and the fixed
  enum value `WAITING_FOR_UPLOAD` are logged; `materialName`, `defendantId`, `courtDocumentId` and
  `description` are available at the call site and are deliberately excluded.
- NFR-003 (Additive only — no behaviour change): the new statement is the final statement in a
  `void` method, after `saveAndFlush`, takes no branch and cannot throw into the business path.
- NFR-004 (Countability): emitted exactly once per persisted placeholder row, at INFO, on the
  success path only.

### Out of scope for this story
- Any change to `RetrieveMaterialAndUploadTask.java` or `CheckIngestionStatusForAllDefendantsTask.java`
  (FR-002–FR-004 are verify-only) or to either answer-generation task (FR-005 needs no Java change).
- The KQL query files that consume this line — Story 2.
- `defendantId` / `courtdocId` in the new log line — available at the call site, excluded by design
  pending OQ-009 security sign-off; not needed by the tile.
- Any Micrometer/Prometheus reintroduction, any new logging framework or appender.

### Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs above covered by automated tests: the four new `IdpcAvailabilityServiceTest` tests per
      `02-design.md` §1.4 (`logsWaitingForUploadLine_whenPlaceholderPersisted`,
      `doesNotLogWaitingForUpload_whenDocumentAlreadyExists`,
      `logsOneWaitingForUploadLinePerPersistedDocument`,
      `waitingForUploadLineContainsNoSensitiveValues`), using the existing `ListAppender` pattern
      (`DebugLoggingInterceptorTest`), plus confirmation that the existing seven
      `IdpcAvailabilityServiceTest` tests and the existing answer-generation task tests pass
      unmodified.
- [ ] `gradle clean build` (incl. `integration`) passes; PMD/JaCoCo green at existing thresholds;
      CodeQL and secrets-scanner clean. No new `integrationTest` or `pactVerificationTest` is
      required by this story (`02-design.md` §1.4 note).
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; all test UUIDs synthetic
      (`UUID.randomUUID()`).
- [ ] Jira ticket updated with test evidence.

### Notes / open questions
- The Story 1 diff of `src/main/java` is exactly `IdpcAvailabilityService.java`, +2 lines, -0 lines
  (`02-design.md` §1.3). Nothing else in `src/main/java`, `logback-spring.xml`, `build.gradle`, any
  `application*.yml`, or any Flyway migration changes.
- OQ-009 (security sign-off on `caseId`/`docId`/`materialId` being dashboard-visible from a shared
  Azure Portal dashboard, and confirmation that `defendantId`/`courtdocId` stay excluded) is owned
  by the security reviewer and is due before merge — carried forward from `01-requirements.md`, not
  resolved by this story.
- Jira sub-ticket: [DD-43470](https://hmcts.atlassian.net/browse/DD-43470).

---

## Story 2 — KQL query definitions in `support/dashboard-kql/` (CDKS)
**Jira: [DD-43471](https://hmcts.atlassian.net/browse/DD-43471)**
**No dependency on Story 1 or Story 3** (the queries are drafted in full at Stage 2 against the
message text of lines that already exist on `develop` today, plus Story 1's new line — the query
files can be authored and landed independently of Story 1's PR merging first, though both must be
in before Story 3 can wire real tiles against them).

As a **production support engineer**,
I want **two KQL query files — one per dashboard tile — checked into this repo's own
`support/dashboard-kql/` folder, executed at least once against a real Log Analytics workspace to
prove they run**,
so that **the query that drives each dashboard tile lives and versions with the log lines it
depends on, and I can trust the numbers on the dashboard before Story 3 wires it up**.

### Background
`02-design.md` §2 gives both queries in full, plus the supporting script and README, mirroring
`service-cp-crime-hearing-results-document-subscription`'s (HRDS) `support/` convention exactly
(ADR-004). No `src/main/java` code is touched by this story. Four new files only. **Revised
2026-09-22:** both `.kql` files now also carry a `TimeGenerated > ago(30d)` fallback filter (ADR-005)
and the namespace literal is `ns-ste-ccm-29` (was `ns-dev-ccm-03`, ADR-004 decision point 4) — this
story delivers both, per `02-design.md` §2.3/§2.4 as updated.

```
support/
├── README.md
├── sync-dashboard-to-terraform.sh
└── dashboard-kql/
    ├── ingestion-phase-counts.kql
    └── answer-generation-outcomes.kql
```

### Acceptance criteria
- [ ] AC-007: Given a successful upload, a successful ingestion, an ingestion failure with
      `reason='FILE_SIZE_OVER_LIMIT'`, and an ingestion failure with any other reason, when
      `ingestion-phase-counts.kql` runs over the resulting log events, then it attributes each event
      to exactly one of `UPLOADED`, `INGESTED`, `EXCEEDED_FILE_SIZE_LIMIT`, `FAILED` — with no event
      counted twice and none unattributed (`02-design.md` §2.3's five mutually exclusive branches).
- [ ] AC-008: `ingestion-phase-counts.kql` does **not** count the polling-exception line
      `Document status check FAILED with reason='…'` as a terminal ingestion failure.
- [ ] AC-008a *(added 2026-09-22, raised at Code Review — OQ-011)*: `ingestion-phase-counts.kql`'s
      header comment explicitly records, in writing, that the retry-exhaustion path
      (`CheckIngestionStatusForAllDefendantsTask.java:213-214` — `updateIngestionPhase(documentId,
      FAILED)` on exhausted polling retries) is **not** visible to this query, because that path
      emits no distinguishing log line and adding one is a Java change outside this story's scope.
      This AC is satisfied by the written record (`02-design.md` §2.3), not by query behaviour — the
      undercount itself is not fixed by Story 2 and is tracked as a follow-up under OQ-011.
- [ ] AC-009: Given a set of answer-generation requests over a bounded time window in which some
      succeed first time, some succeed after one or more retries, and some exhaust `maxAttempts`,
      when `answer-generation-outcomes.kql` runs over that window, then it reports a total count of
      `Async RAG started …` events, a succeeded count of `Answer Generation updated in the DB …`
      events, and a failed count of `Answer Generation Failed for …` events — one row per RAG
      transaction, per ADR-001, not one per business request.
- [ ] AC-010: Per ADR-001 (accepted, supersedes the original AC-010 wording in
      `01-requirements.md` — recorded here so the ticket is closed against the current decision, not
      re-tested against the superseded one): a query that fails an attempt and then succeeds on
      retry mints a new `ragTransactionId` and is counted as **two** outcomes (one failed, one
      succeeded), not de-duplicated to one.
- [ ] AC-011: `answer-generation-outcomes.kql`'s header comment explicitly names and excludes, with
      reasons, the two infrastructure-error paths not counted as failures (`Failed to start async
      RAG for caseId=`, `Failed to check answer generation status RAG for`) per ADR-002 — satisfying
      the "or the design explicitly and in writing records which of these are excluded and why"
      limb of the original AC.
- [ ] AC-013: `support/dashboard-kql/` exists and contains one query file per tile; each file's
      header comment names the source log statement(s) it depends on (class and message marker), so
      FR-006's coupling is discoverable from the query side.
- [ ] AC-014: Each query is syntactically valid KQL against the `ContainerLogV2` schema and has been
      executed successfully at least once against a real environment's Log Analytics workspace, with
      the result recorded on this story's Jira ticket.
- [ ] AC-016: No PII, case content, court reference number, `CJSCPPUID`, connection string,
      subscription key, or real workspace GUID appears in any of the four new files — synthetic
      namespace literals (`ns-dev-ccm-03`, `ns-ste-ccm-29`) only.

### NFR links
- NFR-001 (Data protection): applies to the query files and `support/README.md` in the same way it
  applies to code — no PII, no case content, no real workspace identifiers.

### Out of scope for this story
- Any `src/main/java`, `logback-spring.xml`, or `build.gradle` change — this story is entirely
  non-code artefacts.
- Wiring these queries into an actual dashboard tile — Story 3, a different repo.
- The `IsQueryContainTimeRange` per-tile override and the dashboard-level default-time-range terraform
  template change, and the namespace-generalization fix (ADR-003, ADR-004) — both Story 3 concerns,
  in a different repo, **not** KQL-file content. (The `TimeGenerated > ago(30d)` KQL-level fallback
  itself, per ADR-005, *is* in scope and delivered by this story — see Background above; only the
  terraform-side `IsQueryContainTimeRange`/default-range plumbing is Story 3's.)
- `run-query.sh`, `logs-kql/`, `chart-kql/`, `alerts-kql/` folders — HRDS has them, this story does
  not create them (`02-design.md` §5).
- **Fixing the Tile-1 `FAILED` undercount at its source.** The retry-exhaustion path
  (`CheckIngestionStatusForAllDefendantsTask.java:213-214`) needs a new Java log line to become
  KQL-visible — that is a `src/main/java` change, which this story explicitly excludes above. This
  story only documents the gap in the query header (AC-008a); closing it is a follow-up ticket
  (OQ-011), not part of DD-43432's delivered scope.

### Definition of done
- [ ] Code (file) reviewed and approved via normal PR review, even though no Java is touched.
- [ ] `support/dashboard-kql/ingestion-phase-counts.kql` and `answer-generation-outcomes.kql` land
      exactly as drafted in `02-design.md` §2.3–§2.4; `support/sync-dashboard-to-terraform.sh` lands
      as the HRDS adaptation described in `02-design.md` §2.5 (executable bit set, `DEST="$TF_REPO/queries/cdks"`,
      `mkdir -p "$DEST"` added); `support/README.md` lands per `02-design.md` §2.6.
- [ ] AC-014's execution evidence (query run against a real Log Analytics workspace) is captured and
      attached to this story's Jira ticket — this is a manual verification step, not automatable in
      `gradle clean build`.
- [ ] `gradle clean build` (incl. `integration`) still passes unchanged — this story adds no code
      under `src/`, so no new test target is introduced by it.
- [ ] No PII/case content/court reference/`CJSCPPUID` in the diff; secrets-scanner clean.
- [ ] Jira ticket updated with test evidence, including the AC-014 execution result.

### Notes / open questions
- OPEN-DS-001 (`02-design.md` §2.2): CDKS's pod-name prefix is not determinable from this repo, so
  both queries filter on `LogJson.app == 'cp-case-document-knowledge-service'` rather than
  `PodName startswith '<prefix>'` (HRDS's convention). This is correct and sufficient as drafted; an
  optional `PodName` narrowing is a non-blocking follow-up for the requester/production support, not
  a prerequisite for this story.
- The `ns-ste-ccm-29` namespace literal (revised 2026-09-22 from `ns-dev-ccm-03`) in both `.kql` files
  is a known, deliberate, non-blocking dependency for Story 3 (ADR-004) — it does not block landing
  this story. Making CDKS itself environment-aware, instead of one hardcoded literal, is a separately
  tracked follow-up (OQ-012), not this story.
- The `TimeGenerated > ago(30d)` fallback filter (ADR-005, added 2026-09-22) is delivered by this
  story; it AND's with the portal's picker, so a picker range wider than 30 days is still capped at
  30 days — see `02-design.md` §2.3/§2.4 implementer notes for the tradeoff.
- Jira sub-ticket: [DD-43471](https://hmcts.atlassian.net/browse/DD-43471).

---

## Story 3 — Dashboard tile wiring (`cp-amp-terraform-az-dashboard`) — scope note only
**Jira: [DD-43472](https://hmcts.atlassian.net/browse/DD-43472)**
**Different repository, different owner (platform/SRE). Consumes Story 2's queries. Not implemented
by this pipeline run.**

As a **platform/SRE dashboard owner**,
I want **CDKS's two KQL queries wired into a new dashboard config alongside the existing
`hearing-results-document-subscription` and `pcr-data` dashboards**,
so that **production support engineers can see Tile 1 (ingestion phase breakdown) and Tile 2
(answer generation outcomes) on the Azure Portal, sourced from Story 2's queries**.

### Background
This story is tracked under DD-43432 and is genuinely in scope for the ticket, but it lands in
`cp-amp-terraform-az-dashboard` — a different repository, owned by a different team — and is **not**
implemented by this pipeline run. This section exists so whoever picks it up in that repo knows what
is expected and what it depends on; it deliberately does not carry the story-level task breakdown
Stories 1–2 have, per the requester's own instruction to keep this ticket to three sub-stories
without over-specifying the one that is out of this repo.

### What Story 3 covers (per `02-design.md` §3)
- A new `configs/cdks.json` tile-layout file declaring two tiles: `"query": "ingestion-phase-counts"`
  and `"query": "answer-generation-outcomes"` (filenames from Story 2, without the `.kql`
  extension), with no `chart`/`metric` keys so both render as `AnalyticsGrid` tables.
- The matching `queries/cdks/` folder, populated by running Story 2's
  `sync-dashboard-to-terraform.sh` from a checkout with both repos as sibling directories.
- A `dashboards.tf` / `tile_inputs_template` change (ADR-003) so these two tiles can be registered
  with `IsQueryContainTimeRange: false` — the shared module currently emits `true` unconditionally.
  The dashboard-level default time range lowering from 90 to 30 days remains worth doing as picker-UX
  polish, but per **ADR-005** (added 2026-09-22) is no longer strictly required for a 30-day default
  to hold, since both `.kql` files (Story 2) now carry their own `TimeGenerated > ago(30d)` fallback.
- A namespace-generalization fix (ADR-004) — `dashboards.tf` currently does a single hardcoded
  `replace(query, "ns-dev-amp-01", var.namespace)` against one global `var.namespace`; that search
  string matches **neither** of CDKS's namespace literals, so it is a silent no-op against CDKS's
  queries either way and needs a per-dashboard namespace map instead. Until that fix lands, **ste**
  tiles happen to show correct data (the Story-2 literal, revised 2026-09-22, is `ns-ste-ccm-29`) —
  every other environment shows ste's data under a dashboard deployed there.

### Dependencies and constraints
- Consumes Story 2's two `.kql` files verbatim via `sync-dashboard-to-terraform.sh` — cannot be
  meaningfully started until Story 2's files exist.
- Both the `IsQueryContainTimeRange` template change and the namespace-generalization fix are shared
  `cp-amp-terraform-az-dashboard` module changes, not CDKS-specific, and are owned by that repo's
  team. The requester is raising both directly with that repo's owner (OQ-010,
  `01-requirements.md`); this is an external dependency for Story 3, not a blocker on Stories 1–2.
- Review and merge route is that repository's own — not this pipeline's Code Review or CI stages.

### Definition of done (indicative only — owned by the receiving team, not finalized here)
- [ ] `configs/cdks.json` and `queries/cdks/*.kql` land, sourced from Story 2 via the sync script.
- [ ] `dashboards.tf`'s per-tile `IsQueryContainTimeRange` override and the namespace map fix are
      reviewed and merged in `cp-amp-terraform-az-dashboard`.
- [ ] Terraform plan/apply executed for at least the dev environment; both tiles render and show
      real data on the Azure Portal dashboard.
- [ ] Jira ticket updated with confirmation that both tiles are live.

### Notes / open questions
- **Ownership confirmed as a different team** (per this pipeline's task framing). Whether CDKS
  engineers raise that repo's PR themselves once the terraform fix lands, and whether tile delivery
  is a hard dependency for closing DD-43432 or can trail Stories 1–2, is OQ-010 — owned by the
  requester, due before this story starts in earnest.
- No file in `cp-amp-terraform-az-dashboard` has been read or verified as part of Stages 1–3 of this
  pipeline; the summary above is drawn entirely from `02-design.md` §3, which itself is based on a
  read of that repo's `main.tf`, `locals.tf`, `variables.tf`, `dashboards.tf` and `vars/*.tfvars`.
- Jira sub-ticket: [DD-43472](https://hmcts.atlassian.net/browse/DD-43472).

---

## Summary

| Story | Title | Repo | Jira | Depends on |
|---|---|---|---|---|
| 1 | Add the missing `WAITING_FOR_UPLOAD` log line | CDKS | [DD-43470](https://hmcts.atlassian.net/browse/DD-43470) | none |
| 2 | KQL query definitions in `support/dashboard-kql/` | CDKS | [DD-43471](https://hmcts.atlassian.net/browse/DD-43471) | none |
| 3 | Dashboard tile wiring (scope note only) | `cp-amp-terraform-az-dashboard` | [DD-43472](https://hmcts.atlassian.net/browse/DD-43472) | Consumes Story 2's queries |

**Not a story here** (per `01-requirements.md`'s Out of scope, unchanged at Stage 3): any new or
changed business behaviour, API change, schema/Flyway change, retry-policy change, or
ingestion/answer-generation logic change; reintroducing Micrometer, `/actuator/prometheus`,
Application Insights SDK or an OpenTelemetry metrics exporter; alerting (rules, thresholds, action
groups, on-call routing — a separate ticket per OQ-010); any tile beyond the two named; ingestion
phases outside the five named (`UPLOADING`, `INGESTING`, `NOT_FOUND`); backfill of historical data;
rewording or restructuring any existing log line not named in FR-001–FR-005, or a repo-wide logging
audit.

**Carried-forward open items:**
- ~~Real Jira sub-tickets required.~~ **Done** — created by the requester on 2026-09-21:
  [DD-43470](https://hmcts.atlassian.net/browse/DD-43470) (Story 1),
  [DD-43471](https://hmcts.atlassian.net/browse/DD-43471) (Story 2),
  [DD-43472](https://hmcts.atlassian.net/browse/DD-43472) (Story 3).
- **OQ-001** — confirm `00-input-brief.md` is the complete and current DD-43432 ticket text, and post
  the Stage 1 summary comment to the ticket manually (no Jira MCP tool available).
- **OQ-008** — confirm the five-phase Tile-1 set (excluding `UPLOADING`, `INGESTING`, `NOT_FOUND`) is
  intentional and complete.
- **OQ-009** — security reviewer sign-off, before merge, that `caseId`/`docId`/`materialId`/`queryId`/
  `ragTransactionId` being queryable from a shared Azure Portal dashboard is acceptable, and that
  `defendantId`/`courtdocId` staying excluded from Story 1's new line is correct.
- **OQ-010** — requester to confirm Story 3's ownership/merge route with the
  `cp-amp-terraform-az-dashboard` repo owner, and whether Story 3's delivery is a hard dependency for
  closing DD-43432 or can trail Stories 1–2.

---

## Stage gate

**Stage 3 (User Story) is a Human gate.** This document — the three sub-stories and their acceptance
criteria — must be reviewed and explicitly approved before Stage 4 (Test Specs) begins. Real Jira
sub-tickets are now linked (above), satisfying the pipeline's hard rule. Stage 4 is not started by
this document.
