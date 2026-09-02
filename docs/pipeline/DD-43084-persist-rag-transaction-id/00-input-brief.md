# 00 — Input Brief

> Raw, unstructured input as given by the requester. Jira ticket: **DD-43084**
> (https://tools.hmcts.net/jira/browse/DD-43084).

## Brief (verbatim)

As per DD-43084: currently when we call the RAG service from `GenerateAnswerForQueryTask` we get
back a `transactionId`, but that same `transactionId` is not getting persisted in any of the
answers tables. Come up with requirements, design, and all other pipeline stages (mirroring the
approach used for DD-43036, manual scheduler trigger), and a plan to store the `transactionId`
(answer-related) in the answers table(s).

## Source

Jira ticket DD-43084 (title/description not fetched — no Jira MCP/tool access in this session;
the brief above is the requester's own restatement of the ticket in conversation). The ticket
number and one-line problem statement should be verified against Jira directly before this
requirement is taken to the Story stage — see Open Question OQ-001 in `01-requirements.md`.

## Ground truth gathered from the codebase before drafting requirements

- `GenerateAnswerForQueryTask` (`jobmanager/queryflow/GenerateAnswerForQueryTask.java`) calls
  `documentInformationSummarisedAsynchronouslyApi.answerUserQueryAsync(request)`, reads
  `transactionId` off the response, and hands it forward only via Task Manager job data
  (`CTX_RAG_TRANSACTION_ID` = `"ragTransactionId"`), addressed to the next task.
- `CheckStatusOfAnswerGenerationTask` reads that same `ragTransactionId` back out of job data,
  uses it only to (a) poll `answerUserQueryStatus(transactionId, ...)` and (b) as a log
  correlation value. Once the poll succeeds, it calls one of four upsert methods —
  `AnswerGenerationService.upsertAnswer(...)`, `CaseLevelLatestDocumentAnswerService.upsert(...)`,
  `CaseLevelAllDocumentsAnswerService.upsert(...)`, `DefendantAnswerService.upsert(...)` — and the
  `transactionId` variable is dropped; none of the four method signatures accept it, and none of
  the four target tables (`answers`, `case_level_latest_doc_answers`,
  `case_level_all_documents_answers`, `defendant_answers`) have a column for it.
- This is a direct instance of the CDKS hard rule "do not drop RAG response fields" —
  `.claude/context/cdks-context.md` already calls out `doc_id`/`llm_input` as fields that must be
  preserved; `transactionId` is the same category of RAG-provenance field and is currently being
  silently discarded at the last step before persistence.
