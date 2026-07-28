# 00 — Input Brief

> Raw, unstructured input as given by the requester. Jira ticket: **TBC** — no ticket has been
> raised yet; replace the `TBC-manual-scheduler-trigger` folder name with `<JIRA-TICKET>-manual-scheduler-trigger`
> once one exists, and link it before the Test Specs stage per the hard rule in CLAUDE.md.

## Brief (verbatim)

Add a new endpoint to trigger scheduled jobs intraday / nightly.

The endpoint should be accessible only for the System Users (same as
`casedocumentknowledge-service.discovery-scheduler-configuration` endpoint).

The user input would be the scheduler to kick off i.e. INTRADAY or NIGHTLY.

On receiving the request, based on the input that specific discovery operation should be
performed.

## Source

Provided directly in conversation by the requester (no Confluence/Jira source document supplied).
