# Agentic SDLC — CSDK Cheat-Sheet

*One page. Print it / pin it. Full detail in `agentic-catalogue.md` and `agentic-sdlc-guide.md`.*

---

## ① Set up (once per person — 3 commands)

```
/plugin marketplace add /media/ssaa/extra/moj/csdk/agentic-plugins-marketplace-main
/plugin install hmcts-sdlc-orchestrator@agentic-plugins-marketplace
/reload-plugins
```

✅ **Worked if:** reload shows about `15 agents · 4 hooks`, and `/agents` lists the agents.
❌ **Wrong plugin if:** reload shows `6 agents · 0 hooks` → you installed `code-review`, not the orchestrator.

---

## ② Top 5 prompts to try

| # | Type this… | You get… |
|---|-----------|----------|
| 1 | *"Here's the brief: <paste>. Take it through requirements, stories, tests and an implementation — pause at each gate."* | The full 8-stage pipeline (stops for your approval) |
| 2 | *"Review this branch against CPP standards."* | A standards / security / quality PR review |
| 3 | *"Trace the document-ingestion event across the clients and jobmanager."* | A cross-service event-flow map |
| 4 | *"Review my new Flyway migration in db/migration."* | A safe / append-only migration check |
| 5 | *"Write integration tests for the new endpoint."* | Tests in our `integrationTest` style |

> 🚦 The pipeline never ships on its own — it **pauses for your review** at requirements, design, stories,
> tests, code-review and deploy. Say *"continue"*, *"change X"*, or *"stop"*.

---

## ③ Handy commands

| Command | Does |
|---------|------|
| `/agents` | List available agents |
| `/plugin` | Show installed plugins |
| `/reload-plugins` | Apply plugin changes without restarting |

---

## ④ CSDK rules the assistant already follows

- ✅ Every AI answer **cited & auditable** (uncited = bug)
- 🔐 **Azure Managed Identity only** — no connection strings / SAS / keys
- 📝 **JSON logging**; never log case content
- 🗄️ **Flyway migrations are append-only** (never edit a shipped `V*.sql`)
- 🧪 **`gradle integration`** must pass — it's part of the build
- 🚫 **No real PII** in WireMock / Azurite test data

---

*Questions / rough edges → feed back to the plugin owner (it's v0.1.0). Known quirks: `migration-reviewer`
mentions Liquibase (we use Flyway); `rbac-auditor` mentions Drools (not used here); Helm/Terraform
validators have no infra in this repo.*
