# Test Specs: Structured Logging for Azure Monitor Dashboard Tiles (KQL replacement for Prometheus)

> **Stage 4 — Test Specs** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Parent Jira: DD-43432** · Branch: `DD-43432` ·
> Stories: [`03-stories.md`](./03-stories.md) · Design: [`02-design.md`](./02-design.md) ·
> Requirements: [`01-requirements.md`](./01-requirements.md) ·
> Input brief: [`00-input-brief.md`](./00-input-brief.md) ·
> ADRs: [`adrs/DD-43432-structured-logging-dashboard-tiles.md`](../adrs/DD-43432-structured-logging-dashboard-tiles.md)
> (ADR-001 – ADR-004, all **Accepted** at the Stage-2 gate on 2026-09-21 — not reopened here).
>
> **Sub-tickets:** Story 1 → [DD-43470](https://hmcts.atlassian.net/browse/DD-43470) ·
> Story 2 → [DD-43471](https://hmcts.atlassian.net/browse/DD-43471) ·
> Story 3 → [DD-43472](https://hmcts.atlassian.net/browse/DD-43472).
> CLAUDE.md's hard rule — *every story needs a linked Jira ticket before the test stage* — is satisfied.
>
> **Written prospectively — no implementation exists yet (A-TDD).** Nothing below is evidence of
> coverage. Every Story-1 scenario states **"To be proven by:"**, which names a test *to write*, not
> a test that passed. The four named unit tests cannot be written until Stage 5 lands the single
> `log.info(...)` statement described in `02-design.md` §1.2 — until then there is no
> `Saved CaseDocument placeholder docId=` event for a `ListAppender` to capture.
>
> **This ticket is unusually small, and this document is deliberately shaped to match it.** Its
> entire production diff is **+2 lines in one Java file** (`02-design.md` §1.3). Consequently:
> Story 1 has four new unit tests and four no-op/regression verifications; Story 2 has **no
> automatable tests at all** and is discharged by human execution against a real Log Analytics
> workspace; Story 3 has no test specs because it lands in a different repository. Each of those
> three positions is stated explicitly below rather than left as a silent gap.

---

## Scope boundaries this document inherits and does not attempt to work around

1. **No new `integrationTest`. No new `pactVerificationTest`.** Stated by `02-design.md` §1.4's
   Stage-4 note and by Story 1's DoD. There is no API change, no OpenAPI model change, no schema or
   Flyway change, no ACL change and no service-boundary change anywhere in DD-43432, so
   `src/pactVerificationTest/` is untouched and both consumed API artefact versions are unchanged.
   `gradle integration` must still pass **unmodified**, as a regression signal (AC-015).
2. **No accessibility tests.** CDKS is backend-only (CLAUDE.md's "Not applicable in this repo" list;
   `accessibility-check` is explicitly excluded there). The WCAG 2.1 AA hard rule applies to
   downstream consumers of CDKS's API, not to a log line or a KQL query.
3. **A unit-tier `ListAppender` proves the *event*, never the JSON encoding.** It observes the
   Logback `ILoggingEvent` — level, formatted message, MDC property map. It says nothing about what
   `LogstashEncoder` renders to stdout. AC-017's "`logback-spring.xml` is unmodified" is therefore a
   **diff-level** check (Scenario 1.8), not something any test in this document observes. Do not
   write an assertion that implies otherwise. This is the same separation DD-43183's Stage 4 drew
   under its OQ-102, and it is inherited here unchanged.
4. **No scenario in this repository can execute KQL.** There is no Log Analytics emulator, no
   `ContainerLogV2` fixture, and no Azure credential available to the build (Managed Identity only,
   and the build has no identity). Every Story-2 scenario below is a **manual/operational
   verification run by a human against a real workspace**, with the output attached to DD-43471.
   Stage 4 cannot and does not produce automated test code for any of them. This is a property of
   the deliverable, not an omission.
5. **No live Azure resource is called from any test.** Unchanged from the repo's standing rule.

---

## The contract under test

Every Story-1 scenario asserts against exactly this one new statement (`02-design.md` §1.2), to be
added as the **last** statement of `IdpcAvailabilityService.persistCaseDocument(...)`, after
`caseDocumentRepository.saveAndFlush(entity)`:

```java
log.info("Saved CaseDocument placeholder docId={}, caseId={}, materialId={}, ingestionPhase={}",
        docId, caseId, info.materialId(), DocumentIngestionPhase.WAITING_FOR_UPLOAD);
```

Formatted at runtime as:

```
Saved CaseDocument placeholder docId=<uuid>, caseId=<uuid>, materialId=<uuid>, ingestionPhase=WAITING_FOR_UPLOAD
```

| Property | Value | Why a test must pin it |
|---|---|---|
| Message prefix | `Saved CaseDocument placeholder docId=` | **Load-bearing.** The Tile-1 KQL selects `UPLOADED` with `startswith_cs 'Saved CaseDocument docId='`. Without the `placeholder ` token the two phases are indistinguishable and every placeholder is double-counted as an upload (`02-design.md` §1.2). |
| Level | `INFO` | NFR-004 — must be at or above the `INFO` root threshold or the event never reaches `ContainerLogV2`. |
| Values logged | `docId`, `caseId`, `materialId` (internal UUIDs), `WAITING_FOR_UPLOAD` (fixed enum name) | NFR-001 / AC-004. |
| Values deliberately **not** logged | `materialName`, `defendantId`, `courtDocumentId`, `documentTypeDescription` — all available at the call site | NFR-001, and OQ-009's recommendation to exclude `defendantId` / `courtdocId`. |
| Emission point | after a **successful** `saveAndFlush` | AC-001 says "per newly *persisted* placeholder document"; if the flush throws, no row exists and no line must be emitted. |
| Cardinality | exactly once per persisted placeholder row | NFR-004 — a KQL `count()` must equal the count of real events. |

---

## Test inventory — files to create or extend

| Tier | File | New / extend | Story |
|---|---|---|---|
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/services/IdpcAvailabilityServiceTest.java` | **extend** — 4 new test methods + `ListAppender` fixture | 1 (DD-43470) |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/jobmanager/caseflow/RetrieveMaterialAndUploadTaskTest.java` | **unmodified — run as regression** | 1 (AC-006) |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/jobmanager/caseflow/CheckIngestionStatusForAllDefendantsTaskTest.java` | **unmodified — run as regression** | 1 (AC-006) |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/jobmanager/queryflow/GenerateAnswerForQueryTaskTest.java` | **unmodified — run as regression** | 1 (AC-012) |
| Unit | `src/test/java/uk/gov/hmcts/cp/cdk/jobmanager/queryflow/CheckStatusOfAnswerGenerationTaskTest.java` | **unmodified — run as regression** | 1 (AC-012) |
| Integration | `src/integrationTest/**` | **no file added, changed or deleted** — the whole suite runs as regression | 1, 2 (AC-015) |
| Contract | `src/pactVerificationTest/**` | **untouched** | — |
| Manual | Azure Portal / `az monitor log-analytics query` against a real workspace | evidence attached to DD-43471 | 2 (DD-43471) |

**Naming convention.** `IdpcAvailabilityServiceTest` today uses bare `shouldX_whenY` /
`verbNoun_whenCondition` method names with a `@DisplayName` carrying the prose. The four new methods
are **named exactly as `02-design.md` §1.4 specifies** — `logsWaitingForUploadLine_whenPlaceholderPersisted`,
`doesNotLogWaitingForUpload_whenDocumentAlreadyExists`, `logsOneWaitingForUploadLinePerPersistedDocument`,
`waitingForUploadLineContainsNoSensitiveValues` — which is consistent with the class's existing style.
Each carries a `@DisplayName` naming its AC, matching the house pattern already used by
`caseIdIsPresentInMdcDuringExecutionAndRestoredAfter` ("DD-43183 Story 5, AC-003: …").

---

## Story 1 — Add the missing `WAITING_FOR_UPLOAD` log line ([DD-43470](https://hmcts.atlassian.net/browse/DD-43470))

Targets `services/IdpcAvailabilityService.persistCaseDocument(...)` per `02-design.md` §1.2.
Four scenarios are new automated unit tests; four are no-op/regression verifications.

### Shared harness for Scenarios 1.1 – 1.4

**Class under test:** `uk.gov.hmcts.cp.cdk.services.IdpcAvailabilityService`.
**Test class:** `src/test/java/uk/gov/hmcts/cp/cdk/services/IdpcAvailabilityServiceTest.java` —
extended, not replaced. It is already `@ExtendWith(MockitoExtension.class)` with no Spring context,
constructs the service directly from three `@Mock`s (`ProgressionClient`, `DocumentIdResolver`,
`CaseDocumentRepository`), has an `ArgumentCaptor<CaseDocument> caseDocumentCaptor`, and clears MDC
in `@AfterEach`.

**Log-capture mechanism — the in-repo `ListAppender` idiom, and no other.** Copy it from
`src/test/java/uk/gov/hmcts/cp/cdk/http/DebugLoggingInterceptorTest.java` (lines 30–49), which is
the canonical example in this codebase; the same idiom is used by `IntradayDiscoverySchedulerTest`,
`NightlyDiscoverySchedulerTest` and `DiscoveryTriggerServiceTest`. Concretely:

- a field `ListAppender<ILoggingEvent> appender` and a field
  `ch.qos.logback.classic.Logger logger`;
- in `@BeforeEach`: `logger = (Logger) LoggerFactory.getLogger(IdpcAvailabilityService.class);`
  then `appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);`
- in `@AfterEach`: `logger.detachAppender(appender);` — **before** the existing `MDC.clear()`,
  which stays.

**Do not add a level override, and do not add `src/test/resources/logback-test.xml`.**
`DebugLoggingInterceptorTest` calls `logger.setLevel(Level.DEBUG)` only because it asserts on a
DEBUG line. The statement under test here is INFO, and `src/test/resources/` contains only
`application.yml` and `test-openapi-spec.yml` — no Logback test configuration — so plain-Logback
default configuration applies and INFO is enabled. Adding a configuration file to make an INFO
assertion pass would mask a future regression that lowers the statement below the root threshold
(`logback-spring.xml`'s root is `INFO`), which is exactly what NFR-004 exists to prevent.

**Trap — `IdpcAvailabilityService` emits three different INFO lines; filter, never count the whole list.**
The same class logger also receives `Skipping defendantId={} as doc already exists` (line 81) and
`Latest defendant identified: {}` (line 95). Every assertion below must first **filter** the captured
events by the `Saved CaseDocument placeholder docId=` prefix on `getFormattedMessage()`. An assertion
of the form `assertThat(appender.list).hasSize(1)` will fail for the wrong reason, and a
`DebugLoggingInterceptorTest`-style "join all captured output into one string" helper is
**unsafe here** — see Scenario 1.4, where it would produce a false failure.

**Assert on `getFormattedMessage()`, not `getMessage()`.** The latter returns the un-interpolated
`{}` template, so every value assertion would trivially fail.

**All fixture values synthetic** — `UUID.randomUUID()` for every identifier; no real case,
defendant, material or court identifier, and no court reference number (AC-016). The existing
fixtures in this class already comply.

---

**Scenario 1.1 — A newly persisted placeholder emits exactly one INFO event carrying `docId`, `caseId` and `materialId`** *(AC-001)*

- **Given** `progressionClient.getCourtDocumentsForAllDefendants(any(), any())` returns a single
  synthetic `LatestMaterialInfo` (a fresh `materialId`, `defendantId` and `courtDocumentId`, all
  `UUID.randomUUID().toString()`), **and** `documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any())`
  returns `Optional.empty()` — i.e. the new-document path, not the skip branch. This is the exact
  arrangement of the existing `shouldLeaveRagDocumentReferenceNull_whenPersistingWaitingForUploadRow`.
- **When** `service.retrieveDocuments(caseId, userId)` is invoked.
- **Then** filtering the captured events to those whose `getFormattedMessage()`
  **starts with** `Saved CaseDocument placeholder docId=` yields **exactly one** event; that event's
  `getLevel()` is `Level.INFO`; and its `getFormattedMessage()` contains, as separate substrings,
  the persisted `docId` (from `caseDocumentCaptor.getValue().getDocId().toString()`, which is also
  `result.getFirst().docId()`), `caseId.toString()`, the stubbed `materialId` string, and
  `ingestionPhase=WAITING_FOR_UPLOAD`.
- **To be proven by:** `IdpcAvailabilityServiceTest.logsWaitingForUploadLine_whenPlaceholderPersisted`
  (new).
- **Assert the prefix with `startsWith`, not `contains`.** The prefix is the KQL's selector
  (`startswith_cs`), so `startsWith` is the assertion that actually matches what production
  observability depends on. A `contains` assertion would still pass if someone prepended text to the
  message and silently broke the tile.
- **Level assertion is not optional.** `logger.info(...)` → `logger.debug(...)` is a one-character
  change that leaves every message-content assertion green while removing the event from
  `ContainerLogV2` entirely (root threshold is `INFO`). `getLevel()` is the only thing that catches it.

---

**Scenario 1.2 — A defendant whose document already exists emits no placeholder event** *(AC-002)*

- **Given** the arrangement of the existing `skipsExistingDocuments` test: one synthetic
  `LatestMaterialInfo`, with `documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any())`
  returning `Optional.of(UUID.randomUUID())` — the `existingDocUuid.isPresent()` branch at
  `IdpcAvailabilityService:80-83`.
- **When** `service.retrieveDocuments(caseId, userId)` is invoked.
- **Then** **zero** captured events start with `Saved CaseDocument placeholder docId=`; the existing
  `Skipping defendantId=` event **is** still present exactly once; and
  `verify(caseDocumentRepository, never()).saveAndFlush(any())` still holds — proving the absence of
  the log line is a consequence of no row being persisted, not of the statement being unreachable
  for some unrelated reason.
- **To be proven by:** `IdpcAvailabilityServiceTest.doesNotLogWaitingForUpload_whenDocumentAlreadyExists`
  (new).
- **Why the `Skipping` half matters.** Asserting only "no placeholder event" would also pass if the
  whole method silently did nothing. Asserting that the *expected* line is still there pins that the
  skip branch is genuinely the branch taken.

---

**Scenario 1.3 — `n` persisted placeholders produce exactly `n` events, one per `docId`** *(AC-003, NFR-004)*

- **Given** the arrangement of the existing `returnsNewDocuments_forMultipleDefendants` test, with
  `n = 2`: two synthetic `LatestMaterialInfo` records for two distinct `defendantId`s, with
  `resolveExistingDocIdForDefendant` returning `Optional.empty()` for both.
- **When** `service.retrieveDocuments(caseId, userId)` completes.
- **Then** the count of captured events starting with `Saved CaseDocument placeholder docId=` is
  exactly `2`; and the **set** of `docId` values appearing across those two messages equals the set
  of `docId`s on the two returned `NewIdpcDocument`s (equivalently, the two values captured by
  `verify(caseDocumentRepository, times(2)).saveAndFlush(caseDocumentCaptor.capture())` →
  `caseDocumentCaptor.getAllValues()`). No event is duplicated and no persisted document is missing
  an event.
- **To be proven by:** `IdpcAvailabilityServiceTest.logsOneWaitingForUploadLinePerPersistedDocument`
  (new).
- **Assert set equality, not just the count.** A count-only assertion passes if the same `docId` is
  logged twice and the other never — which is precisely the defect NFR-004 ("a KQL `count()` equals
  the count of real events") is about.
- **Vary `materialId` between the two records.** `returnsNewDocuments_forMultipleDefendants` reuses
  one `materialId` for both `m1` and `m2`. Copying that verbatim weakens this test: a `docId` /
  `materialId` transposition in the log statement's argument list could not be detected. Give each
  record its own `UUID.randomUUID()` `materialId` in the new test. This is a strengthening of the
  copied fixture only; it changes nothing in the existing test, which stays exactly as it is.
- **The `n`-row half of AC-003 — "a KQL `count()` over the tile predicate returns `n`" — is not
  provable here.** That clause belongs to Story 2 and is discharged by Scenario 2.2/2.7 below. This
  unit test proves only the emission side of it. Do not let the test's `@DisplayName` claim the KQL half.

---

**Scenario 1.4 — The event carries no material name, defendant id, court document id or description** *(AC-004, NFR-001)*

- **Given** a single synthetic `LatestMaterialInfo` on the new-document path, constructed with
  **distinctive non-UUID synthetic tokens** in every field the log line must not carry — e.g.
  `materialName = "SYNTHETIC-MATERIAL-NAME"` (22 chars, so `MaterialNameValidator.truncateMaterialName`
  leaves it intact and the token survives into any accidental leak),
  `documentTypeDescription = "SYNTHETIC-DESCRIPTION"` — plus fresh `UUID.randomUUID()` values for
  `defendantId` and `courtDocumentId`.
- **When** `service.retrieveDocuments(caseId, userId)` is invoked.
- **Then** taking **the single event** whose formatted message starts with
  `Saved CaseDocument placeholder docId=`, that message `doesNotContain` `"SYNTHETIC-MATERIAL-NAME"`,
  `doesNotContain` `"SYNTHETIC-DESCRIPTION"`, `doesNotContain` the `defendantId` string and
  `doesNotContain` the `courtDocumentId` string — while still containing the three permitted UUIDs
  and the enum name (so the test cannot pass by the statement having been deleted).
- **To be proven by:** `IdpcAvailabilityServiceTest.waitingForUploadLineContainsNoSensitiveValues`
  (new).
- **Load-bearing trap — assert over the one filtered event, never over the joined output of the
  whole appender.** `IdpcAvailabilityService:95` logs `Latest defendant identified: {}` with the
  **defendantId as its argument**, on the same class logger, in the same call. A
  `DebugLoggingInterceptorTest`-style `formattedLogOutput()` helper that joins every captured event
  into one string would therefore contain the `defendantId` and this test would **fail against
  correct code**. The assertion is scoped to the placeholder event alone. (The pre-existing
  `Latest defendant identified` line is out of scope for DD-43432 — FR-002–FR-005 are verify-only
  and `01-requirements.md`'s Out-of-scope forbids a repo-wide logging audit. If its defendantId
  exposure is a concern, that is a new ticket, not a change here. Noted as OQ-401.)
- **This test cannot prove "no PII" in general** — it proves that four specific values available at
  the call site are absent from one specific message. The general claim is discharged by the design
  review of a +2-line diff and by the `block-pii` hook. State both; claim only the first.

---

**Scenario 1.5 — The seven existing `IdpcAvailabilityServiceTest` tests pass with their assertions unmodified** *(AC-005, NFR-003)*

- **Given** the new `log.info(...)` statement is in place, and the test class has gained the
  `ListAppender` fixture and four new methods.
- **When** `gradle test` runs.
- **Then** `caseIdIsPresentInMdcDuringExecutionAndRestoredAfter`, `returnsEmpty_whenNoMaterials`,
  `skipsExistingDocuments`, `returnsNewDocuments_forMultipleDefendants`,
  `shouldLeaveRagDocumentReferenceNull_whenPersistingWaitingForUploadRow`,
  `truncatesMaterialName_preservingPdfExtension` and `doesNotTruncate_whenExactly50Characters` all
  pass, with **no assertion in any of them changed**, no stub changed, and no expected value changed.
- **To be proven by:** the `gradle test` run, **plus a diff-level check** on
  `git diff develop -- src/test/java/uk/gov/hmcts/cp/cdk/services/IdpcAvailabilityServiceTest.java`:
  the only changes permitted are (a) new imports, (b) the two new fields, (c) added lines **inside**
  the existing `@BeforeEach` / `@AfterEach` for appender attach/detach, and (d) the four new
  `@Test` methods. Any change inside the body of one of the seven existing test methods is an
  AC-005 failure and must be raised at code review, not waved through.
- **Precision about what AC-005 protects.** AC-005 says the existing tests pass "with their
  assertions unmodified". The `@BeforeEach`/`@AfterEach` fixture additions *are* edits to existing
  methods, and they are expected and allowed — they are fixture wiring, not assertions. Say this
  plainly in the PR description so a reviewer reading AC-005 literally does not flag it.
- **`retrieveDocuments(...)`'s return value and the persisted `CaseDocument` columns** are covered by
  these same seven tests (the captor assertions in
  `shouldLeaveRagDocumentReferenceNull_whenPersistingWaitingForUploadRow`, and the returned-list
  assertions in the other four). No additional test is needed for the "byte-for-byte unchanged" limb;
  the +2/-0 diff (`02-design.md` §1.3) is the structural argument and these tests are the behavioural one.

---

**Scenario 1.6 — No-op verification: the three existing Tile-1 log statements are identical to `develop`** *(AC-006, FR-002 – FR-004)*

**This scenario deliberately writes no test code.** AC-006 is a claim about the *absence* of a
change, and the correct instrument for that is a diff, not a test. A test asserting
"`RetrieveMaterialAndUploadTask` logs `Saved CaseDocument docId=`" would duplicate the KQL's coupling
inside the Java test suite and would still pass if the line's *level* or *emission point* moved.

- **Given** the delivered `DD-43470` branch.
- **When** the reviewer runs
  `git diff develop -- src/main/java/uk/gov/hmcts/cp/cdk/jobmanager/`
  and `gradle test` runs the existing jobmanager task suites.
- **Then** the diff output is **empty**; and `RetrieveMaterialAndUploadTaskTest` and
  `CheckIngestionStatusForAllDefendantsTaskTest` pass with **no edit to either file** (confirmed by
  their absence from the PR diff). Specifically unchanged: the message text, log level and emission
  point of `RetrieveMaterialAndUploadTask.java:129-130`
  (`log.info("Saved CaseDocument docId={}, caseId={}, materialId={}, sizeBytes={}, blobUri={}, requestId={}", …)`),
  `CheckIngestionStatusForAllDefendantsTask.java:116`
  (`log.info("INGESTION SUCCESS identifier='{}', docId={}", …)`) and
  `CheckIngestionStatusForAllDefendantsTask.java:194-200`
  (`log.error("ingestion FAILED for identifier='{}' reason='{}' (caseId={}, docId={}).", …)`).
- **Verified by:** the PR diff, at code review (Stage 6), recorded as a checklist item on DD-43470.
- **An untouched file is a stronger signal than a diffed one.** This is the same reasoning DD-43185's
  Stage 4 applied to `IntradayDiscoverySchedulerLiveTest`. Resist the temptation to add a
  "characterisation test" pinning these three message strings — FR-006 already makes the `.kql`
  header comments the documented coupling point, and `01-requirements.md` OQ-007 records that no
  automated enforcement is expected.

---

**Scenario 1.7 — No-op verification: answer-generation retry behaviour and log lines are identical to `develop`** *(AC-012, FR-005)*

**Also no new test code**, and for a stronger reason than Scenario 1.6: per ADR-001 and ADR-002,
**Tile 2 requires no Java change whatsoever**. There is nothing new to test.

- **Given** the delivered `DD-43470` branch.
- **When** the reviewer runs
  `git diff develop -- src/main/java/uk/gov/hmcts/cp/cdk/jobmanager/queryflow/`
  and `gradle test` runs the two existing queryflow task suites.
- **Then** the diff output is **empty**; `maxAttempts`, the `CTX_ANSWER_RETRY_COUNT` increment, the
  `GENERATE_ANSWER_FOR_QUERY` re-dispatch and every returned `ExecutionStatus` are unchanged; the
  three tile-driving statements (`GenerateAnswerForQueryTask.java:99`,
  `CheckStatusOfAnswerGenerationTask.java:145-146` and `:150-151`) are unchanged in text, level and
  emission point; and `GenerateAnswerForQueryTaskTest` and `CheckStatusOfAnswerGenerationTaskTest`
  pass with **no edit to either file**.
- **Verified by:** the PR diff, at code review, recorded as a checklist item on DD-43470.
- **The AC's own wording anticipates a new log assertion — there will not be one.** AC-012 reads
  "existing … unit tests pass unmodified **apart from any new log assertions**". That escape clause
  was written at Stage 1 before ADR-001 removed the need for any Tile-2 Java change. Under the
  accepted design the clause is unused: both test files must be byte-identical to `develop`. If a
  Stage-5 implementer adds a log assertion to either file, that is a scope breach, not a permitted
  variation.

---

**Scenario 1.8 — No-op verification: the logging pipeline and build configuration are untouched** *(AC-017, NFR-002)*

- **Given** the delivered `DD-43470` branch.
- **When** the reviewer runs
  `git diff develop -- src/main/resources/logback-spring.xml build.gradle`.
- **Then** the output is **empty**: no new appender, no new encoder, no root-level change, no new
  logging dependency, no `System.out`. The new statement reaches stdout through the *existing*
  SLF4J → `logback-spring.xml` → `LogstashEncoder` path, and `caseId` reaches the JSON as a
  top-level field through the *existing* `CorrelationScope.withIdentifiers(...)` at
  `IdpcAvailabilityService:64` — not through a raw `MDC.put`.
- **Verified by:** the PR diff, at code review. Additionally, a grep of the diff for `MDC.put` and
  `System.out` must return nothing.
- **Explicitly not proven by any test in this document** (scope boundary 3). The unit tier observes
  Logback events, not encoder output. The claim "the event is rendered as JSON with a top-level
  `caseId` field" is inherited from the unchanged appender configuration; if the gate wants it
  positively demonstrated, the cheapest honest route is a one-off manual check of a pod log line in
  dev after deployment — which Story 2's Scenario 2.1 pre-flight already produces as a by-product.

---

### Story 1 — what is *not* covered, stated plainly

- **The tile itself.** Whether the new event is actually selected by `ingestion-phase-counts.kql`'s
  `startswith_cs 'Saved CaseDocument placeholder docId='` branch, and whether it is correctly
  *excluded* from the `UPLOADED` branch, is Story 2's Scenario 2.2. Story 1's tests prove the
  message shape; only a real query run proves the coupling holds end to end.
- **The JSON encoding** — scope boundary 3.
- **Behaviour under a `saveAndFlush` failure.** The design's exactly-once-per-persisted-row property
  comes from statement ordering (the log call is after the flush, so an exception skips it). No test
  is specified for it: manufacturing a `saveAndFlush` failure to assert that a subsequent line was
  *not* logged proves the JVM executes statements in order. Recorded here as a deliberate omission,
  not an oversight.

---

## Story 2 — KQL query definitions in `support/dashboard-kql/` ([DD-43471](https://hmcts.atlassian.net/browse/DD-43471))

### Why every scenario here is manual

Story 2 touches **no `src/main/java`, no `src/test/java` and no `src/integrationTest/java`**. Its
entire deliverable is four non-code files (`support/README.md`,
`support/sync-dashboard-to-terraform.sh`, `support/dashboard-kql/ingestion-phase-counts.kql`,
`support/dashboard-kql/answer-generation-outcomes.kql`) whose behaviour is defined by a query engine
that does not exist in this repository or in its build. There is no Log Analytics emulator, no
`ContainerLogV2` fixture format, and no Azure credential available to `gradle build` (the service
uses Managed Identity; the build has no identity and must not acquire one).

**Every scenario below is therefore executed by a human, by hand, against a real Log Analytics
workspace, and the output is captured and attached to the DD-43471 Jira ticket.** That is AC-014's
explicit requirement and it is the whole of Story 2's test evidence. **Stage 4 does not and cannot
produce automated test code for any of them, and nothing in `gradle clean build` will ever exercise
them.** This is a property of the deliverable, not a gap in this document.

### How to execute

Either route is acceptable; record which was used:

- **Azure Portal** → Monitor → Logs → the environment's Log Analytics workspace → paste the query →
  Run. Screenshot the result grid.
- **Azure CLI** — `az monitor log-analytics query --workspace <id> --analytics-query "$(cat support/dashboard-kql/<file>.kql)" --output table`,
  authenticated as the operator's own AAD identity (`az login`), **not** with a service-principal
  secret or a workspace key. Capture stdout.

**Do not commit, paste into this repo, or attach to a public artefact:** the real workspace GUID, the
real subscription id, any pod name, or any row of raw log output containing a case, defendant,
material or court identifier. The evidence attached to DD-43471 is the **aggregate result grid**
(phase/outcome names and counts) plus, where a row-level sample is genuinely needed, a
**redacted** one. Real identifier values must not leave the workspace (AC-016, and CLAUDE.md's PII
hard rule, which applies to Jira artefacts as well as to code).

### Constructing a verifiable dataset — the two complementary methods

"A known set of log events" is the crux of every scenario below. There are two ways to get one, and
both are needed, because neither alone closes the ACs:

**Method A — inline `datatable` harness (deterministic, proves the predicate logic).**
`ContainerLogV2` is a Container Insights system table; synthetic rows cannot be injected into it.
The workable equivalent is to run a **harness variant** of the query in which the
`ContainerLogV2 | where PodNamespace == … | where ContainerName != …` source is replaced by an inline
`datatable` of hand-written synthetic rows, leaving **every downstream `where` / `summarize` /
`union` / `project` clause byte-identical to the shipped file**. For example:

```kusto
let cdks =
    datatable(Message: string) [
        'Saved CaseDocument placeholder docId=00000000-0000-0000-0000-000000000001, caseId=…, materialId=…, ingestionPhase=WAITING_FOR_UPLOAD',
        'Saved CaseDocument docId=00000000-0000-0000-0000-000000000002, caseId=…, materialId=…, sizeBytes=1, blobUri=…, requestId=…',
        'INGESTION SUCCESS identifier=\'blob-1\', docId=00000000-0000-0000-0000-000000000003',
        'ingestion FAILED for identifier=\'blob-2\' reason=\'FILE_SIZE_OVER_LIMIT\' (caseId=…, docId=…).',
        'ingestion FAILED for identifier=\'blob-3\' reason=\'SOME_OTHER_REASON\' (caseId=…, docId=…).',
        'Document status check  FAILED with reason=\'timeout\' …'
    ];
union … // the shipped file's clauses, unchanged
```

All identifier values are synthetic zero-padded UUIDs and fake blob names. This is a **verification
harness pasted into the Portal query editor for the duration of the check — it is not committed, and
the shipped `.kql` files are not modified to accommodate it.** It is what makes AC-007, AC-008,
AC-010 and AC-011 provable exactly rather than approximately, because the expected counts are known
by construction.

**Method B — real traffic in a real workspace (proves the query actually binds to real data).**
Exercise the dev environment's real flows and query the real table. This is the only method that can
catch the failure modes Method A structurally cannot: a wrong table name, a wrong namespace literal,
`LogJson.app` not being populated the way `02-design.md` §2.2 predicts, Container Insights
pre-parsing or re-wrapping `LogMessage`, or the log events simply not arriving. **Method B alone is
what satisfies AC-014.**

**Both are required.** Method A without Method B proves arithmetic against a fiction. Method B
without Method A cannot prove "no event counted twice and none unattributed" (AC-007), because in a
live workspace nobody knows the true denominator.

> **OQ-402 — confirm the harness approach before Story 2's verification is run.** The `datatable`
> harness is a Stage-4 proposal, not something Stages 1–3 specified, and HRDS has no equivalent
> practice on record. If the reviewer would rather accept Method B alone (real traffic, reconciled
> against a hand-count from a raw `| where Message startswith_cs …| project Message` listing), say so
> and Scenarios 2.2–2.6 collapse to their Method-B halves — at the cost of a weaker AC-007. Owner:
> requester + Story 2 implementer · Due: before DD-43471's verification run.

---

**Scenario 2.1 — PRE-FLIGHT: the common `let cdks = …` prefix returns rows at all** *(gates every scenario below; OPEN-DS-001)*

**Run this first. If it fails, nothing else in Story 2 means anything.**

- **Given** the shared prefix block that opens both shipped `.kql` files:
  `ContainerLogV2 | where PodNamespace == 'ns-dev-ccm-03' | where ContainerName != 'istio-proxy' | extend LogJson = parse_json(LogMessage) | where tostring(LogJson.app) == 'cp-case-document-knowledge-service' | extend Message = tostring(LogJson.message)`,
  and a dev workspace over a period in which CDKS is known to have been running and serving traffic.
- **When** the operator appends `| summarize Rows = count(), Distinct = dcount(Message)` and runs it
  over an explicit window (`| where TimeGenerated > ago(1d)` added **for this pre-flight only** —
  the shipped files carry no time filter, per ADR-003).
- **Then** `Rows` is **greater than zero**, and spot-checking `| take 20 | project Message` shows
  recognisable CDKS log messages.
- **Why this is the single most important check in Story 2.** Every tile branch is a
  `summarize count()` with no `by` clause, which **always returns exactly one row — including a row
  with `Count = 0`**. That is deliberate (it guarantees all five phases render, `02-design.md` §2.3
  notes). The side effect is that a query whose base predicate matches **nothing at all** still
  renders a perfectly well-formed tile showing five zeros. A naive "the query ran without error"
  reading of AC-014 would pass a completely broken query. This pre-flight is what distinguishes
  "returns zero" from "returns nothing".
- **Specific things this pre-flight decides:**
  1. whether `PodNamespace == 'ns-dev-ccm-03'` is the correct literal for the workspace being queried;
  2. whether `tostring(LogJson.app)` is populated — `02-design.md` §2.2 predicts it is, from
     `logback-spring.xml`'s `LogstashEncoder` custom fields, but that prediction has **not** been
     verified against a real `ContainerLogV2` row in any session so far;
  3. whether `parse_json(LogMessage)` behaves as a no-op or a parse, depending on whether Container
     Insights hands back `LogMessage` as `dynamic` or `string` in this workspace.
- **Also run OPEN-DS-001's discovery query** at the same sitting (`02-design.md` §2.2: `| summarize
  Lines = count() by PodName | order by Lines desc`) and record the observed pod-name prefix on the
  ticket. Adding a `PodName startswith` narrowing remains **optional and non-blocking** — do not hold
  Story 2 for it.
- **Evidence:** row count + a redacted sample listing + the `PodName` summary, attached to DD-43471.

---

**Scenario 2.2 — Tile 1 attributes every ingestion event to exactly one of the five phases** *(AC-007)*

- **Given** a known set of log events comprising, at minimum, one of each of the five tile-driving
  messages, in **deliberately unequal** counts so that a wrong-branch attribution cannot pass
  unnoticed (e.g. 3 × `Saved CaseDocument placeholder docId=`, 5 × `Saved CaseDocument docId=`,
  2 × `INGESTION SUCCESS identifier=`, 4 × `ingestion FAILED … reason='FILE_SIZE_OVER_LIMIT'`,
  1 × `ingestion FAILED … reason='<anything else>'`) — constructed by **Method A** for the exact
  arithmetic, and reproduced by **Method B** at whatever counts real dev traffic happens to yield.
- **When** `ingestion-phase-counts.kql` is executed unmodified (Method B) and in harness form
  (Method A).
- **Then** the result is **exactly five rows** in the order `WAITING_FOR_UPLOAD`, `UPLOADED`,
  `INGESTED`, `EXCEEDED_FILE_SIZE_LIMIT`, `FAILED`, with counts `3, 5, 2, 4, 1` under Method A; the
  sum of the five counts equals the number of seeded tile-driving events (nothing unattributed); and
  no seeded event contributes to more than one row (nothing double-counted).
- **The two assertions that carry the AC:**
  1. **`UPLOADED` reads 5, not 8.** `startswith_cs 'Saved CaseDocument docId='` must **not** match
     `Saved CaseDocument placeholder docId=` — the prefixes diverge at character 19. This is the
     single most likely way Tile 1 is silently wrong, and it is the whole reason the new log line
     carries the `placeholder ` token (`02-design.md` §1.2). Verify it explicitly, in both directions.
  2. **`EXCEEDED_FILE_SIZE_LIMIT` + `FAILED` equals the `ingestion FAILED` total**, because the two
     branches are exact complements (`contains` / `!contains`) over the same base predicate.
- **Case-sensitivity sub-check.** `CheckIngestionStatusForAllDefendantsTask` logs the **raw**
  RAG-supplied status via `normalise(rawStatus, 255)`, while the phase decision compares
  `status.toUpperCase(Locale.ROOT)` — so the logged text is not guaranteed upper-case
  (`02-design.md` §2.2). Seed a lower-case `reason='file_size_over_limit'` variant under Method A and
  confirm it still lands in `EXCEEDED_FILE_SIZE_LIMIT`. If it lands in `FAILED`, the `contains`
  predicate has been hardened to `contains_cs` somewhere and the tile under-counts.
- **Sequencing caveat, unavoidable.** Under **Method B** the `WAITING_FOR_UPLOAD` row will read
  **`0`** until Story 1 (DD-43470) is merged **and deployed** to the environment being queried — the
  log line does not exist before then. That is correct behaviour, not a failure. Record it as such on
  the ticket; do not "fix" the query to make the row non-zero. The non-zero case is proved under
  Method A now, and re-checked under Method B after DD-43470 deploys. **Recommend a second, short
  Method-B re-run post-deployment as DD-43471's final evidence item.**
- **Evidence:** both result grids, attached to DD-43471.

---

**Scenario 2.3 — Tile 1 does not count the transient polling-exception line as a terminal failure** *(AC-008)*

- **Given** a known set including at least one
  `Document status check  FAILED with reason='…'` event
  (`CheckIngestionStatusForAllDefendantsTask.java:204-209` — a polling exception that **retries**),
  alongside a known number of genuine `ingestion FAILED for identifier='…'` events.
- **When** `ingestion-phase-counts.kql` runs.
- **Then** `FAILED` + `EXCEEDED_FILE_SIZE_LIMIT` equals the count of **genuine** terminal ingestion
  failures only; the polling-exception events contribute to neither row and to no other row.
- **Why it holds:** the base predicate is `startswith_cs 'ingestion FAILED for identifier='`, and the
  polling line starts with `Document status check`. A **case-insensitive `contains`** predicate, by
  contrast, would match both — which is exactly why the design chose `startswith_cs`. Verify the
  shipped file actually uses `startswith_cs` here, not `contains`.
- **Evidence:** a Method-A run with the polling line seeded, showing the counts unmoved by it,
  attached to DD-43471.

---

**Scenario 2.3a — The retry-exhaustion `FAILED` path is a documented exclusion, not a behavioural test** *(AC-008a — added 2026-09-22, raised at Code Review; documentary, not de novo)*

> **This is a review of the shipped file's header comment, not a query-behaviour test — there is
> nothing to seed.** `CheckIngestionStatusForAllDefendantsTask.java:213-214` sets
> `ingestion_phase = FAILED` when polling retries are exhausted, with no distinguishing log line
> (`01-requirements.md` fact 11). No query text can make that path countable without a Java log
> line, which is outside Story 2's scope (`03-stories.md` Story 2, out-of-scope) — so this scenario
> proves the gap is **written down**, not fixed.

- **Given** the shipped `ingestion-phase-counts.kql`.
- **When** its header comment block is reviewed at PR.
- **Then** the header explicitly names the retry-exhaustion path (`:213-214`) as not visible to this
  query, and states why (no distinguishing log line, fix out of this ticket's Java scope) — matching
  `02-design.md` §2.3's header text verbatim in substance.
- **Why this is the right bar for AC-008a.** The AC was written to require either a working
  predicate or an explicit, written exclusion — the second limb is deliberately available (same
  pattern as AC-011's "or the design explicitly and in writing records which are excluded and why").
  A behavioural test would need to simulate polling-retry exhaustion end-to-end, which is a
  `CheckIngestionStatusForAllDefendantsTask` concern outside Story 2's non-code scope.
- **What this does NOT prove.** It does not prove Tile 1's `FAILED` count is complete — it proves
  the opposite is documented. The real-world undercount (fact 11, OQ-011) stands until a follow-up
  ticket adds the missing log line; do not close OQ-011 against this scenario.
- **Evidence:** the header-comment text, quoted on DD-43471 alongside Scenario 2.7's file review.

---

**Scenario 2.4 — Tile 2 reports total / succeeded / failed per RAG transaction** *(AC-009)*

- **Given** a known set of answer-generation events spanning all three shapes ADR-001 names:
  `Async RAG started for caseId=` (total), `Answer Generation updated in the DB for caseId=`
  (succeeded), `Answer Generation Failed for caseId=` (failed) — in unequal counts, including at
  least one transaction still in flight (a `started` with neither outcome yet) so the
  `Succeeded + Failed < Total` case is exercised.
- **When** `answer-generation-outcomes.kql` runs.
- **Then** exactly three rows appear in the order `Total RAG transactions`, `Succeeded`, `Failed`,
  with counts matching the seeded numbers, and `Succeeded + Failed ≤ Total`.
- **Note on AC-009's original wording.** `01-requirements.md` AC-009 asks for "a total-request count
  equal to the number of distinct **requests** issued (not attempts)". **ADR-001 reversed that**: the
  counting unit is one **RAG transaction**, i.e. one attempt. `03-stories.md` already restates AC-009
  in ADR-001's terms and that restatement is what is verified here. The original wording must not be
  used as the oracle.
- **Evidence:** result grid, attached to DD-43471.

---

**Scenario 2.5 — A retried query is counted as two outcomes, not de-duplicated** *(AC-010 — superseded wording; recorded as such)*

> **AC-010 as written in `01-requirements.md` is SUPERSEDED by ADR-001 (Accepted, 2026-09-21).**
> Its original text — *"A request that fails an attempt and then succeeds on retry is counted
> **once**, as succeeded"* — predates the requester's decision and describes behaviour the delivered
> query deliberately **does not** have. It must not be tested against. `02-design.md` §4.2 and
> `03-stories.md` both already record the supersession; this scenario tests the **replacement**
> behaviour and exists mainly so that AC-010 is closed against the current decision rather than
> quietly skipped.

- **Given** a known set representing one business query that fails once and then succeeds on retry:
  two `Async RAG started` events with **two different** `transactionId` values (a re-dispatch mints a
  brand-new `ragTransactionId` — `01-requirements.md` fact 7), one
  `Answer Generation Failed for caseId=` against the first, and one
  `Answer Generation updated in the DB for caseId=` against the second.
- **When** `answer-generation-outcomes.kql` runs.
- **Then** the tile reports `Total RAG transactions = 2`, `Succeeded = 1`, `Failed = 1` — **not**
  `Total = 1, Succeeded = 1, Failed = 0`.
- **Also confirm the query contains no de-duplication logic** — no `arg_max`, no `summarize … by
  queryId`, no `distinct`. Its absence is the design (`02-design.md` §2.4); its presence would be a
  silent reversion to the superseded AC.
- **Evidence:** a Method-A run with the two-transaction fixture, attached to DD-43471, **with an
  explicit note on the ticket that AC-010 is closed against ADR-001's definition, not its own
  original wording.**

---

**Scenario 2.6 — The excluded failure paths are documented and genuinely excluded** *(AC-011)*

AC-011's original text offers two alternative limbs: either all three terminal-failure paths are
reachable by the failure predicate, **or** the design "explicitly and in writing records which of
these are excluded and why". **ADR-002 takes the second limb.** Verification is therefore in two parts:

- **Part A — documentary (a file review, not a query run).** `answer-generation-outcomes.kql`'s
  header comment names and gives a reason for each excluded line:
  `Failed to start async RAG for caseId=` (infrastructure error, no transaction exists),
  `Failed to check answer generation status RAG for` (transient polling error, retries the same
  transaction, may still succeed), `Answer generation failed. Retrying` (a retry decision, not an
  outcome), `Max retries reached for caseId=` (follows a line already counted as `Failed`). Confirmed
  by reading the delivered file at PR review.
- **Part B — behavioural.** Seed a known set containing one of each of the four excluded messages and
  **zero** `Answer Generation Failed for caseId=` events. Run the query. `Failed` must read **`0`**.
- **The near-collision this part exists to catch.** `Answer generation failed. Retrying {}/{} …`
  (`CheckStatusOfAnswerGenerationTask.java:161`) differs from the counted line
  `Answer Generation Failed for caseId=` (`:150`) **only in casing and in what follows the word
  `failed`**. `startswith_cs` separates them; a case-insensitive `startswith` would match both and
  inflate `Failed` by one per retry. If Part B yields `Failed = 1`, the predicate has lost its `_cs`
  suffix. This is the single most likely way Tile 2 is silently wrong.
- **Evidence:** the file-review sign-off plus the Method-A result grid showing `Failed = 0`,
  attached to DD-43471.

---

**Scenario 2.7 — The folder, the files, and their FR-006 header coupling exist** *(AC-013)*

- **Given** the delivered DD-43471 branch.
- **When** the reviewer inspects `support/dashboard-kql/`.
- **Then** the folder exists and contains exactly **one query file per tile** —
  `ingestion-phase-counts.kql` and `answer-generation-outcomes.kql`; **each** file's header comment
  block names, per tile segment, the **source class** and the **message marker** it binds to, plus
  the FR-006 rule ("changing the text, level or emission point of any of these breaks this tile —
  update this file in the same change"); and `support/README.md`'s Conventions note repeats that rule
  from the prose side.
- **Cross-check the header against reality, don't just check it is present.** For each marker named
  in a header comment, confirm the quoted string is a genuine prefix of the actual Java message
  template on the delivered branch. A header that names a message that no longer exists is worse than
  no header — it is a coupling document that lies. Particular attention to
  `Saved CaseDocument placeholder docId=`, which does **not** exist on `develop` and only appears once
  DD-43470 lands.
- **Verified by:** PR review of DD-43471 (file review, no execution).

---

**Scenario 2.8 — Each query is valid KQL and has been run successfully against a real workspace** *(AC-014)*

- **Given** both shipped `.kql` files, **unmodified** (no harness substitution — this scenario is
  Method B only).
- **When** each is executed against a real environment's Log Analytics workspace, via the Portal or
  `az monitor log-analytics query`.
- **Then** each returns without a syntax or schema error; `ingestion-phase-counts.kql` returns
  **exactly five** rows with the expected `Phase` names in pipeline order, and
  `answer-generation-outcomes.kql` returns **exactly three** rows with the expected `Outcome` names;
  each result has two columns (`Phase`/`Outcome`, `Count`) with `Count` an integer.
- **"Ran successfully" is necessary but not sufficient — pair it with Scenario 2.1.** Both queries
  will run cleanly and return their full row shape even if the base predicate matches nothing at all
  (see Scenario 2.1's rationale). AC-014 is only genuinely closed when the pre-flight has shown the
  base predicate matches real rows **and** these two runs return their expected shape.
- **Evidence:** result grids for both queries, the workspace **environment name** (dev / ste — not
  the GUID), the execution timestamp and the portal time-range setting used, attached to DD-43471.
  This is the deliverable AC-014 names, and it is Story 2's definition of done.

---

**Scenario 2.9 — No PII, secret or real workspace identifier in any of the four new files** *(AC-016)*

- **Given** the four delivered files (`README.md`, `sync-dashboard-to-terraform.sh`, and the two
  `.kql` files).
- **When** the secrets scanner runs in CI, the `block-secrets` / `block-pii` hooks run on every write,
  and a human reads the diff.
- **Then** none contains a workspace GUID, subscription id, connection string, SAS token, account
  key, subscription key, case/defendant/material/court identifier, or any real log content. The only
  namespace literals present are `ns-dev-ccm-03` and `ns-ste-ccm-29`. Any example UUID appearing in a
  comment is an obviously synthetic zero-padded value.
- **Extends to the Jira evidence.** The result grids attached under Scenarios 2.1–2.8 must be
  aggregate counts or redacted samples — a raw `| take 20 | project Message` screenshot containing
  live case identifiers would breach the same rule the files are being checked against. Say this to
  whoever runs the verification **before** they run it, not after.
- **Verified by:** CI secrets-scanner + CodeQL + PR review.

---

### Story 2 — coverage honesty

| AC | Closable by | Automatable? |
|---|---|---|
| AC-007 | Scenario 2.2 (Method A exact, Method B corroborating) | **No** |
| AC-008 | Scenario 2.3 | **No** |
| AC-008a | Scenario 2.3a — documentary only (header-comment review) | Yes, by review |
| AC-009 | Scenario 2.4 | **No** |
| AC-010 (superseded) | Scenario 2.5, closed against ADR-001's definition | **No** |
| AC-011 | Scenario 2.6 — Part A documentary, Part B behavioural | Part A yes (file review); Part B **no** |
| AC-013 | Scenario 2.7 — file review at PR | Yes, by review |
| AC-014 | Scenario 2.8, gated on Scenario 2.1 | **No** — this AC is *defined* as a manual run |
| AC-016 | Scenario 2.9 — secrets scanner + review | Partly (scanner) |

**Nothing in `gradle clean build` exercises any Story-2 artefact.** The build must still pass
unchanged — Story 2 adds no file under `src/`, so `gradle test`, `gradle integration` and
`pactVerificationTest` are all untouched and serve purely as a "did this story accidentally break
something" regression signal (AC-015).

---

## Story 3 — Dashboard tile wiring ([DD-43472](https://hmcts.atlassian.net/browse/DD-43472)) — no test specs, by design

**This document produces no test specifications for Story 3, and that is deliberate — recorded here
explicitly rather than omitted silently.**

Story 3 lands entirely in **`cp-amp-terraform-az-dashboard`**, a different repository owned by
platform/SRE, with its own review route, its own CI, and its own terraform plan/apply gate. It is
consistent with the treatment Story 3 already receives at every earlier stage:
`01-requirements.md` scopes it at story level only ("Out of scope *for this repo*"), `02-design.md`
§3 is labelled "scope note only — not designed here", and `03-stories.md` gives it a scope note
without the task breakdown Stories 1–2 carry. Stage 4 follows that same boundary.

Concretely:

- No file in `cp-amp-terraform-az-dashboard` has been read or verified in this session, so any test
  scenario written here would be speculation about a repository this pipeline has not inspected.
- This repo's pipeline stages (Code Review, Build & Test, Deploy) do not run against that repo, so
  there is no stage in which a Story-3 test spec written here would ever be executed.
- Its acceptance evidence — `terraform plan`/`apply` for at least dev, and both tiles rendering real
  data on the Azure Portal — is produced by that repo's own pipeline and recorded on DD-43472 by the
  receiving team.
- Its two external dependencies (ADR-003's per-tile `IsQueryContainTimeRange: false` override, and
  ADR-004's per-dashboard namespace map replacing the single hardcoded
  `replace(query, "ns-dev-amp-01", var.namespace)`) are shared-module changes owned by that repo.
  The requester is raising both with its owner (OQ-010). Neither blocks Story 1 or Story 2.

**The one thing Story 3 consumes from this pipeline** is Story 2's two `.kql` files, copied verbatim
by `support/sync-dashboard-to-terraform.sh`. The correctness of the *query text* is therefore proved
by Story 2's Scenarios 2.1–2.8 **before** Story 3 starts, which is the right place for it — the
terraform repo should be wiring up an already-verified query, not discovering a broken one at
`apply` time.

---

## Cross-cutting — build and quality gates *(AC-015)*

- **Given** either story's delivered branch.
- **When** `gradle clean build` runs (which includes `integration`, per CLAUDE.md's hard rule) and CI
  runs `ci-build-publish`, `code-analysis`, `codeql` and `secrets-scanner`.
- **Then** all pass; PMD and JaCoCo are green **at their existing, unmodified thresholds**; no
  threshold is lowered to go green; no test is `@Disabled` to go green.
- **JaCoCo note for Story 1.** The production diff is two physical lines forming one statement on a
  path that five of the seven existing tests already execute, and the four new tests cover it
  directly. Coverage on new code should rise, not fall. If it falls, something other than the
  designed change has been added — investigate rather than adjust the threshold.
- **Story 2 adds no code under `src/`**, so it cannot move a coverage number; if JaCoCo reports a
  change on DD-43471, the story has exceeded its stated scope.

---

## Coverage summary

| AC | Story | Proved by | Tier |
|---|---|---|---|
| AC-001 | 1 | Scenario 1.1 → `logsWaitingForUploadLine_whenPlaceholderPersisted` | Unit |
| AC-002 | 1 | Scenario 1.2 → `doesNotLogWaitingForUpload_whenDocumentAlreadyExists` | Unit |
| AC-003 | 1 | Scenario 1.3 → `logsOneWaitingForUploadLinePerPersistedDocument` (emission half); Scenario 2.2 (KQL `count()` half) | Unit + manual |
| AC-004 | 1 | Scenario 1.4 → `waitingForUploadLineContainsNoSensitiveValues` | Unit |
| AC-005 | 1 | Scenario 1.5 — existing seven tests pass, assertions unmodified + diff check | Unit + review |
| AC-006 | 1 | Scenario 1.6 — `git diff develop -- …/jobmanager/` empty; existing suites unmodified | Review |
| AC-007 | 2 | Scenario 2.2 | **Manual** |
| AC-008 | 2 | Scenario 2.3 | **Manual** |
| AC-008a | 2 | Scenario 2.3a — header-comment review, not a query-behaviour test | Review |
| AC-009 | 2 | Scenario 2.4 | **Manual** |
| AC-010 | 2 | Scenario 2.5 — **superseded wording**; closed against ADR-001 | **Manual** |
| AC-011 | 2 | Scenario 2.6 — Part A (file review) + Part B (query run) | Review + **manual** |
| AC-012 | 1 | Scenario 1.7 — `git diff develop -- …/jobmanager/queryflow/` empty; existing suites unmodified | Review |
| AC-013 | 2 | Scenario 2.7 | Review |
| AC-014 | 2 | Scenario 2.8, gated on Scenario 2.1 | **Manual — by definition** |
| AC-015 | 1, 2 | Cross-cutting section | CI |
| AC-016 | 1, 2 | Scenario 1.4 (the log line) + Scenario 2.9 (the files) + secrets-scanner + hooks | Unit + CI + review |
| AC-017 | 1 | Scenario 1.8 — `git diff develop -- logback-spring.xml build.gradle` empty | Review |

**ACs not closable by anything in this repository:** AC-007, AC-008, AC-009, AC-010, AC-011, AC-014
(**not** AC-008a or AC-013, despite sitting inside that numeric span — both of those are closable by
in-repo file review). All six are Story 2 and all six are manual by the nature of the deliverable,
not by omission. Six of eighteen ACs having no automated coverage is unusual and is being stated up
front rather than discovered at the gate.

### Tier notes

- **Four new unit tests, zero new integration tests, zero new contract tests, zero accessibility
  tests.** Proportionate to a +2-line production diff, and consistent with `02-design.md` §1.4's
  explicit Stage-4 note.
- **The unit tier proves the log *event*; only a real workspace query proves the *tile*.** The two
  halves meet at one string — `Saved CaseDocument placeholder docId=` — which Scenario 1.1 asserts
  from the Java side and Scenario 2.2 asserts from the KQL side. Neither side alone closes the loop,
  and no test in either repo enforces that the two stay in agreement. FR-006 makes that a documented
  convention; `01-requirements.md` OQ-007 records that no automated enforcement is expected.
- **Story 1 and Story 2 can be tested independently and in either order**, matching their stated
  independence (`03-stories.md`). The single exception is Scenario 2.2's `WAITING_FOR_UPLOAD` row
  under Method B, which reads `0` until DD-43470 is deployed.

---

## Open questions raised at Stage 4

New, unresolved, and each needing an owner's answer. None is assumed resolved by this document.

- **OQ-401 — `Latest defendant identified: <defendantId>` logs a defendant identifier, and this
  ticket does not touch it.** Discovered while designing Scenario 1.4: `IdpcAvailabilityService:95`
  interpolates a raw `defendantId` into an INFO message on the same logger as the new line. DD-43432
  explicitly excludes `defendantId` from the *new* line (OQ-009's recommendation), which sits oddly
  beside a neighbouring pre-existing line that logs it outright. Out of scope here —
  `01-requirements.md` forbids rewording lines not named in FR-002–FR-005 and forbids a repo-wide
  logging audit — but it should be a deliberate decision, not an accident. Raise as a separate
  ticket, or record that internal defendant UUIDs in pod logs are accepted. — Owner: security
  reviewer, alongside OQ-009 · Due: before merge of DD-43470.
- **OQ-402 — confirm the `datatable` verification-harness approach for Story 2** (stated in full
  above). — Owner: requester + Story 2 implementer · Due: before DD-43471's verification run.
- **OQ-403 — which environment is AC-014 executed against, and by whom?** `02-design.md` names
  `ns-dev-ccm-03` (dev) and `ns-ste-ccm-29` (ste). Nothing in Stages 1–3 says who has Log Analytics
  reader access to either workspace, or whether the CDKS engineer raising DD-43471 has it at all.
  If they do not, AC-014 cannot be closed by the implementer and needs a named production-support
  or platform contact. — Owner: requester · Due: before DD-43471 starts.
- **OQ-404 — does Scenario 2.2 need a post-deployment re-run, and does that gate DD-43471's
  closure?** Under Method B the `WAITING_FOR_UPLOAD` row reads `0` until DD-43470 is deployed, so
  the fullest Tile-1 evidence is only obtainable after Story 1 ships. Either (a) close DD-43471 on
  Method-A evidence for that row plus a follow-up check, or (b) hold DD-43471 open until DD-43470 is
  in dev. Option (a) is recommended — Stories 1 and 2 are stated as independent and (b) would create
  a dependency Stage 3 deliberately did not have. — Owner: requester · Due: Stage 4 gate.

Carried forward from earlier stages, still open and not resolved here: **OPEN-DS-001** (pod-name
prefix — non-blocking; Scenario 2.1 is the natural moment to settle it), **OQ-001** (the Jira brief
was never confirmed against the live ticket, and no Stage-1 summary comment has been posted),
**OQ-008** (five-phase tile set confirmed complete), **OQ-009** (security sign-off on
`caseId`/`docId`/`materialId`/`queryId`/`ragTransactionId` becoming queryable from a **shared**
Azure Portal dashboard — a wider audience than pod logs; **required before merge**), **OQ-010**
(Story 3 ownership and merge route), **OQ-011** (Tile-1 `FAILED` undercounts the retry-exhaustion
path at `CheckIngestionStatusForAllDefendantsTask.java:213-214` — accepted as a written exclusion
for this ticket via AC-008a/Scenario 2.3a; non-blocking here, but a follow-up ticket to add the
missing log line is owed before Stage 5).

---

## Stage-4 gate

**Stage 4 (Test Specs) is a Human gate.** This document must be reviewed and **explicitly approved**
before Stage 5 (Code / implementation) begins for Story 1 (DD-43470). Nothing here is implemented:
no test code has been written, no file under `src/test/`, `src/integrationTest/` or
`src/pactVerificationTest/` has been created or modified, and the production `log.info(...)`
statement does not yet exist on this branch.

Do not proceed to Stage 5 until:

1. The Story-1 scenarios (1.1 – 1.8) are approved, including the four test names and the
   `ListAppender` mechanism.
2. The Story-2 position is accepted — that AC-007, AC-008, AC-009, AC-010, AC-011 and AC-014 have
   **no automated coverage** and are discharged by human execution with evidence on DD-43471
   (AC-008a is the one Story-2 AC in this range that **is** closable, by header-comment review).
3. The Story-3 position is accepted — **no test specs**, because it lands in another repository with
   its own review process.
4. OQ-401 – OQ-404 have decisions, and OQ-009 has a route to sign-off before merge.
