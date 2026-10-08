# Requirements: IDPC Load per Court Centre (ops / data request)

> **Stage 1 — Requirements** · Service: `cp-case-document-knowledge-service` (CDKS)
> **Jira: DD-43712** · Type: **Task (ops / data request)** · Branch: `DD-43712-idpc-load-per-court`
>
> **This is not a feature requirement.** No CDKS production code, API, schema, migration or
> configuration changes as part of this ticket. The deliverable is the **execution of an existing,
> already-written runbook** (`ops/HOWTO-idpc-load-per-court.md`) using an existing, already-written
> offline tool (`ops/tools/IdpcLoad.java`), and the **counts-only report artefacts** it produces for
> a named requester. The requirements below therefore specify a **process** and its outputs, not
> software behaviour to be built.
>
> **DD-43712 is explicitly a reusable template ticket** — "reusable for any *how many IDPCs does
> court X receive per day* request". Its three key inputs (which courts, which date window, who
> asked) are **blank in the brief as given** and are recorded here as open questions (OQ-001 –
> OQ-003), not guessed. Per CLAUDE.md: *never invent requirements, ACs, or test data*.

---

## Context

A requester wants to know how many **IDPCs** (Initial Details of the Prosecution Case documents) a
named court centre receives per day — the real document load, as a basis for capacity sizing and for
understanding CDKS ingestion volumes.

No single database holds both *which court heard a case* and *which IDPCs that case has*. The
Hearing database knows court and listing date; the Progression database knows court documents. The
join is therefore done **offline on a developer's laptop**: `ops/tools/IdpcLoad.java` generates
read-only SQL for each database, the developer runs that SQL by hand with their own read-only
production credentials, exports each result as CSV, and the tool then assembles a counts-only
report.

Prior art (HOWTO §8, a Cheshire run of Aug–Sep 2026 using the earlier hand-built SQL) established
*why* this is measured rather than estimated: using defendant or listing counts as a proxy for IDPC
load **overstated it by 2.6×–5.9×**. The whole point of this ticket is to replace an estimate with a
measurement.

Both `ops/tools/IdpcLoad.java` and `ops/HOWTO-idpc-load-per-court.md` already exist on this branch
and were validated in an earlier dry run on a different branch. This ticket puts their *use* — and,
per OQ-010, their *commit* — through the SDLC pipeline properly.

### Actors

| Actor | Role in this task |
|---|---|
| **Requester** | Asks "how many IDPCs does court X receive per day?". Names the court(s), and the reason. Receives the counts-only report. Identity unknown for this run — OQ-003. |
| **CDKS developer (operator)** | Runs the runbook end to end: confirms court ids, generates SQL, runs it against production, exports CSVs, builds the report, shares it, and destroys the intermediates. Must hold **read-only** production access to both the Hearing and Progression databases. |
| **Production DBA** | Not required for a normal run, but is the escalation point if the P2 plan probe shows a sequential scan of `court_document`, and the approver if read-only production access or query sign-off is needed (HOWTO Appendix A exists to be handed to a DBA). |
| **Data-protection / security reviewer** | Owns the rule that case ids never leave the operator's private work folder, and that nothing with case-level identifiers reaches Jira, Confluence, Slack or the repo. |
| **Future operators** | DD-43712 is a template; anyone re-running it for a different court or window is bound by the same requirements. |

### Facts verified against this branch

