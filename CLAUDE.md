# HMCTS SDLC Pipeline — case-document-knowledge-service (CSDK)

> Tailored from `hmcts-sdlc-orchestrator` for this repo. The 8-stage pipeline, gates, agents,
> skills and `context/*` docs come from the installed plugin — install it first
> (`/plugin install hmcts-sdlc-orchestrator@agentic-plugins-marketplace`) or the references below
> will not resolve.

## Project context

**case-document-knowledge-service (CSDK)** — AI/RAG-powered answers for Crime Common Platform case
documents. **Every answer must be cited and auditable.** This service reads court/case material and
returns generated answers; correctness, traceability, and data protection are first-order concerns,
not afterthoughts.

This is an HMCTS engineering project. All work must comply with HMCTS engineering standards, the GDS
Service Manual, and MOJ security and accessibility requirements.

Always load before any pipeline stage:
- `.claude/context/csdk-context.md` — **single source of truth**: stack, packages, integrations, API surface, hard rules. Load this first.
- `.claude/context/tech-stack.md`
- `.claude/context/hmcts-standards.md`
- `.claude/context/azure-cloud-native.md` — Cloud-Native posture and Shared Responsibility Model on Azure.
- `.claude/context/logging-standards.md` — mandatory JSON logging for Spring Boot services.

Load on demand:
- `.claude/context/azure-sdk-guide.md` — when touching any Azure integration (this service uses Managed Identity; see `clients/common/Azure*`).
- `.claude/context/cloud-adoption-rationale.md` — only when lock-in / cloud-cost trade-offs or an ADR require it. Do not auto-load.

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

| # | Stage                 | Agent file                          | Gate  |
|---|-----------------------|-------------------------------------|-------|
| 1 | Requirements          | agents/requirements-analyst.md      | Human |
| 2 | Architecture & Design | agents/architecture-designer.md     | Human |
| 3 | User Story            | agents/story-writer.md              | Human |
| 4 | Test Specs            | agents/test-engineer.md             | Human |
| 5 | Code                  | agents/implementation.md            | Auto  |
| 6 | Code Review           | agents/code-reviewer.md             | Human |
| 7 | Build & Test          | agents/ci-orchestrator.md           | Auto  |
| 8 | Deploy Sandbox        | agents/deployer.md                  | Human |

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

> Not applicable in this repo: `helm-config-validator` / `terraform-validate` — there are **no Helm charts
> or Terraform** here (deployment infra lives elsewhere). Skip them unless infra is added to this repo.

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
- **Citations & auditability are mandatory** — any change to answer generation/RAG flow must preserve source citations and the audit trail. An answer without a verifiable citation is a defect.
- **No PII / case data / court reference numbers** in artefacts, prompts, logs, or test fixtures. Use synthetic data; WireMock stubs and Azurite seed data must be non-real.
- **JSON logging to stdout is mandatory** (`logback-spring.xml`). No `System.out`; no logging of case content or document bodies. See `context/logging-standards.md`.
- **Azure via Managed Identity only.** Connection strings, SAS tokens, and account keys are not permitted in code, config, env vars, or compose files. Use the existing `Azure*`/APIM client pattern. See `context/azure-sdk-guide.md`.
- **Flyway migrations are append-only** — never edit a shipped `V*.sql`; add the next version. Route migration changes through `migration-reviewer`.
- **Integration tests are part of `build`/`check`** — code is not "done" until `gradle integration` passes against the compose stack.
- **Quality gates** — PMD and JaCoCo must pass; do not lower thresholds to go green. CodeQL and the secrets scanner must be clean.
- **Use the HMCTS Spring Boot templates** as the master source for build files, Dockerfile, and logback config — do not hand-scaffold. Deviations require an ADR.
- **Accessibility (WCAG 2.1 AA)** is non-negotiable for any user-facing output.
- If confidence in a decision is low, write an ADR (`docs/pipeline/adrs/`) and surface it for review.
