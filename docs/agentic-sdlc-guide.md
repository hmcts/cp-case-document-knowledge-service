# Agentic SDLC for CSDK — Team Guide

*A plain-English guide to the HMCTS agentic development flow on `case-document-knowledge-service`.
Written for people new to Claude Code — no prior experience assumed.*

---

## TL;DR (read this first)

We've added an **AI development assistant** to this repo that knows our CSDK standards and walks any
piece of work through the same **8 steps every time** — requirements → design → stories → tests →
code → review → build → deploy — **pausing for a human to approve each step**. You stay in control;
it does the heavy lifting and never skips a standard. Setup is ~3 commands, then you just describe
what you need in plain English.

---

## 1. What is this, exactly?

Three building blocks, simplest first:

- **Claude Code** — an AI coding assistant that runs in your terminal/IDE and can read and edit this repo.
- **A plugin** (`hmcts-sdlc-orchestrator`) — an add-on, built by our HMCTS team, that teaches Claude our
  way of working: our standards, our test style, our Azure rules, plus a set of specialist "agents".
- **`CLAUDE.md`** — a file in this repo (already added) that Claude reads automatically every time you
  open it here. It's the "house rules" for CSDK. **It's been tailored to this exact service** — our
  stack (Spring Boot 4 / Java 21 / PostgreSQL), our commands, and our must-nots (no PII in logs,
  Azure via Managed Identity, every AI answer must be cited).

Think of it as a **very well-briefed new team member** who has already read all our standards docs and
will follow the same checklist on every task.

---

## 2. The 8-stage pipeline (and why the "gates" matter)

When you ask for a feature, Claude runs these in order and **stops at each 🚦 human gate** so you can
review before it continues:

| # | Stage | What it produces | Gate |
|---|-------|------------------|------|
| 1 | Requirements | A clear, written spec of what's being asked | 🚦 You approve |
| 2 | Architecture & Design | How it should be built; an ADR if it's a big decision | 🚦 You approve |
| 3 | User Story | The story + acceptance criteria (linked to Jira) | 🚦 You approve |
| 4 | Test Specs | The tests, written **before** the code | 🚦 You approve |
| 5 | Code | The implementation | auto |
| 6 | Code Review | A standards/security/quality review of that code | 🚦 You approve |
| 7 | Build & Test | Runs the build, unit + integration tests | auto |
| 8 | Deploy (sandbox) | Prepares a sandbox deployment | 🚦 You approve |

**Why the gates are the point:** the AI never ships anything on its own. At every 🚦 you read what it
did and say "yes, continue" or "no, change this". For a service handling **court case data**, that
human control is essential — and it gives us an audit trail of decisions.

---

## 3. How this helps us (the value)

| Without it | With the agentic flow |
|---|---|
| Standards live in a wiki nobody re-reads | Standards load automatically on every task |
| Tests written last (or skipped under pressure) | Tests written at stage 4, before code |
| PR reviews vary by reviewer | Consistent CPP review checklist every time |
| Easy to forget "no connection strings / no PII in logs" | Built-in rules + guard hooks block it |
| New joiners take weeks to learn our conventions | The assistant already knows them on day 1 |
| "Did we follow the process?" is hard to evidence | Each stage leaves a written artefact in `docs/pipeline/` |

**Specifically tuned for CSDK**, the assistant already knows to:
- Keep **every AI answer cited and auditable** (treat an uncited answer as a bug).
- Use **Azure Managed Identity** only — never connection strings, SAS tokens, or keys.
- Use **structured JSON logging** and never log case content.
- Treat **Flyway migrations as append-only** (never edit a shipped `V*.sql`).
- Run the **integration tests** (`gradle integration`) — they're part of our build, not optional.
- Keep **no real PII** in WireMock / Azurite test data.

---

## 4. Setup — step by step (~3 commands, once per person)

> **Each person runs three commands once.** A committed `.claude/settings.local.json` records *which*
> marketplace/plugin we use, but — due to a current Claude Code limitation
> ([issue #32606](https://github.com/anthropics/claude-code/issues/32606)) — that settings file does
> **not** auto-install the plugin. So everyone still runs the three commands below. We use the local
> folder (not GitHub), which is the most reliable route.

**Step 1 — register the marketplace (local folder):**
```
/plugin marketplace add /media/ssaa/extra/moj/csdk/agentic-plugins-marketplace-main
```

**Step 2 — install the orchestrator:**
```
/plugin install hmcts-sdlc-orchestrator@agentic-plugins-marketplace
```

**Step 3 — apply it:**
```
/reload-plugins
```

**Verify it worked.** After reload, the summary line should read about **`15 agents · 4 hooks`**, and
`/agents` should list the pipeline + auxiliary agents.

> ⚠️ Two common first-timer mistakes:
> 1. Running `/plugin marketplace add hmcts/agentic-plugins-marketplace` (the GitHub form) — if it
>    can't reach the repo, the marketplace never registers. Use the **local folder path** above instead.
> 2. Installing `code-review` or another plugin from the menu by mistake. The name must be exactly
>    `hmcts-sdlc-orchestrator`. Tell-tale sign you got the wrong one: reload shows `6 agents · 0 hooks`.

**The `CLAUDE.md` is already in this repo**, so the CSDK house-rules load automatically whenever you open
Claude Code inside `case-document-knowledge-service` — independent of the plugin install above.

---

## 5. How to use it day to day

Just describe what you want in plain English. Examples:

**Run the whole pipeline:**
> "Here's the brief for a new case-document query type — take it through requirements, stories, tests,
> and an implementation."

Claude runs the stages in order and pauses at each 🚦 for your review.

**Use one capability on its own:**
| You say… | It does… |
|---|---|
| "Review this PR against CPP standards" | Standards/security/quality review |
| "Review this Flyway migration" | Checks the migration is safe + append-only |
| "Trace the document-ingestion event across the clients and job manager" | Maps the event flow |
| "Audit access control on the Answers controller" | RBAC / permissions audit |
| "Write integration tests for the new endpoint" | Tests in our `integrationTest` style |

**You're always in charge:** at any gate you can say *"change X"*, *"explain why you did Y"*, or *"stop"*.

---

## 6. FAQ (for newcomers)

**Do I need to memorise commands?** No. The only special commands are the 3 setup ones above. After
that, you talk to it in normal English.

**Will it change code without me knowing?** No — it shows you each change and stops at the gates. Nothing
deploys on its own.

**What are "guard hooks"?** Small safety checks the plugin runs automatically — they block accidental
commits of secrets or PII and dangerous commands. They protect you; you don't configure them.

**What if it gets something wrong?** It's a first release (v0.1.0) — expect rough edges. Tell it to fix
it, and report recurring issues (see below). You're the reviewer; it's the assistant.

**Does it replace code review / Jira / CI?** No. It *complements* them — it helps you produce better
inputs faster, but humans still approve, and our normal Jira + CI gates still apply.

---

## 7. Feedback & help

This is the first release — the plugin owner wants to hear about:
- Anything that didn't install cleanly
- Agents/skills that picked the wrong tool or pattern for CSDK
- Missing standards the agents should know
- Gaps in the 8-stage flow

Raise it via our internal channel or the marketplace repo. Log issues as we go so we have a consolidated
list for our first review.

---

*Companion doc: `agentic-adoption.md` (repo root) has the value matrix and rollout plan for leads.*
*The CSDK house-rules the assistant follows live in `CLAUDE.md` (repo root).*
