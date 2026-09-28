# Design: Structured Logging for Azure Monitor Dashboard Tiles (KQL replacement for Prometheus)

> **Stage 2 — Architecture & Design** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43432** · Branch: `DD-43432` ·
> Requirements: [`01-requirements.md`](./01-requirements.md) ·
> Input brief: [`00-input-brief.md`](./00-input-brief.md) ·
> ADRs: [`adrs/DD-43432-structured-logging-dashboard-tiles.md`](../adrs/DD-43432-structured-logging-dashboard-tiles.md)
> (ADR-001 – ADR-004, all **Accepted** — this document makes them concrete, it does not revisit them.
> **Updated 2026-09-22:** ADR-004's namespace literal was revised (`ns-dev-ccm-03` → `ns-ste-ccm-29`)
> and ADR-005 was added, adding a 30-day `ago()` fallback filter to both queries — this document's
> §2.2–§2.6 below have been updated to match; see ADR-005 for the rationale.)
>
> **Scope of the change, in one line:** one new `log.info(...)` statement in one Java file, plus four
> new non-code files under a new `support/` folder. Nothing else in `src/main/java` moves.

---

## 0. Shape of the change

| Story | Repo | Artefacts | Java change? |
|---|---|---|---|
| **1** — log-line change | CDKS (this repo) | 1 production line + 1 unit-test addition | **Yes — exactly one `log.info(...)`** |
| **2** — KQL query definitions | CDKS (this repo) | `support/dashboard-kql/*.kql` (2), `support/sync-dashboard-to-terraform.sh`, `support/README.md` | No |
| **3** — dashboard tile wiring | `cp-amp-terraform-az-dashboard` | `configs/cdks.json`, `queries/cdks/*.kql`, `dashboards.tf` tweaks | No — §3, scope note only |

Stories 1 and 2 are independent of one another and of Story 3; neither is blocked by ADR-004's
flagged terraform dependency.

---

## 1. FR-001 — the one Java change (Story 1)

### 1.1 Current state, verified on this branch

`src/main/java/uk/gov/hmcts/cp/cdk/services/IdpcAvailabilityService.java` — `persistCaseDocument(...)`
occupies lines 117–130 and contains **no log statement at all**:

```java
117    private void persistCaseDocument(final UUID docId, final UUID caseId, final LatestMaterialInfo info) {
118        final CaseDocument entity = new CaseDocument();
119        entity.setDocId(docId);
...
125        entity.setIngestionPhase(DocumentIngestionPhase.WAITING_FOR_UPLOAD);
126        entity.setDefendantId(fromString(info.defendantId()));
127        entity.setCourtdocId(fromString(info.courtDocumentId()));
128
129        caseDocumentRepository.saveAndFlush(entity);
130    }
```

The class is already `@Slf4j` (line 45) and already imports `DocumentIngestionPhase` (line 11).
`caseId` is already in MDC for the whole call — `retrieveDocuments` wraps the flow in
`CorrelationScope.withIdentifiers(caseId.toString(), null, null)` at line 64 — so the JSON event
will carry a top-level `caseId` field in addition to the in-message value. `docId` and `materialId`
are **not** in MDC at this call site, which is why they must be interpolated into the message.

### 1.2 The exact edit

Insert **two physical lines** (one statement) immediately after line 129, i.e. as the last statement
in `persistCaseDocument`, after the row is flushed. No other line in the file changes.

```java
        caseDocumentRepository.saveAndFlush(entity);

        log.info("Saved CaseDocument placeholder docId={}, caseId={}, materialId={}, ingestionPhase={}",
                docId, caseId, info.materialId(), DocumentIngestionPhase.WAITING_FOR_UPLOAD);
    }
```

**Resulting message at runtime:**

```
Saved CaseDocument placeholder docId=<uuid>, caseId=<uuid>, materialId=<uuid>, ingestionPhase=WAITING_FOR_UPLOAD
```

#### Why this exact wording

