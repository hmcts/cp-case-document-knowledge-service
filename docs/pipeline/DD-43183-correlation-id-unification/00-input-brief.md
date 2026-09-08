# DD-43183 — Input Brief

> Raw input as pasted from https://tools.hmcts.net/jira/browse/DD-43183

## User Story

As a production support engineer
I want one correlation identifier that survives from the inbound API call through every downstream call and appears in every log line and error response
So that when a user gives me an error reference I can retrieve the complete story of that request in a single log query.

## Acceptance Criteria

### Scenario: One correlation convention replaces three
Given RequestContextFilter reads X-Correlation-Id
And TracingFilter reads bare traceId and spanId headers
And CorrelationIdInterceptor reads X-Request-ID
When the change is deployed
Then a single documented inbound header is honoured, with the others accepted as aliases for backwards compatibility
And the resolved value is placed in MDC under one documented key
And the resolved value is returned to the caller on the response

### Scenario: Correlation propagates to every downstream call
Given an inbound request carries correlation ID "abc-123"
When CSDK calls the RAG service, Progression, or Hearing via a RestClient built by RestClientFactoryConfig
Then the outbound request carries "abc-123"
And CorrelationIdInterceptor no longer replaces it with a fresh UUID.randomUUID()
This is the single highest-value fix in this epic: today the chain is broken at every service boundary

### Scenario: Asynchronous work inherits the correlation ID
Given an ingestion is started with correlation ID "abc-123"
When a JobManager task in caseflow, queryflow or hearing later executes for that work
Then the task restores "abc-123" into MDC from ExecutionInfo jobData for the duration of execution
And a JobManagerKeys constant is added for the key rather than an inline string literal

### Scenario: Error responses carry a usable trace identifier
Given management.tracing.enabled is currently false, so GlobalExceptionHandler.traceId() always returns null
When any handler in GlobalExceptionHandler returns an ErrorResponse
Then the traceId field is populated with a non-null value in every environment
And a support engineer can find every log line for that request by searching that single value

### Scenario: Business identifiers are present on every operational log line
Given a log statement is emitted from a service, task, scheduler or client class
Then MDC contains caseId and, where applicable, docId and transactionId
And they appear as discrete JSON fields via LogstashEncoder, not embedded in the message string
And RagAnswerAsyncServiceImpl's "completed successfully" messages carry the transactionId (they currently carry no identifier at all)
And no document content, answer text, llm_input value or personal data is logged at any level

### Scenario: OTLP export configuration is corrected
Given management.otlp.tracing.enabled is currently bound to ${OTEL_METRICS_ENABLED:false}
When the change is deployed
Then tracing export is controlled by its own OTEL_TRACES_ENABLED variable
And the default endpoints are the OTLP/HTTP spec paths /v1/traces and /v1/metrics
And enabling tracing in a non-production environment produces spans in the collector, evidenced by a screenshot attached to the ticket

### Scenario: MDC does not leak between requests
Given RequestContextFilter clears MDC in a finally block
When correlation handling is extended to virtual threads (spring.threads.virtual.enabled)
Then a test asserts no MDC value from request A is visible while handling request B
