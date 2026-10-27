# Feature Toggle Inventory — case-document-knowledge-service (CDKS)

> Jira: **DD-43875**
> Status: **Analysis / inventory** (point-in-time audit — not a design or ADR)
> Service: case-document-knowledge-service (CDKS)
> Scope: every env-backed enable/disable toggle in the service, validated against the deployment
> pipeline repo `cpp-aks-deploy`
> Evidence: app config (`src/main/resources/application*.yml`, `@ConditionalOnProperty`, `@Value`) +
> `cpp-aks-deploy` (branch `ai-insight-api`) ansible `group_vars/**/casedocumentknowledge-service_values.yaml.j2`
> Date: 2026-10-26

---

## 1. Method

**App side** — a toggle is an env-backed boolean that gates behaviour, found as:
- an `enabled:`/`${...:true|false}` key in `application*.yml`,
- a `@ConditionalOnProperty` on a bean, or
- a boolean `@Value` injection.

**Deploy side** — per-environment values live in
`cpp-aks-deploy/ansible/group_vars/<env>/casedocumentknowledge-service_values.yaml.j2`, with a base
file at `ansible/group_vars/casedocumentknowledge-service_values.yaml.j2`. The ansible role merges
these with **recursive `combine`** (confirmed in `roles/aks_deploy/tasks/generate_db_map.yml`):
`base → env → env/stack (devccmNN)`, most-specific wins.

**Effective state** = most-specific deploy override, else the app's shipped default. The
discovery/hearing/stagger/priority flags are set **only** in the CDKS value files (verified: no
occurrences elsewhere in the deploy repo); `feature-flags-manager.yaml` does not touch CDKS. The
`dev/devccmNN` per-stack files override only `CP_CDK_ARTEMIS_HA` (infra) — not the
discovery/hearing/A1/A2 toggles.

**Environments:** `dev`, `sit`, `nft`, `ste` (non-live platform `nle`); `prp`, `prd`, `prx` (live
platform `lve`).

---

## 2. Application feature toggles — effective state per environment

| Flag (env var) | dev | sit | nft | ste | prp | prd | prx | Default | Deploy source |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|---|
| `CP_CDK_HEARING_IS_HEARING_FOR_CASES_ENABLED` | ✗ | ✗ | ✗ | **✓** | ✗ | ✗ | ✗ | `false` | `ste` sets `true`; others inherit default |
| `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED` | ✗ | ✗ | ✗ | **✓** | ✗ | ✗ | ✗ | `false` | `ste` sets `true`; others inherit default |
| `CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED` | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | **✓** | `false` | Set `true` in **every** env |
| `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_STAGGER_ENABLED` (A1, DD-43823) | ✗ | ✗ | ✗ | **✓** | ✗ | ✗ | ✗ | `false` | `ste` sets `true`; others inherit default |
| `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_PRIORITY_ENABLED` (A2, DD-43823) | ✗ | ✗ | ✗ | **✓** | ✗ | ✗ | ✗ | `false` | `ste` sets `true`; others inherit default |

✓ = enabled, ✗ = disabled.

---

## 3. Infra / platform toggles — effective state

| Flag (env var) | Effective state | Default | Notes |
|---|---|:--:|---|
| `CP_CDK_ARTEMIS_HA` | ✓ dev/sit/nft/prp/prd/prx · ✗ ste · ✗ most `dev/devccmNN` stacks (02–05, 07–10) | `false` | Set per-env; several dev stacks override to `false`. ⚠️ app yml comment flags this key as possibly non-binding ("was 'ha', won't bind") — verify it actually takes effect. |
| `CP_CDK_ARTEMIS_SSL_ENABLED` | ✓ all except ste (✗) | `true` | `ste` runs a local non-TLS Artemis. |
| `CP_CDK_ARTEMIS_CLIENT_AUTH_REQUIRED` | ✗ everywhere | `false` | Not set in deploy. |
| `CP_CDK_ARTEMIS_VERIFY_HOST` | ✗ everywhere | `false` | Not set in deploy. |
| `CP_CDK_DB_AUTO_COMMIT` | ✗ everywhere | `false` | Not set in deploy. |
| `VIRTUAL_THREADS` | ✗ everywhere | `false` | Not set in deploy. |
| `CP_CDK_HTTP_AUDIT_ENABLED` | ✓ everywhere | `true` | Not set in deploy → default. HTTP audit filter (cp-audit-filter). |
| `OTEL_TRACES_ENABLED` | ✗ everywhere | `false` | Base forces tracing off via `MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=false` **and** `JAVA_OPTS` excludes tracing autoconfig. |
| `OTEL_METRICS_ENABLED` | ✗ everywhere | `false` | Metrics OTLP export unset → default. |

---

## 4. Findings

1. **A1 (stagger) and A2 (priority) are enabled in `ste` only.** `ste` runs the full new path —
   nightly discovery + hearing-cases + A1 + A2 all `true`. Every other environment inherits the
   default `false` for both. So the burst-shaping is being exercised only in `ste`.
