# Team Onboarding — Agentic SDLC on CSDK

*One doc to get the whole team productive with Claude Code + the `hmcts-sdlc-orchestrator` plugin on
`case-document-knowledge-service`. Read top-to-bottom, or jump to the section you need.*

**Audience:** every engineer on CSDK. No prior Claude Code experience assumed.
**Companion docs:** `agentic-cheatsheet.md` (1-page), `agentic-catalogue.md` (every capability),
`agentic-adoption.md` (value/ROI for leads), `CLAUDE.md` (the house-rules Claude follows).

---

## 1. What we've set up (the 2-minute recap)

We added an AI development assistant to this repo that already knows our CSDK standards and runs work
through a consistent **8-stage pipeline with a human approval gate at each stage**. It's installed and live.

| Thing | Where | What it gives you |
|-------|-------|-------------------|
| Plugin `hmcts-sdlc-orchestrator` | installed in Claude Code | 15 agents · 16 skills · 4 guard hooks · context docs |
| `CLAUDE.md` | repo root | CSDK "house rules" — loads automatically every session |
| Guard hooks | auto | block PII / secrets / dangerous commands at submit + write time |
| This kit | repo root | cheat-sheet, catalogue, adoption brief, this guide |

**It does not replace** you, Jira, code review, or CI. It produces better inputs faster; **humans still
approve at every gate.**

---

## 2. The demo plan (run-of-show — ~25 min)

> Goal: the team leaves able to install it and run their first capability the same day.

| Time | Segment | What to show | Talking point |
|------|---------|--------------|---------------|
| 0:00 | **Why** (3m) | The before/after table from §3 of `agentic-sdlc-guide.md` | "Standards stop living in a wiki nobody reads." |
| 0:03 | **Install live** (3m) | Run the 3 commands (§4 below) on a fresh machine; show `15 agents · 4 hooks` | "Three commands, once per person." |
| 0:06 | **House rules** (2m) | Open `CLAUDE.md`; point at the CSDK-specific rules | "It already knows Managed Identity, Flyway, citations." |
| 0:08 | **Headline: full pipeline** (6m) | Example A below — kick off, **stop at the requirements gate**, show the artefact | "Nothing ships on its own — you approve each 🚦." |
| 0:14 | **One auxiliary** (3m) | Example C — `event-flow-mapper` on document ingestion; compare to saved `docs/pipeline/event-flow-document-ingestion.md` | "Onboarding a new joiner to a flow in 30 seconds." |
| 0:17 | **A guard hook** (2m) | Paste a fake secret/PII string; show it blocked | "Defense in depth for court data." |
| 0:19 | **A skill** (3m) | Example B — `review-pr` on a recent change | "Consistent review every time." |
| 0:22 | **Wrap** (3m) | Hand out `agentic-cheatsheet.md`; show feedback file; next steps | "Pilot one real story this sprint; measure cycle time." |

**Pre-flight checklist (do 5 min before):**
- [ ] Plugin loaded: `/reload-plugins` shows `15 agents · 4 hooks`
- [ ] Have a small real change / branch ready for the `review-pr` demo
- [ ] Have a throwaway "fake secret" string ready for the hook demo
- [ ] `docs/pipeline/event-flow-document-ingestion.md` open in a tab for comparison
- [ ] `agentic-cheatsheet.md` printed / linked

---

## 3. Get started (3 commands, once per person)

```
/plugin marketplace add hmcts/agentic-plugins-marketplace
/plugin install hmcts-sdlc-orchestrator@agentic-plugins-marketplace
/reload-plugins
```
✅ Worked if reload shows about `15 agents · 4 hooks`. ❌ `6 agents · 0 hooks` = you got the wrong plugin
(`code-review`) — reinstall with the exact name above.

