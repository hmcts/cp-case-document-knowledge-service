package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.util.Objects.isNull;
import static java.util.stream.Collectors.toCollection;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_DOCUMENT_INGESTION_STATUS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GENERATE_ANSWER_FOR_QUERY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_REFERENCE_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuidOrNull;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INGESTION_FAILED;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INGESTION_SUCCESS;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.services.QueryResolver;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Task(CHECK_DOCUMENT_INGESTION_STATUS)
@Slf4j
public class CheckDocumentIngestionStatusTask implements ExecutableTask {

    private final DocumentIngestionStatusApi documentIngestionStatusApi;
    private final CaseDocumentRepository caseDocumentRepository;
    private final QueryResolver queryResolver;
    private final ExecutionService executionService;
    private final JobManagerRetryProperties retryProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final UUID documentId = parseUuidOrNull(jobData.getString(CTX_DOC_ID_KEY, null));
        final UUID caseId = parseUuidOrNull(jobData.getString(CTX_CASE_ID_KEY, null));
        final String documentReference = jobData.getString(CTX_DOC_REFERENCE_KEY, null);

        if (isNull(documentId) || isNull(documentReference)) {
            log.error("{} missing required data docId={} documentReference={}", CHECK_DOCUMENT_INGESTION_STATUS, documentId, documentReference);
            return complete(executionInfo);
        }

        log.info("Polling ingestion status for documentReference='{}', docId={}", documentReference, documentId);
        try {
            final ResponseEntity<@NotNull DocumentIngestionStatusReturnedSuccessfully> response =
                    documentIngestionStatusApi.documentStatusByReference(documentReference);

            if (isNull(response) || !response.getStatusCode().is2xxSuccessful() || isNull(response.getBody())) {
                log.info("Status not available yet for documentReference='{}' → retrying", documentReference);
                return retry(executionInfo);
            }

            final DocumentIngestionStatusReturnedSuccessfully body = response.getBody();

            if (INGESTION_SUCCESS == body.getStatus()) {
                updateIngestionPhase(documentId, DocumentIngestionPhase.INGESTED);
                log.info("INGESTION SUCCESS documentReference='{}', docId={}", documentReference, documentId);
                final Set<UUID> candidateQueryIds = queryResolver.resolve().stream()
                        .map(Query::getQueryId)
                        .filter(Objects::nonNull)
                        .collect(toCollection(LinkedHashSet::new));

                if (candidateQueryIds.isEmpty()) {
                    log.debug("{}: All resolved queries had null IDs; nothing to generate answers.", CHECK_DOCUMENT_INGESTION_STATUS);
                    return complete(executionInfo);
                }

                log.info("Queries count: {}", candidateQueryIds.size());
                for (UUID questionId : candidateQueryIds) {
                    final JsonObject singleCaseJobData = createObjectBuilder(jobData)
                            .add(CTX_SINGLE_QUERY_ID, questionId.toString())
                            .build();

                    final ExecutionInfo executionInfoNew = executionInfo()
                            .from(executionInfo)
                            .withAssignedTaskName(GENERATE_ANSWER_FOR_QUERY)
                            .withJobData(singleCaseJobData)
                            .withExecutionStatus(ExecutionStatus.STARTED)
                            .build();

                    executionService.executeWith(executionInfoNew);

                    log.info("Created {} for docId={} questionId={}", GENERATE_ANSWER_FOR_QUERY, documentId, questionId);
                }

                return complete(executionInfo);
            } else if (INGESTION_FAILED == body.getStatus()) {

                updateIngestionPhase(documentId, DocumentIngestionPhase.FAILED);
                log.error(
                        "ingestion FAILED for documentReference='{}' reason='{}' (caseId={}, docId={}).",
                        documentReference,
                        body.getStatus(),
                        caseId,
                        documentId
                );
                return complete(executionInfo);
            }
        } catch (final Exception exception) {
            log.error(
                    "Document status check  FAILED with reason='{}' for (caseId={}, docId={}).",
                    exception.getMessage(),
                    caseId,
                    documentId
            );
            return retry(executionInfo);
        }
        log.debug("Ingestion status not complete for documentReference='{}' → retrying", documentReference);
        return retry(executionInfo);
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        final JobManagerRetryProperties.RetryConfig retry = retryProperties.getVerifyDocumentStatus();
        return Optional.of(
                IntStream.range(0, retry.getMaxAttempts())
                        .mapToLong(i -> retry.getDelaySeconds())
                        .boxed()
                        .toList()
        );
    }

    private void updateIngestionPhase(final UUID documentId, final DocumentIngestionPhase phase) {
        caseDocumentRepository.findById(documentId).ifPresent(doc -> {
            doc.setIngestionPhase(phase);
            doc.setIngestionPhaseAt(utcNow());
            caseDocumentRepository.saveAndFlush(doc);
        });
    }

    private ExecutionInfo retry(final ExecutionInfo executionInfo) {
        return executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .withShouldRetry(true)
                .build();
    }

    private ExecutionInfo complete(final ExecutionInfo executionInfo) {
        return executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }
}
