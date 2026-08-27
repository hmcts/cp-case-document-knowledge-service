# DD-43185 — Input Brief

> Raw input as pasted from https://tools.hmcts.net/jira/browse/DD-43185

## User Story

As a production support engineer
I want CSDK to actively report work that has stopped making progress, and to prove its schedulers ran
So that I find out about a stalled ingestion or a scheduler that never fired from an alert, not from a legal adviser in a live hearing.

## Acceptance Criteria

### Scenario: Documents stalled in a non-terminal phase are exposed as a gauge
Given case_documents rows carry ingestion_phase and ingestion_phase_at
When the monitoring gauge is refreshed
Then cdk_documents_stalled publishes a count per phase for rows where ingestion_phase is one of WAITING_FOR_UPLOAD, UPLOADING, INGESTING and ingestion_phase_at is older than a configurable threshold (default 30 minutes, property cdk.monitoring.stalled-threshold)
And the gauge is tagged by phase only
And the query is a single indexed aggregate that does not scan case content

### Scenario: Queries stuck without an answer are exposed
Given case_query_status carries status and status_at, indexed by idx_cqs_status_at_desc
Then cdk_queries_awaiting_answer publishes the count of rows in ANSWER_NOT_AVAILABLE older than the threshold

### Scenario: Failed document verification is exposed
Given DocumentVerificationTaskRepository already provides countByStatus(DocumentVerificationStatus)
Then cdk_document_verification_tasks is published as a gauge tagged by status (PENDING, IN_PROGRESS, SUCCEEDED, FAILED) reusing that existing method

### Scenario: Gauge refresh is cheap
Given the stalled-work gauges are refreshed on a fixed schedule
Then the refresh runs no more often than every 60 seconds
And it is ShedLock-guarded so that only one pod in the cluster performs it
And each aggregate query completes in under 500ms against production-scale data, evidenced by an EXPLAIN plan attached to the ticket

### Scenario: Each scheduler records a successful-run heartbeat
Given IntradayDiscoveryScheduler runs on cron "0 0/10 7-19 * * MON-FRI"
And NightlyDiscoveryScheduler runs on cron "0 0 2 * * *"
When run() completes without throwing
Then a gauge cdk_scheduler_last_success_epoch_seconds is updated, tagged by scheduler name
And a counter cdk_scheduler_runs_total is incremented with tags scheduler and outcome (success|failure)

### Scenario: A scheduler that throws is recorded rather than lost
Given DiscoveryService.runIntradayDiscovery() throws
When IntradayDiscoveryScheduler.run() executes
Then the exception is caught, logged at ERROR with the exception object and the scheduler name
And cdk_scheduler_runs_total{outcome="failure"} is incremented
And the exception is not rethrown into the Spring scheduler
Today neither scheduler has a try/catch and neither writes any state; a failed run leaves only a missing "finished" log line

### Scenario: A disabled scheduler is visible, not silent
Given CP_CDK_SCHEDULER_INTRADAY_DISCOVERY_ENABLED and CP_CDK_SCHEDULER_NIGHTLY_DISCOVERY_ENABLED both default to false
When the service starts
Then a gauge cdk_scheduler_enabled reports 0 or 1 per scheduler
And the enabled state is logged at INFO at startup
A flag left off after a release is currently undetectable from outside the pod

### Scenario: Stuck-work reporting degrades safely
Given the monitoring aggregate query fails or times out
Then the gauges retain their last known value and the failure is logged at WARN
And no user-facing request path is affected