> Why not just commit a settings file? Because project settings don't auto-install the plugin
> (Claude Code issue #32606). Everyone runs the 3 commands once.

---

## 4. How to use Claude — Specification by Example

These are **worked examples** in Given/When/Then form (the same BDD style the pipeline uses). Copy a
*When* line, adapt it, and go. Each shows the **prompt** and **what you get back**.

### Example A — Full pipeline for a new feature
```gherkin
Given  a brief for a new capability on CSDK
When   I say: "Here's the brief: <paste>. Take it through requirements, architecture,
       stories, tests and an implementation — pause at each gate for my review."
Then   Claude runs requirements-analyst → architecture-designer → story-writer →
       test-engineer → implementation, STOPPING at each 🚦 human gate
And    each approved stage writes an artefact under docs/pipeline/
And    nothing proceeds until I say "continue" (or "change X")
```
*Use for:* net-new features / services. *Tip:* review the requirements artefact carefully — everything
downstream builds on it.

### Example B — Review a change against CPP standards
```gherkin
Given  a branch or diff I want reviewed
When   I say: "Review the current branch against CPP standards."
Then   Claude applies the review-pr skill: standards, security, logging, Azure,
       correctness — and lists findings as Must-fix / Should-fix / Nit
```
*Use for:* every PR before requesting human review. *Tip:* fix Must-fix items, then re-run.

### Example C — Understand a flow (great for onboarding)
```gherkin
Given  I'm new to how documents get ingested
When   I say: "Trace the document-ingestion event across the clients and jobmanager."
Then   Claude (event-flow-mapper) produces a stage-by-stage map: controller → JobManager
       task chain → APIM/RAG upload → status polling → answer generation
```
*Use for:* learning unfamiliar code, impact analysis. *Reference:* see the saved
`docs/pipeline/event-flow-document-ingestion.md`.

### Example D — Review a database migration (CSDK = Flyway, append-only)
```gherkin
Given  I added src/main/resources/db/migration/V1011__add_xyz.sql
When   I say: "Review my new Flyway migration — is it safe and append-only?"
Then   Claude checks it doesn't edit a shipped migration, is forward-only, and
       won't break existing data
```
*Note:* the `migration-reviewer` agent is worded for Liquibase; phrase prompts as **Flyway** (captured in
`feedback-to-plugin-owner.md`).

### Example E — Author tests in our in-repo style
```gherkin
Given  a new endpoint on AnswersController
When   I say: "Write integration tests for it in our src/integrationTest style
       (JUnit 5 + REST Assured + WireMock, compose-backed)."
Then   Claude scaffolds tests matching our sourceSet, not a separate Maven repo
And    I run them with: gradle integration
```

### Example F — Record an architecture decision
```gherkin
Given  we chose approach X over Y and want it on record
When   I say: "Write an ADR for choosing <X> over <Y> for <decision>."
Then   Claude writes docs/pipeline/adrs/NNN-<title>.md in standard ADR format
```

---

## 5. Get better results (and improve delivery performance)

**Better prompts → better output:**
- **Give context, not just a verb.** "Add an endpoint" ⟶ "Add a `GET /answers/{id}` endpoint to
  `AnswersController` returning `Answer`, with the same access-control pattern as the existing endpoints."
- **Point at real anchors** — file names, classes, existing patterns ("follow how `IngestionController` does it").
- **State the done-criteria** — "…and add an integration test; it must pass `gradle integration`."
- **Use the gates** — at a 🚦 you can say *"change X"*, *"why did you do Y?"*, or *"stop"*. Cheap to steer early.
- **Let `CLAUDE.md` do the heavy lifting** — you don't need to repeat "use Managed Identity / JSON logging /
  no PII"; it's already loaded. If you find yourself repeating a rule, add it to `CLAUDE.md` instead.

**Right-size the process (speed):**
- **Fast path** for low-risk changes: just Example B (`review-pr`) + `gradle build`.
- **Full pipeline** for net-new / regulated work: Example A with all gates.
- **Parallel auxiliaries** — `dependency-audit`, `event-flow-mapper`, `rbac-auditor` are independent; fire
  them when relevant without blocking the pipeline.

**Measure it (so "feels faster" becomes a number):** baseline these on 3–5 recent changes, then re-check
after a sprint of use.

| DORA metric | Expect |
|---|---|
| Lead time for changes | ↓ |
| Deployment frequency | ↑ |
| Change-failure rate | ↓ |
| MTTR | ↓ |

---

## 6. Guardrails — do / don't

| ✅ Do | 🚫 Don't |
|------|---------|
| Read each gate before approving | Rubber-stamp the gates |
| Keep answers cited & auditable | Ship an answer-flow change that drops citations |
| Use synthetic data in tests/stubs | Put real PII / case data in prompts, fixtures, or logs |
| Add the next Flyway version | Edit a shipped `V*.sql` |
| Use Managed Identity / APIM pattern | Add connection strings, SAS tokens, or keys |
| Run `gradle integration` before "done" | Treat integration tests as optional |

The guard hooks will block the worst mistakes automatically — but they're a backstop, not a substitute for
the rules above.

---

## 7. Help & feedback

- **Stuck?** Ask Claude in plain English, or check `agentic-cheatsheet.md`.
- **Wrong tool / pattern for our stack?** It's v0.1.0 — log it. We've started a list in
  `feedback-to-plugin-owner.md` (known: Liquibase vs our Flyway, Drools vs our Spring ACL, Maven test repos
  vs our in-repo Gradle tests).
- **Want a rule changed for everyone?** Propose an edit to `CLAUDE.md` — that's the single source of truth.

---

*First release, real expectations: it's an assistant that already knows our standards and never skips a
gate. You're the reviewer; it does the heavy lifting. Start with one capability today.*