2. **Intraday discovery is now enabled in every environment**, but the **hearing-cases path is on
   only in `ste`**. So non-`ste` environments run intraday discovery via the **legacy
   `GET_CASES_FOR_HEARING`** path — not the per-case hearing path, and not A1/A2 (which apply to the
   nightly hearing path).
3. **Nightly discovery is enabled only in `ste`** — it is **disabled in all live-platform
   environments (`prp`, `prd`, `prx`)** and in `dev`/`sit`/`nft`. Production does not run the nightly
   burst today; confirm this is intentional before enabling A1/A2 beyond `ste`.
4. **Two stale toggles exist in the deploy repo that the app does not read** (see §5).

---

## 5. Stale / dead toggles found in deploy (cleanup candidates)

| Flag | Where (deploy) | Problem |
|---|---|---|
| `CP_CDK_FEATURE_USE_JOB_MANAGER: "true"` | set in **every** env value file | **No code reads it** — no `feature.use-job-manager` property, `@ConditionalOnProperty`, or `@Value` in the app. Dead toggle, likely a leftover from when JobManager was opt-in. |
| `CDK_INGESTION_BATCH_PARTITION_{QUERY,CASE,FILTER}_GRID_SIZE` | base + `dev` + `ste` | Dead Spring Batch grid-size keys. The app side is **removed in DD-43866**; these deploy lines are then dead too. |

**Recommendation:** raise a `cpp-aks-deploy` PR to remove both from the CDKS value files. Harmless
at runtime (unbound), but they misrepresent the service's real configuration surface.

---

## 6. Next steps
- Land this inventory against DD-43875.
- Deploy-repo cleanup PR for the two stale toggles in §5 (coordinate `CDK_INGESTION_BATCH_PARTITION_*`
  removal with DD-43866).
- Product/platform confirmation that discovery being **prod-disabled** is expected before enabling
  A1/A2 in any environment.

---

## Appendix A — Complete toggle reference

| Env var | Property | Default | Consumer | Kind |
|---|---|:--:|---|---|
| `CP_CDK_HEARING_IS_HEARING_FOR_CASES_ENABLED` | `cqrs.client.hearing.is-hearing-for-cases-enabled` | `false` | `DiscoveryService` (`@Value`) — selects hearing-cases path vs legacy | app |
| `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED` | `scheduler.nightly-discovery.enabled` | `false` | `NightlyDiscoveryScheduler` (`@ConditionalOnProperty`, `matchIfMissing=true`) | app |
| `CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED` | `scheduler.intraday-discovery.enabled` | `false` | `IntradayDiscoveryScheduler` (`@ConditionalOnProperty`, `matchIfMissing=true`) | app |
| `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_STAGGER_ENABLED` | `scheduler.nightly-discovery.stagger.enabled` | `false` | `NightlyEntryTaskPlanner` (A1 staggering) | app |
| `CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_PRIORITY_ENABLED` | `scheduler.nightly-discovery.priority.enabled` | `false` | `NightlyEntryTaskPlanner` (A2 ordering/priority) | app |
| `CP_CDK_HTTP_AUDIT_ENABLED` | `audit.http.enabled` | `true` | cp-audit-filter (HTTP audit) | platform |
| `VIRTUAL_THREADS` | `spring.threads.virtual.enabled` | `false` | Spring Boot (JVM virtual threads) | platform |
| `OTEL_TRACES_ENABLED` | `management.tracing…otlp.export.enabled` | `false` | Micrometer/OTLP tracing | platform |
| `OTEL_METRICS_ENABLED` | `management…otlp.metrics.export.enabled` | `false` | Micrometer/OTLP metrics | platform |
| `CP_CDK_ARTEMIS_SSL_ENABLED` | `…artemis.ssl-enabled` | `true` | Artemis JMS client | platform |
| `CP_CDK_ARTEMIS_CLIENT_AUTH_REQUIRED` | `…artemis.client-auth-required` | `false` | Artemis JMS client | platform |
| `CP_CDK_ARTEMIS_VERIFY_HOST` | `…artemis.verify-host` | `false` | Artemis JMS client | platform |
| `CP_CDK_ARTEMIS_HA` | `…artemis.high-availability` | `false` | Artemis JMS client (⚠️ possible binding issue) | platform |
| `CP_CDK_DB_AUTO_COMMIT` | `…datasource.hikari.auto-commit` | `false` | HikariCP | platform |

**Static (hardcoded, not env-toggleable):** `authz.http.enabled: true` (authorization/Drools filter),
`spring.flyway.enabled: true` (migrations), actuator endpoint enables. Listed for completeness; out
of scope for this env-toggle audit.

## Appendix B — Deploy evidence (file references)

- Base: `cpp-aks-deploy/ansible/group_vars/casedocumentknowledge-service_values.yaml.j2`
- Per env: `cpp-aks-deploy/ansible/group_vars/<env>/casedocumentknowledge-service_values.yaml.j2`
  for `dev`, `sit`, `nft`, `ste`, `prp`, `prd`, `prx` (plus `dev/devccm01..10`, which set no toggles).
- Merge semantics: `cpp-aks-deploy/ansible/roles/aks_deploy/tasks/generate_db_map.yml` (recursive `combine`).
