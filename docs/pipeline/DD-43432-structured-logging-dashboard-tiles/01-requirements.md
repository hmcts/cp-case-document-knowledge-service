# Requirements: Structured Logging for Azure Monitor Dashboard Tiles (KQL replacement for Prometheus)

> **Stage 1 — Requirements** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43432** · Branch: `DD-43432`
> Direct follow-up to DD-43182 / DD-43185, whose Prometheus/Micrometer instrumentation was
> withdrawn in full on 2026-09-18 (see `adrs/DD-43182-operational-metrics-instrumentation.md`
> ADR-012 and `adrs/DD-43185-stalled-work-scheduler-monitoring.md` ADR-009). Those ADRs explain
> **why this ticket exists**; they are not themselves requirements for DD-43432.
> **The deliverable shape is already fixed by the requester and is not open at this stage:**
> exactly three sub-stories — (1) CDKS log-line changes, (2) CDKS `support/dashboard-kql` query
> files, (3) tile wiring in `cp-amp-terraform-az-dashboard`. Only story (1) and the CDKS-side
> half of story (2) are in this repo; the requirements below are scoped accordingly.

---

## Context

CDKS now has **no custom metrics surface at all** — `/actuator/prometheus`, the Micrometer
Prometheus registry and every `cdk_*` meter were deleted. The platform-confirmed replacement is
**structured JSON logs → Container Insights → Azure Monitor `ContainerLogV2` → KQL → a
Terraform-managed Azure Portal dashboard**, mirroring the pattern already in production for the
`service-cp-crime-hearing-results-document-subscription` (HRDS) service.

Two dashboard tiles are wanted:

- **Tile 1 — Ingestion phase breakdown:** how many documents reached `UPLOADED`, `INGESTED`,
  `FAILED`, `EXCEEDED_FILE_SIZE_LIMIT`, and `WAITING_FOR_UPLOAD`.
- **Tile 2 — Answer generation:** total answer-generation requests issued, and of those how many
  succeeded and how many failed.

The key finding from the ADR-012 investigation holds up against the current `develop`: **most of
the log lines these tiles need already exist**, because the same call sites were already logging at
INFO/WARN/ERROR before the withdrawn Micrometer instrumentation was bolted alongside them. This
ticket is therefore a small, additive logging change plus query/dashboard definition — not new
functionality.

CDKS already emits structured JSON to stdout via `logback-spring.xml`
(`net.logstash.logback.encoder.LogstashEncoder`, custom fields `app` / `service`), and DD-43183
already puts `correlationId`, `caseId`, `docId` and `transactionId` into MDC via `CorrelationScope`
— so those four already appear as **top-level JSON fields** on every log event emitted inside a
scope. Values interpolated into a log *message* (`docId={}`, `caseId={}`, …) are, by contrast, only
present inside the message string. That distinction matters for how the KQL is written (OQ-003).

### Actors

| Actor | Interest in this change |
|---|---|
| Production support engineer | Primary consumer. Reads both tiles on the Azure Portal dashboard to see ingestion throughput/failures and answer-generation health. |
| Platform / SRE (Azure Monitor + dashboard owners) | Own `cp-amp-terraform-az-dashboard` and the `ContainerLogV2` ingestion path; merge story 3. |
| CDKS engineers | Make the log changes; thereafter must treat these log lines as a consumed interface (FR-006). |
| Security / data-protection reviewer | Confirms no PII, case content or `CJSCPPUID` reaches the log-backed dashboard (NFR-001, OQ-009). |

### Code facts verified against this branch

