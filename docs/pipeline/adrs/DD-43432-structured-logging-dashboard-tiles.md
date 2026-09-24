# Architecture Decision Records — Structured Logging for Azure Monitor Dashboard Tiles

> Service: `cp-case-document-knowledge-service` (CDKS) · Jira: [DD-43432](https://hmcts.atlassian.net/browse/DD-43432)
> Taken at Stage 1/2 boundary, resolving Stage 1 open questions OQ-002 through OQ-007.
> Requirement: [`../DD-43432-structured-logging-dashboard-tiles/`](../DD-43432-structured-logging-dashboard-tiles/) ·
> Requirements: [`01-requirements.md`](../DD-43432-structured-logging-dashboard-tiles/01-requirements.md)
>
> Decisions below were made directly with the requester in conversation on 2026-09-21, cross-checked
> against the actual reference repositories rather than assumed: `service-cp-crime-hearing-results-document-subscription`
> (`support/dashboard-kql/`, `support/README.md`, `support/sync-dashboard-to-terraform.sh`) and the
> full `cp-amp-terraform-az-dashboard` repo (`main.tf`, `locals.tf`, `variables.tf`, `dashboards.tf`,
> all four `vars/*.tfvars`, `queries/README.md`, both dashboards' query folders).

---

## ADR-001: Tile 2 (answer generation) counts one per RAG transaction, using existing log lines unchanged

**Status:** Accepted · **Date:** 2026-09-21

**Context:** Stage 1 (OQ-002) identified that the candidate log lines for Tile 2 fire per RAG *attempt*,
not per business *request* — `CheckStatusOfAnswerGenerationTask` re-dispatches
`GENERATE_ANSWER_FOR_QUERY` on an `ANSWER_GENERATION_FAILED` response, and each re-dispatch calls
RAG's async-initiate endpoint again, which issues a **brand-new `ragTransactionId`**. So a naive
count of "requests" needed either (a) a new de-duplication key that survives retries (e.g. `queryId`
as the invariant business key), or (b) accepting that each RAG round-trip is counted independently.

**Decision:** Count **one per RAG transaction**, not one per business request. A query that fails
once and succeeds on retry is recorded as two outcomes (one failed, one succeeded) — it is not
collapsed into a single "succeeded" result. Under this definition:

- **Total** = count of `Async RAG started for caseId=…, docId=…, queryId=…, transactionId={}` (`GenerateAnswerForQueryTask.java:99`)
- **Succeeded** = count of `Answer Generation updated in the DB for …, ragTransactionId={}, task completed.` (`CheckStatusOfAnswerGenerationTask.java:145`)
- **Failed** = count of `Answer Generation Failed for …, ragTransactionId={}, task completed.` (`CheckStatusOfAnswerGenerationTask.java:150`) — and **only** this line; see ADR-002.

Each of these three lines already fires exactly once per `ragTransactionId` (confirmed: a retry
always mints a new one, so there is no cross-attempt double-count risk within a single line).
**No Java code change is required to satisfy Tile 2** — FR-005 is fully met by the existing lines;
the work is entirely in the Story 2 KQL query.

**Alternatives considered:**
- *De-duplicate by `queryId` (business request), count once per query.* Rejected by requester —
  simpler to reason about and matches "for now, simple stats", at the cost of a query that fails
  twice before succeeding appearing as 3 rows rather than 1.
- *Add a stable `outcome=` field to existing lines (Stage-1 Option A).* Not needed — the existing
  lines already discriminate cleanly by message text once "one per transaction" is accepted.
- *Add a dedicated terminal-outcome line per request (Stage-1 Option B).* Not needed for the same
  reason; would have been a larger diff for no additional benefit under this counting definition.

**Consequences:**
- Positive: zero production code change for Tile 2; the existing diagnostic lines remain free to be
  reworded later for anything *except* the three phrases named above (FR-006 still applies to those).
- Positive: KQL is a straightforward three-way `LogMessage contains` count, no `arg_max`/de-dup logic.
- Negative: the dashboard's "total requests" number is a transaction-attempt count, not a
  business-request count — a spike in retries (e.g. RAG having a bad day) inflates "total" and
  "failed" simultaneously. Acceptable per requester; revisit if this proves misleading in practice.

---

## ADR-002: Tile 2 "failed" counts only the clean RAG-reported failure, not infrastructure errors

**Status:** Accepted · **Date:** 2026-09-21

**Context:** Two other error paths exist besides the RAG-reported `ANSWER_GENERATION_FAILED` case:
`GenerateAnswerForQueryTask.java:115` (`Failed to start async RAG …` — the initial call to RAG itself
errored; no `ragTransactionId` was ever obtained) and `CheckStatusOfAnswerGenerationTask.java:189`
(`Failed to check answer generation status RAG …` — a polling error on an *existing* transaction,
which retries via `retry(executionInfo)` at the task level and may still succeed).

**Decision:** Tile 2's "failed" count includes **only** `CheckStatusOfAnswerGenerationTask.java:150`
(the clean RAG-reported `ANSWER_GENERATION_FAILED` case). The two infrastructure-error paths are
excluded — they are operational noise, not a genuine answer-generation outcome, and counting `:189`
would risk double-counting a transaction that logs a transient polling error once and then succeeds
on a later poll of the *same* `ragTransactionId`.

**Consequences:** Positive: no double-count risk from `:189`'s self-healing retry. Negative: a
persistent RAG connectivity outage (all calls failing at `:115`, before any transaction exists) would
not show up in Tile 2 at all — it has no `ragTransactionId` and no tile-visible signal today. Accepted
as a known gap; not in scope for DD-43432 (infrastructure/connectivity health is a different concern
from answer-generation outcome).

---

## ADR-003: Tile time windows follow the Azure Portal's live picker, not a hardcoded query window

**Status:** Accepted 2026-09-21, **revised** 2026-09-22 by [ADR-005](#adr-005-tile-queries-add-a-30-day-agod-fallback-filter-revising-adr-003) — the "not a hardcoded query window" part of this decision no longer holds in full; see ADR-005 for the current behaviour. · **Date:** 2026-09-21

**Context:** HRDS's existing dashboard tiles each hardcode their own window inside the `.kql` file
(`ago(84d)`, `ago(1d)`, `ago(7d)`, `startofday(now())`) and set `"IsQueryContainTimeRange": true` in
the tile's Azure Portal part definition — a signal that tells the portal "this query already has its
own time filter, don't inject one." Confirmed by reading `dashboards.tf`: the dashboard *does* carry
a shared, dashboard-level time-range picker (defaulted to "Past 90 days" for HRDS), but because every
existing tile sets `IsQueryContainTimeRange: true`, that picker is cosmetic for HRDS's current tiles —
changing it live in the portal does not change what any existing tile shows.

**Decision:** CDKS's two tiles will **not** hardcode a time window in their KQL. They rely on the
Azure Portal's own live time-range picker (`IsQueryContainTimeRange: false` on these two tiles), with
the dashboard's default range set to **30 days** rather than HRDS's 90. A viewer can change the range
live (7d / 30d / 90d / custom) and both tiles recompute without any redeploy.

**Consequences:** Positive: genuinely flexible, matches the requester's explicit goal ("more
flexible" than a fixed "today" window). Negative: this is a new pattern for the shared
`cp-amp-terraform-az-dashboard` module — none of HRDS's or `pcr-data`'s existing tiles do this, so
there is no reference implementation to copy verbatim; Story 3 needs to confirm the exact portal tile
part schema for a `IsQueryContainTimeRange: false` / no-hardcoded-window tile (this repo's own
`tile_inputs_template` in `locals.tf` sets `IsQueryContainTimeRange: true` unconditionally today and
will need a per-tile override). No CDKS Java or KQL-file impact either way — this is purely a Story 2
(write the KQL without a time filter) and Story 3 (terraform template + tile config) concern.

---

## ADR-004: `support/dashboard-kql` mirrors HRDS's convention exactly; namespace placeholder is a known Story 3 dependency

**Status:** Accepted, with a flagged external dependency · **Date:** 2026-09-21 · **Decision point 1
revised 2026-09-22** — placeholder literal changed from `ns-dev-ccm-03` to `ns-ste-ccm-29`, see the
end of this ADR.

**Context:** Read HRDS's actual `support/README.md`, `support/dashboard-kql/*.kql`, and
`support/sync-dashboard-to-terraform.sh`, plus the terraform repo's `queries/README.md` and
`dashboards.tf`. Confirmed mechanism: `support/dashboard-kql/` in the *service* repo is the source of
truth (the terraform repo's copy carries a "do not edit directly" banner); a manual, locally-run
script (`sync-dashboard-to-terraform.sh`, requires both repos checked out as sibling directories)
copies the `.kql` files into `cp-amp-terraform-az-dashboard/queries/<dashboard-name>/`; a human then
commits, raises a PR in the terraform repo, and runs its pipeline to apply.

Separately, `dashboards.tf`'s locals block does a **hardcoded** string replace —
`replace(query, "ns-dev-amp-01", var.namespace)` — to substitute the real per-environment namespace
into each query at plan time. `var.namespace` is a single value per environment
(`vars/dev.tfvars` → `ns-dev-amp-01`, `vars/sit.tfvars` → `ns-sit-amp-01`, etc.), and the literal
search string is HRDS's own dev namespace, not parameterized per dashboard. This is harmless today
because HRDS is the only dashboard whose queries use `PodNamespace`/`PodName` filtering — confirmed
`pcr-data`'s queries contain no such filter at all.

CDKS's real namespaces are a different naming family entirely: `ns-dev-ccm-03` (dev),
`ns-ste-ccm-29` (ste), with further environments to be added later.

**Decision:**
1. Story 2 (this repo) creates `support/dashboard-kql/*.kql` (one file per tile) and its own
   `support/sync-dashboard-to-terraform.sh`, mirroring HRDS's files exactly in structure and
   mechanism. The namespace filter in each file is a **hardcoded literal placeholder**
   (~~`ns-dev-ccm-03`~~ **`ns-ste-ccm-29`, revised 2026-09-22** — requester direction; ste rather than
   dev as the default checked-in literal), matching HRDS's own convention of hardcoding one namespace
   as the find-target string. This literal is only the anchor string `replace()` searches for at plan
   time — it does not have to equal the environment actually being deployed to at any given moment.
2. That literal placeholder **will not be correctly substituted by `cp-amp-terraform-az-dashboard` as
   it stands today** — its namespace replacement is single-value and HRDS-specific. Making it work
   for a second dashboard with a differently-named namespace needs a small change in that repo (e.g.
   a per-dashboard namespace map instead of one global `var.namespace`).
3. This is recorded here as a **known Story 3 dependency, owned by the `cp-amp-terraform-az-dashboard`
   repo, not CDKS-repo work**. The requester will raise it directly with that repo's owner once Story
   2's queries are ready. It does not block Stories 1 or 2.
4. **Added 2026-09-22 — tracked follow-up, not this ticket:** a second option was considered and
   deferred — making CDKS itself environment-aware (e.g. `sync-dashboard-to-terraform.sh` taking an
   `--env` argument and substituting the namespace itself) rather than relying on the terraform repo's
   single global `replace()`. Rejected for now because it would duplicate or special-case logic already
   owned by `dashboards.tf` for every dashboard, not just CDKS's. Requester chose the phased approach:
   ship the simple literal-swap (points 1–3, this ADR, unchanged mechanism) now, and revisit true
   per-environment dynamism in CDKS as a separate follow-up once there is bandwidth. Not an open
   question against this ticket's scope — deliberately deferred, not forgotten.

**Consequences:** Positive: Story 2 can proceed immediately without waiting on the terraform fix —
the placeholder is correct and consistent with HRDS's own pattern even before the generalization
lands. Negative: CDKS's dashboard cannot actually go live in any environment until the terraform-side
fix is merged; tracked as an external blocker for Story 3 only, not Stories 1–2.

---

## ADR-005: Tile queries add a 30-day `ago()` fallback filter, revising ADR-003

**Status:** Accepted · **Date:** 2026-09-22

**Context:** ADR-003 decided the two `.kql` files would carry **no** time filter at all, relying
entirely on the Azure Portal dashboard's live time-range picker (with the dashboard-level default
lowered to 30 days as a Story 3 terraform change). Requester direction on 2026-09-22: the tiles must
show a sensible default — "last 30 days" — even before anyone has touched the picker, and that default
should not depend on Story 3's terraform-repo change having landed and been applied correctly in every
environment.

**Decision:** Add `| where TimeGenerated > ago(30d)` to the base `let cdks = …` pipeline in both
`ingestion-phase-counts.kql` and `answer-generation-outcomes.kql`, so it applies to every branch of
the `union`. The tiles remain registered with `IsQueryContainTimeRange: false` (ADR-003 stands on that
point), so the Azure Portal's own picker still injects its own filter on top when a viewer sets one —
the two filters **AND together**. Net effect:

- No picker interaction (or a picker default wider than 30 days, if Story 3's terraform-repo default
  lowering has not landed yet): capped at 30 days by this filter. The "default to last 30 days"
  requirement is now true **by construction**, independent of the Story 3 terraform default — the
  same property the PR #230 review asked for in a different context (a single scan/window rather than
  relying on tile config).
- Picker set narrower than 30 days (e.g. 7 days): the picker's filter is the more restrictive one and
  wins, as expected.
- Picker set **wider** than 30 days (e.g. 90 days, custom): still capped at 30 days by this filter —
  a viewer explicitly asking for a wider range will not get one. This is a real, accepted limitation,
  not an oversight — see Consequences.

**Alternatives considered:**
- *Rely solely on Story 3's terraform-level default lowering (90 → 30 days), no KQL change.* This was
  ADR-003's original position. Rejected on revisit — it only sets the *initial* picker value; nothing
  stops a viewer (or a stale/uncorrected terraform apply) from showing an unbounded or very wide range,
  and the guarantee lives entirely in a different repo this pipeline hasn't read.
- *Set `IsQueryContainTimeRange: true` and drop the picker's influence entirely, always showing exactly
  30 days.* Rejected — this reproduces the exact problem ADR-003 called out in HRDS's existing tiles
  (the picker becomes cosmetic), which was the reason ADR-003 chose `false` in the first place.

**Consequences:** Positive: the 30-day default holds regardless of Story 3's terraform state, with no
cross-repo dependency for CDKS's own guarantee. Negative: a viewer who explicitly picks a wider range
gets a silently-capped result rather than what they asked for — flagged here for Story 3 / the
dashboard owner to decide if that tradeoff needs surfacing in the tile UI (e.g. a subtitle noting the
30-day cap); not resolved by this ADR. Story 3's terraform-level default-lowering (90 → 30 days) is no
longer strictly required for the 30-day guarantee to hold, but remains worth doing as picker-UX
polish — it is not being withdrawn from Story 3's scope by this decision.
