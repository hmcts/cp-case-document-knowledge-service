# Agentic SDLC — Capability Catalogue (CSDK)

*Demo-ready reference for `hmcts-sdlc-orchestrator` v0.1.0 on `case-document-knowledge-service`.*
*To enable (once per person):*
`/plugin marketplace add /media/ssaa/extra/moj/csdk/agentic-plugins-marketplace-main` →
`/plugin install hmcts-sdlc-orchestrator@agentic-plugins-marketplace` → `/reload-plugins`.
*Then just type the example prompts.*

**Plugin contents at a glance:** 15 agents · 16 skills · 4 guard hooks · 7 context docs · 4 `opsx` commands

---

## A. The 8-stage pipeline (agents, run in order with human gates)

| # | Agent | What it produces | Demo prompt |
|---|-------|------------------|-------------|
| 1 | `requirements-analyst` | Structured requirements from a raw brief | *"Turn this brief into requirements: add a 'case summary' query type to CSDK."* |
| 2 | `architecture-designer` | Design + ADR for the capability | *"Design how the case-summary query should fit our clients/jobmanager flow."* |
| 3 | `story-writer` | HMCTS/GDS user stories + acceptance criteria | *"Write the user stories for the approved case-summary requirements."* |
| 4 | `test-engineer` | Full test suite, written before code | *"Write the tests for these stories (unit + integrationTest)."* |
| 5 | `implementation` | Production code that makes tests green | *"Implement the code to pass the case-summary tests."* |
| 6 | `code-reviewer` | Standards/security/quality review of the branch | *"Review this branch against CPP standards."* |
| 7 | `ci-orchestrator` | Triggers CI, monitors build, triages failures | *"Run the build and tell me why CI failed."* |
| 8 | `deployer` | Sandbox deploy + smoke checks | *"Deploy the verified build to sandbox and smoke-test it."* |

> 🚦 The pipeline **pauses for your approval** after stages 1, 2, 3, 4, 6 and 8. Nothing ships on its own.

**The headline demo:** one sentence kicks off the whole chain —
> *"Here's the brief for a new case-summary query type — take it through requirements, design, stories,
> tests, and an implementation, pausing for my review at each gate."*

---

## B. Auxiliary agents (call any time, no pipeline needed)

| Agent | Use it for | Demo prompt |
|-------|-----------|-------------|
| `event-flow-mapper` | Trace an event across services/contexts | *"Trace the document-ingestion event from controller through clients and jobmanager."* |
| `migration-reviewer` | Review a DB migration for safety | *"Review my new Flyway migration in db/migration."* † |
| `rbac-auditor` | Audit access-control rules | *"Audit access control on AnswersController."* † |
| `helm-config-validator` | Validate Helm charts (secrets, missing config) | *"Validate the Helm chart for this service."* ‡ |
| `test-analyzer` | Find coverage gaps / flaky tests | *"Where are our biggest test-coverage gaps?"* |
| `doc-generator` | Auto-generate README / CLAUDE.md | *"Generate an updated README for this repo."* |
| `research` | Deep cross-service investigation | *"How does CSDK get hearing data end to end?"* |

---

## C. Skills (focused playbooks Claude applies on demand)

| Skill | Use it for |
|-------|-----------|
| `review-pr` | PR review against CPP coding standards |
| `cpp-test-authoring` | Author/extend Serenity BDD or REST-Assured tests |
| `dependency-audit` | Find version mismatches / outdated / vulnerable deps |
| `architecture-design` | Choose CQRS context-service vs Modern-by-Default |
| `api-contract-check` | Validate API contracts (OpenAPI/JSON Schema) vs implementation |
| `springboot-service-from-template` | Stand up a new HMCTS Spring Boot service from the canonical template |
| `springboot-api-from-template` | Start a new HMCTS Marketplace API spec repo |
| `write-acceptance-criteria` | Derive testable ACs from a requirement |
| `generate-bdd-specs` | Produce Cucumber/Gherkin feature files |
| `accessibility-check` | WCAG 2.1 AA review (+ GOV.UK Frontend) |
| `adr-template` | Record an architecture decision in standard ADR format |
| `review-checklist` | The CPP code-review checklist |
| `terraform-validate` | Validate Terraform modules ‡ |
| `pipeline-debug` | Debug Azure DevOps pipeline configs |
| `context-service-guide` | Navigate legacy `cpp-context-*` services — LEGACY only |
| `context-scaffold` | Scaffold modules/commands/events in a legacy context service — LEGACY only |

---

## D. Guard hooks (automatic safety — always on once loaded)

| Hook | Blocks |
|------|--------|
| `block-pii` | Prompts/writes containing personal / case data |
| `block-secrets` | Secrets, keys, tokens, connection strings |
| `guard-bash` | Dangerous shell commands |
| `guard-paths` | Writes to sensitive paths |

*Demo idea:* try to paste a fake secret or PII string and show the hook stopping it.

---

## E. Context docs (loaded automatically so every answer follows our standards)

`tech-stack` · `hmcts-standards` · `coding-standards` · `azure-cloud-native` · `azure-sdk-guide` ·
`logging-standards` · `cloud-adoption-rationale`

These are why the assistant already "knows" CSDK: Managed-Identity-only Azure, JSON logging, append-only
migrations, cited/auditable answers — without you re-explaining each time.

---

## F. `opsx` slash commands (lightweight ops workflow)

`/opsx:explore` · `/opsx:propose` · `/opsx:apply` · `/opsx:archive` — a propose→apply→archive flow for
operational changes.

---

## Suggested 10-minute demo running order

1. **Show the house-rules** — open `CLAUDE.md`, point out the CSDK-specific rules. *(30s)*
2. **One-sentence pipeline kickoff** (headline demo above) — let it produce requirements, then **stop at the gate**. Emphasise the 🚦. *(3 min)*
3. **A single auxiliary** — `event-flow-mapper` on document ingestion (visual, CSDK-specific). *(2 min)*
4. **A guard hook** — paste a fake secret, show it blocked. *(1 min)*
5. **A skill** — `review-pr` on a recent change. *(2 min)*
6. **Wrap** — show `agentic-sdlc-guide.md` (how the team gets started) + the value table. *(1 min)*

---

> † `migration-reviewer` / `rbac-auditor` ship with generic wording (Liquibase / Drools) — CSDK actually
> uses **Flyway** and has no Drools. They still work; flag as v0.1.0 feedback to the plugin owner.
> ‡ `helm-config-validator` / `terraform-validate` have **no infra in this repo** to target — skip in the
> CSDK demo unless you point them at the infra repo.
