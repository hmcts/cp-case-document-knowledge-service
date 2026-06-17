# Agentic SDLC Plugin — Team Adoption Brief

**Plugin:** `hmcts-sdlc-orchestrator` (v0.1.0) · internal marketplace `hmcts/agentic-plugins-marketplace`
**Owner of this brief:** satish@scrumconnect.com
**Status:** Pilot — first release, rough edges expected
**Pilot repo:** `cp-case-document-knowledge-service` (CSDK)

---

## 1. What it is (30-second version)

A Claude Code plugin that bundles the **8-stage CPP SDLC pipeline** — requirements → architecture →
stories → tests → code → review → CI → deploy — as agents, skills, hooks, and context docs you drop
into any repo. Each stage pauses at a **human gate** for review. It also ships standalone capabilities
(PR review, Helm validation, RBAC audit, test authoring) you can call on demand.

Why it matters for us: it codifies **HMCTS standards, logging rules, and Azure managed-identity policy**
into context that loads on every session — so the standards travel with the work instead of living in a
wiki nobody opens.

---

## 2. Install (per person, one-time + per repo)

> Steps 1, 2, 4 are interactive Claude Code slash commands — each engineer runs them in their own session.
> Step 3 places the pipeline definition into the repo.

```
1)  /plugin marketplace add hmcts/agentic-plugins-marketplace      # one-time, per machine
2)  /plugin install hmcts-sdlc-orchestrator@agentic-plugins-marketplace   # one-time, per machine
3)  cp ~/.claude/plugins/hmcts-sdlc-orchestrator/CLAUDE.md ./CLAUDE.md    # per repo (merge if one exists)
4)  /agents   → should list the 8 stages + auxiliaries
    /plugin   → hmcts-sdlc-orchestrator shows as enabled
```

**Heads-up:** install activates **guard hooks** (`block-pii`, `block-secrets`, `guard-bash`, `guard-paths`)
that run on every prompt submission and file write. Intended behaviour — but know that step 2 lets those
scripts execute in your environment on each action. Fine for trusted internal tooling.

---

## 3. Is this good practice? Yes — with four adjustments

The core pattern (standards-as-context + gated agent pipeline) is sound and well-suited to a regulated
MOJ/HMCTS context. Before rolling out widely, address these:

| # | Issue | Fix |
|---|-------|-----|
| 1 | **Dangling references** — the repo `CLAUDE.md` points at plugin-relative paths (`agents/…`, `context/…`). Without the plugin installed, they silently resolve to nothing. | Make `/plugin install` a documented **hard prereq**. |
| 2 | **Floating version** — it's v0.1.0, rough edges expected. | **Pin to a release tag**, not `main`, for reproducible behaviour across the team. |
| 3 | **One-size pipeline** — 8 gated stages is right for net-new work, overkill for a one-line fix. | Define a **fast path** (review + CI) vs **full pipeline**. |
| 4 | **Copy drift** — standards copied per repo will diverge over time. | Keep standards in the plugin (single source); repo `CLAUDE.md` stays thin and references it. |

---

## 4. Value matrix

### 4a. What to adopt first (Impact × Effort)

Lead with the top-left quadrant — immediate ROI, low risk if the release is still rough.

```
            LOW EFFORT                    HIGH EFFORT
HIGH   ┌──────────────────────────┬──────────────────────────┐
IMPACT │ ★ DO FIRST               │ PLAN / PILOT             │
       │ • review-pr              │ • full 8-stage pipeline  │
       │ • cpp-test-authoring     │ • event-flow-mapper      │
       │ • dependency-audit       │ • rbac-auditor           │
       │ • helm/terraform-validate│ • migration-reviewer     │
       ├──────────────────────────┼──────────────────────────┤
LOW    │ NICE-TO-HAVE             │ DEFER                    │
IMPACT │ • doc-generator          │ • bespoke agents nobody  │
       │ • adr-template           │   has asked for yet      │
       └──────────────────────────┴──────────────────────────┘
```

### 4b. ROI table (fill the time columns from our own baseline)

| Capability | Replaces (manual task) | Baseline | With plugin | Quality / risk gain |
|---|---|---|---|---|
| `review-pr` | Manual CPP-standards review | ~___ | minutes | Consistent checklist, fewer escapes |
| `cpp-test-authoring` | Hand-writing Serenity / REST-Assured | ~___ | minutes | Coverage + pattern consistency |
| Guard hooks | Hoping no one commits PII / secrets | n/a | automatic | **Hard block** on court-data leakage |
| `helm-config-validator` / `terraform-validate` | Manual chart / IaC review | ~___ | minutes | Catches misconfig pre-deploy |
| `dependency-audit` | Manual CVE / licence check | ~___ | minutes | Earlier vuln detection |
| Full 8-stage pipeline | Ad-hoc req → deploy flow | ___ days | gated, traceable | Audit trail, no skipped gates |

### 4c. Measure it — don't assert it

Tie value to the four **DORA metrics** so "feels faster" becomes a defensible number:

| Metric | What we expect to move |
|---|---|
| Lead time for changes | ↓ (faster req → merge) |
| Deployment frequency | ↑ (less manual ceremony) |
| Change-failure rate | ↓ (gates + standards + hooks) |
| MTTR | ↓ (consistent logging / observability) |

---

## 5. How to expedite development — adoption plan

| Phase | Timebox | Action | Success signal |
|---|---|---|---|
| **0 — Baseline** | Week 1 | Record current PR-review turnaround, test-writing time, and lead time on 3–5 recent CSDK changes. | A documented "before." |
| **1 — Quick wins** | Weeks 1–2 | Roll out top-left quadrant (`review-pr`, `cpp-test-authoring`, `dependency-audit`). No pipeline ceremony. | Engineers using them daily; faster reviews. |
| **2 — Pilot pipeline** | Weeks 2–4 | Run **one real story** end-to-end through the 8 stages on CSDK. Capture friction vs value at each gate. | One shipped story + cycle-time delta. |
| **3 — Decide & scale** | Week 5 | Review DORA deltas. Keep what worked, pin a version, define fast-path vs full-path policy, roll to more repos. | Go/no-go with numbers. |

**Practical accelerators:**
- **Parallelise independent auxiliaries** — `rbac-auditor`, `helm-config-validator`, `dependency-audit`
  have no inter-dependencies; run them concurrently.
- **Templates over scaffolding** — the plugin mandates the HMCTS Spring Boot templates; that removes the
  slowest, most error-prone part of standing up a service.
- **Right-size the gates** — all gates for net-new / regulated work; collapse to review + CI for low-risk
  changes where compliance allows.

---

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| v0.1.0 picks wrong tools / patterns for our context | Pilot on one repo; pin version; feed issues back to plugin owner. |
| Guard hooks add latency to every action | Measure during pilot; raise if the prompt/write overhead is noticeable. |
| Engineers skip install → broken `CLAUDE.md` references | Install is a documented hard prereq; verify with `/agents` + `/plugin`. |
| Standards drift across repos | Single source in the plugin; thin per-repo `CLAUDE.md`. |
| PII / case data in artefacts or prompts | Guard hooks + the pipeline's hard rules; reinforce in review. |

---

## 7. Feedback channel

This is v0.1.0 — the owner is actively collecting:
- Anything that didn't install cleanly
- Agents / skills that picked wrong tools or patterns for our context
- Missing standards / context the agents should know about
- Workflow gaps in the 8-stage pipeline

Raise via the internal marketplace repo or your usual channel. Log pilot findings as we go so we have a
consolidated list at the Phase 3 review.
