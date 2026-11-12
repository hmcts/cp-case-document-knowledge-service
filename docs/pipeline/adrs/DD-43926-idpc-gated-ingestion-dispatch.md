# ADRs – DD-43926: Create IDPC ingestion tasks only when an IDPC is available

Jira: [DD-43926](https://hmcts.atlassian.net/browse/DD-43926) (spike — investigation & recommendation).
Design paper: [`../DD-43926-idpc-gated-ingestion-dispatch/02-design.md`](../DD-43926-idpc-gated-ingestion-dispatch/02-design.md) (v2, 11-Nov-2026)
Tickets: parent [DD-43976](https://hmcts.atlassian.net/browse/DD-43976) – see [`../DD-43926-idpc-gated-ingestion-dispatch/03-stories.md`](../DD-43926-idpc-gated-ingestion-dispatch/03-stories.md)

## ADR-001: Fetch documents for many cases in one type-filtered Progression call, one task per batch

- **Status:** Proposed (TA forum)
- **Context:** Today one `CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS` task is created per listed case. Each one calls Progression, which returns every document type, and CDKS keeps only the IDPC. Progression `courtdocumentsearch` already accepts a comma-separated list of `caseId` values and reads them with one database query (design §5.1).
- **Decision:** Discovery removes duplicate case IDs, orders them by hearing date and splits them into batches of N (default 50). It creates one `FETCH_DOCUMENTS_BATCH` job per batch, with start times S seconds apart. Each job makes **one** Progression call filtered to the IDPC type, groups the result by (case, defendant), and creates `RETRIEVE_MATERIAL_AND_UPLOAD` tasks only for new material. The per-case CHECK task is no longer created.
- **Consequences:**
  - (+) About 50 times fewer jobs and Progression calls, at a planned rate.
  - (+) Retry per batch; pending batches survive a pod restart.
  - (+) RETRIEVE tasks are created only for a real, new IDPC.
  - (−) A fetch task still exists at batch level (100 instead of 5,000 for 5,000 cases).
  - (−) Batch size is limited by APIM URL length and rate limits (to confirm).
  - (−) It is still polling: a new IDPC is seen at the next discovery run.
- **Not chosen:** one long task that loops over all batches (lost on restart, retries repeat finished batches, no rate control).

## ADR-002: How Progression filters by document type – decide after analysis

- **Status:** Open – pending the analysis ticket [DD-43977](https://hmcts.atlassian.net/browse/DD-43977) (design §6.0)
- **Context:** For the batch response to hold only IDPC documents, Progression must filter by type. It already does this in SQL through `sectionId`, but only on the paginated media type `courtdocuments.with.pagination+json`. An initial reading suggests that media type takes one case only, returns no case or defendant per document, returns the first material only, keeps PDF only, and is paged.
- **Options:**
  - (a) Use `with.pagination` + `sectionId` as is.
  - (b) A small change to `with.pagination` (several `caseId` values; `caseIds`/`defendantIds` and all materials in the summary).
  - (c) A new optional, generic `documentTypeId` parameter on `courtdocuments+json` (design §6.1–6.7).
- **Decision:** To be recorded here when the analysis ticket closes, agreed with the Progression team.
- **Until then:** CDKS can ship the batch fetch with the type-filter toggle off. The existing CDKS type check in `ProgressionDtoMapper` then stays in place (design §7.7).

## ADR-003: Consume Progression document events from Azure Service Bus

- **Status:** Deferred – target design, no implementation ticket now
- **Context:** Polling finds a new IDPC only at the next run. HMCTS standards for Modern by Default services require cross-service async events to use Azure Service Bus with Managed Identity. CPP is upgrading its events to Service Bus.
- **Decision (target):** CDKS subscribes to a Progression court-document event on Service Bus, with a subscription filter on `documentTypeId` and Managed Identity. It stores events in a local IDs-only table, `document_availability`, unique on (case, defendant, material). The Part 1 batch fetch stays as a nightly safety check.
- **Consequences:**
  - (+) A new IDPC is picked up in seconds, with no Progression call for most cases.
  - (−) Depends on Progression and Platform (topic, subscription, event contract).
  - (−) A new table, which needs a retention rule and must go through `migration-reviewer`.
- **Not chosen:** a direct Artemis subscription (not in line with the standard; needs user/password and keystore, not Managed Identity), and `public.progression.court-document-added` (its payload has personal data).
