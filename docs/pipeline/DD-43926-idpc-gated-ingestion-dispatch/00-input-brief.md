# Input brief: Only create ingestion tasks when an IDPC exists

> **Jira: [DD-43926](https://hmcts.atlassian.net/browse/DD-43926)** — this TA forum design paper was
> originally drafted against a placeholder `DD-TBD` (no ticket raised yet, from a direct ask reviewed
> at the 2026-10-30 TA forum). DD-43926 — the formally-scoped spike "Investigate bulk Case ID support
> and alternative approaches for retrieving IDPC availability from Progression" — covers the same
> ground and is now the ticket this folder is filed under; the folder and ADR were renamed
> accordingly. Section 5 of `02-design.md` ("What Progression already provides") answers DD-43926's
> AC1 directly — not from CDKS-side speculation, but from reading Progression's own source
> (`hmcts/cpp-context-progression`: the RAML spec and `CourtDocumentQueryView.searchCourtDocuments`).
> Section 10 (options considered) and Section 9 (asks to other teams) answer AC2/AC4/AC5. `03-stories.md`'s
> two stories are the "follow-up implementation stories" DD-43926's AC5 asks to identify — they are
> **not** DD-43926 itself and need their own ticket numbers once raised; DD-43926's own scope stops at
> investigation and recommendation (its Out of Scope line excludes implementation).

## Raw request (as given, 2026-10-30)

> "Can you please check job task where it add check idpc for defendant. I do not like design, I want
> task only created when idpc there to make things simple. Please review and create story."

---

## DD-43926 — formal spike ticket, as given (2026-11-05)

> ## Summary
> **SPIKE – Investigate bulk Case ID support and alternative approaches for retrieving IDPC
> availability from Progression**
>
> ### Background
> Currently, CDKS (`cp-case-document-knowledge-service`) calls the Progression `courtdocumentsearch`
> service individually for each Case ID to identify whether IDPC documents are available. During
> nightly and intraday discovery, CDKS processes multiple cases and creates
> `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS` tasks, even when no new IDPC documents exist. We are
> reviewing the ingestion design to create retrieval tasks only when new IDPC documents are
> available. However, the proposed change would still require one Progression API call per case. We
> need to investigate whether this interaction can be optimised to reduce the number of calls to
> Progression.
>
> ### Spike Objective
> Investigate whether Progression supports retrieving document availability for multiple Case IDs in
> a single API request, or whether another integration approach could reduce the number of
> individual requests.
>
> ### Scope of Investigation
> 1. Check whether the existing Progression `courtdocumentsearch` API supports passing multiple Case
>    IDs in a single request.
> 2. If bulk requests are not currently supported, discuss with the Progression team whether a bulk
>    API or alternative endpoint is available or feasible.
> 3. Investigate whether Progression provides any event-driven mechanism or notification when new
>    IDPC documents become available.
> 4. Explore alternative approaches, including batching, asynchronous processing or incremental
>    document discovery.
> 5. Assess the impact of each option on API performance, response times, error handling, rate
>    limits and CDKS ingestion processing.
> 6. Confirm how any proposed solution would support multiple defendants and identify newly
>    available IDPC documents without reprocessing already-ingested material.
>
> ### Expected Outcome
> - Confirmation of whether Progression supports multiple Case IDs in a single API call.
> - Identification of feasible alternatives if bulk requests are not supported.
> - High-level assessment of technical changes required in CDKS and/or Progression.
> - Recommendation of the preferred approach, including dependencies, risks and expected benefits.
> - Follow-up implementation stories identified where applicable.
>
> ### Acceptance Criteria
> **AC1:** Existing Progression API capabilities are reviewed and bulk Case ID support is confirmed
> with the Progression team.
> **AC2:** At least one feasible alternative is assessed if bulk requests are not supported.
> **AC3:** The impact on CDKS discovery, IDPC availability checks, performance and failure handling
> is documented.
> **AC4:** A recommended solution is documented, including whether changes are required in
> Progression, CDKS or both.
> **AC5:** Findings are reviewed with the relevant technical teams, and follow-up Jira stories are
> identified.
>
> ### Out of Scope
> Implementation of API changes or modifications to the existing CDKS ingestion flow. This ticket is
> for technical investigation and solution recommendation only.

---

## Review of the current design

### How it works today

```
Nightly discovery (02:00)                    Intraday discovery (every 10 min, 07:00–19:50)
  isHearingForCasesEnabled = true:             per scheduled_ingestion_request for today:
  1 CHECK task per unique case                   GET_CASES_FOR_HEARING task
           │                                              │  1 CHECK task per case on the list
           ▼                                              ▼
  CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS  ── 1 job row per case, whether or not an IDPC exists
           │  IdpcAvailabilityService.retrieveDocuments(caseId)
           │    → Progression courtdocumentsearch (all docs for the case, filtered to IDPC type)
           │    → skip defendants whose latest IDPC material is already ingested
           │    → persist WAITING_FOR_UPLOAD placeholder per new IDPC
           ▼
  RETRIEVE_MATERIAL_AND_UPLOAD  ×1 per new IDPC document (0 in most cases)
```

Code: `DiscoveryService.dispatchCaseEligibilityCheck` and `GetCasesForHearingTask.execute` create the
CHECK task. `CheckIdpcAvailabilityAllDefendantsTask` runs the check.

### Findings

| # | Finding | Evidence |
|---|---|---|
| F1 | **Many CHECK tasks probably do nothing, but this has not been measured.** A CHECK task is created for every listed case, whether or not it has an IDPC. | *Corrected 2026-10-30.* An earlier draft said "2 in 3" using DD-43712's 14–37% coverage. That figure is wrong for this purpose: DD-43712 counted IDPCs **uploaded within a 30-day window** at three Cheshire courts. It did not count CHECK outcomes, and it misses cases whose IDPC arrived earlier. Measure the real figure from logs first: compare the count of `Created CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS` lines with the count of `Saved CaseDocument placeholder` lines. |
| F2 | **In production this is the intraday path, and only for rooms someone asked for.** Intraday is on in every environment, but it only re-runs `scheduled_ingestion_request` rows, which are written by the manual `startIngestionProcess` call (court + room + date). For each requested room, on its hearing day, all 78 runs dispatch GET_CASES_FOR_HEARING, and then a CHECK task for every case on the list. | `application-cdk.yml:57`; `JobManagerService.java:85-93`; `DiscoveryService.runIntradayDiscovery`; DD-43875 toggle inventory |
| F3 | **Nightly discovery and its burst controls run only in `ste`.** Nightly discovery, `is-hearing-for-cases` and the DD-43823 stagger and priority are all off in dev, sit, nft, prp, prd and prx. | DD-43875 toggle inventory §2 |
| F4 | **The check logic is already reusable, and the manual flow already runs it inline.** `IngestionProcessorByCaseService` calls `IdpcAvailabilityService.retrieveDocuments` synchronously and dispatches RETRIEVE only for new documents, which is exactly the shape requested. | `IngestionProcessorByCaseService.java:102,140` |
| F5 | **The burst control (DD-43823) is attached to the CHECK task.** The stagger start time and the hearing-proximity priority are stamped on CHECK tasks. If CHECK goes, they must move to RETRIEVE. That is arguably better, because only real work is then shaped. | `NightlyEntryTaskPlanner`, `JobManagerService.dispatchCaseDocumentIngestionTasksCheckIdpcAvailability` |
| F6 | **Per-case retry comes from the task framework today** (CHECK uses `default-retry`, 3 × 20 s). Running the check inline loses that, so the failure behaviour has to be decided. | `CheckIdpcAvailabilityAllDefendantsTask.getRetryDurationsInSecs`; DD-43182 design §retry table |
| F7 | **The number of Progression calls does not change.** Whether the check runs inline or as a task, it is still one courtdocumentsearch call per case per run. The gain is fewer job rows, less executor churn and less log/retry noise, plus a simpler flow. It is not lower Progression load. | `ProgressionClientImpl.getCourtDocumentsForAllDefendants` |
| F8 | **Possible duplicate placeholders (existing issue, not new).** `case_documents` has no unique constraint on (case, material, defendant), so two concurrent checks of the same case could both insert a placeholder. Inline checking does not make this worse, but the story should not hide it. | `db/migration/V1001…sql` (no such unique index) |

### Options considered

| Option | Description | Verdict |
|---|---|---|
| **A. Check inline at discovery; create RETRIEVE tasks only** | Discovery and GET_CASES_FOR_HEARING call the existing `IdpcAvailabilityService` per case. If there is a new IDPC, they dispatch RETRIEVE; otherwise they create nothing. | **Recommended.** Meets the request, reuses existing code (F4), and keeps the toggle and rollback simple. |
| B. Keep CHECK, but pre-check before creating it | Check first, then create the CHECK task, which checks again | Rejected: two Progression calls per case for no gain |
| C. Event-driven: subscribe to Progression "court document added" (IDPC type) | Create work only when Progression signals a new IDPC | Best long-term, and it would also cut Progression calls (F7). But CDKS has no Progression event subscription today (no JMS listener in `src/main`), so it needs a new integration and cross-team work. Raised as an open question, not in scope. |
