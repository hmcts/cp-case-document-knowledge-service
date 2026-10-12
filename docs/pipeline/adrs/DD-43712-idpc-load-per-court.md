# ADRs — DD-43712 (IDPC load per court centre)

## ADR-001: This PR lands the reusable tool, not a specific measurement run

**Status:** Accepted (2026-10-08)

**Context:** DD-43712 is written as a reusable template ticket ("how many IDPCs does court X
receive per day") with three blank input fields — which court(s), which date window, who asked.
Stage 1 (`01-requirements.md`) initially treated those three fields as blocking, on the reading
that this ticket's deliverable is the counts-only report from one specific run.

**Decision:** The deliverable for this ticket/PR is narrower and different: commit the
already-written, already dry-run-validated capability — `ops/tools/IdpcLoad.java`,
`ops/HOWTO-idpc-load-per-court.md` and `ops/README.md` — into the repo, reviewed, so it is available
as a standing capability. Running it for a named court and window is a separate activity each time,
to be tracked on whichever future ticket actually names them. This decision was made directly by the
requester.

**Consequences:**
- OQ-001/002/003 (court/window/requester) no longer block this PR; they remain live questions only
  for whoever next actually runs the tool for a real request.
- OQ-004/005/011/012/013 (Jira comment, operator prerequisites, report destination, H1 export,
  ongoing cadence) are all run-time concerns and are likewise deferred, not blocking.
- OQ-006/007/008/009 (undocumented `--allow-inside-git-repo` flag, git-guard not re-applied after
  `hearing`, dangling HOWTO references, zero build/test coverage) are properties of the code/docs
  actually being committed. Per this ticket's existing "no change to `IdpcLoad.java` or the HOWTO —
  both are already written and reviewed" constraint, none are fixed inline here; they are carried
  into the PR description as known, non-blocking findings for the human reviewer, and tracked as
  follow-ups.
- The conventional Stage 2–5 pipeline artefacts (Architecture & Design, User Story, Test Specs,
  Implementation) do not apply — there is no CDKS architecture change, no story to split, and no
  test suite to extend for a tool that is deliberately outside every Gradle source set. This ticket
  goes from Stage 1 (Requirements) directly to Stage 6 (Code Review) on the PR, then Stage 7
  (Build & Test — confirming the existing CDKS suite is unaffected) before merge to `main`.

See `docs/pipeline/DD-43712-idpc-load-per-court/01-requirements.md`, "Scope decision (2026-10-08)",
for the full reasoning.