| # | Statement | Verified? | Evidence |
|---|---|---|---|
| 1 | `UPLOADED` has a log line | **Yes** | `RetrieveMaterialAndUploadTask.java:129-130` — `log.info("Saved CaseDocument docId={}, caseId={}, materialId={}, sizeBytes={}, blobUri={}, requestId={}", …)`, emitted immediately after `saveDocumentUploaded(...)` |
| 2 | `INGESTED` has a log line | **Yes** | `CheckIngestionStatusForAllDefendantsTask.java:116` — `log.info("INGESTION SUCCESS identifier='{}', docId={}", blobName, documentId)` |
| 3 | `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT` share one log line and are distinguished by `reason=` | **Yes** | `CheckIngestionStatusForAllDefendantsTask.java:194-200` — `log.error("ingestion FAILED for identifier='{}' reason='{}' (caseId={}, docId={}).", blobName, status, caseId, documentId)`; the phase itself is chosen at `:191-192` (`FILE_SIZE_OVER_LIMIT` → `EXCEEDED_FILE_SIZE_LIMIT`, else `FAILED`) |
| 4 | `WAITING_FOR_UPLOAD` has **no** log line | **Yes — confirmed gap** | `IdpcAvailabilityService.persistCaseDocument(...)` (`:117-130`) sets `entity.setIngestionPhase(DocumentIngestionPhase.WAITING_FOR_UPLOAD)` at `:125` and calls `saveAndFlush` at `:129` with no log statement anywhere in the method. The only logs in the class are `:81` (`Skipping defendantId=…`) and `:95` (`Latest defendant identified: …`) |
| 5 | `caseId` is already in MDC at the `WAITING_FOR_UPLOAD` call site | **Yes** | `IdpcAvailabilityService.retrieveDocuments` wraps the whole flow in `CorrelationScope.withIdentifiers(caseId.toString(), null, null)` (`:64`). `docId` and `materialId` are **not** in MDC there |
| 6 | Answer-generation log lines exist but are not tile-ready | **Yes** | `GenerateAnswerForQueryTask.java:99` (`Async RAG started …`), `:115` (`log.error("Failed to start async RAG …")`); `CheckStatusOfAnswerGenerationTask.java:145` (`Answer Generation updated in the DB …`), `:150` (`Answer Generation Failed …`), `:161` (`Answer generation failed. Retrying {}/{} …`), `:178` (`log.warn("Max retries reached …")`), `:189` (`log.error("Failed to check answer generation status RAG …")`) |
| 7 | The answer-generation lines cannot be counted naively | **Yes — this is the core problem** | Retries re-dispatch `GENERATE_ANSWER_FOR_QUERY` (`CheckStatusOfAnswerGenerationTask.java:168-175`), so `Async RAG started` fires **once per attempt**, not once per request. `Answer Generation Failed` (`:150`) is logged *before* the retry decision (`:159`), so it fires on attempts that subsequently succeed. Only `Max retries reached` (`:178`) is a terminal failure — and it is not the only one (`:115` and `:189` are separate retrying error paths). There is no common outcome field across any of them |
| 8 | Ingestion phases `UPLOADING`, `INGESTING`, `NOT_FOUND` are not meaningful tile values | **Yes** | `UPLOADING` appears only as the JPA field initialiser on `CaseDocument.java:73` and is never explicitly transitioned to; `INGESTING` is never written anywhere in `src/main/java`; `NOT_FOUND` is only ever set on an API *response* (`IngestionService.java:46`), never persisted. The brief's five-phase tile is therefore the correct and complete set (see OQ-008) |
| 9 | No `support/` folder exists in this repo yet | **Yes** | `support/dashboard-kql` is a new folder created by story 2 |
| 10 | Structured JSON logging is already in place; no framework change needed | **Yes** | `src/main/resources/logback-spring.xml` — single `LogstashEncoder` console appender wrapped in `AsyncAppender`, root at `INFO` |
| 11 | A second, distinct code path also sets `ingestion_phase = FAILED`, with no matching log line — same class of gap as fact 4 | **Yes — confirmed gap, raised at Code Review (PR #230)** | `CheckIngestionStatusForAllDefendantsTask.java:212-216`: when polling retries are exhausted (`latestRetryCount == LAST_RETRY_COUNT`), `updateIngestionPhase(documentId, FAILED)` fires at `:214` with no log line of its own — the only preceding log is the generic `:212` `"Ingestion status not complete for identifier='{}' → retrying"`, which also fires on every retry that later succeeds and so cannot serve as a KQL predicate for this phase. Fact 3's KQL predicate (`:194-200`) cannot see this path at all, meaning documents that fail by **timing out** — likely the most common real ingestion failure — never register as `FAILED` on Tile 1 |

**Note on source:** this document is grounded in `00-input-brief.md` and in the code read on this
branch. Jira DD-43432 itself was **not** fetched — no Atlassian/Jira MCP tool is available in this
session — so no Stage-1 summary comment has been posted to the ticket (OQ-001).

---

## Functional Requirements

Scope: CDKS (`src/main/java`) only. FR-007 covers the CDKS-side KQL folder at requirement level;
the terraform dashboard work is out of this repo (see *Out of scope for this repo*).

| ID | Requirement |
|----|-------------|
| **FR-001** | **Add the missing `WAITING_FOR_UPLOAD` log line.** `IdpcAvailabilityService.persistCaseDocument(...)` must emit exactly one `INFO` log event immediately after the placeholder `CaseDocument` is successfully persisted (`caseDocumentRepository.saveAndFlush(entity)`, currently `:129`). The line must carry, at minimum, **`docId`**, **`caseId`** and **`materialId`**, using the same `key=value` interpolation convention as its sibling lines in the same pipeline (`RetrieveMaterialAndUploadTask.java:129` is the reference: `docId={}, caseId={}, materialId={}, …`). The line must also be attributable to the `WAITING_FOR_UPLOAD` phase unambiguously enough for a KQL predicate to select it and nothing else (exact marker per OQ-004). `defendantId` and `courtdocId` are available at the call site but are **not** required by the tile — include only if OQ-009 clears them. It must be emitted only on the success path, i.e. it must not fire for a defendant skipped at `:80-83` because a document already exists. |
| **FR-002** | **`UPLOADED` — verify, do not change.** The existing `log.info("Saved CaseDocument docId={}, caseId={}, materialId={}, sizeBytes={}, blobUri={}, requestId={}", …)` at `RetrieveMaterialAndUploadTask.java:129-130` is confirmed present and sufficient for Tile 1: it is emitted exactly once per successful upload, at INFO, and already carries `docId`, `caseId` and `materialId`. Story scope for this phase is **verify and write the KQL against it** — the Java line is not to be reworded, re-levelled, moved, or re-ordered. |
| **FR-003** | **`INGESTED` — verify, do not change.** The existing `log.info("INGESTION SUCCESS identifier='{}', docId={}", blobName, documentId)` at `CheckIngestionStatusForAllDefendantsTask.java:116` is confirmed present and sufficient for Tile 1. Story scope is **verify and write the KQL against it**; no Java change. |
| **FR-004** | **`FAILED` / `EXCEEDED_FILE_SIZE_LIMIT` — verify, do not change.** The existing `log.error("ingestion FAILED for identifier='{}' reason='{}' (caseId={}, docId={}).", blobName, status, caseId, documentId)` at `CheckIngestionStatusForAllDefendantsTask.java:194-200` is confirmed present and sufficient for Tile 1, and the `reason=` value already distinguishes the two phases (`FILE_SIZE_OVER_LIMIT` → `EXCEEDED_FILE_SIZE_LIMIT`, any other failure status → `FAILED`, per `:191-192`). Story scope is **verify and write the KQL to split on `reason=`**; no Java change. Note that the separate `log.error("Document status check FAILED with reason='{}' …")` at `:204-209` is an *exception-during-polling* path that retries — the KQL must not conflate it with a terminal ingestion failure. **Known, accepted gap (fact 11, OQ-011):** a third, distinct path — polling-retry exhaustion at `:213-214` — also terminally sets `FAILED`, but emits no log line of its own to drive a KQL predicate. Because this story's Java scope is verify-only for FR-002–FR-004 (no new log line is added here), this path is **explicitly excluded** from the Tile-1 `FAILED` count for this ticket (AC-008a), rather than silently left broken — see OQ-011 for the follow-up. |
| **FR-005** | **Answer-generation outcome must be countable (Tile 2) — RESOLVED, no Java change needed.** Requester decision (2026-09-21): count **one per RAG transaction**, not one per business request — a retried query that fails then succeeds counts as two separate outcomes (one failed, one succeeded), not de-duplicated to one. Under this definition the existing lines are already sufficient: `Async RAG started …` (`GenerateAnswerForQueryTask.java:99`) = total attempted, `Answer Generation updated in the DB …` (`CheckStatusOfAnswerGenerationTask.java:145`) = succeeded, `Answer Generation Failed for …` (`:150`) = failed — each already fires exactly once per `ragTransactionId` (fact 7 confirms a retry mints a brand-new `ragTransactionId`, so there is no cross-attempt double-counting risk). Requester decision: the two infrastructure-error paths (`GenerateAnswerForQueryTask.java:115` — async-start failure, no transactionId yet; `CheckStatusOfAnswerGenerationTask.java:189` — status-poll failure, retries the *same* transactionId and may still succeed) are **excluded** from the failed count — only the clean RAG-reported `ANSWER_GENERATION_FAILED` case (`:150`) counts as failed. **No new or changed Java log line is required for Tile 2.** See ADR-001. |
| **FR-006** | **Log lines that drive a tile become a consumed interface.** Every log line that a dashboard KQL query matches on — the four existing Tile-1 lines (FR-002 – FR-004), the new `WAITING_FOR_UPLOAD` line (FR-001), and whatever Tile-2 lines FR-005 settles on — must be identifiable by a **stable** predicate. From this ticket onwards, changing the text, level, field names or emission point of any of these lines requires the corresponding file under `support/dashboard-kql` to be updated in the same change. This is a documented convention, not a build-enforced one (OQ-007 covers whether any automated check is expected). |
| **FR-007** | **KQL query definitions live in this repo.** The queries backing both tiles are held in a new `support/dashboard-kql/` folder in CDKS, mirroring HRDS's convention, so the query and the log line it depends on live and version together. One query per tile. The folder is created by this ticket (fact 9); exact file naming, header/comment format and whether the files are parameterised are per the HRDS reference (OQ-007). |

---

## Non-Functional Requirements

Deliberately minimal — this is an additive logging change to an already-shipped service. Generic
platform NFRs (availability, scalability, versions, migration governance, PMD/JaCoCo) are covered
by CLAUDE.md's hard rules and are not restated. Only the four below carry decision content.

| ID | Category | Requirement |
|----|----------|-------------|
| **NFR-001** | Data protection | No PII or case content in any new or changed log line — per hard rule 1 and hard rule 5 in `.claude/context/cdks-context.md` ("no PII / case content in logs"; "never log document content, answer text, or `CJSCPPUID` values"). Permitted: internal UUID identifiers already logged by the sibling lines (`docId`, `caseId`, `materialId`, `queryId`, `transactionId` / `ragTransactionId`, `requestId`, `blobName`) and the fixed enum/outcome values. Prohibited: document content, document/material name, answer text, `llm_input`, `CJSCPPUID`, court reference numbers, defendant names, and any free-text field sourced from an upstream payload. |
| **NFR-002** | Logging mechanism | All new and changed lines are emitted through the **existing** SLF4J → `logback-spring.xml` → `LogstashEncoder` JSON-to-stdout pipeline. No new logging framework, no new appender, no new encoder, no change to `logback-spring.xml`, no `System.out`. Any MDC field used must go through the existing `CorrelationScope` / `CorrelationIds` API rather than raw `MDC.put`. |
| **NFR-003** | Additive only — no behaviour change | The change must not alter control flow, return values, `ExecutionStatus`/`ExecutionInfo` outcomes, exception handling, retry counts or thresholds, transaction boundaries, or persisted state of any method it touches. This is the same discipline applied to the DD-43182 metrics-removal work: observability is added around existing behaviour, never woven into it. A log statement must never be able to throw into, or short-circuit, the business path. |
| **NFR-004** | Countability | Each tile-driving log line is emitted **exactly once per occurrence of the event it represents**, at a level at or above the `INFO` root threshold, so that a KQL `count()` over matching lines equals the count of real events. No duplicate emission on a retried attempt unless the tile semantics explicitly want per-attempt counts (FR-005 / OQ-002), and no suppression, sampling or rate-limiting of these lines. |

---

## Acceptance Criteria

Shaped per `skills/write-acceptance-criteria` — measurable and testable. Tile-2 criteria are
deliberately outcome-level, pending OQ-002.

**FR-001 — new `WAITING_FOR_UPLOAD` log line**
- AC-001: Given a case with a defendant whose latest IDPC material has no existing `case_documents` row, when `IdpcAvailabilityService.retrieveDocuments(...)` runs, then exactly one INFO log event is emitted per newly persisted placeholder document, and that event contains the `docId`, `caseId` and `materialId` of that document.
- AC-002: Given a defendant whose document already exists (the `existingDocUuid.isPresent()` branch), when the same method runs, then **no** `WAITING_FOR_UPLOAD` log event is emitted for that defendant — only the existing `Skipping defendantId=…` line.
- AC-003: Given `n` newer IDPC documents are persisted for one case, when the method completes, then exactly `n` `WAITING_FOR_UPLOAD` events appear, and a KQL `count()` over the tile predicate returns `n`.
- AC-004: The emitted JSON event contains no document name, material name, defendant name, court reference number, `CJSCPPUID`, or document content (NFR-001).
- AC-005: The return value of `retrieveDocuments(...)` and the persisted `CaseDocument` rows are byte-for-byte unchanged by this ticket; existing `IdpcAvailabilityService` unit tests pass with their assertions unmodified (NFR-003).

**FR-002 – FR-004 — existing Tile-1 lines**
- AC-006: The four existing log statements at `RetrieveMaterialAndUploadTask.java:129`, `CheckIngestionStatusForAllDefendantsTask.java:116` and `:194` are present on the delivered branch with their message text, log level and emission point **identical to `develop`** — a diff of those three statements against `develop` is empty.
- AC-007: Given a successful upload, a successful ingestion, an ingestion failure with `reason='FILE_SIZE_OVER_LIMIT'`, and an ingestion failure with any other reason, when the Tile-1 KQL query runs over the resulting log events, then it attributes each event to exactly one of `UPLOADED`, `INGESTED`, `EXCEEDED_FILE_SIZE_LIMIT`, `FAILED` — with no event counted twice and none unattributed.
- AC-008: The Tile-1 query does **not** count the polling-exception line `Document status check FAILED with reason='…'` (`:204`) as a terminal ingestion failure.
- AC-008a *(added 2026-09-22, raised at Code Review — fact 11, OQ-011)*: The Tile-1 `FAILED` count is **known and explicitly not** to include ingestion failures that occur solely via polling-retry exhaustion (`CheckIngestionStatusForAllDefendantsTask.java:213-214`) — that path emits no log line distinguishing it from an in-progress retry, and adding one is out of this ticket's Java scope (FR-002–FR-004 are verify-only). This is a **documented exclusion**, not a defect in the KQL as delivered; OQ-011 tracks the follow-up to close it.

**FR-005 — Tile 2 answer generation**
- AC-009 *(revised 2026-09-22 per ADR-001 — original wording struck through)*: ~~Given a set of answer-generation requests over a bounded time window in which some succeed first time, some succeed after one or more retries, and some exhaust `maxAttempts`, when the Tile-2 KQL query runs over that window, then it reports a total-request count equal to the number of distinct requests issued (not attempts), a succeeded count equal to the number that produced a stored answer, and a failed count equal to the number that terminally failed, with `succeeded + failed ≤ total`.~~ **Revised:** ADR-001 counts **one per RAG transaction, not one per distinct business request** — each retry mints a new `ragTransactionId` and re-dispatches, so the total-request count is a **total-transaction (attempt) count**: the number of `Async RAG started …` events. Succeeded and failed counts are as originally stated (an event that produced a stored answer; one that terminally failed), and `succeeded + failed ≤ total` still holds.
- AC-010 *(revised 2026-09-22 per ADR-001 — original wording struck through)*: ~~A request that fails an attempt and then succeeds on retry is counted once, as succeeded — not as both failed and succeeded.~~ **Revised:** a request that fails an attempt and then succeeds on retry is counted as **two separate outcomes** — one failed, one succeeded — and is **not** collapsed into a single "succeeded" result, because each retry is a distinct RAG transaction with its own `ragTransactionId` (ADR-001).
- AC-011 *(revised 2026-09-22 per ADR-001/ADR-002 — original wording struck through)*: ~~All three terminal-failure paths are reachable by the query's failure predicate: retry exhaustion (`CheckStatusOfAnswerGenerationTask.java:178`), failure to start the async RAG call (`GenerateAnswerForQueryTask.java:115`), and failure while polling status (`CheckStatusOfAnswerGenerationTask.java:189`) — or the design explicitly and in writing records which of these are excluded and why (OQ-002).~~ **Revised:** only the clean, RAG-reported `ANSWER_GENERATION_FAILED` case (`CheckStatusOfAnswerGenerationTask.java:150`) counts as failed. The other two paths — failure to start the async RAG call (`GenerateAnswerForQueryTask.java:115`) and failure while polling status (`CheckStatusOfAnswerGenerationTask.java:189`) — are **explicitly excluded**, per ADR-002, as operational/infrastructure noise rather than a genuine answer-generation outcome. OQ-002 is resolved by ADR-001, not outstanding.
- AC-012: Existing retry behaviour is unchanged: `maxAttempts`, the `CTX_ANSWER_RETRY_COUNT` increment, the re-dispatch of `GENERATE_ANSWER_FOR_QUERY`, and every returned `ExecutionStatus` are identical to `develop`; existing `GenerateAnswerForQueryTask` and `CheckStatusOfAnswerGenerationTask` unit tests pass unmodified apart from any new log assertions.

**FR-006, FR-007 — query files**
- AC-013: `support/dashboard-kql/` exists and contains one query file per tile; each file names the source log statement(s) it depends on (class and message marker) so FR-006's coupling is discoverable from the query side.
- AC-014: Each query is syntactically valid KQL against the `ContainerLogV2` schema and has been executed successfully at least once against a real environment's log workspace, with the result recorded on the ticket.

**Cross-cutting**
- AC-015: `./gradlew clean build` (including `integration`) passes; PMD and JaCoCo are green at existing unmodified thresholds; CodeQL and the secrets scanner are clean.
- AC-016: No PII, case content, court reference number or `CJSCPPUID` is introduced into code, config, tests or fixtures; any test data used is synthetic.
- AC-017: `logback-spring.xml` is unmodified, and no new logging dependency appears in `build.gradle` (NFR-002).

---

## Constraints

- **CDKS hard rules** (`.claude/context/cdks-context.md`): no PII/case content in logs; JSON logging to stdout only via `logback-spring.xml`; PMD + JaCoCo must pass; integration tests are part of `build`.
- **Platform**: Prometheus/Grafana is **not** available to this service (ADR-012). Azure Monitor `ContainerLogV2` + KQL + Terraform-managed Azure Portal dashboards is the only supported path. Do not reintroduce Micrometer, an actuator metrics endpoint, or any parallel metrics surface.
- **Reference implementation is normative**: HRDS (`service-cp-crime-hearing-results-document-subscription` `support/dashboard-kql/`) and its `cp-amp-terraform-az-dashboard` config `hearing-results-document-subscription.json` define the conventions to mirror. These are read-only references named in the input brief; they have **not** been read in this session and are treated as out-of-repo dependencies.
- **Fixed story shape** (requester, not re-litigable): exactly three sub-stories, split across the two repos as stated in the header. Stage 3 must produce that split and no other.
- Classification **OFFICIAL-SENSITIVE** — the resulting dashboard is a derived view of production log data and inherits that classification.

---

## Out of scope

- **Any new or changed business behaviour.** No API change, no schema/Flyway change, no retry-policy change, no scheduler change, no ingestion or answer-generation logic change. `api-cp-crime-caseadmin-case-document-knowledge` is untouched.
- **Reintroducing metrics.** No Micrometer, no `/actuator/prometheus`, no Application Insights SDK, no OpenTelemetry metrics exporter.
- **Alerting.** Alert rules, thresholds, severities, action groups and on-call routing are not part of this ticket — it delivers two dashboard tiles. If tiles are wanted to be alert-able, that is a separate ticket (OQ-010).
- **Tiles beyond the two named.** Scheduler heartbeat, stalled-work counts, external-call latency/error rates, JobManager task outcomes, Artemis or Azure Blob operations — all previously in DD-43182/DD-43185 scope, none of them in DD-43432.
- **Ingestion phases outside the five named.** `UPLOADING`, `INGESTING` and `NOT_FOUND` get no log line and no tile segment (see fact 8 and OQ-008).
- **Backfill.** Tiles show data from first deployment of the new log lines onward; no historical reconstruction.
- **Rewording or restructuring existing log lines not named in FR-002 – FR-005**, and no repo-wide logging audit.

### Out of scope *for this repo*

Both of the following are tracked under the same DD-43432 ticket and are genuinely in scope for the
ticket — they are simply not CDKS `src/main/java` requirements, so they are scoped at story level
rather than specified here:

- **Story 2 — `support/dashboard-kql` query files.** Lands in this repo but is a non-code artefact with no Java, no tests and no build integration; FR-007 states the requirement, and the file-level detail (naming, header format, parameterisation) follows the HRDS reference and is settled at story/design level (OQ-007).
- **Story 3 — `cp-amp-terraform-az-dashboard` tile wiring.** Lands in a **different repository**, owned by platform/SRE, following the `hearing-results-document-subscription.json` pattern. Out of this pipeline's repo entirely: it gets its own story-level scoping note, not requirements here, and its own review/merge route (OQ-010). No file in that repo has been read or verified in this session.

---

## Fixed sub-story shape (given, for Stage 3)

Recorded here only so the FRs above map cleanly. **Not a proposal — this split is already decided.**

| # | Story | Repo | Covers |
|---|---|---|---|
| 1 | Structured log-line changes for both tiles | CDKS | FR-001 (add), FR-002 – FR-004 (verify only), FR-005 (once OQ-002 is settled), FR-006, all NFRs |
| 2 | KQL query definitions in `support/dashboard-kql` | CDKS | FR-007, AC-007 – AC-011, AC-013, AC-014 |
| 3 | Dashboard tile wiring | `cp-amp-terraform-az-dashboard` | Consumes story 2's queries; scoped in that repo |

---

## Decisions made (2026-09-21, in conversation — supersede the original open questions below)

Resolved through direct discussion with the requester, cross-checked against the actual reference
repos (`service-cp-crime-hearing-results-document-subscription/support/dashboard-kql/`,
`service-cp-crime-hearing-results-document-subscription/support/README.md`,
`service-cp-crime-hearing-results-document-subscription/support/sync-dashboard-to-terraform.sh`,
and the full `cp-amp-terraform-az-dashboard` repo — `main.tf`, `locals.tf`, `variables.tf`,
`dashboards.tf`, all four `vars/*.tfvars`, `queries/README.md`, both dashboards' query folders).
Full detail recorded in ADR-001 (`adrs/DD-43432-structured-logging-dashboard-tiles.md`).

- **Tile 1 is throughput/flow only, not a backlog snapshot** (resolves former OQ-006). Stalled-work
  / current-state views are explicitly deferred to a later ticket. FR-001 stands as written.
- **Tile 2 counts one per RAG transaction, not one per business request** (resolves former OQ-002).
  A retried query that fails then succeeds is two outcomes, not de-duplicated to one. Only the clean
  RAG-reported `ANSWER_GENERATION_FAILED` line (`:150`) counts as failed; the two infrastructure-error
  paths (`:115`, `:189`) are excluded. **No Java change is required for Tile 2** — see updated FR-005.
- **Message-text matching is the established convention, not a stable `event=` marker** (resolves
  former OQ-003 and OQ-004). Confirmed by reading HRDS's own `.kql` files: they match on
  `LogMessage contains '<phrase>'` directly against existing prose (e.g. `'getDocument request'`),
  occasionally `parse_json(LogMessage)` to pull a field out, never a dedicated marker field. FR-002 –
  FR-004's "verify, do not touch" stands without tension.
- **Tile windows follow the Azure Portal's live time-range picker, default "Last 30 days"; no
  hardcoded `ago()`/`startofday()` in the KQL** (resolves former OQ-005). This differs from HRDS's
  current tiles, which all hardcode their own window and set `IsQueryContainTimeRange: true` (so
  HRDS's dashboard-level picker, defaulted to "Past 90 days" in `dashboards.tf`, is actually cosmetic
  for its existing tiles). CDKS's two tiles will instead omit their own time filter and rely on the
  portal-injected range (`IsQueryContainTimeRange: false`), with the dashboard-level default lowered
  to 30 days. This is a Story 2 (KQL) + Story 3 (terraform) concern — no CDKS Java impact.
  **Revised 2026-09-22 (ADR-005):** both `.kql` files now also carry their own
  `TimeGenerated > ago(30d)` fallback filter, so "default to last 30 days" holds regardless of
  whether Story 3's terraform-level default-lowering has landed — the KQL-level filter and the
  portal's picker AND together, so a picker range wider than 30 days is still capped at 30 days.
  `IsQueryContainTimeRange: false` still stands; the portal's picker still narrows further when set.
- **`support/dashboard-kql/` conventions confirmed** (resolves former OQ-007): flat `.kql` files, one
  per tile, referenced by filename (no extension) from the dashboard's `configs/<name>.json`
  `tiles[].query` field; a `support/sync-dashboard-to-terraform.sh` script (manual, not CI/CD, requires
  both repos checked out as siblings) copies them into
  `cp-amp-terraform-az-dashboard/queries/<dashboard-name>/`; the terraform repo's copies carry a
  "do not edit directly" banner pointing back at the service repo. CDKS's Story 2 mirrors this
  exactly: its own `support/dashboard-kql/*.kql` + its own `support/sync-dashboard-to-terraform.sh`.
- **Namespace placeholder — hardcoded literal in the `.kql` file, per HRDS's own convention, but with
  a real gap flagged for Story 3.** CDKS's actual namespaces are `ns-dev-ccm-03` (dev) and
  `ns-ste-ccm-29` (ste), with further environments to follow. `dashboards.tf`'s locals block currently
  does `replace(query, "ns-dev-amp-01", var.namespace)` — that search string is hardcoded to HRDS's
  own dev namespace and `var.namespace` is a single value shared across every dashboard the module
  renders. This works today only because HRDS is the sole dashboard using `PodNamespace`/`PodName`
  filters (`pcr-data`'s queries don't use this pattern at all, confirmed by inspection). It will not
  correctly substitute CDKS's differently-named namespaces without a small terraform change (a
  per-dashboard namespace map, not a single global `var.namespace`). **Requester will raise this with
  the `cp-amp-terraform-az-dashboard` repo owner directly** — recorded here as a known Story 3
  dependency, not CDKS-repo work, and not blocking Stories 1–2.
  **Revised 2026-09-22 (ADR-004, decision point 4):** the checked-in placeholder literal changed from
  `ns-dev-ccm-03` to `ns-ste-ccm-29` — same mechanism as above, just a different default anchor
  string. Because the terraform `replace()` search string (`ns-dev-amp-01`) matches neither CDKS
  literal, this substitution is a silent no-op against CDKS's queries either way; until the namespace
  map fix lands, **ste** tiles now happen to show correct data (not dev, as before the swap) while
  every other environment shows ste's data. A further option — making CDKS itself environment-aware
  rather than relying on one hardcoded literal — was considered and deliberately deferred as a
  follow-up (OQ-012), not part of this ticket.

## Open Questions still outstanding

- **OQ-001 (source of truth / Jira comment):** DD-43432 was not fetched — no Atlassian/Jira MCP tool is available in this session — so this document is grounded solely in `00-input-brief.md` plus code read on this branch, and **no Stage-1 summary comment has been posted to the ticket**. Confirm the brief is the complete and current ticket text, and post the summary manually. — Owner: requester · Due: before Stage 2.

- **OQ-008 (phase-set completeness — low risk, confirm only):** the brief names five phases. The `DocumentIngestionPhase` enum has eight; the other three are effectively dead for tile purposes (`UPLOADING` is only a JPA field default and is never explicitly transitioned to; `INGESTING` is never written anywhere in `src/main/java`; `NOT_FOUND` is only ever an API response value) — see fact 8. Confirm the five-phase tile is intentional and complete. — Owner: requester · Due: Stage 2.

- **OQ-009 (security sign-off on dashboard-visible identifiers):** the tile-driving lines carry `caseId`, `docId`, `materialId` and (for Tile 2) `queryId` / `ragTransactionId`. These are internal UUIDs, not court reference numbers, and are already logged today — but DD-43432 makes them queryable from a shared Azure Portal dashboard, which is a wider audience than pod logs. Confirm with the security reviewer that this is acceptable, and confirm whether FR-001 may additionally carry `defendantId` / `courtdocId` (available at the call site, not needed by the tile — recommend excluding by default). — Owner: security reviewer · Due: before merge.

- **OQ-010 (story 3 ownership and merge route):** requester is coordinating directly with the `cp-amp-terraform-az-dashboard` repo owner on the namespace-generalization fix above. Still to confirm: whether CDKS engineers raise that repo's PR themselves once the fix lands, and whether tile delivery is a hard dependency for closing DD-43432 or can trail stories 1–2. — Owner: requester · Due: before Stage 3.

- **OQ-011 (Tile-1 FAILED undercount via retry exhaustion — added 2026-09-22, raised at Code Review on PR #230):** `CheckIngestionStatusForAllDefendantsTask.java:213-214` sets `ingestion_phase = FAILED` when polling retries are exhausted, but emits no log line distinguishing this from an in-progress retry (fact 11) — the same class of gap as `WAITING_FOR_UPLOAD` (fact 4), except this ticket's Java scope (FR-002–FR-004, verify-only) does not cover adding one. Tile 1's `FAILED` count therefore undercounts this path, which is plausibly the more common real-world failure mode (timeouts) versus the explicit-status-failure path fact 3 covers. Accepted as an explicit, written exclusion for this ticket (AC-008a) rather than blocking Stories 1–2. A follow-up story/ticket should add a distinguishing log line at `:213-214`, mirroring FR-001's pattern for `WAITING_FOR_UPLOAD`. — Owner: requester · Due: raise the follow-up ticket before Stage 5 (Deploy Sandbox); non-blocking for this ticket.

- **OQ-012 (CDKS-side namespace dynamism — added 2026-09-22, requester direction during Stage 5 implementation):** the checked-in namespace literal in both `.kql` files was revised from `ns-dev-ccm-03` to `ns-ste-ccm-29` (ADR-004, decision point 4), keeping the existing single-hardcoded-literal-plus-Terraform-`replace()` mechanism unchanged. A more thorough fix — making CDKS itself environment-aware (e.g. an `--env` argument to `sync-dashboard-to-terraform.sh`) instead of relying on one hardcoded literal per file — was raised and deliberately deferred: ship the simple swap now, revisit real per-environment dynamism as a follow-up once there is bandwidth. Not blocking Stories 1–2. — Owner: requester · Due: follow-up ticket, no fixed date.

---

## Stage gate

**Stage 1 (Requirements) is a Human gate.** The two decisions that were blocking (former OQ-002 and
OQ-006) are now resolved by direct requester decision (above; full rationale in ADR-001). Remaining
open items (OQ-001, OQ-008, OQ-009, OQ-010, OQ-011, OQ-012) are non-blocking confirmations/exclusions,
not open design questions. Proceeding to Stage 2 (Architecture & Design) on requester confirmation.
