# Ops Tools

Ad-hoc, offline operational tools for the `cp-case-document-knowledge-service` (CDKS) team. Nothing
under this folder is part of the application build — no Gradle source set, no PMD/JaCoCo coverage.
Each tool is a single file, runs with a JDK and nothing else, and is reviewed/used as-is.

| Tool | Purpose |
|---|---|
| `tools/IdpcLoad.java` | Measures IDPC (Initial Details of the Prosecution Case) document load per court centre, by generating read-only SQL to run by hand against the Hearing and Progression databases, then building a counts-only report. See `HOWTO-idpc-load-per-court.md`. |

### IdpcLoad

> Read `HOWTO-idpc-load-per-court.md` in full before running this for the first time — it covers
> prerequisites, safety guarantees, and the step-by-step for `hearing` → `progression` → `report`.

- **Nothing in production is written or changed.** The tool itself holds no database credential and
  opens no network connection — it only generates `SELECT`/`EXPLAIN` SQL for *you* to run with your
  own read-only credentials.
- **Case ids never leave your private work folder.** The only shareable output is `report.md`,
  `report-summary.csv` and `report-daily.csv` — counts only. See the HOWTO's "Data protection"
  section before sharing anything.
- **This ticket is a template**, reusable for any "how many IDPCs does court X receive per day"
  request — see [DD-43712](https://hmcts.atlassian.net/browse/DD-43712) and
  `docs/pipeline/DD-43712-idpc-load-per-court/`. Running it for a named court and window is a
  separate activity each time; fill in the court(s), the window and the requester before starting
  (HOWTO §4, Step 1).
