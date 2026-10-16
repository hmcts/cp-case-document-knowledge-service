# Nightly Discovery — Task Burst Analysis & Mitigation Options

> Jira: **DD-43823**
> Status: **Analysis / for discussion** (pre-decision — not an ADR yet)
> Service: case-document-knowledge-service (CDKS)
> Scope: the `isHearingForCasesEnabled = true` nightly discovery path
> Evidence: **NFT** environment — primary batch **2026-08-20** (306 cases), corroborated by **2026-10-15** (77 cases)
> Date: 2026-10-16

---

## 1. Problem statement

The nightly discovery scheduler dispatches one entry task per in-scope case, which then
**fans out** into a cascade of asynchronous tasks (retrieve → ingest-status → generate-answer →
check-status). For a busy night this can be thousands of tasks created in a short burst. We need to
understand:

- how large the burst actually is, and what drives it;
- what the real throughput ceiling is;
- whether, and how, to **throttle / stagger** the work so a large night completes before court
  opens without overloading downstream (RAG), and without starving user-initiated (manual) requests.

---

## 2. How the fan-out works (task model)

Entry point per case: `DiscoveryService.runNightlyDiscovery()` →
`dispatchCaseDocumentIngestionTasksCheckIdpcAvailability()` →
`CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS` (one per `uniqueCaseId`).

Each task, as its last act before completing, dispatches the next task(s) via
`executionService.executeWith(...)`. The cascade:

```
CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS            ×1  per caseId
  │  (IdpcAvailabilityService.retrieveDocuments → N new/changed IDPC docs, across ALL defendants)
  └─> RETRIEVE_MATERIAL_AND_UPLOAD                ×N  (one per new document)   [CheckIdpc…Task:88-101]
        │  (download from Progression, copy blob, save CaseDocument)
        └─> CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS   ×1 each → ×N total     [RetrieveMaterial…Task:138-145]
              │  polls RAG ingestion status; on INGESTION_SUCCESS fans out by query level:
              │
              ├─ DEFENDANT queries (D):   GENERATE_ANSWER_FOR_QUERY  ×D  per doc        [:170-187]  (every doc)
              ├─ CASE queries (C):        GENERATE_ANSWER_FOR_QUERY  ×C              [:122-141]  (only if isLatestDefendant)
              └─ CASE_ALL_DOCUMENTS (A):  CHECK_ALL_DOCUMENTS_INGESTION_STATUS ×1   [:143-168]  (only if isLatestDefendant)
                    │  waits until *all* docs for the case are INGESTED
                    └─> GENERATE_ANSWER_FOR_QUERY  ×A                               [CheckAllDocs…Task:68-80]

GENERATE_ANSWER_FOR_QUERY                                                            [GenerateAnswer…Task]
  │  starts async RAG answer generation
  └─> CHECK_STATUS_OF_ANSWER_GENERATION   ×1 each                                    [:103-110]
        │  polls RAG; persists the answer (CASE / CASE_ALL_DOCUMENTS / DEFENDANT / default)
        └─> on ANSWER_GENERATION_FAILED (retryCount < max):
              GENERATE_ANSWER_FOR_QUERY   ×1  (re-dispatch, bounded loop)            [CheckStatus…Task:164-175]
```

**Definitions**
- **N** = IDPC documents with a *new / not-yet-ingested* version this run (= defendants with a new
  IDPC; one doc per defendant). Already-ingested IDPCs are skipped.
- **K** = cases dispatched (`uniqueCaseIds.size()`).
- **d** = average IDPC documents per case (= N / K).
- Query mix (current production): **D = 6** (DEFENDANT), **C = 0** (CASE), **A = 4**
  (CASE_ALL_DOCUMENTS). `L` (latest-defendant docs per case) is structurally **1** — see
  `IdpcAvailabilityService.retrieveDocumentsScoped()` (the `defendantToDocIdMap` is keyed by
  `defendantId`, and `latestDefendantId` is the defendant whose new IDPC has the max `uploadDateTime`).

**Counts (happy path, current query mix)**

