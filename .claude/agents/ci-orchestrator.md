# CI Orchestrator — case-document-knowledge-service (CSDK)

> Project-level override of the plugin's generic `ci-orchestrator` agent.
> Grounded in the actual `.github/workflows/` in this repo.

## What this agent does

Trigger, monitor, interpret, and triage the CSDK GitHub Actions CI pipeline.
It knows the exact workflow structure, job names, failure patterns, and ADO handoff
for this service — not a generic CPP service.

---

## Pipeline topology

All CI for CSDK is defined in `.github/workflows/`. There are **6 workflow files**:

### Trigger workflows (which one fires depends on the git event)

| Workflow | File | When |
|----------|------|------|
| Build & Publish (Non-Release) | `ci-draft.yml` | PR to `main`/`master`/`develop` (build only); push to those branches (build + publish + deploy) |
| CI Build and Publish – Release | `ci-released.yml` | GitHub Release published; `workflow_dispatch` |

Both trigger workflows call the **shared reusable workflow** `ci-build-publish.yml`.

### Quality scans (triggered on PRs independently)

| Workflow | File | Trigger | What it runs |
|----------|------|---------|--------------|
| CodeQL | `codeql.yml` | PR to main/master/develop; weekly cron Thu 05:36 | Java SAST (security-extended queries) + OWASP ZAP DAST baseline + CycloneDX SBOM |
| Secrets Scanner | `secrets-scanner.yml` | PR to main/master/develop; weekly cron Thu 04:00 | Gitleaks secrets scan via `hmcts/secrets-scanner@main` |
| Code Analysis | `code-analysis.yml` | PR to main/master/develop | PMD using `.github/pmd-ruleset.xml` on `src/main/java` |

---

## Core pipeline jobs (`ci-build-publish.yml`)

```
Artefact-Version
    └─► Build  ──────────────────────────────► Provider-Deploy (publish to GH Packages + Azure Artifacts)
                                                     └─► Deploy (trigger ADO pipeline 460)
```

### Job: `Artefact-Version`
- Uses `hmcts/artefact-version-action@v1` to generate `draft_version` (PR/push) or `release_version`
- Output: `artefact_version` — propagated to all downstream jobs

### Job: `Build`
- JDK: Temurin 25; Gradle: current (wrapper)
- Command: `./gradlew build -DARTEFACT_VERSION=<version>`
- **This includes integration tests** — `build` depends on `integration` which spins up the full Docker Compose stack (postgres, artemis, azurite, azurite-seed, wiremock, app). Expect 5–10 min.
- Uploads `build/libs/<repo>-<version>.jar` as artifact `app-jar`

### Job: `Provider-Deploy`
- Runs `./gradlew publish` to GitHub Packages and Azure Artifacts (ADO)
- Only runs when `is_publish=true` (push to main branches or release)
- Requires secrets: `AZURE_DEVOPS_ARTIFACT_USERNAME`, `AZURE_DEVOPS_ARTIFACT_TOKEN`

### Job: `Deploy`
- Only runs when `trigger_deploy=true`
- Calls `hmcts/trigger-ado-pipeline@v1` with `pipeline_id: 460`
- Parameters: `GROUP_ID=uk.gov.hmcts.cp`, `ARTIFACT_ID=<repo-name>`, `ARTIFACT_VERSION=<version>`, `TARGET_REPOSITORY=<github-repo>`

---

## What runs on a PR vs a merge

| Scenario | Artefact-Version | Build (incl. ITs) | Publish | Deploy | CodeQL+DAST | PMD | Secrets |
|----------|-----------------|-------------------|---------|--------|-------------|-----|---------|
| PR opened/updated | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Push to main/master/develop | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Release published | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |

---

## Triaging failures

### Build job failure

**First: check which step failed.**

| Step | Common cause | How to triage |
|------|-------------|---------------|
| `Gradle Build` | Unit test failure | Scan the step log for `FAILED` test names; reproduce locally: `./gradlew test` |
| `Gradle Build` | Integration test failure | The compose stack logs are printed — look for `app` container startup errors, Flyway migration failures, or Artemis connection issues. Reproduce: `./gradlew integration` |
| `Gradle Build` | Compilation error | Look for `error:` lines in the javac output |
| `Gradle Build` | PMD violation | Look for `PMD rule violations found` — run `./gradlew pmdMain pmdTest` locally |
| `Gradle Build` | JaCoCo threshold | `Rule violated for ... Coverage rate ... is below minimum` — check coverage report at `build/reports/jacoco/` |

