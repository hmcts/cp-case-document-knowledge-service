# DD-43182 — Input Brief

> Raw input as pasted from https://tools.hmcts.net/jira/browse/DD-43182

## Acceptance Criteria

### Scenario: Document ingestion phase transitions are counted
Given a document moves between DocumentIngestionPhase values
When the phase is persisted on CaseDocument.ingestionPhase
Then a counter cdk_document_ingestion_phase_total is incremented
And it carries tags: phase (one of NOT_FOUND, WAITING_FOR_UPLOAD, UPLOADING, UPLOADED, INGESTING, INGESTED, FAILED, EXCEEDED_FILE_SIZE_LIMIT) and source
And no tag contains a caseId, docId, defendantId, court reference or any other case identifier (unbounded cardinality and OFFICIAL-SENSITIVE data are both prohibited in metric tags)

### Scenario: Ingestion duration is measured end to end
Given a document enters phase UPLOADING
When it reaches INGESTED or FAILED
Then a timer cdk_document_ingestion_duration_seconds records the elapsed time
And the timer publishes histogram buckets so that p50, p95 and p99 are queryable in Prometheus

### Scenario: Every outbound dependency call is timed and counted
Given CSDK calls RAG, Progression, Hearing or Azure Blob
When the call completes or throws
Then a timer cdk_external_call_duration_seconds is recorded
And it carries tags: dependency (rag|progression|hearing|azure_blob), operation, outcome (success|client_error|server_error|timeout)
And a RagClientException thrown from RagAnswerAsyncServiceImpl or ApimDocumentIngestionClient is recorded as outcome=server_error, not swallowed

### Scenario: The 3-minute no-retry HTTP configuration is observable
Given RestClientFactoryConfig sets a 3-minute response timeout with disableAutomaticRetries()
When a downstream call exceeds the timeout
Then cdk_external_call_duration_seconds records outcome=timeout
And a gauge cdk_http_pool_connections_leased over the PoolingHttpClientConnectionManager is published so that pool exhaustion (max 200 total / 50 per route) is visible before it causes an outage

### Scenario: Answer generation outcomes are counted
Given an answer generation transaction runs through GenerateAnswerForQueryTask and CheckStatusOfAnswerGenerationTask
When it reaches a terminal state
Then a counter cdk_answer_generation_total is incremented with tags: outcome (succeeded|failed|timed_out), query_level (CASE|DEFENDANT|CASE_ALL_DOCUMENTS)

### Scenario: JobManager retry behaviour is measurable
Given a task returns ExecutionStatus.INPROGRESS with shouldRetry=true
Then a counter cdk_task_retry_total is incremented with tag task_name from TaskNames
And when a task exhausts its configured attempts (default 3, verify-document-status 50, questions-retry 100) a counter cdk_task_retry_exhausted_total is incremented with tag task_name
And cdk_task_retry_exhausted_total is documented as the primary "work is being silently abandoned" signal

### Scenario: Metrics are scrapeable and correctly tagged
When Prometheus scrapes /actuator/prometheus
Then every cdk_* metric is present
And each carries the existing common tags service, cluster and region from management.metrics.tags
And the scrape completes in under 1 second with fewer than 2,000 series per pod

### Scenario: Instrumentation does not change behaviour
Given metric recording throws for any reason
Then the business operation completes unaffected
And the failure is logged at WARN once per minute at most, never per occurrence