| Quantity | Formula | Per case at N=1 |
|---|---|---|
| Answers generated | `6·N + 4·K` | 10 |
| Task executions | `1 + 2N + L + 2·(N·D + L·(C+A))` → `14·N + 10·K` | 24 |

Answers are the meaningful unit because **RAG answer-generation is the bottleneck** (see §4).

---

## 3. Execution model (why the burst is self-limiting — but not the latency)

`executeWith()` does **not** run a task. It inserts a row into a **`jobs` table** (task-manager-service
library: `ExecutionService` → `JobService` → `JobsRepository.insertJob(..., int priority)`). A single
background poller, `JobExecutor.checkAndAssignJobs()`, wakes every `pollInterval`, claims up to
**`batchSize`** *unassigned* jobs whose **start-time ≤ now**, and submits them to a bounded
`ThreadPoolTaskExecutor`. Each job runs one task inside its own `TransactionTemplate`
(`TaskExecutor.run`).

**Consequences**
- A burst of thousands of `executeWith` calls creates thousands of **queue rows**, not thousands of
  concurrent executions. Concurrency is bounded by the pool.
- The nightly `@SchedulerLock` (PT2H) only covers the *dispatch loop* (enqueue K entry tasks + the
  hearing/whitelist HTTP calls). The cascade drains **after** and independent of the lock.
- The library already exposes the levers needed to stagger:

| Lever | Mechanism | Effect |
|---|---|---|
| **Priority** | `ExecutionInfo.withPriority(1..10)`, stored per job | claim order; 1 = highest. Manual ingestion already uses `HIGH=1`, nightly `DEFAULT=10` |
| **Deferred start** | `ExecutionInfo.withAssignedTaskStartTime(future)` | job invisible to the poller until its start-time (retries already use this for backoff) |
| **Drain rate** | poller `batchSize` + `pollInterval`; pool `core/max/queue` | how fast the backlog is claimed and how many run at once |

---

## 4. NFT data analysis (sample batch 2026-08-20)

### 4.1 Per-case shape
Sampled completed cases showed, consistently:
- `docs = 1`, `answer_rows = 10`, `max_version = 1` → exactly **6N+4 = 10** answers for N=1,
  generated **once** (no re-versioning). The analytical formula is confirmed against real data.
