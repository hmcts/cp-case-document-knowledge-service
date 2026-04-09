package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.util.Objects.isNull;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_ALL_DOCUMENTS_INGESTION_STATUS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GENERATE_ANSWER_FOR_QUERY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_REFERENCE_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_LATEST_DEFENDANT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_QUERYIDS_ARRAY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_QUERY_LEVEL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.normalise;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuidOrNull;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INGESTION_FAILED;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INGESTION_SUCCESS;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INVALID_METADATA;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.domain.QueryLevel;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryVersionRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Task(CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS)
@Slf4j
public class CheckIngestionStatusForAllDefendantsTask implements ExecutableTask {

    private final DocumentIngestionStatusApi documentIngestionStatusApi;
    private final CaseDocumentRepository caseDocumentRepository;
    private final QueryVersionRepository queryVersionRepository;
    private final ExecutionService executionService;
    private final JobManagerRetryProperties retryProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final UUID documentId = parseUuidOrNull(jobData.getString("docId", null));
        final UUID caseId = parseUuidOrNull(jobData.getString("caseId", null));
        final String blobName = jobData.getString("blobName", null);
        final String documentReference = jobData.getString(CTX_DOC_REFERENCE_KEY, null);
        final boolean isLatestDefendant = jobData.getBoolean(CTX_LATEST_DEFENDANT, false);
        final String defendantId = jobData.getString(CTX_DEFENDANT_ID_KEY);
        final Set<String> failureStatuses = Set.of(
                INGESTION_FAILED.name(),
                INVALID_METADATA.name()
        );

        if (isNull(documentId) || isNull(documentReference)) {
            log.error("{} missing required data docId={} documentReference={}", CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS, documentId, documentReference);
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
            final String rawStatus = body.getStatus() != null ? body.getStatus().getValue() : null;

            if (rawStatus == null) {
                return retry(executionInfo);
            }

            final String status = normalise(rawStatus, 255);

            if (INGESTION_SUCCESS.name().equalsIgnoreCase(status)) {

                updateIngestionPhase(documentId, DocumentIngestionPhase.INGESTED);
                log.info("INGESTION SUCCESS identifier='{}', docId={}", blobName, documentId);

                final Map<String, List<UUID>> queriesByLevel = getQueriesByLevel();
                final List<UUID> caseQueries = queriesByLevel.getOrDefault(QueryLevel.CASE.toString(), List.of());
                log.info("{} Queries count: {}", QueryLevel.CASE, caseQueries.size());

                if (isLatestDefendant && !caseQueries.isEmpty()) {

                    for (UUID questionId : caseQueries) {
                        final JsonObject singleCaseJobData = createObjectBuilder(jobData)
                                .add(CTX_SINGLE_QUERY_ID, questionId.toString())
                                .add(CTX_QUERY_LEVEL, QueryLevel.CASE.toString())
                                .build();

                        final ExecutionInfo executionInfoNew = executionInfo()
                                .from(executionInfo)
                                .withAssignedTaskName(GENERATE_ANSWER_FOR_QUERY)
                                .withJobData(singleCaseJobData)
                                .withExecutionStatus(ExecutionStatus.STARTED)
                                .build();

                        executionService.executeWith(executionInfoNew);

                        log.info("Created {} for docId={} questionId={} ", GENERATE_ANSWER_FOR_QUERY, documentId, questionId);
                    }
                }

                final List<UUID> caseAllDocsQueries = queriesByLevel.getOrDefault(QueryLevel.CASE_ALL_DOCUMENTS.toString(), List.of());
                if (isLatestDefendant && !caseAllDocsQueries.isEmpty()) {

                    final JsonArrayBuilder queryIdsArrayBuilder = Json.createArrayBuilder();

                    caseAllDocsQueries.forEach(queryId -> {
                        queryIdsArrayBuilder.add(queryId.toString());
                    });

                    final JsonArray queryIdsArray = queryIdsArrayBuilder.build();

                        final JsonObject singleCaseJobData = createObjectBuilder(jobData)
                                .add(CTX_QUERYIDS_ARRAY, queryIdsArray)
                                .add(CTX_QUERY_LEVEL, QueryLevel.CASE_ALL_DOCUMENTS.toString())
                                .build();

                    final ExecutionInfo executionInfoNew = executionInfo()
                            .from(executionInfo)
                            .withAssignedTaskName(CHECK_ALL_DOCUMENTS_INGESTION_STATUS)
                            .withJobData(singleCaseJobData)
                            .withExecutionStatus(ExecutionStatus.STARTED)
                            .build();

                    executionService.executeWith(executionInfoNew);

                    log.info("Created {} for docId={} questionId's array={} ", CHECK_ALL_DOCUMENTS_INGESTION_STATUS, documentId, queryIdsArray);

                }

                final List<UUID> defendantQueries = queriesByLevel.getOrDefault(QueryLevel.DEFENDANT.toString(), List.of());
                if (!defendantQueries.isEmpty()) {
                    for (UUID queryId : defendantQueries) {
                        JsonObject job = createObjectBuilder(jobData)
                                .add(CTX_SINGLE_QUERY_ID, queryId.toString())
                                .add(CTX_QUERY_LEVEL, QueryLevel.DEFENDANT.toString())
                                .build();
                        executionService.executeWith(
                                executionInfo()
                                        .from(executionInfo)
                                        .withAssignedTaskName(GENERATE_ANSWER_FOR_QUERY)
                                        .withJobData(job)
                                        .withExecutionStatus(ExecutionStatus.STARTED)
                                        .build()
                        );
                    }
                    log.info("Executed DEFENDANT level queries for defendant {} ", defendantId);
                }

                return complete(executionInfo);
            } else if (failureStatuses.contains(status.toUpperCase())) {

                updateIngestionPhase(documentId, DocumentIngestionPhase.FAILED);
                log.error(
                        "ingestion FAILED for identifier='{}' reason='{}' (caseId={}, docId={}).",
                        blobName,
                        status,
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
        log.debug("Ingestion status not complete for identifier='{}' → retrying", blobName);
        return retry(executionInfo);
    }

    private @NotNull Map<String, List<UUID>> getQueriesByLevel() {
        return queryVersionRepository.snapshotDefinitionsAsOf(utcNow()).stream()
                .filter(r -> r.queryId() != null && r.level() != null)
                .collect(Collectors.groupingBy(
                        QueryVersionRepository.SnapshotDefinition::level,
                        Collectors.mapping(QueryVersionRepository.SnapshotDefinition::queryId, Collectors.toList())
                ));
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        final var retry = retryProperties.getVerifyDocumentStatus();
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
