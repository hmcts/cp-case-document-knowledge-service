# Support Queries

KQL queries for the `cp-case-document-knowledge-service` (CDKS) service.

| Folder | Purpose |
|---|---|
| `dashboard-kql/` | Dashboard tile queries — source of truth for [`cp-amp-terraform-az-dashboard`](https://github.com/hmcts/cp-amp-terraform-az-dashboard) |

### dashboard-kql

> 1. Run `./sync-dashboard-to-terraform.sh` to copy files to the Terraform repo
> 2. Commit, push and raise a PR in `cp-amp-terraform-az-dashboard`
> 3. Once merged, run the Terraform pipeline in that repo to apply the changes to Azure

| Query | Description |
|---|---|
| `ingestion-phase-counts.kql` | Document ingestion phase breakdown — table, dashboard time range |
| `answer-generation-outcomes.kql` | Answer generation total / succeeded / failed — table, dashboard time range |

### Conventions

- **Both queries carry a `TimeGenerated > ago(30d)` fallback filter**, revising ADR-003's original
  "no filter at all" position — the tile still shows a bounded, meaningful window when the Azure
  Portal dashboard's time-range picker hasn't been touched. The picker still narrows the range
  further when a user selects one (the tile stays registered with `IsQueryContainTimeRange = false`);
  the two filters AND together, so picking a range wider than 30 days is still capped at 30 days.
- **`ns-ste-ccm-29` is a deliberate hardcoded literal**, substituted per environment by the terraform
  repo at plan time (ADR-004) — it is only the anchor string that substitution searches for, not
  necessarily the environment actually being deployed to. Known limitation: making CDKS itself
  environment-aware, rather than relying on this single literal, is tracked as a follow-up (not this
  ticket) — see ADR-004 and Story 3's scope note.
- **Each query's header comment names the log statements it binds to** (FR-006). Changing the text,
  level or emission point of any of those Java log lines requires updating the corresponding query in
  the same change.

Jira: [DD-43432](https://hmcts.atlassian.net/browse/DD-43432).
