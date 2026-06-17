# Feedback — hmcts-sdlc-orchestrator (v0.1.0)

**From:** satish@scrumconnect.com
**Repo trialled on:** `case-document-knowledge-service` (CSDK) — a **modern** Spring Boot 4 / Java 21 / Gradle service
**Plugin version:** 0.1.0
**Date:** 2026-06-16

---

## TL;DR

Install and the core 8-stage pipeline work well. The main theme of the feedback: **several auxiliary
agents/skills are written for the *legacy* `cpp-context-*` WildFly/Maven CQRS stack, and assume tooling
that a modern Spring Boot service doesn't use** (Liquibase, Drools, separate Maven test repos). They still
run, but they reference the wrong technology for our context. Also two small install/docs gaps. Details and
suggested fixes below.

## What worked well

- `/plugin marketplace add <local-dir>` + `/plugin install` + `/reload-plugins` loaded cleanly: **15 agents, 4 hooks**.
- The 8 pipeline agents and the human-gate model are clear and genuinely useful.
- Context docs (HMCTS standards, Azure cloud-native, logging) are exactly the kind of thing that reduces drift.
- `review-pr`, `event-flow-mapper`, `dependency-audit` mapped well to our repo with no changes.

---

## Tech-stack mismatches (auxiliaries aligned to legacy, not modern-by-default)

| Sev | Component | What it assumes (quote) | CSDK reality | Suggested fix |
|-----|-----------|--------------------------|--------------|---------------|
| **High** | `migration-reviewer` (agent) | *"Reviews **Liquibase** database migrations… Validates **changesets** for backwards compatibility, **rollback** safety…"*; expects `{name}-viewstore-liquibase/ … changelog.xml` | CSDK uses **Flyway** — versioned, **append-only** `src/main/resources/db/migration/V*.sql`. No changesets, no `changelog.xml`, no rollback concept. | Add Flyway support (detect `db/migration/V*__*.sql`); switch the "rollback safety" check to "append-only / never edit a shipped migration"; pick Liquibase vs Flyway per repo. |
| **High** | `rbac-auditor` (agent) | *"Checks **Drools** rule consistency…"*; *"Each service defines its own **Drools** rules… `access-control-parent`"*; expects `.drl` files | CSDK uses **Spring controller-level** access control: `controllers/accesscontrol/PermissionConstants`, `resources/acl/`. **No Drools, no `.drl`.** | Detect the access-control style; support annotation/ACL-based RBAC for Spring services, not only Drools. |
| **Medium** | `cpp-test-authoring` (skill) | Targets **two separate Maven repos** — `cpp-ui-e2e-serenity` (Serenity/Cucumber, Java 17, Maven) and `cpp-apitests` (JUnit 5 + REST Assured, Java 17, Maven) | CSDK is **one Gradle (Java 21) repo** with its own `src/integrationTest` sourceSet (JUnit 5 + REST Assured + WireMock + docker-compose). Tests live **in-repo**, not in those two repos. | Support in-repo `src/integrationTest` (Gradle) test authoring, not only the two standalone Maven repos. |
| **Low** | `context-service-guide`, `context-scaffold` (skills) | Marked **"LEGACY ONLY — must not be applied to new Spring Boot work"** | Correct — just not applicable to CSDK | None needed; flagging so the legacy/modern split is explicit in the catalogue. |
| **Low** | `helm-config-validator` (agent), `terraform-validate` (skill) | Expect Helm charts / Terraform in the repo | CSDK has **no Helm or Terraform** in-repo (infra lives elsewhere) | Document that these target infra repos; degrade gracefully when none present. |

---

## Install / docs gaps

1. **GitHub marketplace add didn't work for us; local path did.**
   The documented `/plugin marketplace add hmcts/agentic-plugins-marketplace` did not register for us (the
   repo wasn't reachable in our setup), and a teammate accidentally installed `code-review` instead.
   `/plugin marketplace add <absolute-local-dir>` worked reliably.
   **Suggest:** document the local-directory install path as a first-class option.

2. **`extraKnownMarketplaces` + `enabledPlugins` in project settings does not auto-install.**
   Putting the marketplace/plugin in `.claude/settings.local.json` records intent but does **not** install
   the plugin (Claude Code [issue #32606](https://github.com/anthropics/claude-code/issues/32606)). Each
   user still has to run the 3 `/plugin` commands.
   **Suggest:** note this in the README so teams don't expect "commit settings → done".

3. **The orchestrator `CLAUDE.md` references plugin-relative paths** (`agents/…`, `context/…`, `skills/…`).
   Copied into a repo *without* the plugin installed, those references silently dangle.
   **Suggest:** add a one-line "install the plugin first" prereq at the top of the template `CLAUDE.md`.

---

## Suggestions (nice-to-have)

- A **"modern Spring Boot" profile** for the auxiliaries (Flyway/annotation-RBAC/in-repo Gradle tests) vs the
  existing **"legacy context-service"** profile (Liquibase/Drools/Maven). CSDK is modern-by-default; the
  current defaults lean legacy.
- Tag each agent/skill in the catalogue as **LEGACY** or **MODERN** so adopters pick the right ones.

---

*Happy to pair on the Flyway / Spring-RBAC / in-repo-test variants — we have a concrete modern-stack repo
(CSDK) to test them against. Reply here or raise issues on the marketplace repo.*
