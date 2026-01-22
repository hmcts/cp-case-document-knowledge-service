package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_FROM_MATERIAL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_COURTDOCUMENT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuid;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.getCourtDocuments;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_IDPC_AVAILABILITY)
public class CheckIdpcAvailabilityTask implements ExecutableTask {

    private final ProgressionClient progressionClient;
    private final ExecutionService executionService;
    private final DocumentIdResolver documentIdResolver;
    private final JobManagerRetryProperties retryProperties;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final String caseIdString = jobData.getString(CTX_CASE_ID_KEY, null);
        final String userId = jobData.getString(CPPUID, null);
        final String requestId = jobData.getString("requestId", "unknown");

        if (!hasText(caseIdString)) {
            log.warn(
                    "Missing '{}' in jobData → skipping. requestId={}",
                    CTX_CASE_ID_KEY, requestId
            );
            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();
        }

        if (!hasText(userId)) {
            log.warn(
                    "Missing '{}' in jobData; downstream call may fail, Hence closing currentTask{} requestId={}",
                    CPPUID, CHECK_IDPC_AVAILABILITY, requestId
            );
            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();
        }

        Optional<UUID> caseIdUuidOptional;

        try {
            caseIdUuidOptional = parseUuid(caseIdString);

            final Optional<LatestMaterialInfo> latest =
                    getCourtDocuments(progressionClient, caseIdUuidOptional.get(), userId);
            latest.ifPresent(info -> {
                JsonObjectBuilder updatedJobData = Json.createObjectBuilder(jobData);
                updatedJobData.add(CTX_MATERIAL_ID_KEY, info.materialId());
                updatedJobData.add(CTX_MATERIAL_NAME, info.materialName());
                updatedJobData.add(CTX_COURTDOCUMENT_ID_KEY, info.courtDocumentId());


                final Optional<UUID> existingDocUuid =
                        documentIdResolver.resolveExistingDocId(caseIdUuidOptional.get(), UUID.fromString(info.materialId()));

                final String existingDocId = existingDocUuid.map(UUID::toString).orElse(null);
                final String newDocId = existingDocId == null ? UUID.randomUUID().toString() : null;


                if (existingDocId != null) {
                    log.info("Resolved existing docId={} for caseId={}, materialId={} , hence skipping upload: ", existingDocId, caseIdUuidOptional.get(), info.materialId());
                } else {
                    log.debug("No existing docId; generated new docId={} for caseId={}, materialId={}.",
                            newDocId, caseIdUuidOptional.get(), info.materialId());
                }

                if (newDocId != null) {
                    updatedJobData.add(CTX_DOC_ID_KEY, newDocId);

                    ExecutionInfo newTask = ExecutionInfo.executionInfo()
                            .from(executionInfo)
                            .withAssignedTaskName(RETRIEVE_FROM_MATERIAL)
                            .withJobData(updatedJobData.build())
                            .withExecutionStatus(ExecutionStatus.STARTED)
                            .build();

                    executionService.executeWith(newTask);

                }

                log.debug(
                        "Resolved material for caseId {} → id={}, name={}, requestId={}",
                        caseIdString,
                        info.materialId(),
                        info.materialName(),
                        requestId
                );
            });


            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (Exception ex) {
            log.error(
                    "{} failed. caseId={}, requestId={}", CHECK_IDPC_AVAILABILITY,
                    caseIdString, requestId, ex
            );

            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.INPROGRESS)
                    .withShouldRetry(true)
                    .build();
        }
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        var retry = retryProperties.getDefaultRetry();
        return Optional.of(
                IntStream.range(0, retry.getMaxAttempts())
                        .mapToLong(i -> retry.getDelaySeconds())
                        .boxed()
                        .toList()
        );
    }
}
