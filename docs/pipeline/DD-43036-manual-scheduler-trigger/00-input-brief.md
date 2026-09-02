# 00 — Input Brief

> Raw, unstructured input as given by the requester. Jira ticket: **DD-43036**.

## Brief (verbatim)

Add a new endpoint to trigger scheduled jobs intraday / nightly.

The endpoint should be accessible only for the System Users (same as
`casedocumentknowledge-service.discovery-scheduler-configuration` endpoint).

The user input would be the scheduler to kick off i.e. INTRADAY or NIGHTLY.

On receiving the request, based on the input that specific discovery operation should be
performed.

## Source

Provided directly in conversation by the requester (no Confluence/Jira source document supplied).