| # | Statement | Verified? | Evidence |
|---|---|---|---|
| 1 | The tool cannot reach a database or the network | **Yes** | `ops/tools/IdpcLoad.java` imports only `java.io`, `java.nio`, `java.time`, `java.util`, `java.util.regex`, `java.util.stream`. No `java.sql`, no `java.net`, no JDBC driver, no `ProcessBuilder`. The only mentions of those terms are in the class comment (lines 26–28). It accepts no host, connection string or credential argument (`Args`, lines 773–826). |
| 2 | All generated SQL is read-only | **Yes** | The only statements emitted are `SET statement_timeout`, `SELECT`, `WITH … SELECT` and `EXPLAIN (ANALYZE, BUFFERS)` — `hearing()` (lines 109–151), `summarySql`/`dailySql`/`checkSql`/`probeSql` (lines 332–412). No `INSERT`/`UPDATE`/`DELETE`/`CREATE`/`DROP` anywhere. |
| 3 | Every generated file carries a statement timeout | **Yes** | `STATEMENT_TIMEOUT = "180s"` (line 44), emitted as the first statement of H1, H2, P1, P2, P3 and P4. |
| 4 | The 87 GB `court_document` table is only ever read by primary key | **Yes** | `pipeline()` (lines 291–326): `docs AS MATERIALIZED` hits `court_document_index` only; `fetched AS MATERIALIZED` joins `court_document` on `cd.id = d.court_document_id`. The `AS MATERIALIZED` fences are load-bearing and carry an in-SQL comment saying so. |
| 5 | The work folder is refused inside a git repository | **Yes, with a caveat** | `guardOutsideGitRepo` (lines 618–626) walks every parent looking for `.git`. **Caveat:** it is called only from `hearing` (line 101) — `progression` and `report` do not re-check (OQ-007). |
| 6 | Files containing case ids are created private to the operator | **Yes** | `write(..., containsCaseIds = true)` → POSIX `rw-------`; directories `rwx------` (`mkdirsPrivate`, lines 628–644). Silently degrades on non-POSIX filesystems (line 649). |
| 7 | Every value read from a CSV export is validated as a UUID before being written into SQL | **Yes** | `requireUuid` (lines 600–606) applied to `case_id` and `court_centre_id` from `case_court_map.csv` (lines 193–195) and to `--courts` (line 84). SQL-injection via a malformed export is therefore not possible for id fields; court *names* are escaped with `sqlText` (line 562). |
| 8 | The tool warns, but does not refuse, above a 62-day window | **Yes** | `hearing()`, lines 93–97: `days > 62` prints a `WARN` to stderr about the 180 s timeout and continues. 30 days is the HOWTO's recommendation. |
| 9 | SHARED and ALL-COURTS scopes are generated automatically, and conditionally | **Yes** | `progression()`, lines 211–230: `zz-shared-cases` is added only when at least one case appears at more than one court; `zz-all-courts-deduplicated` only when `casesByCourt.size() > 1`. A single-court request therefore produces **neither** — which is correct, and means the ticket's multi-court checklist item is conditional. |
| 10 | The IDPC document type the tool filters on matches CDKS's own configuration | **Yes** | `IDPC_DOCUMENT_TYPE_ID` (line 42) is byte-identical to `progression.doc-type-id` in `src/main/resources/application-clients.yml:31`. |
| 11 | "Ingestible" is defined to match CDKS's real retention behaviour | **Yes** | `pipeline()`'s `ranked` CTE takes `ROW_NUMBER() … PARTITION BY court_document_id ORDER BY ts_text::timestamptz DESC`, and `rn = 1` is counted as ingestible — i.e. the newest material per document, which is what `ProgressionDtoMapper` keeps (HOWTO §7). "Uploaded" is every material upload including re-sends. |
| 12 | Withdrawn documents are excluded | **Yes** | `idpc` CTE filters `f.is_removed = false` (line 309). |
| 13 | Day boundaries are UK court dates, not UTC | **Yes** | `COURT_TIME_ZONE = "Europe/London"` (line 43), applied as `(ts_text::timestamptz AT TIME ZONE 'Europe/London')::date` (line 319). |
| 14 | "Active days" is derived from the daily (P4) export, not from P3's own `days_with_idpcs` column | **Yes** | `Totals.activeDays()` (lines 420–422) counts days in the merged daily map with `idpcs_uploaded > 0`. P3's `days_with_idpcs` is produced but not consumed by the report — correct, because summing that column across split parts would double-count shared days. |
| 15 | The report refuses to build if any expected result CSV is missing | **Yes** | `report()`, lines 443–472: every `manifest.csv` row of kind `summary`/`daily` must have its file present, else a `UsageException` listing all missing files. |
| 16 | Scopes above the split threshold are chunked, and parts are summed on the way back | **Yes** | `DEFAULT_MAX_CASES_PER_FILE = 3000` (line 45), `chunk` (lines 589–598), `--max-cases` override; `report()` accumulates part totals into one `Totals` per scope (lines 441–457). |
| 17 | `report.md` contains counts only | **Yes** | The markdown builder (lines 487–535) emits scope label, counts, percentages and dates only. No case id or case-level field is ever carried into `Totals` or `report-daily.csv`. |
| 18 | The re-upload condition is detected and stated automatically in the report | **Yes** | the report builder tracks `uploaded != ingestible` across scopes (line 503) and drives an explicit sentence in the "How to read this" section (lines 526–527). |
| 19 | An undocumented escape hatch exists that disables the git-repo guard | **Yes — flagged** | `--allow-inside-git-repo` (line 620). It appears in neither `USAGE` (lines 834–848) nor the HOWTO. See OQ-006. |
| 20 | Three paths referenced by the HOWTO do not exist on this branch | **Yes** | `ops/generated/`, `ops/RUNBOOK-idpc-per-court.md` and `ops/idpc-per-court-report.sh` are all absent; `ops/` contains only `HOWTO-idpc-load-per-court.md` and `tools/IdpcLoad.java`. The HOWTO §10 housekeeping instruction and two Appendix C rows are therefore dangling. See OQ-008. |
| 21 | `ops/` is currently untracked, and nothing in `.gitignore` covers it | **Yes** | `git status` shows `?? ops/`; `.gitignore` contains no `ops` entry. See OQ-008 and OQ-010. |
| 22 | The tool runs on this repo's JDK with no build integration | **Yes** | Single-file source launch, JDK 21+ (HOWTO §4); local JDK is 25, repo targets Java 25. It is referenced by no Gradle source set, so PMD/JaCoCo/CodeQL build-stage coverage of it is not automatic (OQ-009). |
| 23 | Two runbook prerequisites are absent from a stock macOS developer machine | **Yes** | `shred` (HOWTO Step 8 clean-up) and `psql` (HOWTO §4, Steps 3/5/6) are both not present on this machine. `shred` is GNU coreutils and is not shipped with macOS at all. See OQ-005. |

