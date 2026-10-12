# How to measure IDPC load for any court centre

| | |
|---|---|
| **Answers** | "How many IDPCs does court X send per day, how many cases have one, and what is the peak day?" |
| **Audience** | Any CDKS developer with read-only production database access |
| **Tool** | `ops/tools/IdpcLoad.java`, a single Java file that runs on any JDK 21+ with no build step |
| **Time** | About 30 minutes the first time, then about 10 minutes per run |
| **Risk** | Read-only `SELECT`s only. Nothing in production is written or changed. |

---

## Contents

1. [What you get](#1-what-you-get)
2. [How it works](#2-how-it-works)
3. [Safety guarantees](#3-safety-guarantees)
4. [Before you start](#4-before-you-start)
5. [Quick start](#5-quick-start-one-screen)
6. [Step-by-step](#6-step-by-step)
   - [Step 1: Find the court centre ids](#step-1--find-the-court-centre-ids-hearing-db)
   - [Step 2: Generate the Hearing SQL](#step-2--generate-the-hearing-sql-laptop)
   - [Step 3: Run H2 and export the case → court map](#step-3--run-h2-and-export-the-case--court-map-hearing-db)
   - [Step 4: Generate the Progression SQL](#step-4--generate-the-progression-sql-laptop)
   - [Step 5: Safety checks](#step-5--safety-checks-progression-db)
   - [Step 6: Run P3/P4 and export the results](#step-6--run-p3p4-and-export-the-results-progression-db)
   - [Step 7: Build the report](#step-7--build-the-report-laptop)
   - [Step 8: Clean up](#step-8--clean-up)
7. [Reading the report](#7-reading-the-report)
8. [Worked example: Cheshire, Aug–Sep 2026](#8-worked-example--cheshire-augsep-2026)
9. [Troubleshooting](#9-troubleshooting)
10. [Data protection](#10-data-protection)
11. [Appendix A: The SQL that runs in production](#appendix-a--the-sql-that-runs-in-production)
12. [Appendix B: Why it is built this way](#appendix-b--why-it-is-built-this-way)
13. [Appendix C: Reference](#appendix-c--reference)

---

## 1. What you get

For any set of court centres and any date window, you get `report.md`, `report-summary.csv` and `report-daily.csv`.
They look like this:

| Scope | Cases listed | With IDPC | Coverage | IDPC docs | Uploaded | Ingestible | Active days | **IDPCs / active day** | Peak day |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Chester Magistrates' Court | 681 | 192 | 28.2% | 284 | 284 | 284 | 25 | **11.4** | … |
| _ALL COURTS (each case counted once)_ | … | … | … | … | … | … | … | … | … |

The report contains **counts only**, with no case ids, so it is safe to share in Jira, Confluence or Slack.

> An **IDPC** is a Progression court document with `documentTypeId = 41be14e8-9df5-4b08-80b0-1e670bc80a5b`.
> This is the same value CDKS uses (`src/main/resources/application-clients.yml:31`). Withdrawn documents are excluded.

---

## 2. How it works

No single database knows both **which court** heard a case and **which IDPCs** that case has. So you ask two
databases, and the Java tool joins the answers **offline on your laptop**:

```
   YOUR LAPTOP (IdpcLoad.java)            PRODUCTION (you run the SQL by hand)
   ───────────────────────────            ────────────────────────────────────
 ① hearing      ──writes──▶  H1, H2 .sql ──▶  HEARING DB ──export──▶ case_court_map.csv
                                                                              │
 ② progression  ◀──reads──────────────────────────────────────────────────────┘
                ──writes──▶  P1–P4 .sql ──▶  PROGRESSION DB ──export──▶ results/*.csv
                                                                              │
 ③ report       ◀──reads──────────────────────────────────────────────────────┘
                ──writes──▶  report.md, report-summary.csv, report-daily.csv
```

| Command | Reads | Writes |
|---|---|---|
| `hearing` | court ids and a date window from the command line | `hearing/H1-listing-volume.sql`, `hearing/H2-case-court-map.sql`, `window.properties` |
| `progression` | `case_court_map.csv` (your H2 export) | `progression/P1…P4-*.sql`, `progression/manifest.csv` |
| `report` | `results/*.csv` (your P3/P4 exports) | `report/report.md`, `report-summary.csv`, `report-daily.csv` |

---

## 3. Safety guarantees

1. **The tool cannot connect to a database.** It contains no JDBC driver, no `java.sql` import and no networking code,
   and it has no option that accepts a connection string, host or password. Anyone can check this in five seconds:

   ```bash
   grep -nE "java\.sql|javax\.sql|jdbc|java\.net|HttpClient|Socket|ProcessBuilder|Runtime\.getRuntime" ops/tools/IdpcLoad.java
   # Only matches: a line of the class comment. No code.
   ```

   **You** run every query, with your own read-only credentials, in the client you already use.
2. **All SQL is read-only.** It contains only `SET statement_timeout`, `SELECT` and `EXPLAIN`. See [Appendix A](#appendix-a--the-sql-that-runs-in-production).
3. **Every query has a timeout.** Each file starts with `SET statement_timeout = '180s'`.
4. **The large table is never scanned.** `court_document` (87 GB) is only read by primary key. Step 5 proves this before any heavy query runs.
5. **Case ids stay private.**
   - The tool refuses to create its work folder inside a git repository.
   - Files that contain case ids are created readable by you only (mode `600`).
   - Every value read from a CSV is validated as a UUID before it is written into SQL, so a malformed export cannot inject SQL.

---

## 4. Before you start

- [ ] **JDK 21+** on your laptop: check with `java -version`. No Gradle or Maven is needed.
- [ ] A clone of this repo, for `ops/tools/IdpcLoad.java`
- [ ] **Read-only** access to the production **Hearing** DB (`ha_*` tables)
- [ ] **Read-only** access to the production **Progression** DB (`court_document*` tables)
- [ ] A SQL client that can **export a result as CSV with a header row**: `psql` 12+, DBeaver, pgAdmin or DataGrip

All commands below are run from the **repo root**.

---

## 5. Quick start (one screen)

```bash
# ① Generate the Hearing SQL (court ids from Step 1; dates are inclusive)
java ops/tools/IdpcLoad.java hearing \
     --courts 94bfe02b-c901-3d36-a676-24791a8bbe7b,1d477ab6-1434-3419-bfa1-daaf6b6e843a \
     --from 2026-08-24 --to 2026-09-22
WORK=~/idpc-work/2026-08-24_2026-09-22

# ② HEARING DB: export H2 as CSV
psql "$HEARING_DB" -q --csv -f $WORK/hearing/H2-case-court-map.sql > $WORK/case_court_map.csv

# ③ Generate the Progression SQL
java ops/tools/IdpcLoad.java progression --work $WORK

# ④ PROGRESSION DB: safety checks. Read the output, then STOP if anything looks wrong
psql "$PROGRESSION_DB" -q -f $WORK/progression/P1-check-case-ids.sql
psql "$PROGRESSION_DB" -q -f $WORK/progression/P2-probe-plan.sql

# ⑤ PROGRESSION DB: run every P3/P4 file, one CSV each
cd $WORK/progression
for f in P3-*.sql P4-*.sql; do
  psql "$PROGRESSION_DB" -q --csv -f "$f" > "../results/${f%.sql}.csv" || echo "FAILED: $f"
done
cd -

# ⑥ Build the report
java ops/tools/IdpcLoad.java report --work $WORK
```

The tool prints the next step each time it runs. The sections below explain each step in full.

---

## 6. Step-by-step

### Step 1 — Find the court centre ids (Hearing DB)

Court names vary in spelling and change over time, so the tool works only with **`court_centre_id`**.
Find the ids by running this in the **Hearing** DB, with your own search terms:

```sql
SET statement_timeout = '180s';

SELECT h.court_centre_id,
       h.court_centre_name,
       COUNT(DISTINCT h.id) AS hearings,
       MIN(hd.date)         AS earliest_hearing,
       MAX(hd.date)         AS latest_hearing
FROM ha_hearing h
LEFT JOIN ha_hearing_day hd ON hd.hearing_id = h.id
WHERE h.court_centre_name ILIKE ANY (ARRAY['%leeds%', '%bradford%'])   -- ← your search terms
GROUP BY h.court_centre_id, h.court_centre_name
ORDER BY h.court_centre_name;
```

- Check for **false matches**. For example, `%chester%` also matches *Manchester*.
- `latest_hearing` should be recent. If it isn't, the court has no current load.
- Copy the `court_centre_id` values you want.

**Known ids** (confirmed September 2026):

| Court | `court_centre_id` |
|---|---|
| Chester Magistrates' Court | `94bfe02b-c901-3d36-a676-24791a8bbe7b` |
| Crewe Magistrates' Court | `1d477ab6-1434-3419-bfa1-daaf6b6e843a` |
| Warrington Combined Court | `cb2ff1e3-e68e-3037-a563-1a0fcc165273` |

### Step 2 — Generate the Hearing SQL (laptop)

```bash
java ops/tools/IdpcLoad.java hearing \
     --courts <court-id-1>,<court-id-2>,<court-id-3> \
     --from 2026-08-24 --to 2026-09-22
```

| Option | Meaning |
|---|---|
| `--courts` | Comma-separated `court_centre_id` values. One court is fine. |
| `--from`, `--to` | Inclusive window, `YYYY-MM-DD`. It is used both for "cases listed" and for "IDPCs uploaded". 30 days is recommended. The tool warns above 62 days. |
| `--work` | Optional. Defaults to `~/idpc-work/<from>_<to>`. Must be outside any git repo. |

This writes:

```
~/idpc-work/2026-08-24_2026-09-22/
├── window.properties          ← the window and courts; later steps read this
├── hearing/
│   ├── H1-listing-volume.sql  ← optional context; result is counts only
│   └── H2-case-court-map.sql  ← required
└── results/                   ← you will save exports here
```

### Step 3 — Run H2 and export the case → court map (Hearing DB)

Run `hearing/H2-case-court-map.sql` on the **Hearing** DB. Save the result **with its header row** as
**`case_court_map.csv` in the work folder root**.

**psql**

```bash
psql "$HEARING_DB" -q --csv -f $WORK/hearing/H2-case-court-map.sql > $WORK/case_court_map.csv
```

**DBeaver / DataGrip / pgAdmin**

1. Open `H2-case-court-map.sql` and execute it as a script (DBeaver: **Alt+X**).
2. Select the result grid, then choose **Export data → CSV**. Keep the delimiter `,`, keep the **header row on**, and keep the encoding UTF-8.
3. Save it as `case_court_map.csv` in the work folder.

**Check the export:**

```bash
head -1 $WORK/case_court_map.csv                        # case_id,court_centre_id,court_centre_name
cut -d, -f2 $WORK/case_court_map.csv | sort | uniq -c   # rows per court
```

A few hundred to about 1,000 cases per court per 30 days is normal.

*Optional:* also run `H1-listing-volume.sql` and export it to `results/H1-listing-volume.csv`. It gives hearings,
cases and defendants per court per day. This is useful context, but it is **not** IDPC volume (see §8).

### Step 4 — Generate the Progression SQL (laptop)

```bash
java ops/tools/IdpcLoad.java progression --work $WORK
```

Example output:

```
Read 1983 rows: 1894 distinct cases, 89 listed at more than one court.

  Chester Magistrates' Court                       681 cases  (~26s)
  Crewe Magistrates' Court                         478 cases  (~18s)
  Warrington Combined Court                        824 cases  (~31s)
  SHARED (listed at more than one court)            89 cases  (~3s)
  ALL COURTS (each case counted once)             1894 cases  (~71s)
```

This writes to `progression/`:

| File | Purpose | Export result? |
|---|---|---|
| `P1-check-case-ids.sql` | Safety check: ids found, and documents that will be read (index only) | Optional |
| `P2-probe-plan.sql` | Safety check: `EXPLAIN ANALYZE` on 10 cases | **No.** Read it on screen. |
| `P3-summary-<scope>.sql` | Totals for one scope | **Yes** → `results/P3-summary-<scope>.csv` |
| `P4-daily-<scope>.sql` | Per-day counts for one scope | **Yes** → `results/P4-daily-<scope>.csv` |
| `manifest.csv` | The list of expected results; `report` reads it | n/a |

There is one P3 and one P4 for **each court**, plus `zz-shared-cases` (when there is more than one court and they share
cases) and `zz-all-courts-deduplicated` (when there is more than one court).

A scope with more than 3,000 cases is split into `-part1`, `-part2` and so on, so that each file stays inside the timeout.
**Export every part.** The report adds them together. Use `--max-cases 1500` for smaller parts.

### Step 5 — Safety checks (Progression DB)

`court_document` is 87 GB. **Always run these two checks before P3/P4.**

**5a. `P1-check-case-ids.sql`** (index only, a few seconds)

| Column | Expected | STOP if |
|---|---|---|
| `cases_found_in_progression` | Close to `cases_listed` | **0** for any court. The ids don't line up; see §9. |
| `documents_to_fetch` | Thousands to tens of thousands | **Hundreds of thousands** for one court. Shorten the window. |

**5b. `P2-probe-plan.sql`** (runs the real query on 10 cases and shows the plan)

Run it **without** `--csv`, and read the plan:

| The plan **must** contain | The plan must **not** contain |
|---|---|
| `court_document_index_prosecution_case_id_idx` | `Seq Scan on court_document` |
| `Index Scan using court_document_primary_key` | |

**If you see `Seq Scan on court_document`, STOP. Do not run P3/P4.** Ask a DBA to look at the plan.

**Predicted time per file** = `Execution Time` × (cases in file ÷ 10). The baseline is about 375 ms per 10 cases, so
roughly 26 s for 700 cases.

### Step 6 — Run P3/P4 and export the results (Progression DB)

Run **every** `P3-*.sql` and `P4-*.sql` on the **Progression** DB. Save each result, with its header row, as
`results/<same file name>.csv`.

**psql** (runs them all)

```bash
cd $WORK/progression
for f in P3-*.sql P4-*.sql; do
  echo "running $f"
  psql "$PROGRESSION_DB" -q --csv -f "$f" > "../results/${f%.sql}.csv" || echo "FAILED: $f"
done
cd -
```

**DBeaver / DataGrip / pgAdmin:** open each file, execute it as a script, and export the grid to CSV with a header. The name
must match: `P3-summary-chester-magistrates-court.sql` → `results/P3-summary-chester-magistrates-court.csv`.

> Don't edit the generated SQL. Keep `SET statement_timeout` and `AS MATERIALIZED`.
> If you need a change, regenerate the files.

### Step 7 — Build the report (laptop)

```bash
java ops/tools/IdpcLoad.java report --work $WORK
```

The tool checks that **every** result listed in `manifest.csv` is present. It adds split parts together and writes:

```
report/report.md            ← paste into Jira / Confluence
report/report-summary.csv   ← one row per scope (adds idpcs_per_calendar_day and the peak day)
report/report-daily.csv     ← one row per scope per day, for charts and capacity sizing
```

### Step 8 — Clean up

```bash
# Keep the shareable outputs, then destroy everything that holds case ids
cp -r $WORK/report ~/idpc-reports-$(date +%F)
find $WORK -type f \( -name '*.sql' -o -name 'case_court_map.csv' \) -exec shred -u {} +
rm -rf $WORK
```

---

## 7. Reading the report

| Column | Meaning |
|---|---|
| **Cases listed** | Cases before the court in the window (from the Hearing DB) |
| **With IDPC** | Of those, cases that received at least one IDPC in the window |
| **Coverage** | With IDPC ÷ Cases listed |
| **IDPC docs** | Distinct IDPC documents |
| **Uploaded** | Every material upload, re-uploads included. This is what the court sends. |
| **Ingestible** | The latest material per document only. This is what CDKS keeps, because `ProgressionDtoMapper` keeps the newest by `uploadDateTime`. |
| **Active days** | Days on which at least one IDPC was uploaded (UK dates) |
| **IDPCs / active day** | Ingestible ÷ Active days. **This is the load figure.** |
| **Peak day** | The busiest single day and its ingestible count. **Size capacity from this.** |

**Rules for quoting the numbers:**

1. Quote **IDPCs / active day**, not ÷ 30. Courts don't receive IDPCs every day, so the calendar-day rate (in the CSV) understates load by about a third.
2. If **Uploaded ≠ Ingestible**, courts are re-sending documents. Quote both. The report flags this automatically.
3. **Never add court rows together.** A case listed at two courts counts at both. Use the **ALL COURTS** row for a combined total.
4. A markedly **low coverage** figure is a finding to investigate, not something to explain away.
5. **Combined** courts (Crown and magistrates' work) are not directly comparable with magistrates-only courts.
6. Always state the **window**. One 30-day window is a sample, not a baseline.

---

## 8. Worked example — Cheshire, Aug–Sep 2026

Window 24 Aug – 22 Sep 2026. Published as `IDPC-Volume-By-Court.pdf`. (These figures were produced with the earlier
hand-built version of the same SQL, `ops/generated/B1–B4*.sql`.)

| Court | Cases listed | With IDPC | Coverage | IDPC docs | Active days | **IDPCs / active day** | Old estimate (defendants) |
|---|---:|---:|---:|---:|---:|---:|---:|
| Chester Magistrates' | 681 | 192 | 28% | 284 | 25 | **11.4** | 34.5 (3.0× too high) |
| Crewe Magistrates' | 478 | 65 | 14% | 74 | 20 | **3.7** | 21.7 (5.9× too high) |
| Warrington Combined | 824 | 301 | 37% | 350 | 25 | **14.0** | 37.1 (2.6× too high) |

Findings:
- Using defendant or listing counts as a stand-in for IDPC load overstated it by **2.6–5.9×**. Always measure.
- Uploaded = Ingestible at all three courts, so there were no re-uploads.
- 89 of 1,894 cases (4.7%) were listed at more than one court.
- Crewe's coverage is under half that of the other two courts. This is still unexplained.

---

## 9. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Work folder … is inside the git repository` | `--work` points into a repo | Use the default (`~/idpc-work/…`) or any folder outside the repo |
| `window.properties not found` | `progression`/`report` was run before `hearing`, or with a different `--work` | Use the same `--work` path the `hearing` step printed |
| `… is not a UUID` | The export has extra lines (psql notices, `SET`, row counts) or a changed column | Use `psql -q --csv`, or re-export the grid with a header and nothing else |
| `has no 'case_id' column` | Exported without a header, or columns were renamed | Re-export **with** the header; don't rename columns |
| `WARN: court … has no cases` | Wrong id, or no hearings in the window | Recheck Step 1 (`latest_hearing`) |
| P1: `cases_found_in_progression = 0` | The ids don't match Progression | Confirm H2 was unchanged: it must export `ha_case.id`, **not** hearing ids |
| All counts are 0 but P1 found the cases | The IDPC type id has changed | Compare `doc-type-id` in `application-clients.yml` with `IDPC_DOCUMENT_TYPE_ID` in the tool |
| P2 shows `Seq Scan on court_document` | Stale statistics, or an edited file | Regenerate; don't remove `AS MATERIALIZED`; ask a DBA |
| `canceling statement due to statement timeout` | The file is too large | Regenerate with `--max-cases 1500` (or smaller), then re-run and re-export |
| `Missing result files` from `report` | Some P3/P4 results were not exported, or were misnamed | Export exactly `results/<sql name>.csv` for every file listed |
| `invalid input syntax for type timestamp` | An unusual `uploadDateTime` value | Report it with the file name; the query's format guard should skip such values |

---

## 10. Data protection

This follows the CLAUDE.md hard rule: no case data in artefacts.

| Artefact | Contains case ids? | Allowed destination |
|---|---|---|
| `case_court_map.csv` | **Yes** | Your private work folder only. Shred it after use. |
| `progression/P*.sql` | **Yes** | Your private work folder, then your SQL client. **Never** commit, paste, attach or email them. |
| `hearing/H*.sql`, `manifest.csv`, `window.properties` | No (court ids and dates only) | Can be shared |
| `results/P3-*`, `P4-*`, `H1-*` | No (counts only) | Can be shared |
| `report/*` | No (counts only) | Can be shared |

- **Never** add `caseurn` (a court reference number), defendant fields or `payload` to any query.
- **Housekeeping:** `ops/generated/` in this repo holds case ids from the first Cheshire run and is **not git-ignored**.
  Delete it, or add `ops/generated/` to `.gitignore`, before any commit.

---

## Appendix A — The SQL that runs in production

This is exactly what the tool generates. `<…>` marks the values it fills in. Share this appendix with a DBA if you
need approval to run the queries.

### H2 — case → court map (Hearing DB)

```sql
SET statement_timeout = '180s';

SELECT DISTINCT c.id              AS case_id,
                h.court_centre_id,
                h.court_centre_name
FROM ha_hearing h
JOIN ha_case c         ON c.hearing_id  = h.id
JOIN ha_hearing_day hd ON hd.hearing_id = h.id
WHERE h.court_centre_id IN ('<court-id>', ...)
  AND hd.date BETWEEN DATE '<from>' AND DATE '<to>'
  AND COALESCE(hd.is_cancelled, false) = false
ORDER BY 3, 1;
```

`H1-listing-volume.sql` uses the same filter, plus `ha_defendant_case`, and returns counts per day per court.

### P3 — summary (Progression DB)

```sql
SET statement_timeout = '180s';

WITH seed AS (
  SELECT unnest(ARRAY[ '<case-id>', ... ]::uuid[]) AS case_id
),
-- AS MATERIALIZED is REQUIRED: it stops the planner pushing the payload regex
-- down into a sequential scan of court_document (87 GB). Do not remove it.
docs AS MATERIALIZED (                           -- index only
    SELECT DISTINCT s.case_id, cdi.court_document_id
    FROM seed s
    JOIN court_document_index cdi ON cdi.prosecution_case_id = s.case_id
),
fetched AS MATERIALIZED (                        -- primary-key lookups only
    SELECT d.case_id, cd.id AS court_document_id, cd.payload, cd.is_removed
    FROM docs d
    JOIN court_document cd ON cd.id = d.court_document_id
),
idpc AS (
    SELECT f.case_id, f.court_document_id, f.payload
    FROM fetched f
    WHERE f.is_removed = false
      AND f.payload ~ '"documentTypeId"\s*:\s*"41be14e8-9df5-4b08-80b0-1e670bc80a5b"'
),
raw AS (                                         -- one row per material upload
    SELECT i.case_id, i.court_document_id,
           (regexp_matches(i.payload, '"uploadDateTime"\s*:\s*"([^"]{10,40})"', 'g'))[1] AS ts_text
    FROM idpc i
),
ranked AS (                                      -- rn = 1 is the latest material per document
    SELECT r.case_id, r.court_document_id,
           (r.ts_text::timestamptz AT TIME ZONE 'Europe/London')::date AS upload_day,
           ROW_NUMBER() OVER (PARTITION BY r.court_document_id
                              ORDER BY r.ts_text::timestamptz DESC) AS rn
    FROM raw r
    WHERE r.ts_text ~ '^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}'
)
SELECT '<scope>'::text                         AS scope,
       <part>                                  AS part,
       <n>                                     AS cases_in_file,
       COUNT(DISTINCT rk.case_id)              AS cases_with_idpc,
       COUNT(DISTINCT rk.court_document_id)    AS idpc_documents,
       COUNT(*)                                AS idpcs_uploaded,
       COUNT(*) FILTER (WHERE rk.rn = 1)       AS idpcs_ingestible,
       COUNT(DISTINCT rk.upload_day)           AS days_with_idpcs
FROM ranked rk
WHERE rk.upload_day BETWEEN DATE '<from>' AND DATE '<to>';
```

### P4 — per day (Progression DB)

This uses the same CTEs as P3, with this final `SELECT`:

```sql
SELECT rk.upload_day                       AS day,
       '<scope>'::text                     AS scope,
       COUNT(*)                            AS idpcs_uploaded,
       COUNT(*) FILTER (WHERE rk.rn = 1)   AS idpcs_ingestible,
       COUNT(DISTINCT rk.case_id)          AS cases
FROM ranked rk
WHERE rk.upload_day BETWEEN DATE '<from>' AND DATE '<to>'
GROUP BY 1, 2
ORDER BY 1;
```

### P1 — id check and read volume (Progression DB, index only)

```sql
WITH seed AS (
  SELECT '<court name>'::text AS scope, unnest(ARRAY[ '<case-id>', ... ]::uuid[]) AS case_id
  UNION ALL ...
)
SELECT s.scope,
       COUNT(DISTINCT s.case_id)               AS cases_listed,
       COUNT(DISTINCT cdi.prosecution_case_id) AS cases_found_in_progression,
       COUNT(DISTINCT cdi.court_document_id)   AS documents_to_fetch
FROM seed s
LEFT JOIN court_document_index cdi ON cdi.prosecution_case_id = s.case_id
GROUP BY s.scope ORDER BY s.scope;
```

### P2 — plan probe

This is `EXPLAIN (ANALYZE, BUFFERS)` of the P3 pipeline for the first 10 cases of the largest court.

---

## Appendix B — Why it is built this way

These shortcuts look tempting but were tried and ruled out in September 2026. Please don't retry them.

| Tempting shortcut | Why it fails |
|---|---|
| Filter `court_document` by payload or date | 48.9M rows / 87 GB. Only primary-key lookups are safe. |
| Take court + date from Progression's `hearing` table | 5.8M rows / 75 GB with no index on `confirmed_date`, so it needs a full scan |
| Use `caseprogressiondetail` for the court | It has only 105 rows |
| Filter on `document_category` | It holds a single value or NULL |
| **Seed Progression with `hearing_id`** | IDPCs are **case-level** documents indexed by `prosecution_case_id`, so a hearing-id seed matches **zero** IDPCs. This is why `ops/generated/01–07*.sql` found nothing. |
| Count in the CDKS database | CDKS has no case → court mapping, and it only holds what it has already ingested |
| Use a temp table and `\copy` in Progression | GUI clients reconnect and lose temp tables. Inline `ARRAY[...]` seeds work in every client. |
| Estimate from defendant counts | This overstated load by 2.6–5.9× (see §8) |

---

## Appendix C — Reference

| Item | Value |
|---|---|
| Tool | `ops/tools/IdpcLoad.java` (`java ops/tools/IdpcLoad.java help`) |
| IDPC `documentTypeId` | `41be14e8-9df5-4b08-80b0-1e670bc80a5b` (constant `IDPC_DOCUMENT_TYPE_ID`) |
| Day boundary | `Europe/London` (constant `COURT_TIME_ZONE`) |
| Statement timeout | `180s` (constant `STATEMENT_TIMEOUT`) |
| Default split size | 3,000 cases per file (`--max-cases`) |
| Hearing DB tables | `ha_hearing` (court), `ha_hearing_day` (date, `is_cancelled`), `ha_case` (case id), `ha_defendant_case` |
| Progression path | `court_document_index.prosecution_case_id` (indexed) → `court_document_id` → `court_document` PK → `payload` |
| Baseline speed | About 375 ms per 10 cases (September 2026) |
| Superseded material | `ops/RUNBOOK-idpc-per-court.md` (hearing-id seed), `ops/generated/*` (hand-built Cheshire run) |
| Other method (CDKS-ingested docs only, via the hearing API) | `ops/idpc-per-court-report.sh` |
