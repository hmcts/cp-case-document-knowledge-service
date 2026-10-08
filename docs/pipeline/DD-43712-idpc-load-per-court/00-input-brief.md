# 00 — Input Brief

**Jira ticket:** [DD-43712](https://hmcts.atlassian.net/browse/DD-43712)
**Type:** Task (ops / data request)

---

## Raw brief, as given

> **Type:** Task (ops / data request)
>
> **Description**
>
> Template — reusable for any "how many IDPCs does court X receive per day" request.
>
> Measure IDPC load (documents received per day) for one or more named court centres, using
> `ops/tools/IdpcLoad.java` and `ops/HOWTO-idpc-load-per-court.md`. The tool generates read-only
> SQL to run by hand against the Hearing and Progression databases, then builds a counts-only
> report — no case data ever leaves a developer's private work folder.
>
> **Fill in before starting:**
>
> - Court(s) requested: [names as given by the requester]
> - Date window: [YYYY-MM-DD to YYYY-MM-DD — 30 days recommended; tool warns above 62 days]
> - Requested by / for: [name, team, reason if given]
>
> **Acceptance criteria:**
>
> - [ ] Confirm `court_centre_id` for every named court first (HOWTO Step 1) — never assume a name
>   maps to a known id without checking. Court names vary in spelling, change over time, and can be
>   ambiguous (regional groupings, "false matches" like "Chester" also matching "Manchester",
>   "Combined Court" vs a specific Magistrates' sitting at the same building). Re-verify even if a
>   court has been measured before — naming may have changed since.
> - [ ] Run the full pipeline (hearing → progression → report) for the agreed window.
> - [ ] Produce `report.md` with, per court: cases listed, coverage %, IDPC docs, uploaded vs.
>   ingestible, active days, IDPCs / active day (the load figure), and peak day.
> - [ ] If more than one court is requested, also report the SHARED and ALL COURTS (deduplicated)
>   scopes the tool generates automatically — never add individual court rows together, a case
>   listed at two courts counts at both.
> - [ ] Quote IDPCs / active day, not a flat ÷ calendar days — courts don't receive IDPCs every
>   day, so dividing by all days understates the true rate (see HOWTO §7).
> - [ ] Flag if Uploaded ≠ Ingestible for any court (would mean re-uploads/re-sends are happening).
> - [ ] A markedly low coverage figure for any court is a finding to report, not something to
>   explain away silently.
> - [ ] Share back only the counts-only artefacts (`report.md`, `report-summary.csv`,
>   `report-daily.csv`) — never the intermediate files containing case IDs
>   (`case_court_map.csv`, `progression/P*.sql`), per the tool's own data-protection table.
> - [ ] Clean up: shred SQL/CSV files that contain case IDs from the work folder once the report
>   is built (HOWTO Step 8).
>
> **Notes:**
>
> - Needs read-only production DB access (Hearing + Progression) — see HOWTO §4 "Before you
>   start."
> - One window is a sample, not a baseline — if load monitoring is wanted on an ongoing basis,
>   re-run this ticket's steps for each new window rather than treating one result as permanent.
> - No case data, case IDs, or court reference numbers should appear in this ticket, its comments,
>   or any attachment — counts only.

---

## Source artefacts referenced by this brief (already present in this repo, this branch)

- `ops/tools/IdpcLoad.java` — generates the read-only SQL and builds the counts-only report.
- `ops/HOWTO-idpc-load-per-court.md` — the step-by-step runbook the tool's steps/sections above
  refer to (Step 1 court-id confirmation, §4 DB access prerequisites, §7 IDPCs/active-day
  rationale, Step 8 clean-up).

Both were validated in an earlier dry run on a different branch (manual trace through the
HOWTO using sample/synthetic data, not committed at the time) before being carried onto this
dedicated branch for proper SDLC treatment.