**Note on source:** this document is grounded in `00-input-brief.md`, `ops/HOWTO-idpc-load-per-court.md`
and `ops/tools/IdpcLoad.java` as read on this branch. **Jira DD-43712 itself was not fetched** — no
Atlassian/Jira MCP tool is available in this session — so no Stage-1 summary comment has been posted
to the ticket (OQ-004).

---

## Functional requirements

Scope: the measurement process and its artefacts. Each FR describes something the **run** must do;
`IdpcLoad.java` already implements the mechanics of FR-004 to FR-010.

| ID | Requirement | Priority |
|---|---|---|
| **FR-001** | **Record the request before starting.** The three template fields on DD-43712 — court(s) requested (as named by the requester), date window (`YYYY-MM-DD` to `YYYY-MM-DD`), and requested by / for (name, team, reason) — must be filled in on the ticket before any query is run. A run with any of the three unfilled is not a valid run, because the result cannot be attributed, bounded or re-produced. | Must |
| **FR-002** | **Use the measurement definitions the tool encodes — do not re-define them.** An *IDPC* is a Progression court document whose payload carries the `documentTypeId` held in `IDPC_DOCUMENT_TYPE_ID`, which must remain identical to `progression.doc-type-id` in `src/main/resources/application-clients.yml`. Withdrawn documents (`is_removed = true`) are excluded. Day boundaries are `Europe/London` court dates. *Uploaded* counts every material upload including re-sends; *Ingestible* counts only the latest material per document, matching what CDKS actually retains. No run may silently substitute its own definitions. | Must |
| **FR-003** | **Confirm `court_centre_id` for every named court, every time.** Before generating any SQL, run HOWTO Step 1 against the Hearing database with the requester's search terms and establish the `court_centre_id` for each named court from `ha_hearing`. A court **name** must never be assumed to map to a known id. This re-confirmation is required **even for courts measured before**, because names vary in spelling and change over time. The operator must actively check for (a) false matches — a substring search for one town can also match a longer town name containing it; (b) regional groupings; (c) a Combined Court versus a separate magistrates' sitting at the same building; and (d) `latest_hearing` recency — a court with no recent hearings has no current load and that fact must be reported rather than measured around. | Must |
| **FR-004** | **Generate the Hearing SQL with the tool, for the agreed window.** `IdpcLoad hearing --courts <confirmed ids> --from <from> --to <to>`. The window is inclusive and is used for both "cases listed" and "IDPCs uploaded". 30 days is the recommended window. The tool warns above 62 days (it does not refuse); a window over 62 days must be either split or justified on the ticket. The work folder must be outside any git working tree — the default `~/idpc-work/<from>_<to>` satisfies this. | Must |
| **FR-005** | **Export the case → court map.** Run `hearing/H2-case-court-map.sql` on the **Hearing** database and save the result, **with its header row**, as `case_court_map.csv` in the work folder root, with columns `case_id`, `court_centre_id`, `court_centre_name` unrenamed and no extra client output (notices, `SET` echoes, row counts). Optionally also export `H1-listing-volume.sql` to `results/H1-listing-volume.csv` as context — H1 is listing volume, **not** IDPC volume, and must never be quoted as the load figure. | Must |
| **FR-006** | **Generate the Progression SQL.** `IdpcLoad progression --work <work>`. This derives one scope per court from the exported map, plus — **only when more than one court is in play** — a `SHARED` scope (cases listed at more than one court, generated only if such cases exist) and an `ALL COURTS (each case counted once)` deduplicated scope. Any `WARN: court … has no cases` must be resolved (wrong id, or genuinely no hearings in the window) before continuing, not ignored. Scopes above the 3,000-case split threshold are emitted as `-partN` files; **every part must be exported.** | Must |
| **FR-007** | **Run both safety checks before any heavy query, and honour their STOP conditions.** `P1-check-case-ids.sql` (index-only) must show `cases_found_in_progression` close to `cases_listed`: **STOP if it is 0 for any court**, and **STOP if `documents_to_fetch` is in the hundreds of thousands for one court** (shorten the window). `P2-probe-plan.sql` must be run **without** CSV export and read on screen: the plan **must** contain `court_document_index_prosecution_case_id_idx` and `Index Scan using court_document_primary_key`, and **must not** contain `Seq Scan on court_document`. **If a sequential scan appears, STOP — do not run P3/P4 — and escalate to a DBA.** | Must |
| **FR-008** | **Run and export every P3 and P4 file.** Each result is saved with its header row as `results/<same file name>.csv` — the name must match the SQL file exactly, because `report` reads `manifest.csv` to find them. The generated SQL must not be hand-edited: `SET statement_timeout` and `AS MATERIALIZED` must stay. If a change is needed, regenerate. A `canceling statement due to statement timeout` is handled by regenerating with a smaller `--max-cases`, not by editing the file or removing the timeout. | Must |
| **FR-009** | **Build the report with the tool.** `IdpcLoad report --work <work>`. The run is complete only when the tool builds the report without reporting missing result files; a partial report assembled by hand is not acceptable. | Must |
| **FR-010** | **Produce the three counts-only artefacts.** `report/report.md`, `report/report-summary.csv` and `report/report-daily.csv`. `report.md` carries, per scope: cases listed, with IDPC, coverage %, IDPC docs, uploaded, ingestible, active days, **IDPCs / active day** (the load figure), and peak day with its ingestible count. `report-summary.csv` adds `idpcs_per_calendar_day` and splits the peak day into date and count. `report-daily.csv` gives one row per scope per day for charting and capacity sizing. | Must |
| **FR-011** | **Report the findings, not just the numbers.** When handing back: (a) quote **IDPCs / active day**, never a flat division by calendar days — courts do not receive IDPCs every day, and the calendar-day rate understates true load by roughly a third; (b) if **Uploaded ≠ Ingestible** for any scope, flag that courts are re-sending documents and quote both figures; (c) **never add individual court rows together** — a case listed at two courts is counted at both — use the `ALL COURTS` row for any combined total; (d) report a markedly **low coverage** figure for any court as a finding to investigate, not something to explain away; (e) state that **Combined** courts (Crown plus magistrates' work) are not directly comparable with magistrates-only courts; (f) always state the **window**, and that one window is a sample, not a baseline. | Must |
| **FR-012** | **Share only the counts-only artefacts.** `report/*` may be shared (Jira, Confluence, Slack). `results/P3-*`, `results/P4-*`, `results/H1-*`, `hearing/H*.sql`, `manifest.csv` and `window.properties` are also counts/parameters only and may be shared. **`case_court_map.csv` and `progression/P*.sql` contain case ids** and must never be committed, pasted, attached, emailed or uploaded anywhere — private work folder and the operator's own SQL client only. No query may be extended to select a court reference number, defendant field, or document `payload`. | Must |
| **FR-013** | **Destroy the case-id-bearing intermediates once the report is built.** Copy `report/` out to a retained location, then securely erase every `*.sql` and `case_court_map.csv` in the work folder and remove the folder (HOWTO Step 8). The work folder must not survive the run. | Must |
| **FR-014** | **Record the outcome on DD-43712 — counts only.** Post a summary to the ticket containing the window, the courts measured (by name and the counts-level figures), the load figure per court, and any findings per FR-011. No case data, case ids or court reference numbers may appear in the ticket, its comments, or any attachment. | Must |
| **FR-015** | **One window is a sample.** If ongoing load monitoring is wanted, re-run this ticket's steps for each new window. A single result must not be published or reused as a standing baseline. | Should |

---

## Non-functional requirements

| ID | Category | Requirement | Threshold / measure |
|---|---|---|---|
| **NFR-001** | Production safety | Nothing in production is written or changed. Only `SET statement_timeout`, `SELECT` and `EXPLAIN` statements are executed, with the operator's own **read-only** credentials against the Hearing and Progression databases. | Zero write statements; read-only credentials only |
| **NFR-002** | Production safety | Every query executed is bounded by `SET statement_timeout = '180s'`, and the plan probe (FR-007) is run first. `court_document` (≈87 GB / ≈48.9M rows) is never sequentially scanned. | 180 s per statement; zero `Seq Scan on court_document` |
| **NFR-003** | Data protection | Case ids exist only in `case_court_map.csv` and `progression/P*.sql`, only inside a work folder outside any git repository, with owner-only file permissions, and only until FR-013's clean-up. Zero case ids, court reference numbers (`caseurn`), defendant fields or document payloads in any shared artefact, ticket, comment, commit, log or chat message. | Zero tolerance |
| **NFR-004** | Data protection | The tool holds no database credential and opens no network connection; the operator supplies credentials to their own SQL client only. No connection string, password or token is written to the work folder, the repo, or any ticket. | Zero credentials in any artefact |
| **NFR-005** | Data minimisation (UK GDPR / DPA 2018) | Only the fields needed to produce counts are selected. No personal data is extracted, stored or shared; the retained output is aggregate counts with no re-identification path back to a case or an individual. | Counts only in retained artefacts |
| **NFR-006** | Reproducibility | A run is reproducible from the recorded inputs alone: confirmed `court_centre_id` values, the inclusive window, and the tool version on this branch. `window.properties` and `manifest.csv` capture the parameters; both are shareable. | Re-running with the same inputs yields the same report, data drift aside |
| **NFR-007** | Performance / effort | A run completes in roughly 10 minutes of operator time once familiar (about 30 minutes first time). Predicted query time per file is `Execution Time × (cases in file ÷ probe cases)`, baseline ≈375 ms per 10 cases. A few hundred to ~1,000 cases per court per 30 days is the expected order of magnitude. | Per-file execution inside the 180 s timeout |
| **NFR-008** | Accessibility | The shared artefacts are plain Markdown and CSV — readable with a screen reader, no colour-only encoding of meaning, no image-only data. WCAG 2.1 AA applies to any downstream UI that renders these figures, not to the CSVs themselves. | No information conveyed by colour or image alone |

---

## Acceptance criteria

Derived from — and deliberately preserving — DD-43712's own checklist, restated as measurable,
checkable statements. Each maps to the FR it evidences.

**FR-001 — request recorded**
- **AC-001:** Given DD-43712 is picked up, when the run starts, then the ticket shows all three template fields filled: court(s) requested, the inclusive date window in `YYYY-MM-DD` form, and requested by / for. Given any one is blank, then the run does not start (OQ-001 – OQ-003).

**FR-002 — measurement definitions**
- **AC-002:** Given the tool is about to be used, when `IDPC_DOCUMENT_TYPE_ID` is compared with `progression.doc-type-id` in `src/main/resources/application-clients.yml`, then the two values are identical. Given they differ, then the run halts and the discrepancy is raised — a mismatch produces an all-zero report (HOWTO §9).
- **AC-003:** Given the report is produced, when its definitions are read, then withdrawn documents are excluded, days are `Europe/London` dates, and *Uploaded* and *Ingestible* carry the meanings in FR-002 — with no locally invented variant.

**FR-003 — court id confirmation** *(ticket checklist item 1)*
- **AC-004:** Given one or more court names from the requester, when HOWTO Step 1 is run against the Hearing database, then a `court_centre_id` is obtained from `ha_hearing` for **every** named court, and the id, the exact `court_centre_name` it came from, and its `latest_hearing` date are recorded on the ticket before Step 2.
- **AC-005:** Given a name search returns more than one row, when the ids are selected, then every false match, regional grouping and Combined-versus-magistrates ambiguity is explicitly resolved and the resolution recorded — a single-row result is not assumed.
- **AC-006:** Given a court has been measured in a previous run, when this run starts, then its id is **re-confirmed** against the Hearing database rather than copied from the HOWTO's known-ids table or a prior ticket.
- **AC-007:** Given `latest_hearing` for a named court is not recent, when the report is handed back, then that is stated as a finding ("no current load") rather than presented as a zero or low measurement without explanation.

**FR-004 – FR-009 — the pipeline is run in full** *(ticket checklist item 2)*
- **AC-008:** Given confirmed court ids and the agreed window, when `IdpcLoad hearing` is run, then `window.properties`, `hearing/H1-listing-volume.sql` and `hearing/H2-case-court-map.sql` are written to a work folder **outside** any git working tree.
- **AC-009:** Given the window exceeds 62 days, when the tool emits its `WARN`, then the window is either split into shorter runs or the decision to proceed is recorded on the ticket with a reason — the warning is not silently ignored.
- **AC-010:** Given H2 is run on the Hearing database, when the result is exported, then `case_court_map.csv` exists in the work folder root with a header row and the columns `case_id`, `court_centre_id`, `court_centre_name`, and `IdpcLoad progression` parses it without a `is not a UUID` or `has no 'case_id' column` error.
- **AC-011:** Given `IdpcLoad progression` emits `WARN: court … has no cases`, when that happens, then the cause is resolved (wrong id, or no hearings in the window) before P1 is run — the court is not allowed to drop silently out of the report.
- **AC-012:** Given `P1-check-case-ids.sql` is run, when its result is read, then `cases_found_in_progression` is greater than 0 for **every** court and `documents_to_fetch` is in the thousands-to-tens-of-thousands range. Given either STOP condition is hit, then P3/P4 are not run.
- **AC-013:** Given `P2-probe-plan.sql` is run without CSV export, when the plan is read on screen, then it contains both `court_document_index_prosecution_case_id_idx` and `Index Scan using court_document_primary_key` and does **not** contain `Seq Scan on court_document`. Given a sequential scan appears, then P3/P4 are not run and a DBA is engaged.
- **AC-014:** Given every `P3-*.sql` and `P4-*.sql` listed in `manifest.csv` — **including every `-partN` file** — when each is run and exported, then a matching `results/<same name>.csv` with a header row exists for each, and `IdpcLoad report` completes without a `Missing result files` error.
- **AC-015:** Given the generated SQL, when it is executed, then it is byte-identical to what the tool wrote — `SET statement_timeout` and `AS MATERIALIZED` present and unmodified. Given a change is needed, then the files are regenerated rather than edited.

**FR-010 — report content** *(ticket checklist item 3)*
- **AC-016:** Given `IdpcLoad report` completes, when `report/report.md` is opened, then it contains one row per scope with: cases listed, with IDPC, coverage %, IDPC docs, uploaded, ingestible, active days, **IDPCs / active day** in bold, and peak day with its ingestible count — and `report-summary.csv` and `report-daily.csv` are present alongside it.

**FR-006 / FR-011 — multi-court scopes** *(ticket checklist item 4)*
- **AC-017:** Given more than one court was requested, when the report is produced, then an `ALL COURTS (each case counted once)` row is present, and a `SHARED (listed at more than one court)` row is present whenever any case is listed at more than one court — and both are included in what is shared back, with the shared-case count stated.
- **AC-018:** Given a combined figure across courts is quoted, when it is sourced, then it comes from the `ALL COURTS` row. Given individual court rows, then they are **never** summed.
- **AC-019:** Given exactly one court was requested, when the report is produced, then the absence of `SHARED` and `ALL COURTS` rows is expected and is not treated as a failed run.

**FR-011 — how the numbers are quoted** *(ticket checklist items 5, 6, 7)*
- **AC-020:** Given the load figure is quoted to the requester, when it is stated, then it is **IDPCs / active day** (ingestible ÷ days on which IDPCs arrived). Given `idpcs_per_calendar_day` is mentioned at all, then it is explicitly labelled as the understated calendar-day rate, not the load figure.
- **AC-021:** Given any scope where **Uploaded ≠ Ingestible**, when the report is handed back, then re-uploads/re-sends are flagged explicitly and **both** figures are quoted. Given all scopes are equal, then "no re-uploads in this window" is stated.
- **AC-022:** Given a coverage figure markedly lower than the other courts in the same run, when the report is handed back, then it is called out as an open finding for investigation, with no explanation offered unless it can be evidenced.
- **AC-023:** Given a Combined court appears alongside magistrates-only courts, when figures are compared, then the non-comparability is stated.
- **AC-024:** Given any figure is quoted anywhere, when it is quoted, then the inclusive window is stated with it, together with the caveat that one window is a sample, not a baseline (FR-015).

**FR-012 — data protection** *(ticket checklist item 8)*
- **AC-025:** Given the run is handed back, when the shared artefacts are listed, then they are exactly `report.md`, `report-summary.csv` and `report-daily.csv` (optionally plus the counts-only `results/*` and the parameter files). `case_court_map.csv` and `progression/P*.sql` appear nowhere outside the private work folder.
- **AC-026:** Given any artefact attached to or pasted into DD-43712, Confluence, Slack or a commit, when it is inspected, then it contains **zero** case ids, `caseurn` values, court reference numbers, defendant fields or document payloads.
- **AC-027:** Given `git status` after the run, when it is inspected, then no file containing case ids is tracked, staged or present anywhere in the working tree.

**FR-013 — clean-up** *(ticket checklist item 9)*
- **AC-028:** Given the report has been copied out of the work folder, when clean-up completes, then every `*.sql` and `case_court_map.csv` in the work folder has been securely erased and the work folder no longer exists. Given the platform has no `shred` (OQ-005), then an equivalent secure-erase was used and the substitution is recorded.

**FR-014 — ticket record**
- **AC-029:** Given the run is complete, when DD-43712 is updated, then it carries a counts-only summary: window, courts measured, IDPCs / active day per court, peak day, the `ALL COURTS` figure where applicable, and any FR-011 findings — and no case-level identifier of any kind.

---

## Constraints

- **CDKS hard rule — no PII / case data / court reference numbers** in artefacts, prompts, logs or test fixtures (`CLAUDE.md`, `.claude/context/cdks-context.md` hard rule 1). This governs the entire task and is the reason the tool is built the way it is. **This requirements document itself contains no case data, case ids or court reference numbers.**
- **Read-only production access** to both the Hearing (`ha_*`) and Progression (`court_document*`) databases is a hard prerequisite (HOWTO §4). Without it the task cannot start.
- **Operational risk to a 87 GB table.** `court_document` holds ≈48.9M rows. The index-first access path, the `AS MATERIALIZED` fences, the 180 s timeout and the mandatory P2 plan probe are all load-bearing safety controls, not style.
- **Data Protection Act 2018 / UK GDPR** — data minimisation and retention apply to the intermediates: case ids are retained only for the duration of the run and are then destroyed (FR-013).
- **OFFICIAL-SENSITIVE** classification applies to everything derived from production case data, including the intermediate files. The counts-only report is the only artefact assessed as safe to share.
- **Tool prerequisites:** JDK 21+ (no Gradle/Maven), and a SQL client that can export a result as CSV **with a header row** (`psql` 12+, DBeaver, pgAdmin, DataGrip). Neither `psql` nor `shred` is present on a stock macOS machine (OQ-005).
- **Approaches already ruled out (HOWTO Appendix B) — do not retry:** filtering `court_document` by payload or date; taking court/date from Progression's `hearing` table; using `caseprogressiondetail`; filtering on `document_category`; seeding Progression with `hearing_id` (IDPCs are case-level, indexed by `prosecution_case_id`, so a hearing-id seed matches **zero** IDPCs); counting in the CDKS database (it has no case → court mapping and only holds what it already ingested); temp tables with `\copy`; and estimating from defendant counts (overstated by 2.6×–5.9×).
- **No CDKS service change is authorised by this ticket** — no API, schema, Flyway migration, configuration or runtime behaviour change.

---

## Out of scope

- **Any change to CDKS production code, API, schema, Flyway migrations, configuration or deployment.** This ticket runs a read-only measurement; it does not modify the service.
- **Any change to `IdpcLoad.java` or the HOWTO.** Both are already written and reviewed; this ticket is about *running* them. Defects or gaps found during the run (OQ-005 – OQ-009) are raised as follow-ups, not fixed inline.
- **Build integration for the tool** — adding it to a Gradle source set, PMD, JaCoCo, CodeQL or any test suite. It is an ad-hoc single-file program by design (OQ-009).
- **Automation or scheduling of the measurement.** There is no requirement for a recurring job, dashboard tile, alert or API endpoint exposing IDPC load. Repeat measurement means re-running the runbook (FR-015).
- **Ingesting the result into CDKS**, persisting it in the CDKS database, or surfacing it through any CDKS endpoint.
- **Capacity decisions taken from the numbers.** This ticket delivers the measurement; what is done with it (sizing, throttling, scheduler configuration) belongs to whoever requested it.
- **Explaining a low coverage figure.** FR-011/AC-022 require it to be *reported* as a finding; root-causing it is separate work.
- **Any analysis beyond IDPC counts** — other document types, defendant-level analysis, hearing outcomes, or cross-referencing with CDKS's own ingestion records.
- **The superseded material** named in HOWTO Appendix C (`ops/RUNBOOK-idpc-per-court.md`, `ops/generated/*`) and the alternative CDKS-ingested-only method (`ops/idpc-per-court-report.sh`). None of these exist on this branch (fact 20) and none are to be used.

---

## Scope decision (2026-10-08)

**OQ-010 is resolved: yes, this ticket/PR lands `ops/tools/IdpcLoad.java`, `ops/HOWTO-idpc-load-per-court.md`
and `ops/README.md` in the repo.** Decided directly by the requester, in this session, in preference
to the alternative reading of DD-43712 as "run one specific measurement now." Rationale: the brief is
explicitly a reusable template, not a one-off data request with known inputs — landing the
already-written, already dry-run-validated tool and runbook as a reviewable, repo-tracked capability
is a meaningful unit of work on its own, independent of any specific court/window/requester. DD-43712
is the vehicle for *that* landing. Running it for a named court is a separate activity each time,
tracked on whichever future ticket actually names a court and a window — it does not block this PR.

**Consequence for the open questions below:**
- **OQ-001 (which courts?), OQ-002 (which window?), OQ-003 (who asked?)** — **reclassified from
  BLOCKING to deferred.** These only need an answer at **measurement-run time**, not at
  **tool-landing time**. They remain open questions in general (DD-43712 is a template; a future
  run must still fill them in per FR-001/AC-001), but they do **not** block this PR, this branch, or
  Stage 1 approval for the scope actually being delivered here.
- **OQ-004, OQ-011, OQ-012, OQ-013** (Jira comment, report destination, H1 export, ongoing cadence) —
  all presuppose a run has happened or is about to. Also deferred to measurement-run time, not
  blocking this PR.
- **OQ-005** (operator prerequisites: `psql`/`shred` missing on macOS) — also a run-time concern, not
  a landing-time one. Deferred.
- **OQ-006, OQ-007, OQ-008, OQ-009** (undocumented `--allow-inside-git-repo` flag; git-guard not
  re-applied after `hearing`; three dangling HOWTO references; zero build/test coverage) — these
  **are** relevant to landing the tool, since they're properties of the code/docs being committed.
  Per the Out-of-scope section below ("any change to `IdpcLoad.java` or the HOWTO... is raised as a
  follow-up, not fixed inline" — both files are committed exactly as already dry-run-validated), none
  of these block this PR either. They are carried into the PR description as known, accepted
  findings for the human reviewer, consistent with how this repo's other PRs surface non-blocking
  review comments.

The open questions are kept below, unedited, as the record of what was actually asked and decided —
only their blocking status changes.

## Open questions

- **OQ-001 (which courts?) — deferred to measurement-run time, not blocking this PR.** The brief's "Court(s) requested" field is blank. The specific court centre name(s) to measure are unknown and must come from the requester verbatim; they then drive the FR-003 id confirmation. Not guessable. — Owner: requester · Due: before any future run starts.

- **OQ-002 (which window?) — deferred to measurement-run time, not blocking this PR.** The brief's "Date window" field is blank. The inclusive `--from`/`--to` dates are unknown. 30 days is recommended and above 62 days triggers the tool's warning, but the actual window is a requester decision (and may be constrained by what the requester is sizing for). — Owner: requester · Due: before any future run starts.

- **OQ-003 (who asked, and why?) — deferred to measurement-run time, not blocking this PR.** The brief's "Requested by / for" field is blank. Needed to attribute the result, to decide where the report is shared, and because the stated reason determines whether IDPCs / active day or the peak day is the figure that actually answers the question. — Owner: requester · Due: before any future run starts.

- **OQ-004 (Jira not reachable from this session).** DD-43712 was not fetched — no Atlassian/Jira MCP tool is available here — so this document is grounded solely in `00-input-brief.md` plus the two `ops/` files read on this branch, and **no Stage-1 summary comment has been posted to the ticket**. Confirm the brief is the complete and current ticket text, and post the Stage-1 summary manually. — Owner: requester · Due: before Stage 2.

- **OQ-005 (operator environment prerequisites unconfirmed).** Neither `psql` nor `shred` is present on this machine (fact 23); `shred` is GNU coreutils and ships with no macOS release, so HOWTO Step 8's clean-up command cannot run as written on a stock Mac. Confirm which SQL client the operator will use, and agree the sanctioned secure-erase equivalent for macOS before the run (AC-028 depends on it). Also confirm that read-only production credentials for **both** the Hearing and Progression databases are actually held — this is the hardest prerequisite in HOWTO §4 and the most likely thing to block the run on the day. — Owner: operator / platform · Due: before the run starts.

- **OQ-006 (undocumented `--allow-inside-git-repo` escape hatch).** The tool accepts a flag that disables the "work folder must be outside a git repo" guard (fact 19). It appears in neither the tool's own `USAGE` text nor the HOWTO, so an operator could use it without any of the surrounding warnings about case ids being committable. Confirm it must never be used for a production run, and whether it should be documented-with-a-warning or removed. — Owner: tool owner · Due: follow-up; non-blocking if the run uses the default work folder.

- **OQ-007 (git guard is not re-applied after the `hearing` step).** `guardOutsideGitRepo` is called only from `hearing` (fact 5). `progression` and `report` accept `--work` without re-checking, so a work folder that was moved inside a repository between steps would have `progression/P*.sql` — the files that contain case ids — written straight into a git working tree. In practice the guard holds transitively because `progression` requires a `window.properties` that only `hearing` writes, but that is a side effect, not a control. Confirm whether this is accepted as-is or raised as a follow-up hardening. — Owner: tool owner · Due: follow-up; non-blocking.

- **OQ-008 (HOWTO references three paths that do not exist on this branch).** `ops/generated/`, `ops/RUNBOOK-idpc-per-court.md` and `ops/idpc-per-court-report.sh` are all absent (fact 20). Two consequences: (a) HOWTO §10's housekeeping instruction — "`ops/generated/` holds case ids from the first Cheshire run and is **not** git-ignored; delete it or add it to `.gitignore` before any commit" — currently has nothing to act on, but `.gitignore` still has **no** `ops` entry (fact 21), so if that folder is ever recreated the warning is live again; (b) Appendix C's "superseded material" and "other method" rows point at files a reader cannot open. Decide whether to add a defensive `.gitignore` entry and whether to correct the dangling Appendix C references. — Owner: tool owner · Due: before `ops/` is committed (see OQ-010).

- **OQ-009 (tool has no build or quality-gate integration).** `IdpcLoad.java` is a single-file ad-hoc program outside every Gradle source set (fact 22), so PMD, JaCoCo and the test suites do not cover it, and it has no unit tests. This is a deliberate design choice (no build step, runs anywhere with a JDK), but it means a future edit to the SQL generation has no automated safety net — and the SQL it generates runs against production. Confirm this is accepted, and whether CodeQL and the secrets scanner pick the file up given it is not a compiled source set. — Owner: tech lead · Due: before `ops/` is committed.

- **OQ-010 (is committing `ops/` part of DD-43712?) — RESOLVED 2026-10-08, see "Scope decision" above.** `ops/` is currently untracked on this branch (fact 21). The brief says both files were "validated in an earlier dry run on a different branch … then carried onto this dedicated branch for proper SDLC treatment", which implies this ticket also lands them in the repo — but it never said so explicitly, and the acceptance criteria describe only a measurement run. **Resolved: (b) — the report-generating capability (`ops/tools/IdpcLoad.java` + `ops/HOWTO-idpc-load-per-court.md` + `ops/README.md`) is committed under this ticket**, decided directly by the requester. A code review (Stage 6 equivalent — `review-pr`) therefore does apply to this PR.

- **OQ-011 (where does the report go, and is it retained?).** FR-012 establishes what *may* be shared; it does not say where it *will* be. Confirm the destination (ticket comment, Confluence page, a shared drive) and whether the counts-only artefacts are retained beyond the ticket — the earlier Cheshire run was published as a PDF, which suggests a document-level deliverable may be expected again. Also confirm whether `report-daily.csv` is wanted at all, or whether `report.md` alone answers the question. — Owner: requester · Due: before hand-back.

- **OQ-012 (is the optional H1 listing-volume export wanted?).** `H1-listing-volume.sql` gives hearings, cases and defendants per court per day. It is useful context but is **not** IDPC volume, and the prior run showed listing/defendant counts overstate IDPC load by 2.6×–5.9× — so including it risks the wrong number being quoted. Default recommendation: do not export or share it unless the requester specifically asks for listing context, and if shared, label it unambiguously. — Owner: requester · Due: before Step 3.

- **OQ-013 (ongoing monitoring?).** The brief notes that one window is a sample, not a baseline, and that ongoing monitoring means re-running these steps per window. Confirm whether this is genuinely a one-off, or whether a recurring cadence is expected — if the latter, the repeat cadence and who owns it should be agreed now, because nothing in this ticket automates it (and automating it is out of scope). — Owner: requester · Due: at hand-back.

---

## Stage gate

**Stage 1 (Requirements) — approved 2026-10-08, for the scope in "Scope decision" above: landing
`ops/` (tool + HOWTO + README) in the repo.** OQ-001/002/003 (court/window/requester) are no longer
blocking this PR — they were the ticket's own unfilled template fields for *running* a measurement,
and this PR does not run one. OQ-010 (whether `ops/` is committed under this ticket) is resolved: yes.

Given that scope, the conventional Stage 2–5 artefacts (Architecture & Design, User Story, Test
Specs, a `hmcts-sdlc-orchestrator:implementation` pass) do not apply: there is no CDKS architecture
change, no story to split beyond "land these three files," and no test suite to extend (`IdpcLoad.java`
is deliberately outside every Gradle source set — see OQ-009, carried forward as a non-blocking
finding, not fixed here). This ticket proceeds straight to **Stage 6 (Code Review)** on the PR itself,
then **Stage 7 (Build & Test)** — the existing CDKS build/test suite, to confirm landing an untracked,
non-source-set folder changes nothing about the build — before merge to `main`.
