# Input Brief: DD-43432 — Structured logging for dashboard tiles (KQL replacement for Prometheus)

**Jira ticket:** https://hmcts.atlassian.net/browse/DD-43432

**Background:** DD-43182 and DD-43185 originally built Prometheus/Micrometer instrumentation for
CDKS operational metrics. Platform confirmed Prometheus is not supported; the withdrawal of that
instrumentation was completed and merged to `develop` (see `docs/pipeline/adrs/DD-43182-operational-metrics-instrumentation.md`
ADR-012 and `docs/pipeline/adrs/DD-43185-stalled-work-scheduler-monitoring.md` ADR-009). DD-43432 is
the follow-up ticket referenced in both ADRs: the replacement observability approach is structured
logging plus KQL queries against Azure Monitor (`ContainerLogV2`), following the same pattern already
used by the `service-cp-crime-hearing-results-document-subscription` (HRDS) service and its
`cp-amp-terraform-az-dashboard` dashboard definition.

## Raw request (verbatim, as given by the user)

> For Tile 1 Ingestion phase — UPLOADED
>
> "Saved CaseDocument docId=..." (RetrieveMaterialAndUploadTask.java:131)
>
> For Tile 1 Ingestion phase — INGESTED
>
> "INGESTION SUCCESS identifier=..." (CheckIngestionStatusForAllDefendantsTask.java:118)
>
> For Tile 1 Ingestion phase — FAILED / EXCEEDED_FILE_SIZE_LIMIT
>
> "ingestion FAILED for identifier='...' reason='...'", reason already distinguishes the two (:196-201)
>
> For Tile 1 Ingestion phase — WAITING_FOR_UPLOAD
>
> needs a log line
>
> for Tile 2 Answer generation Total requests, in which how many succeeded/failed
>
> need to update logline
>
> https://hmcts.atlassian.net/browse/DD-43432, also by using SDLC platform agent we need to start
> produce specs, sub stories, and specs, dont give too many NFR's, all we need in sub stories simple
> logging updates in CDKS, then another story for KQL in support folder, then one more in dashboard
> tf repo thats it, dont create too much sub stories,

## Scope guardrails given directly by the requester (not to be re-litigated in Requirements)

- Exactly **3 sub-stories**, no more:
  1. CDKS: add/update the structured log lines needed to drive the two dashboard tiles.
  2. CDKS: add the KQL query definitions in a `support/` folder (mirroring the HRDS repo's
     `support/dashboard-kql` convention).
  3. `cp-amp-terraform-az-dashboard`: wire the new KQL queries into dashboard tile definitions
     (mirroring HRDS's `hearing-results-document-subscription.json` dashboard config pattern).
- Keep non-functional requirements minimal — this is a logging/observability change, not a new
  feature; avoid speculative NFRs.

## Code locations verified against current `develop` (as of this branch point)

| Tile | Ingestion phase | Status | Evidence |
|------|-----------------|--------|----------|
| 1 | `UPLOADED` | Log line exists | `RetrieveMaterialAndUploadTask.java` — `log.info("Saved CaseDocument docId={}, caseId={}, materialId={}, sizeBytes={}, blobUri={}, requestId={}", ...)` |
| 1 | `INGESTED` | Log line exists | `CheckIngestionStatusForAllDefendantsTask.java` — `log.info("INGESTION SUCCESS identifier='{}', docId={}", blobName, documentId)` |
| 1 | `FAILED` / `EXCEEDED_FILE_SIZE_LIMIT` | Log line exists, already distinguishes via `reason=` | `CheckIngestionStatusForAllDefendantsTask.java` — `log.error("ingestion FAILED for identifier='{}' reason='{}' (caseId={}, docId={}).", blobName, status, caseId, documentId)` |
| 1 | `WAITING_FOR_UPLOAD` | **No log line — confirmed gap** | `IdpcAvailabilityService.persistCaseDocument(...)` sets `entity.setIngestionPhase(DocumentIngestionPhase.WAITING_FOR_UPLOAD)` with no accompanying log statement |
| 2 | Answer generation — total / succeeded / failed | Log lines exist but are not KQL-tile-ready | `GenerateAnswerForQueryTask.java` — `log.info("Async RAG started for caseId={}, docId={}, queryId={}, transactionId={}", ...)` (candidate "request" event); `CheckStatusOfAnswerGenerationTask.java` — `log.info("Answer Generation updated in the DB for caseId={}, docId={}, queryId={}, ragTransactionId={}, task completed.", ...)` (success), `log.info("Answer Generation Failed for caseId={}, docId={}, queryId={}, ragTransactionId={}, task completed.", ...)` (failure), `log.warn("Max retries reached for caseId={}, queryId={}, ragTransactionId={}", ...)` (a second failure path) — open question: what exact log shape/marker does the KQL query need to reliably compute total/succeeded/failed as one tile |

## Reference repos (read-only reference, not modified as part of DD-43432)

- `/Users/ravik/IdeaProjects/enablenembeeded/cp-amp-terraform-az-dashboard/configs/hearing-results-document-subscription.json` — existing dashboard tile definition pattern (each tile runs one KQL query)
- `/Users/ravik/IdeaProjects/enablenembeeded/service-cp-crime-hearing-results-document-subscription/support/dashboard-kql/` — existing KQL query file pattern to mirror in CDKS's own `support/dashboard-kql`