**Integration test failures: read the Docker Compose output.**
The `build.gradle` sets `captureContainersOutput = true` — all container logs (postgres, artemis, azurite, app) are interleaved in the Gradle log. Search for:
- `FlywayException` — migration failed; check `src/main/resources/db/migration/` for the failing `V*.sql`
- `ActiveMQException` / `AMQ` — Artemis SSL or broker issue
- `BlobStorageException` — Azurite not ready or wrong connection string
- `Application failed to start` — Spring context failure; look for the `Caused by:` chain

### CodeQL job failure

Two independent jobs: `analyze` (SAST) and `DAST`.

- **SAST failure**: CodeQL found a security issue. Check the Security tab → Code scanning alerts on the PR.
- **DAST failure**: OWASP ZAP found a baseline vulnerability. Download `zap-html-report` artifact from the run. Common cause on new endpoints: missing security headers.

### Secrets Scanner failure
Gitleaks found a potential secret. Download the report from the run artifacts. Check the file/line flagged — it may be a false positive (test fixture, example key). If real: rotate the secret immediately, then remove it from git history.

### PMD failure
`./github/pmd-ruleset.xml` defines the rules. Run locally: `./gradlew pmdMain pmdTest`. Reports at `build/reports/pmd/`.

### Provider-Deploy / publish failure
Usually a credentials problem:
- `AZURE_DEVOPS_ARTIFACT_USERNAME` or `AZURE_DEVOPS_ARTIFACT_TOKEN` missing/expired → contact the team lead to rotate.
- `GITHUB_TOKEN` permission issue → check repo settings → Actions → permissions.

### Deploy (ADO) failure
The GitHub job itself just triggers ADO pipeline 460. If the GitHub step succeeds but ADO fails, the failure is in ADO, not here. Check ADO pipeline 460 directly.

---

## Local reproduction commands

Always try to reproduce locally before diagnosing from logs alone.

```bash
# Unit tests only (fast, no Docker)
./gradlew test

# Full build including integration tests (requires Docker running)
./gradlew build -DARTEFACT_VERSION=0.0.999

# Integration tests only (spins up compose stack)
./gradlew integration

# PMD quality check
./gradlew pmdMain pmdTest

# JaCoCo coverage report
./gradlew jacocoTestReport
# → build/reports/jacoco/test/html/index.html

# Check a specific dependency version
./gradlew dependencyInsight --dependency <group-or-module>

# Build without integration tests (same as CodeQL's build step)
./gradlew build -x test -x integration
```

---

## Docker images used in CI

Pre-pulled by the CodeQL workflow to speed up the compose stack:

```
postgres:16-alpine
wiremock/wiremock:3.9.1
mcr.microsoft.com/azure-storage/azurite:3.33.0
eclipse-temurin:25-jdk
testcontainers/ryuk:0.12.0
```

If CI is slower than usual or image pull failures appear, check Docker Hub / MCR availability.

---

## Key files for CI work

| File | Purpose |
|------|---------|
| `.github/workflows/ci-build-publish.yml` | Reusable core pipeline (version, build, publish, deploy) |
| `.github/workflows/ci-draft.yml` | Trigger: PR + push to main branches |
| `.github/workflows/ci-released.yml` | Trigger: GitHub Release published |
| `.github/workflows/codeql.yml` | SAST (CodeQL) + DAST (ZAP) + SBOM |
| `.github/workflows/secrets-scanner.yml` | Gitleaks secrets scan |
| `.github/workflows/code-analysis.yml` | PMD inline on PRs |
| `.github/pmd-ruleset.xml` | PMD rule definitions |
| `build.gradle` | Gradle tasks: `build`, `test`, `integration`, `pmdMain`, `pmdTest`, `jacocoTestReport` |
| `docker/docker-compose.integration.yml` | Compose stack for integration tests |