| Decision | Reason |
|---|---|
| `key=value` comma-separated interpolation | Matches the sibling reference line `RetrieveMaterialAndUploadTask.java:129-130` (`Saved CaseDocument docId={}, caseId={}, materialId={}, sizeBytes={}, blobUri={}, requestId={}`) exactly in style. |
| Field order `docId, caseId, materialId` | Same order as the sibling line (FR-001's stated convention). |
| Prefix `Saved CaseDocument **placeholder** docId=` | **Load-bearing.** The Tile-1 KQL selects `UPLOADED` with `Message startswith_cs 'Saved CaseDocument docId='`. Had the new line reused that prefix verbatim, the two phases would be indistinguishable and every placeholder would be double-counted as an upload. Inserting `placeholder ` makes the two prefixes mutually exclusive under `startswith_cs` in both directions, and is also literally accurate — this row *is* a placeholder (`blobUri = "default_blob_uri"`, `docName = "IDPC"`). |
| Trailing `ingestionPhase={}` bound to `DocumentIngestionPhase.WAITING_FOR_UPLOAD` | Makes the line self-describing for a human reading raw logs, and keeps the phase name tied to the enum constant so a future enum rename shows up at compile time rather than silently drifting from the tile. The KQL does **not** depend on it (it matches on the prefix), so it is safe redundancy, not a second coupling point. |
| `info.materialId()` (a `String`) rather than `entity.getMaterialId()` | Avoids a redundant `UUID.toString()`; `info.materialId()` is the same value already parsed into the entity at line 121. |
| INFO level | Required by NFR-004 (root threshold is `INFO` per `logback-spring.xml`); it is a normal-path business event, not an error. |
| Emitted **after** `saveAndFlush`, not before | AC-001 says "per newly *persisted* placeholder document". If `saveAndFlush` throws, no row exists and no line must be emitted. Placing it after the flush gives exactly-once-per-persisted-row semantics for free. |

#### Data-protection check (NFR-001 / AC-004)

Four values are logged: `docId`, `caseId`, `materialId` (internal UUIDs) and a fixed enum name.
`info.materialName()`, `info.defendantId()`, `info.courtDocumentId()` and `info.description()` are
all available at the call site and are all **deliberately excluded**. `defendantId` / `courtdocId`
are excluded per the requirements' own recommendation pending OQ-009; the tile does not need them.
No document content, no material name, no `CJSCPPUID`, no court reference number.

#### Behaviour-neutrality check (NFR-003 / AC-005)

`persistCaseDocument` is `void` and is called once per new document from `retrieveDocumentsScoped`
(line 86). The new statement is the final statement in the method, takes no branch, mutates nothing,
and SLF4J parameterised logging cannot throw into the business path. `retrieveDocuments(...)`'s
return value, the persisted `CaseDocument` columns, and the skip branch at lines 80–83 are all
untouched. The existing seven tests in `IdpcAvailabilityServiceTest` pass with assertions unmodified.

### 1.3 Confirmation: nothing else in `src/main/java` changes (NFR-003, AC-006)

Per ADR-001 and ADR-002 the other four tile-driving lines are used **as they stand on `develop`**.
For the record, the exact statements the KQL binds to, with their current locations:

| Tile | Segment | File : line | Statement (unchanged) |
|---|---|---|---|
| 1 | `UPLOADED` | `jobmanager/caseflow/RetrieveMaterialAndUploadTask.java:129-130` | `log.info("Saved CaseDocument docId={}, caseId={}, materialId={}, sizeBytes={}, blobUri={}, requestId={}", …)` |
| 1 | `INGESTED` | `jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTask.java:116` | `log.info("INGESTION SUCCESS identifier='{}', docId={}", blobName, documentId)` |
| 1 | `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT` | `jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTask.java:194-200` | `log.error("ingestion FAILED for identifier='{}' reason='{}' (caseId={}, docId={}).", blobName, status, caseId, documentId)` |
| 2 | Total | `jobmanager/queryflow/GenerateAnswerForQueryTask.java:99` | `log.info("Async RAG started for caseId={}, docId={}, queryId={}, transactionId={}", …)` |
| 2 | Succeeded | `jobmanager/queryflow/CheckStatusOfAnswerGenerationTask.java:145-146` | `log.info("Answer Generation updated in the DB for caseId={}, docId={}, queryId={}, ragTransactionId={}, task completed.", …)` |
| 2 | Failed | `jobmanager/queryflow/CheckStatusOfAnswerGenerationTask.java:150-151` | `log.info("Answer Generation Failed for caseId={}, docId={}, queryId={}, ragTransactionId={}, task completed.", …)` |

**The Story 1 diff of `src/main/java` is therefore exactly: `IdpcAvailabilityService.java`, +2 lines,
-0 lines.** `logback-spring.xml`, `build.gradle`, every `application*.yml`, every Flyway migration
and the OpenAPI contract are all untouched (AC-017).

### 1.4 Test design for FR-001

#### Existing coverage

`src/test/java/uk/gov/hmcts/cp/cdk/services/IdpcAvailabilityServiceTest.java` — plain
`@ExtendWith(MockitoExtension.class)` (no Spring context), constructs the service directly with three
`@Mock`s, uses an `ArgumentCaptor<CaseDocument>` and clears MDC in `@AfterEach`. Seven tests:

| Test | Relevance to FR-001 |
|---|---|
| `caseIdIsPresentInMdcDuringExecutionAndRestoredAfter` | Confirms the MDC `caseId` the new event inherits. |
| `returnsEmpty_whenNoMaterials` | Zero-document path. |
| `skipsExistingDocuments` | **Directly reusable for AC-002** — already exercises the `existingDocUuid.isPresent()` branch. |
| `returnsNewDocuments_forMultipleDefendants` | **Directly reusable for AC-003** — already persists `n > 1` documents. |
| `shouldLeaveRagDocumentReferenceNull_whenPersistingWaitingForUploadRow` | Nearest neighbour: already captures the persisted `CaseDocument` and asserts `WAITING_FOR_UPLOAD`. |
| `truncatesMaterialName_preservingPdfExtension`, `doesNotTruncate_whenExactly50Characters` | Not relevant. |

None of them asserts on log output today, and none needs its existing assertions changed (AC-005).

#### Log-capture pattern to use

The codebase already has a Logback `ListAppender` pattern — copy it from
`src/test/java/uk/gov/hmcts/cp/cdk/http/DebugLoggingInterceptorTest.java` (lines 30–48), which is the
canonical example here: field `ListAppender<ILoggingEvent> appender`, attach in `@BeforeEach` to
`(ch.qos.logback.classic.Logger) LoggerFactory.getLogger(<ClassUnderTest>.class)`, detach in
`@AfterEach`. Three other test classes use the same idiom (`IntradayDiscoverySchedulerTest`,
`NightlyDiscoverySchedulerTest`, `DiscoveryTriggerServiceTest`).

One difference from `DebugLoggingInterceptorTest`: that test has to call `logger.setLevel(Level.DEBUG)`
because it asserts on a DEBUG line. FR-001's line is INFO, and there is **no `logback-test.xml` in
`src/test/resources/`** (only `application.yml` and `test-openapi-spec.yml`), so plain-Logback default
configuration applies in this unit test and INFO is enabled. **Do not add a level override, and do
not add a `logback-test.xml`.**

#### New tests to add to `IdpcAvailabilityServiceTest`

| New test | Asserts | Covers |
|---|---|---|
| `logsWaitingForUploadLine_whenPlaceholderPersisted` | Exactly **one** captured event whose `getFormattedMessage()` starts with `Saved CaseDocument placeholder docId=`; its `getLevel()` is `Level.INFO`; the formatted message contains the `docId` captured from `caseDocumentCaptor`, the `caseId`, and the `materialId` string from the stubbed `LatestMaterialInfo`. | AC-001 |
| `doesNotLogWaitingForUpload_whenDocumentAlreadyExists` | Extend the arrangement of `skipsExistingDocuments`: **zero** captured events start with `Saved CaseDocument placeholder docId=`, and the existing `Skipping defendantId=` event is still present. | AC-002 |
| `logsOneWaitingForUploadLinePerPersistedDocument` | Extend the arrangement of `returnsNewDocuments_forMultipleDefendants` (n = 2): the count of events matching the prefix equals `n`, and the set of `docId`s across those messages equals the set of `docId`s on the returned `NewIdpcDocument`s. | AC-003, NFR-004 |
| `waitingForUploadLineContainsNoSensitiveValues` | With `materialName` set to a synthetic non-UUID token (e.g. `"SYNTHETIC-MATERIAL-NAME"`), assert the formatted message `doesNotContain` that token, and `doesNotContain` the `defendantId` and `courtDocumentId` values. | AC-004, NFR-001 |

All four use synthetic `UUID.randomUUID()` values only — no real case data, no court reference
numbers (AC-016). Assert on `ILoggingEvent.getFormattedMessage()` (not `getMessage()`, which returns
the un-interpolated `{}` template).

> **Known gap, raised at Code Review (PR #231, 2026-09-24), tracked as OQ-013 — not fixed here.** All
> four tests above match against a prefix string hardcoded in the test file
> (`WAITING_FOR_UPLOAD_LOG_PREFIX` in `IdpcAvailabilityServiceTest`), not read from
> `support/dashboard-kql/ingestion-phase-counts.kql`. So these tests catch the Java line drifting from
> the *test's own copy* of the string, but not the Java line and the `.kql` file's
> `startswith_cs 'Saved CaseDocument placeholder docId='` predicate drifting from **each other**. The
> same gap applies, with no test coverage at all, to the other 6 markers (`UPLOADED`, `INGESTED`,
> `FAILED`/`EXCEEDED_FILE_SIZE_LIMIT`, `Total`, `Succeeded`, `Failed`) — AC-006/AC-012 only assert a
> `git diff develop` is empty, which doesn't protect against a future rewording. A proposed contract
> test — read each marker from `support/dashboard-kql/*.kql` and assert it is still logged — is
> tracked as [DD-43672](https://hmcts.atlassian.net/browse/DD-43672) (OQ-013), due before Story 3,
> not part of this design.

> **Note for Stage 4 (Test Specs):** the tile behaviour itself — "the KQL attributes each event to
> exactly one phase" (AC-007, AC-008, AC-009 – AC-011) — is **not** unit-testable in this repo. It is
> verified by executing the queries against a real Log Analytics workspace and recording the result on
> the ticket (AC-014). No new `integrationTest` or `pactVerificationTest` is required by this ticket;
> `gradle integration` must still pass unchanged (AC-015).

---

## 2. Story 2 — `support/dashboard-kql/` in this repo

### 2.1 File layout

Four new files, no changes to any existing file. Mirrors
`service-cp-crime-hearing-results-document-subscription/support/` (read on 2026-09-21), reduced to
just the `dashboard-kql` half — CDKS has no `logs-kql`, `chart-kql`, `alerts-kql`, `kql-prod`,
`deploy/` or `run-query.sh`, and this ticket does not create them.

```
support/
├── README.md                              (new — dashboard-kql section only)
├── sync-dashboard-to-terraform.sh         (new — executable, chmod +x)
└── dashboard-kql/
    ├── ingestion-phase-counts.kql         (new — Tile 1)
    └── answer-generation-outcomes.kql     (new — Tile 2)
```

Naming follows HRDS: flat kebab-case `.kql` files, one per tile, referenced by **filename without
extension** from the dashboard repo's `configs/<dashboard>.json` `tiles[].query` field.

### 2.2 Conventions applied to both queries

Derived from HRDS's actual `.kql` files, with the two deliberate ADR-driven divergences called out.

| Convention | CDKS | Same as HRDS? |
|---|---|---|
| Table | `ContainerLogV2` | Yes |
| Namespace filter | `where PodNamespace == 'ns-ste-ccm-29'` — hardcoded literal, revised 2026-09-22 (was `ns-dev-ccm-03`) | Yes (ADR-004; HRDS hardcodes `ns-dev-amp-01`) |
| Sidecar exclusion | `where ContainerName != 'istio-proxy'` | Yes |
| Service discriminator | `where tostring(LogJson.app) == 'cp-case-document-knowledge-service'` | **No** — HRDS uses `PodName startswith '<prefix>'`. See OPEN-DS-001 below. |
| JSON extraction | `extend LogJson = parse_json(LogMessage)` then `extend Message = tostring(LogJson.message)` | Yes (`incoming-events-by-type.kql`, `all-logs-recent.kql`) |
| Time filter | `where TimeGenerated > ago(30d)` — fallback only, revised 2026-09-22 (ADR-005); portal's picker still ANDs on top when set (ADR-003 otherwise stands) | **No** — ADR-003/ADR-005 |
| Multi-segment shape | `let` common prefix + `union` of per-segment `summarize count()` sub-queries | Adapted from `todays-summary.kql` (which unions but hardcodes `startofday(now())`) |
| Header comment | `//` block naming the source class + message marker for each segment | New — required by AC-013 |
| Output | `AnalyticsGrid` table: two visible columns + a sort column | Yes (`todays-summary.kql`) |

#### Why `app` instead of `PodName startswith` — OPEN-DS-001

CDKS's Kubernetes pod-name prefix is **not determinable from this repository**, and this design does
not guess one:

- There is no Helm chart, no `k8s/` folder and no deployment manifest in this repo.
- `.github/workflows/ci-build-publish.yml`'s `Deploy` job passes only
  `"ARTIFACT_ID": "${{ env.REPO_NAME }}"` (= `cp-case-document-knowledge-service`) to ADO pipeline
  `460`; it never names a k8s service or deployment.
- HRDS proves repo name is **not** a safe proxy: its repo is
  `service-cp-crime-hearing-results-document-subscription`, but its own workflow pins
  `service_name: hearing-results-document-subscription` explicitly (`ci-build-publish.yml:403, :430`)
  and that shorter value is what its queries match on. CDKS's workflow has no equivalent input.
- `docker/docker-compose.integration.yml` only yields `SPRING_APPLICATION_NAME:
  cp-case-document-knowledge-service` and the DB/context-path names — nothing about k8s.

What **is** verifiable from this repo is `src/main/resources/logback-spring.xml`, which injects
`{"app":"cp-case-document-knowledge-service","service":"cp-case-document-knowledge-service"}` as
custom fields on **every** JSON event via `LogstashEncoder`. Filtering on `LogJson.app` is therefore
exactly equivalent in intent, verifiable today, and immune to a future pod/deployment rename.
`parse_json()` on an already-`dynamic` `LogMessage` is a no-op, so the expression is safe regardless
of whether Container Insights pre-parses the column; non-JSON stdout lines (JVM banner, raw stack
trace continuations) yield `null` and are filtered out, which is desirable.

**OPEN-DS-001 — for the requester / production support, before Story 3 goes live.** Confirm CDKS's
actual pod-name prefix by running this one-off discovery query against the dev workspace:

```
ContainerLogV2
| where PodNamespace == 'ns-dev-ccm-03'
| where ContainerName != 'istio-proxy'
| where TimeGenerated > ago(1d)
| summarize Lines = count() by PodName
| order by Lines desc
```

If a stable prefix is confirmed, the *optional* follow-up is to add
`| where PodName startswith '<confirmed-prefix>'` as one extra line inside the `let cdks = …` block
in **both** `.kql` files, for full alignment with HRDS. This is a belt-and-braces narrowing, **not** a
prerequisite — the `app` filter alone is correct and sufficient, and Stage 5 must ship the files
exactly as drafted below without waiting on it.

#### `startswith_cs` vs `contains` — a deliberate, load-bearing choice

KQL's `contains` and `startswith` are **case-insensitive**; `contains_cs` / `startswith_cs` are not.
This design uses:

- **`startswith_cs`** for every message-prefix predicate. The prefixes are compile-time string
  literals in Java source, so their casing is fixed and case-insensitive matching buys nothing while
  costing precision. Concretely it removes a near-collision in Tile 2: the retry line
  `"Answer generation failed. Retrying {}/{} for ragTransactionId={}"`
  (`CheckStatusOfAnswerGenerationTask.java:161`) differs from the terminal failure line
  `"Answer Generation Failed for caseId={}, …"` (`:150`) **only** in casing and in what follows
  `failed`. `startswith_cs 'Answer Generation Failed for caseId='` excludes the retry line
  unambiguously; a case-insensitive predicate would rely solely on the `. Retrying` vs ` for caseId=`
  divergence at character 25.
- **`contains`** (case-insensitive) for the `reason=` value split in Tile 1. `CheckIngestionStatusForAllDefendantsTask`
  logs `status` — the value from `normalise(rawStatus, 255)`, i.e. the **raw** RAG-supplied status
  string — while the branch that chooses the phase compares `status.toUpperCase(Locale.ROOT)` against
  `FILE_SIZE_OVER_LIMIT.name()` (`:191-192`). The logged text is therefore **not guaranteed to be
  upper-case**. Case-insensitive `contains "reason='FILE_SIZE_OVER_LIMIT'"` is the correct predicate
  here; `contains_cs` would silently under-count `EXCEEDED_FILE_SIZE_LIMIT` and over-count `FAILED`.

### 2.3 `support/dashboard-kql/ingestion-phase-counts.kql` (Tile 1) — full draft

```kusto
// Tile 1 - Document ingestion phase breakdown (DD-43432)
//
// One row per ingestion phase, counting phase transitions over the Azure Portal dashboard's
// time-range picker, when one is supplied - AND'd with a 30-day ago() fallback below so the tile
// still shows a bounded, meaningful window when no time range has been picked (revises ADR-003's
// "no filter at all" position; the tile is still registered with IsQueryContainTimeRange = false,
// so the portal's own picker keeps narrowing the range on top of this fallback - selecting a
// picker range wider than 30 days will still be capped at 30 days by this filter).
//
// Source log statements in cp-case-document-knowledge-service (FR-001 - FR-004).
// Changing the text, level or emission point of any of these breaks this tile - update this file
// in the same change (FR-006):
//   WAITING_FOR_UPLOAD        services/IdpcAvailabilityService.persistCaseDocument
//                             "Saved CaseDocument placeholder docId="
//   UPLOADED                  jobmanager/caseflow/RetrieveMaterialAndUploadTask
//                             "Saved CaseDocument docId="
//   INGESTED                  jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTask
//                             "INGESTION SUCCESS identifier="
//   FAILED / EXCEEDED_FILE_SIZE_LIMIT
//                             jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTask
//                             "ingestion FAILED for identifier=" , split on reason=
//
// Deliberately NOT counted: "Document status check  FAILED with reason=" - that is a transient
// polling exception that retries, not a terminal ingestion failure (AC-008).
//
// Known, accepted gap (AC-008a, OQ-011): CheckIngestionStatusForAllDefendantsTask:213-214 also
// sets ingestion_phase=FAILED when polling retries are exhausted, but emits no distinguishing log
// line (the only preceding log is the generic "Ingestion status not complete ... -> retrying",
// which also fires on retries that later succeed). This path is NOT visible to this query. It is
// an explicit, written exclusion for this ticket - not fixed here because doing so needs a Java
// change outside this story's verify-only scope for FR-002-FR-004. Tracked as a follow-up (OQ-011).
//
// 'ns-ste-ccm-29' is the default namespace literal; the terraform dashboard repo substitutes the
// per-environment namespace at plan time (ADR-004) - this literal is only the anchor string that
// substitution searches for, it does not have to match the environment actually being deployed to.
let cdks =
    ContainerLogV2
    | where TimeGenerated > ago(30d)
    | where PodNamespace == 'ns-ste-ccm-29'
    | where ContainerName != 'istio-proxy'
    | extend LogJson = parse_json(LogMessage)
    | where tostring(LogJson.app) == 'cp-case-document-knowledge-service'
    | extend Message = tostring(LogJson.message);
union
(cdks
    | where Message startswith_cs 'Saved CaseDocument placeholder docId='
    | summarize Count = count()
    | extend Phase = 'WAITING_FOR_UPLOAD', PhaseOrder = 1),
(cdks
    | where Message startswith_cs 'Saved CaseDocument docId='
    | summarize Count = count()
    | extend Phase = 'UPLOADED', PhaseOrder = 2),
(cdks
    | where Message startswith_cs 'INGESTION SUCCESS identifier='
    | summarize Count = count()
    | extend Phase = 'INGESTED', PhaseOrder = 3),
(cdks
    | where Message startswith_cs 'ingestion FAILED for identifier='
    | where Message contains "reason='FILE_SIZE_OVER_LIMIT'"
    | summarize Count = count()
    | extend Phase = 'EXCEEDED_FILE_SIZE_LIMIT', PhaseOrder = 4),
(cdks
    | where Message startswith_cs 'ingestion FAILED for identifier='
    | where Message !contains "reason='FILE_SIZE_OVER_LIMIT'"
    | summarize Count = count()
    | extend Phase = 'FAILED', PhaseOrder = 5)
| order by PhaseOrder asc
| project Phase, Count = toint(Count)
```

**Notes for the implementer.**

- A parameterless `summarize Count = count()` always returns exactly one row, so the union always
  yields all five phases including zeros — the tile never shows a missing segment. Same mechanism as
  HRDS's `todays-summary.kql`.
- `PhaseOrder` exists only to render the grid in pipeline order (waiting → uploaded → ingested →
  failed); `project` drops it after `order by`, so the tile shows two columns.
- The two `FILE_SIZE_OVER_LIMIT` branches are exact complements over the same base predicate, so
  every `ingestion FAILED` event is attributed to exactly one of the two phases and none is
  double-counted (AC-007).
- `startswith_cs 'Saved CaseDocument docId='` cannot match the new placeholder line, because that
  message reads `Saved CaseDocument placeholder docId=` — the prefixes diverge at character 19.
- KQL double-quoted string literals are used for the `reason=` predicate so the embedded single
  quotes need no escaping. Terraform passes the query through `jsonencode(...)`
  (`dashboards.tf`, `tile_inputs_template`), which escapes the double quotes on the way into the
  portal definition — no manual escaping is needed in the `.kql` file.
- **`TimeGenerated > ago(30d)` (ADR-005, added 2026-09-22)** applies inside `let cdks = …`, so every
  branch of the union inherits it — it is not repeated per branch. Because `IsQueryContainTimeRange`
  stays `false`, the portal's own picker filter still ANDs on top of this one; a picker range wider
  than 30 days is silently capped at 30 days by this line, not widened. See ADR-005 for why that
  tradeoff was accepted.

### 2.4 `support/dashboard-kql/answer-generation-outcomes.kql` (Tile 2) — full draft

```kusto
// Tile 2 - Answer generation outcomes (DD-43432)
//
// Three rows: total RAG transactions started, and how many of them succeeded / failed.
// Counting unit is one RAG transaction, NOT one business request - a query that fails and then
// succeeds on retry mints a brand-new ragTransactionId and is counted as two outcomes, one failed
// and one succeeded (ADR-001). Succeeded + Failed can therefore be less than Total while
// transactions are still in flight.
//
// AND'd with a 30-day ago() fallback below so the tile still shows a bounded, meaningful window
// when no time range has been picked (revises ADR-003's "no filter at all" position; the tile is
// still registered with IsQueryContainTimeRange = false, so the portal's own picker keeps
// narrowing the range on top of this fallback - selecting a picker range wider than 30 days will
// still be capped at 30 days by this filter).
//
// Source log statements in cp-case-document-knowledge-service (FR-005). Changing the text, level
// or emission point of any of these breaks this tile - update this file in the same change (FR-006):
//   Total        jobmanager/queryflow/GenerateAnswerForQueryTask
//                "Async RAG started for caseId="
//   Succeeded    jobmanager/queryflow/CheckStatusOfAnswerGenerationTask
//                "Answer Generation updated in the DB for caseId="
//   Failed       jobmanager/queryflow/CheckStatusOfAnswerGenerationTask
//                "Answer Generation Failed for caseId="
//
// Deliberately NOT counted as failures (ADR-002):
//   "Failed to start async RAG for caseId="            - infrastructure error, no transaction exists
//   "Failed to check answer generation status RAG for" - transient polling error, retries the same
//                                                        transaction and may still succeed
//   "Answer generation failed. Retrying"               - a retry decision, not an outcome
//   "Max retries reached for caseId="                  - follows a line already counted as Failed
//
// 'ns-ste-ccm-29' is the default namespace literal; the terraform dashboard repo substitutes the
// per-environment namespace at plan time (ADR-004) - this literal is only the anchor string that
// substitution searches for, it does not have to match the environment actually being deployed to.
let cdks =
    ContainerLogV2
    | where TimeGenerated > ago(30d)
    | where PodNamespace == 'ns-ste-ccm-29'
    | where ContainerName != 'istio-proxy'
    | extend LogJson = parse_json(LogMessage)
    | where tostring(LogJson.app) == 'cp-case-document-knowledge-service'
    | extend Message = tostring(LogJson.message);
union
(cdks
    | where Message startswith_cs 'Async RAG started for caseId='
    | summarize Count = count()
    | extend Outcome = 'Total RAG transactions', OutcomeOrder = 1),
(cdks
    | where Message startswith_cs 'Answer Generation updated in the DB for caseId='
    | summarize Count = count()
    | extend Outcome = 'Succeeded', OutcomeOrder = 2),
(cdks
    | where Message startswith_cs 'Answer Generation Failed for caseId='
    | summarize Count = count()
    | extend Outcome = 'Failed', OutcomeOrder = 3)
| order by OutcomeOrder asc
| project Outcome, Count = toint(Count)
```

**Notes for the implementer.**

- The three prefixes are mutually exclusive under `startswith_cs`; in particular
  `'Answer Generation Failed for caseId='` does not match the retry line
  `Answer generation failed. Retrying …` (see §2.2).
- `Total` is an attempt count by design (ADR-001). Its known consequence — a bad day at RAG inflates
  `Total` and `Failed` together — is recorded in ADR-001 and is accepted, not a defect in this query.
- Per ADR-002 a total RAG outage shows as `Total` dropping toward zero with no corresponding `Failed`
  rise, because `Failed to start async RAG` is excluded. Documented in the header block so a support
  engineer reading the tile is not misled.
- **`TimeGenerated > ago(30d)` (ADR-005, added 2026-09-22)** applies inside `let cdks = …`, identically
  to Tile 1 — see §2.3's implementer note for the picker-interaction tradeoff, which applies here too.

### 2.5 `support/sync-dashboard-to-terraform.sh`

A byte-for-byte adaptation of HRDS's script (read in full on 2026-09-21). **Exactly three lines
differ** from the HRDS original; everything else — the `SCRIPT_DIR` resolution, the sibling-repo
lookup, the per-file `diff -q` / `cp` loop, the change counter and the four-step "next steps" epilogue
— is copied unchanged.

| Line | HRDS | CDKS |
|---|---|---|
| 2 (comment) | `…to the cp-amp-terraform-az-dashboard queries/hearing-results-document-subscription/ folder.` | `…to the cp-amp-terraform-az-dashboard queries/cdks/ folder.` |
| 14 | `DEST="$TF_REPO/queries/hearing-results-document-subscription"` | `DEST="$TF_REPO/queries/cdks"` |
| — | n/a | Add `mkdir -p "$DEST"` immediately after the `DEST=` assignment — unlike HRDS's, CDKS's destination folder will not exist on first run. |

Requirements, unchanged from HRDS: `#!/usr/bin/env bash`, committed with the executable bit set
(`chmod +x`), run manually from a developer machine with both repos checked out as **sibling
directories** (`$SCRIPT_DIR/../../cp-amp-terraform-az-dashboard`). It is **not** wired into CI, not
into `build.gradle`, and not into any GitHub Actions workflow — deliberately, matching HRDS.

> **Dashboard-name coupling.** `cdks` must be identical in three places: this script's `DEST`, the
> terraform repo's `configs/cdks.json`, and the terraform repo's `queries/cdks/` folder
> (`dashboards.tf` discovers dashboards from `configs/*.json` and pairs each with `queries/<name>/*.kql`).
> If Story 3 lands under a different dashboard name, this script's `DEST` must change with it.

### 2.6 `support/README.md`

Mirrors HRDS's `support/README.md` **only at the sections CDKS actually has**. Do not create the
`logs-kql` / `kql-prod` / `chart-kql` / `alerts-kql` folder rows or query-index sections, and do not
copy the "Running Queries via Azure CLI" section — CDKS has no `run-query.sh` (this ticket does not
create one; queries are run ad hoc from the Azure Portal, or with
`az monitor log-analytics query`, to satisfy AC-014).

Required content, in this order:

1. **Title + one-line intro** — `# Support Queries`, "KQL queries for the `cp-case-document-knowledge-service` (CDKS) service."
2. **Folder table** — a single row: `dashboard-kql/` → "Dashboard tile queries — source of truth for [`cp-amp-terraform-az-dashboard`](https://github.com/hmcts/cp-amp-terraform-az-dashboard)".
3. **`### dashboard-kql`** section with HRDS's three-step sync blockquote, verbatim in structure:
   1. Run `./sync-dashboard-to-terraform.sh` to copy files to the Terraform repo
   2. Commit, push and raise a PR in `cp-amp-terraform-az-dashboard`
   3. Once merged, run the Terraform pipeline in that repo to apply the changes to Azure
4. **Query index table** — two rows:

   | Query | Description |
   |---|---|
   | `ingestion-phase-counts.kql` | Document ingestion phase breakdown — table, dashboard time range |
   | `answer-generation-outcomes.kql` | Answer generation total / succeeded / failed — table, dashboard time range |

5. **A short "Conventions" note**, revised 2026-09-22, recording the things a future editor will
   otherwise get wrong: (a) both queries carry a `TimeGenerated > ago(30d)` fallback filter — the
   portal picker still narrows the range further when set, but the fallback caps the window at 30
   days even when a wider range is picked (ADR-003, as revised by ADR-005); (b) `ns-ste-ccm-29` is a
   deliberate hardcoded literal that the terraform repo substitutes per environment (ADR-004), and the
   known limitation that its replacement is not yet generalised for CDKS's namespace family — making
   CDKS itself environment-aware instead is a deferred follow-up, not this ticket; (c) FR-006 — each
   query's header comment names the log statements it binds to, and changing one of those Java lines
   requires updating the query in the same change.
6. **Jira reference** — DD-43432.

No PII, no real workspace GUIDs, no namespace names other than `ns-dev-ccm-03` / `ns-ste-ccm-29`, no
connection strings or subscription keys anywhere in these files (AC-016; the repo's secrets scanner
runs over them).

---

## 3. Story 3 — `cp-amp-terraform-az-dashboard` (scope note only)

**Not designed here.** Different repository, owned by platform/SRE, with its own review and terraform
apply route; no file in it is created or changed by this pipeline run. Recorded so Stage 3 can write
the story with the right scope and dependencies.

Story 3 adds a new CDKS dashboard alongside the existing `hearing-results-document-subscription` and
`pcr-data` ones: a `configs/cdks.json` tile-layout file declaring two tiles (`"query":
"ingestion-phase-counts"` and `"query": "answer-generation-outcomes"` — filenames from §2.1, without
the `.kql` extension, with no `chart`/`metric` keys so both render as `AnalyticsGrid` tables), and the
matching `queries/cdks/` folder populated by running Story 2's `sync-dashboard-to-terraform.sh`.
Two changes to the shared module are also needed, both flagged in the ADRs and neither of them CDKS-repo
work: (1) per **ADR-003**, `tile_inputs_template` in `dashboards.tf` currently emits
`{"name": "IsQueryContainTimeRange", "value": true}` unconditionally, so it needs a per-tile override
to emit `false` for these two tiles — the dashboard-level default time range lowering from 90 to 30
days remains worth doing as picker-UX polish, but per **ADR-005** (added 2026-09-22) it is no longer
strictly required for a 30-day default to hold, since both `.kql` files now carry their own
`ago(30d)` fallback; (2) per **ADR-004**, `dashboards.tf` substitutes namespaces with a single
hardcoded `replace(query, "ns-dev-amp-01", var.namespace)` against one global `var.namespace` — a
search string that matches **neither** `ns-dev-ccm-03` **nor** `ns-ste-ccm-29` (both are CDKS
literals, not HRDS's `ns-dev-amp-01`), so this `replace()` is a silent no-op against either of CDKS's
queries today and always leaves the pre-baked literal untouched. It needs a per-dashboard namespace
map. Until that lands, CDKS's **ste** tiles happen to work (the checked-in literal, revised
2026-09-22, already *is* `ns-ste-ccm-29`) — but dev, and every other environment, will show ste's data
under a dashboard deployed anywhere else, until the namespace map fix lands. The requester is raising
both directly with that repo's owner (OQ-010); this is a Story 3 blocker only, and does not hold up
Stories 1 or 2.

---

## 4. Traceability

### 4.1 FR / NFR → story → acceptance criteria

| ID | Summary | Story | Repo | Where implemented | Verified by |
|---|---|---|---|---|---|
| **FR-001** | Add `WAITING_FOR_UPLOAD` log line | 1 | CDKS | `IdpcAvailabilityService.java` §1.2 (+2 lines) | AC-001, AC-002, AC-003, AC-004, AC-005 |
| **FR-002** | `UPLOADED` — verify, do not change | 1 (verify) + 2 (KQL) | CDKS | `RetrieveMaterialAndUploadTask.java:129-130` unchanged; §2.3 `PhaseOrder = 2` branch | AC-006, AC-007 |
| **FR-003** | `INGESTED` — verify, do not change | 1 (verify) + 2 (KQL) | CDKS | `CheckIngestionStatusForAllDefendantsTask.java:116` unchanged; §2.3 `PhaseOrder = 3` branch | AC-006, AC-007 |
| **FR-004** | `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT` — verify, split on `reason=` | 1 (verify) + 2 (KQL) | CDKS | `CheckIngestionStatusForAllDefendantsTask.java:194-200` unchanged; §2.3 `PhaseOrder = 4` and `5` branches; retry-exhaustion path (`:213-214`) explicitly excluded, not implemented (OQ-011) | AC-006, AC-007, AC-008, AC-008a |
| **FR-005** | Tile 2 countable — no Java change (ADR-001, ADR-002) | 2 | CDKS | §2.4 in full; no Java diff | AC-009, AC-010, AC-011, AC-012 |
| **FR-006** | Tile-driving lines are a consumed interface | 2 | CDKS | Header comment block in both `.kql` files (§2.3, §2.4); "Conventions" note in `support/README.md` (§2.6) — **documented, not enforced by any test** (OQ-013) | AC-013 |
| **FR-007** | KQL definitions live in this repo | 2 | CDKS | `support/dashboard-kql/` (§2.1) | AC-013, AC-014 |
| **NFR-001** | No PII / case content | 1, 2 | CDKS | Four UUID/enum values only (§1.2); synthetic test data (§1.4) | AC-004, AC-016 |
| **NFR-002** | Existing SLF4J → Logstash pipeline only | 1 | CDKS | `@Slf4j` + parameterised `log.info`; `caseId` via existing `CorrelationScope` at `:64`; `logback-spring.xml` untouched | AC-017 |
| **NFR-003** | Additive only, no behaviour change | 1 | CDKS | Last statement in a `void` method, after `saveAndFlush` (§1.2); §1.3 confirms the diff is +2/-0 | AC-005, AC-006, AC-012 |
| **NFR-004** | Exactly once per event, at or above INFO | 1, 2 | CDKS | Emitted after successful flush, on the success path only (§1.2); `summarize count()` semantics (§2.3) | AC-001, AC-003, AC-009 |
| — | Build, quality gates, no PII in artefacts | 1, 2 | CDKS | `./gradlew clean build`; PMD/JaCoCo/CodeQL/secrets-scanner unchanged | AC-015, AC-016 |
| — | Tile wiring | 3 | `cp-amp-terraform-az-dashboard` | §3 — out of this repo | AC-014 (execution evidence), OQ-010 |

### 4.2 AC → where it is proved

| AC | Proved by |
|---|---|
| AC-001 | New unit test `logsWaitingForUploadLine_whenPlaceholderPersisted` (§1.4) |
| AC-002 | New unit test `doesNotLogWaitingForUpload_whenDocumentAlreadyExists` (§1.4) |
| AC-003 | New unit test `logsOneWaitingForUploadLinePerPersistedDocument` (§1.4) |
| AC-004 | New unit test `waitingForUploadLineContainsNoSensitiveValues` (§1.4) |
| AC-005 | Existing seven `IdpcAvailabilityServiceTest` tests pass with assertions unmodified (§1.4) |
| AC-006 | `git diff develop -- src/main/java/uk/gov/hmcts/cp/cdk/jobmanager/` is empty (§1.3) |
| AC-007 | Tile-1 query structure: five mutually exclusive branches over one base predicate set (§2.3) |
| AC-008 | `startswith_cs 'ingestion FAILED for identifier='` cannot match `Document status check  FAILED with reason=` (§2.3 header + notes) |
| AC-008a | **Documented exclusion, not implemented.** §2.3's header block records that the retry-exhaustion path (`:213-214`) is not visible to this query — satisfied by the written record, per the AC's own wording; no query change can close it without a Java log line (OQ-011). |
| AC-009 | Tile-2 query (§2.4) under ADR-001's per-transaction definition |
| AC-010 | **Superseded by ADR-001** — a retried query is counted as two outcomes, not once. Record this on the ticket when closing AC-010; the AC predates the requester's 2026-09-21 decision and must not be read as still requiring de-duplication. |
| AC-011 | Satisfied by the "explicitly records which are excluded and why" limb: §2.4's header block plus ADR-002 |
| AC-012 | No Java change to either answer-generation task (§1.3) |
| AC-013 | `support/dashboard-kql/` with two files, each carrying a header block naming its source classes and message markers (§2.3, §2.4) |
| AC-014 | Manual execution against a real Log Analytics workspace, result recorded on DD-43432 — Story 2 definition of done |
| AC-015 | CI (`ci-build-publish`, `code-analysis`, `codeql`, `secrets-scanner`) |
| AC-016 | Design review + the `block-pii` / `block-secrets` hooks on every write |
| AC-017 | `git diff develop -- src/main/resources/logback-spring.xml build.gradle` is empty |

### 4.3 Open items carried into Stage 3

| Ref | Item | Owner | Blocking? |
|---|---|---|---|
| **OPEN-DS-001** | CDKS pod-name prefix not determinable from this repo; design uses the verifiable `app` custom field instead. Optional HRDS-alignment follow-up (§2.2). | Requester / prod support | **No** — Stage 5 ships the files as drafted |
| OQ-001 | Brief confirmed as complete ticket text; Stage-1 summary comment posted to DD-43432 manually | Requester | No |
| OQ-008 | Five-phase tile set confirmed complete (`UPLOADING`, `INGESTING`, `NOT_FOUND` excluded) | Requester | No |
| OQ-009 | Security sign-off on `caseId` / `docId` / `materialId` / `queryId` / `ragTransactionId` being dashboard-visible. This design **excludes** `defendantId` and `courtdocId` by default, as recommended. | Security reviewer | Before merge |
| OQ-010 | Story 3 ownership, merge route, and whether tile delivery blocks closing DD-43432 | Requester | Before Stage 3 |
| OQ-011 | Tile-1 `FAILED` undercounts the retry-exhaustion path (`CheckIngestionStatusForAllDefendantsTask.java:213-214`, no log line) — accepted as a written exclusion (AC-008a); a follow-up story/ticket should add the missing log line | Requester | No — non-blocking, follow-up ticket owed before Stage 5 |
| OQ-012 | Namespace literal changed 2026-09-22 to `ns-ste-ccm-29` (ADR-004, decision point 4); phased plan accepted — ship the literal swap now, revisit making CDKS itself environment-aware (rather than one hardcoded literal) as a separate follow-up, not this ticket | Requester | No — deferred by design, not forgotten |
| OQ-013 | No contract test ties the 7 log-line markers to `support/dashboard-kql/*.kql` — only `WAITING_FOR_UPLOAD` is tested at all, and that test hardcodes its own copy of the marker rather than reading it from the `.kql` file (raised at Code Review, PR #231, 2026-09-24). Reopens OQ-007's "no automated enforcement expected" resolution. Tracked as [DD-43672](https://hmcts.atlassian.net/browse/DD-43672), not fixed on DD-43470/DD-43471 | Requester | No for this PR — but should close before Story 3 wires the tiles up |
| — | AC-010 wording is superseded by ADR-001 (§4.2) — note it on the ticket rather than re-testing it | Requester | No |
| — | Both `.kql` files now carry a `TimeGenerated > ago(30d)` fallback filter (ADR-005, added 2026-09-22), revising ADR-003's "no time filter at all" position — see §2.3/§2.4 implementer notes | Requester | No |

---

## 5. What this design deliberately does not do

- **No new metrics surface.** No Micrometer, no `/actuator/prometheus`, no Application Insights SDK,
  no OpenTelemetry metrics exporter (withdrawn under DD-43182 ADR-012 / DD-43185 ADR-009).
- **No rewording of any existing log line.** FR-002 – FR-005 are verify-only; the only text added to
  the codebase is the single new statement in §1.2.
- **No `logback-spring.xml`, `build.gradle`, Flyway, OpenAPI, ACL or API change.**
- **No alerting.** Thresholds, action groups and on-call routing are a separate ticket (OQ-010).
- **No `run-query.sh`, `logs-kql/`, `chart-kql/` or `alerts-kql/` folders.** HRDS has them; CDKS does
  not need them for this ticket and this design does not speculatively create them.
- **No backfill.** Both tiles show data from first deployment of the new log line onward.
- **No third tile**, no scheduler/stalled-work/latency views.

## 6. Hard rules preserved

No Azure call, credential, connection string, SAS token or account key is introduced anywhere — the
Managed-Identity path is untouched. JSON logging to stdout only, through the existing
`logback-spring.xml`; no `System.out`; no document content, material name, answer text, `llm_input`,
court reference number or `CJSCPPUID` in the new log line, the queries, the tests or this document.
No Flyway migration is added or edited. No RAG response field is dropped — the ingestion and
answer-serving flows are not touched at all. PMD and JaCoCo thresholds are unchanged; `gradle
integration` remains part of `build` and must pass. All identifiers used in tests and examples are
synthetic.

---

## Stage gate

**Stage 2 (Architecture & Design) is a Human gate.** This design must be reviewed and explicitly
approved before Stage 3 (User Story) begins. Nothing here is implemented yet, and Stage 3 is not
started by this document. On approval, Stage 3 produces the three sub-stories in the fixed split
already agreed with the requester (§0) — no other split.
