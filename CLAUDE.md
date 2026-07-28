# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.


# HMCTS SDLC Pipeline — case-document-knowledge-service (CSDK)

> Tailored from `hmcts-sdlc-orchestrator` for this repo — install the plugin first
> (`/plugin install hmcts-sdlc-orchestrator@agentic-plugins-marketplace`) or the references below
> will not resolve. Only `csdk-context.md` and `tech-stack.md` are CSDK-specific and stored locally
> under `.claude/context/`; every other context doc, pipeline agent, and auxiliary agent/skill
> below lives in the plugin itself (`$PLUGIN` = `~/.claude/plugins/marketplaces/agentic-plugins-marketplace/plugins/agents/hmcts-sdlc-orchestrator`)
> — don't copy plugin content into this repo, it will just drift out of sync.

## Project context

**case-document-knowledge-service (CSDK)** — persists and surfaces AI/RAG-generated answers for
Crime Common Platform case documents. The service orchestrates document ingestion into the RAG
pipeline, stores the responses it receives, and serves them back via REST API. Answer generation
and citation production are the responsibility of the upstream RAG service; CSDK must not drop or
alter the response data (including source fields) when persisting or mapping to API responses.
Data protection and traceability are first-order concerns.

This is an HMCTS engineering project. All work must comply with HMCTS engineering standards, the GDS
Service Manual, and MOJ security and accessibility requirements.

Always load before any pipeline stage:
- `.claude/context/csdk-context.md` — **single source of truth**: stack, packages, integrations, API surface, hard rules. Load this first. **CSDK-specific, stored locally.**
- `.claude/context/tech-stack.md` — CSDK's actual versions, packages, and layout. **CSDK-specific, stored locally.**
- `$PLUGIN/context/hmcts-standards.md`
- `$PLUGIN/context/azure-cloud-native.md` — Cloud-Native posture and Shared Responsibility Model on Azure.
- `$PLUGIN/context/logging-standards.md` — mandatory JSON logging for Spring Boot services.

Load on demand:
- `$PLUGIN/context/azure-sdk-guide.md` — when touching any Azure integration (this service uses Managed Identity; see `clients/common/Azure*`).
- `$PLUGIN/context/cloud-adoption-rationale.md` — only when lock-in / cloud-cost trade-offs or an ADR require it. Do not auto-load.
- `$PLUGIN/context/coding-standards.md` — Java/Spring Boot naming, structure, method-size, and error-handling conventions. Load during the Code and Code Review stages.

## Tech stack & layout

See `.claude/context/tech-stack.md` — that file is the single source of truth for versions, packages, dependencies, and project layout. Do not duplicate its content here.

## Build, test & quality commands (use these — do not invent)

```bash
gradle clean build                      # full build incl. unit + integration tests
gradle test                             # unit tests only (failFast)
gradle integration                      # integration tests (spins up docker-compose: artemis, db, azurite, azurite-seed, wiremock, app)
gradle pmdMain pmdTest jacocoTestReport # quality reports (build/reports/{pmd,jacoco})
gradle dependencyInsight --dependency <group-or-module>
```
CI runs `./gradlew build -DARTEFACT_VERSION=...` plus CodeQL, code-analysis, and a secrets scanner
(`.github/workflows/` — six workflows: `ci-build-publish`, `ci-draft`, `ci-released`, `code-analysis`, `codeql`, `secrets-scanner`).
`check`/`build` depend on `integration`, so integration tests are not optional.

Source sets:
- `main` — production code
- `test` — JUnit 5 unit tests (Testcontainers PostgreSQL available)
- `integrationTest` — REST Assured / WireMock / compose-backed ITs (`src/integrationTest/`)
- `pactVerificationTest` — consumer-driven contract tests via the `au.com.dius.pact` plugin (`src/pactVerificationTest/`)

---

## Pipeline stages

Run in order. Do not skip or reorder. Halt at every human gate before proceeding.

| # | Stage                 | Agent                                                    | Gate  |
|---|-----------------------|-----------------------------------------------------------|-------|
| 1 | Requirements          | `hmcts-sdlc-orchestrator:requirements-analyst` (plugin)    | Human |
| 2 | Architecture & Design | `hmcts-sdlc-orchestrator:architecture-designer` (plugin)†  | Human |
| 3 | User Story            | `hmcts-sdlc-orchestrator:story-writer` (plugin)            | Human |
| 4 | Test Specs            | `hmcts-sdlc-orchestrator:test-engineer` (plugin)           | Human |
| 5 | Code                  | `.claude/agents/implementation.md` (**local override**)    | Auto  |
| 6 | Code Review           | `hmcts-sdlc-orchestrator:code-reviewer` (plugin)           | Human |
| 7 | Build & Test          | `.claude/agents/ci-orchestrator.md` (**local override**)   | Auto  |
| 8 | Deploy Sandbox        | `hmcts-sdlc-orchestrator:deployer` (plugin)                | Human |

> **Local overrides:** `implementation` and `ci-orchestrator` are project-local rewrites of the
> plugin's generic agents, not copies — they encode CSDK-specific facts the plugin template can't
> know (actual GitHub Actions workflow names, the ADO trigger pipeline ID, JDK/Temurin version,
> Docker images, Flyway/Artemis/Azurite failure-triage tables, and CSDK's
> `@Slf4j @RestController implements FooApi` controller pattern). The plugin has no changelog —
> if it's upgraded, diff these two files by hand against the plugin's versions to catch drift.
>
> † `architecture-designer` is the only "Human" gate stage whose own agent file has no
> self-declared halt instruction (unlike `requirements-analyst`, `story-writer`, and
> `test-engineer`, which each explicitly say to halt and wait for confirmation). It's still
> covered by the "never proceed past a human gate" hard rule below, but don't assume the agent
> will stop itself — the orchestrating session must enforce the pause.

