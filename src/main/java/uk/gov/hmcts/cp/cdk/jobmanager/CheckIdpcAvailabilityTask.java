package uk.gov.hmcts.cp.cdk.jobmanager;

import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.USERID_FOR_EXTERNAL_CALLS;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.parseUuid;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.safeGetCourtDocuments;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;


import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@Task("CHECK_IDPC_AVAILABILITY")
public class CheckIdpcAvailabilityTask implements ExecutableTask {

    private final ProgressionClient progressionClient;
    private final ExecutionService taskExecutionService;
    private final DocumentIdResolver documentIdResolver;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final String caseIdString = jobData.getString(CTX_CASE_ID_KEY, null);
        final String userId = jobData.getString(USERID_FOR_EXTERNAL_CALLS, null);
        final String requestId = jobData.getString("requestId", "unknown");

        boolean proceed = true;

        if (!hasText(caseIdString)) {
            log.warn(
                    "Missing '{}' in jobData → skipping. requestId={}",
                    CTX_CASE_ID_KEY, requestId
            );
            proceed = false;
        }

        if (!hasText(userId)) {
            log.warn(
                    "Missing '{}' in jobData; downstream call may fail. requestId={}",
                    USERID_FOR_EXTERNAL_CALLS, requestId
            );
        }

        Optional<UUID> caseIdUuidOptional;
        if (proceed) {
            caseIdUuidOptional = parseUuid(caseIdString);
            if (caseIdUuidOptional.isEmpty()) {
                log.warn(
                        "CaseId '{}' is not a valid UUID → skipping. requestId={}",
                        caseIdString, requestId
                );
                proceed = false;
            }
        } else {
            caseIdUuidOptional = Optional.empty();
        }

        try {
            JsonObjectBuilder updatedJobData = Json.createObjectBuilder(jobData);

            if (proceed) {
                final Optional<LatestMaterialInfo> latest =
                        safeGetCourtDocuments(progressionClient, caseIdUuidOptional.get(), userId);
                log.info("finished progression for case");
                latest.ifPresent(info -> {
                    updatedJobData.add(CTX_MATERIAL_ID_KEY, info.materialId());
                    updatedJobData.add(CTX_MATERIAL_NAME, info.materialName());

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

                    if(newDocId!=null) {
                        updatedJobData.add(CTX_DOC_ID_KEY, newDocId);

                        ExecutionInfo newTask = ExecutionInfo.executionInfo()
                                .from(executionInfo)
                                .withAssignedTaskName("RETRIEVE_FROM_MATERIAL")
                                .withJobData(updatedJobData.build())
                                .withExecutionStatus(ExecutionStatus.STARTED)
                                .build();

                        taskExecutionService.executeWith(newTask);

                    }

                    log.debug(
                            "Resolved material for caseId {} → id={}, name={}, requestId={}",
                            caseIdString,
                            info.materialId(),
                            info.materialName(),
                            requestId
                    );
                });
            }

            return ExecutionInfo.executionInfo()
                    .from(executionInfo)
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .withJobData(updatedJobData.build())
                    .build();

        } catch (Exception ex) {
            log.error(
                    "CHECK_IDPC_AVAILABILITY failed. caseId={}, requestId={}",
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
        // mirrors RetryingTasklet behaviour
        return Optional.of(List.of(10L, 30L, 60L));
    }
}