- **First** answer lands ~8–25 min after the IDPC-check placeholder; **last** answer ~104–109 min after.
- Within a batch, the **last answers of every case cluster at a single wall-clock instant**
  (e.g. all ~14:44–14:45). Each case's "duration" is therefore
  *(batch completion time) − (that case's enqueue time)* — i.e. **the batch draining through a bounded
  pipeline**, not per-case processing latency.

### 4.2 Sustained throughput (authoritative, whole batch)
Over the full batch window `12:56:35 → 14:45:40` (**109.1 min**):

| Metric | Value |
|---|---|
| Answers persisted | **3,060** |
| Sustained rate | **28.1 answers/min** |
| Implied cases (÷10) | **~306 cases** |
| Rate per case | **~2.8 cases/min** |

The head of the batch (12:56–13:22) ran at ~30/min; the **`CASE_ALL_DOCUMENTS` tail is slower**,
pulling the full-batch average down to **28/min**.

### 4.3 Burst pattern
Per-minute answer counts were **bursty, not a flat plateau**: peaks of 66–77/min interleaved with
**0-answer minutes**, while the thread pool (`max 20`) was not saturated. This signature indicates the
bottleneck is **RAG round-trip latency + poll/claim cadence**, *not* CPU/thread count:
`CHECK_STATUS` fires a RAG status call, backs off (`questions-retry` = 10 s), and a wave of answers
lands together when RAG responds and the poller claims the next batch.

> ⚠️ This is **NFT** RAG. The *shape* (bursty, batch-drain, slow tail) transfers to prod; the
> *absolute* 28/min does not. Re-measure against prod RAG before fixing any rate.

### 4.4 Corroboration — 2026-10-15 batch, and saturated vs. unsaturated rate

A more recent batch (**2026-10-15**, ad-hoc run starting ~12:19, **77 cases**, d=1) was checked to see
whether throughput had drifted after a ~7-week idle gap. Two rates matter:

| Rate | Value | Meaning |
|---|---|---|
| Span-inclusive (`total ÷ (max−min)`) | **3.9/min** over 196.6 min | **misleading** — the window was ~164 idle minutes |
| **Active-minute** (`total ÷ minutes with ≥1 answer`) | **24.1/min** over 32 active minutes (peak 42/min) | the real throughput |

So throughput is **stable over time (~24–28/min)**; the apparent 7× "drop" was entirely idle wall-clock,
not a slower pipeline.

**Key distinction for capacity planning:** the *span-inclusive* rate only equals true throughput when the
batch **saturates** the pipeline.
- **Saturated** (large batch, e.g. 306 cases): queue stays full, active minutes are back-to-back →
  span-rate ≈ active-rate ≈ **28/min**.
- **Unsaturated** (small batch, e.g. 77 cases): work finishes in a few dozen active minutes but trickles
  over hours — bursts separated by long poll/RAG waits. Span-rate collapses, but that is
  **latency-dominated idle time, not a throughput limit** (reinforces the RAG/poll-latency-bound finding).

For sizing a **large** night, use the **saturated/active rate ≈ 24–28 answers/min**. Ignore the
span-inclusive rate of small/unsaturated runs.

> Note: every NFT day sampled (2026-08-07 … 2026-10-15) shows **avg IDPCs/case = 1.0** — NFT contains
> only single-defendant/single-IDPC cases, so **d>1 behaviour is empirically untested**. The d≥2 rows in
> §5 are analytical projections; dedicated multi-defendant test data is needed to validate them.

---

## 5. Key findings

1. **The ceiling is ~24–28 answers/min (saturated), and it is RAG/poll-bound, not pool-bound.**
   Corroborated across two batches 7 weeks apart (28/min at 306 cases; 24/min active at 77 cases — see §4.4).
   → **Enlarging the thread pool will not make it faster.** (Removes "raise pool size" as a lever.)
2. **Throughput invariant:** plan in **answers/min**. Documents (IDPCs) and cases are derived:
   - answers per case = `6d + 4`; answers per IDPC = `6 + 4/d` (10 at d=1 → trending to 6 as d grows).
   - **IDPCs/min** = `28 / (6 + 4/d)` → **2.8 at d=1, ~3.5 at d=2, ~4.7 as d→∞**.
   - **cases/min** = `28 / (6d + 4)` → 2.8 at d=1, 1.75 at d=2, …
   - More defendants per case ⇒ *more IDPCs* but *fewer cases* fit per night (the case-level +4
     answers amortise across more documents).
3. **Nightly window capacity (NFT):** a ~6 h pre-court window (02:00→08:00) × 28/min ≈
   **~10,000 answers/night** ≈ **~1,000 IDPCs at d=1** (≈1,260 at d=2 …).
4. **Total completion time is not today's problem.** The observed ~306-case night used ~1/3 of the
   window. The burst concerns are: RAG burst-overload, the slow tail, fairness vs. manual, and
   headroom for growth / lower prod RAG capacity.
5. **Staggering does not add throughput.** If a night's volume exceeds window capacity, spreading it
   out cannot make it finish sooner — only more RAG throughput or less work (fan-out) can. Staggering's
   value is **smoothing, protection, and fairness**.

### Capacity projection (at measured 28 answers/min, d=1 / 10 answers/case)

| Cases (N=1) | Answers | Drain @28/min | vs ~6 h window |
|---|---|---|---|
| 100 | 1,000 | ~36 min | ✅ |
| 200 | 2,000 | ~71 min | ✅ |
| **306 (measured)** | **3,060** | **~109 min ✓** | ✅ |
| 350 | 3,500 | ~125 min | ✅ |
| 500 | 5,000 | ~178 min | ✅ |
| 1,000 | 10,000 | ~357 min | ⚠️ at the edge |
| 1,500 | 15,000 | ~536 min | ❌ overruns |

General form: **`drain_minutes ≈ (6·N + 4·K) / throughput_answers_per_min`**.

---

## 6. Options to tackle the burst

Split by what each actually buys. **Pool-size tuning is deliberately excluded** — the data shows it is
not the constraint.

### A. Smoothing & protection — recommended now (volume currently fits the window)

**A1 — Deferred-start staggering of the entry `CHECK_IDPC` tasks (primary).**
Use the library's native `withAssignedTaskStartTime` to release per-case entry tasks at a fixed rate
**≤ sustainable RAG rate** instead of all at 02:00. Target ≈ the measured ceiling (prod equivalent of
~2.8–4.7 IDPCs/min, i.e. keep RAG at ≤ its sustainable answers/min). The cascade follows naturally; the
queue stays shallow; no 02:00 spike; manual `HIGH` can jump the line.
- *Pros:* uses a built-in mechanism; small change in `DiscoveryService`; protects RAG; reduces retry churn.
- *Cons:* rate must be derived from the prod RAG measurement; staggers entry tasks only (within-case
  fan-out still bounded by the pool).

**A2 — Priority by hearing proximity (keep/strengthen).**
Within the nightly `DEFAULT` band, give nearer hearing dates higher priority; keep manual `HIGH=1` on
top. If a night ever overruns, the most time-critical cases finish first and only far-future ones slip.
- *Pros:* graceful degradation. *Cons:* ordering only, not rate.

**A3 — Cheap throughput experiment: `batchSize` / poll-interval (not pool size).**
Because the gaps look like poll cadence, test a larger `batchSize` and/or shorter poll interval — may
recover headroom *if* RAG can absorb it. Measure before/after.
- *Pros:* cheap, config-only. *Cons:* no effect if RAG is the hard limit (which is itself a useful result).

### B. Capacity — only if forecast volume approaches the ~1,000-case/night ceiling, or prod RAG is slower

**B1 — Cap-per-run + carry-over backlog.**
Bound IDPCs/cases dispatched per night; persist the remainder and drain on later nightly/intraday runs.
- *Pros:* hard ceiling; spike-proof. *Cons:* deferred cases' answers lag a day — only acceptable if the
  hearing date allows; added state.

**B2 — Reduce the fan-out / the tail (strategic).**
(a) Replace RAG status **polling** (`CHECK_STATUS`, up to 100×10 s) with a **callback/webhook** if RAG
supports it — removes the dominant churn and tightens the tail. (b) Batch `DEFENDANT` queries rather
than firing per document. Both raise *effective* throughput instead of moving load around.
- *Pros:* attacks root cause (burst size + churn). *Cons:* largest change; (a) depends on RAG API.

**B3 — More RAG throughput.**
Since RAG is the ceiling, the only way to genuinely shorten a big night is more RAG answer-generation
capacity/concurrency. Requires the RAG team.

---

## 7. Recommendation

- **Adopt A1 + A2 now** — low-risk smoothing + fairness using built-in mechanisms; protects RAG and
  manual users. Set A1's dispatch rate from the **prod** RAG measurement.
- **Run A3** as a short experiment to see whether cheap headroom exists.
- **Hold B1** as the safety cap; treat **B2 / B3** as the strategic track for genuine volume growth.
- **Do not pursue pool-size tuning** — the data shows it is not the constraint.

---

## 8. Open questions / to confirm before deciding

1. **Prod RAG sustainable rate and any hard rate limit** (NFT ≠ prod). Single most important input —
   it sets A1's rate and whether section B is ever needed.
2. **Forecast peak nightly volume** (N IDPCs, K cases, d defendants/case) vs. the prod window capacity.
3. **Exact claim `ORDER BY`** in `JobsRepository.findUnassignedJobsWithLimit(...)` — confirm it is
   `priority, start-time` so A2 behaves as intended.
4. **Which pool `cdk.ingestion.*` actually configures** (CDKS executor vs. the library's `JobExecutor`
   pool) — relevant to A3.
5. **Does the RAG API support a completion callback/webhook?** — gates B2(a).

---

## Appendix A — SQL used

**End-to-end per-case duration** (IDPC-check placeholder → last answer):
```sql
WITH doc_start AS (
    SELECT case_id, MIN(created_at) AS idpc_check_at, COUNT(*) AS documents
    FROM case_documents GROUP BY case_id
),
answer_end AS (
    SELECT case_id, MAX(created_at) AS last_answer_at, COUNT(*) AS answers
    FROM (
        SELECT case_id, created_at FROM defendant_answers
        UNION ALL SELECT case_id, created_at FROM case_level_all_documents_answers
        UNION ALL SELECT case_id, created_at FROM case_level_latest_doc_answers
        UNION ALL SELECT case_id, created_at FROM answers
    ) a GROUP BY case_id
)
SELECT d.case_id, d.idpc_check_at, e.last_answer_at,
       ROUND(EXTRACT(EPOCH FROM (e.last_answer_at - d.idpc_check_at)) / 60.0, 1) AS duration_minutes,
       d.documents, e.answers
FROM doc_start d JOIN answer_end e USING (case_id)
ORDER BY duration_minutes DESC;
```

**Sustained throughput (authoritative, whole batch):**
```sql
WITH ans AS (
    SELECT created_at FROM defendant_answers
    UNION ALL SELECT created_at FROM case_level_all_documents_answers
    UNION ALL SELECT created_at FROM case_level_latest_doc_answers
    UNION ALL SELECT created_at FROM answers
)
SELECT COUNT(*) AS answers, MIN(created_at) AS first_answer, MAX(created_at) AS last_answer,
       ROUND(COUNT(*) / NULLIF(EXTRACT(EPOCH FROM (MAX(created_at) - MIN(created_at))) / 60.0, 0), 1)
         AS answers_per_min
FROM ans
WHERE created_at BETWEEN TIMESTAMPTZ '<from>' AND TIMESTAMPTZ '<to>';
```

**Per-minute throughput (burst shape):**
```sql
WITH ans AS ( /* same UNION as above */ )
SELECT date_trunc('minute', created_at) AS minute, COUNT(*) AS answers_persisted
FROM ans WHERE created_at BETWEEN TIMESTAMPTZ '<from>' AND TIMESTAMPTZ '<to>'
GROUP BY 1 ORDER BY 1;
```

> Timing markers: **start** = `case_documents.created_at` (set once at the IDPC-check placeholder in
> `IdpcAvailabilityService.persistCaseDocument`, never updated — do **not** use `uploaded_at`, which is
> overwritten at upload). **end** = `MAX(created_at)` across the four answer tables.

## Appendix B — Key code references

| Concern | Location |
|---|---|
| Nightly entry / fan-out | `DiscoveryService.runNightlyDiscovery()` |
| Per-case entry task dispatch | `JobManagerService.dispatchCaseDocumentIngestionTasksCheckIdpcAvailability()` |
| `latestDefendantId` / `isLatestDefendant` | `IdpcAvailabilityService.retrieveDocumentsScoped()` |
| DEFENDANT (per doc) vs CASE / CASE_ALL_DOCS (latest-defendant) gating | `CheckIngestionStatusForAllDefendantsTask.execute()` |
| CASE_ALL_DOCUMENTS barrier | `CheckAllDocumentsIngestionStatusTask.execute()` |
| RAG status polling (churn source) | `CheckStatusOfAnswerGenerationTask` (`questions-retry` 100×10 s) |
| Priority constants | `jobmanager/support/JobPriority.java` (`HIGH=1`, `DEFAULT=10`) |
| Priority / deferred-start levers | task-manager-service: `ExecutionInfo.withPriority`, `withAssignedTaskStartTime`; `JobExecutor`, `JobsRepository` |
| Pool / retry config | `application-cdk.yml` (`cdk.ingestion.*`, `cdk.jobmanager.retry.*`) |

> Note: figures are from the **NFT** environment and must be re-measured against production RAG before
> any rate or capacity decision is finalised.