---

## Auxiliary agents & skills — what applies to CSDK

| Capability | When to use it here |
|-----------|---------------------|
| `review-pr` (skill) | Every PR — CPP standards, Spring Boot, Azure, logging |
| `cpp-test-authoring` (skill) | Add/extend tests — JUnit 5 unit tests, the `integrationTest` sourceSet (REST Assured / WireMock / compose-backed), and `pactVerificationTest` for contract tests |
| `dependency-audit` (skill) | Before merging dependency bumps; complements CodeQL + secrets-scanner |
| `event-flow-mapper` (agent) | Tracing case/document events across `clients/*` and `jobmanager/{caseflow,hearing,queryflow}` (Artemis JMS flows) |
| `migration-reviewer` (agent) | **Any change under `db/migration`** — Flyway migrations are append-only and versioned |
| `rbac-auditor` (agent) | Changes to `controllers/accesscontrol/`, `PermissionConstants`, or `resources/acl/` |
| `doc-generator` / `adr-template` | API docs (OpenAPI) and recording architecture decisions |
| `research` (agent) | Tracing CSDK's integration with the upstream RAG service or the companion OpenAPI spec repo — broader than `event-flow-mapper`'s Artemis-only scope |
| `test-analyzer` (agent) | Coverage-gap / flaky-test analysis across `test`, `integrationTest`, `pactVerificationTest` |
| `api-contract-check` (skill) | Verify controllers/DTOs stay consistent with the consumed `api-cp-crime-caseadmin-case-document-knowledge` OpenAPI artefact (`build.gradle`) |
| `review-checklist` (skill) | Code Review stage, alongside `review-pr` — Spring Boot/Azure/logging checklist items map directly onto CSDK's hard rules |
| `write-acceptance-criteria` (skill) | Requirements stage — deriving ACs from FRs |
| `springboot-service-from-template` (skill) | Template-alignment check — ties to the hard rule against hand-scaffolding build files/Dockerfile/logback |

> Not applicable in this repo:
> - `helm-config-validator` / `terraform-validate` — there are **no Helm charts or Terraform**
>   here (deployment infra lives elsewhere). Skip unless infra is added to this repo.
> - `context-scaffold` / `context-service-guide` — legacy WildFly `cpp-context-*` only; CSDK is
>   Spring Boot (Modern by Default).
> - `pipeline-debug` — needs an in-repo `azure-pipelines.yml` to trace; CSDK's CI is 100% GitHub
>   Actions. The `hmcts/trigger-ado-pipeline` step only fires an external pipeline by ID — there's
>   no template in this repo for the skill to debug.
> - `springboot-api-from-template` — for bootstrapping a brand-new API-spec repo; CSDK already
>   consumes an established one.
> - `accessibility-check` — CSDK is backend-only with no UI to scan. Hard rule below on WCAG 2.1 AA
>   is inherited from the HMCTS template and applies to downstream consumers of CSDK's API, not to
>   CSDK itself.

---

## Artefact output convention

All pipeline artefacts go to `docs/pipeline/`:

```
docs/pipeline/
├── requirements.md
├── user-stories/<story-id>.md
├── test-specs/<story-id>.feature
├── adrs/<NNN>-<title>.md
└── deploy-notes.md
```

---

## Hard rules (CSDK)

- **Never proceed past a human gate** without explicit confirmation.
- **Never invent requirements, ACs, or test data** — flag unknowns as open questions. Every story needs a linked Jira ticket before the test stage.
- **Preserve RAG response data** — any change to the ingestion or answer-serving flow must not drop or transform source fields returned by the RAG service (e.g. `doc_id`, `llm_input`). Citation production is the RAG service's responsibility; CSDK's responsibility is not to lose that data.
- **No PII / case data / court reference numbers** in artefacts, prompts, logs, or test fixtures. Use synthetic data; WireMock stubs and Azurite seed data must be non-real.
- **Security hooks are enforced automatically by the plugin** — `block-pii` and `block-secrets` run on every prompt and on every `Write`/`Edit`, `guard-bash` runs on every `Bash` call, and `guard-paths` runs on every `Read`/`Write`/`Edit` (`$PLUGIN/hooks/hooks.json`). These back the PII and Managed-Identity rules above; don't bypass or work around a hook block — treat it as a signal to fix the underlying content, not the gate.
- **JSON logging to stdout is mandatory** (`logback-spring.xml`). No `System.out`; no logging of case content or document bodies. See `$PLUGIN/context/logging-standards.md`.
- **Azure via Managed Identity only.** Connection strings, SAS tokens, and account keys are not permitted in code, config, env vars, or compose files. Use the existing `Azure*`/APIM client pattern. See `$PLUGIN/context/azure-sdk-guide.md`.
- **Flyway migrations are append-only** — never edit a shipped `V*.sql`; add the next version. Route migration changes through `migration-reviewer`.
- **Integration tests are part of `build`/`check`** — code is not "done" until `gradle integration` passes against the compose stack.
- **Quality gates** — PMD and JaCoCo must pass; do not lower thresholds to go green. CodeQL and the secrets scanner must be clean.
- **Use the HMCTS Spring Boot templates** as the master source for build files, Dockerfile, and logback config — do not hand-scaffold. Deviations require an ADR.
- **Accessibility (WCAG 2.1 AA)** is non-negotiable for any user-facing output.
- If confidence in a decision is low, write an ADR (`docs/pipeline/adrs/`) and surface it for review.
