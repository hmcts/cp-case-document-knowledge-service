package uk.gov.hmcts.cp.cdk.tasks;

import static org.springframework.util.StringUtils.hasText;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.USERID_FOR_EXTERNAL_CALLS;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_CASE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.parseUuid;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskletUtils.safeGetCourtDocuments;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
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
@Task("RESOLVE_MATERIAL_FOR_CASE_TASK")
public class ResolveMaterialForCaseTask implements ExecutableTask {

    private final ProgressionClient progressionClient;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();

        final String caseIdString = jobData.getString(PARTITION_CASE_ID, null);
        final String userId = jobData.getString(USERID_FOR_EXTERNAL_CALLS, null);
        final String requestId = jobData.getString("requestId", "unknown");

        boolean proceed = true;

        if (!hasText(caseIdString)) {
            log.warn(
                    "Missing '{}' in jobData → skipping. requestId={}",
                    PARTITION_CASE_ID, requestId
            );
            proceed = false;
        }

        if (!hasText(userId)) {
            log.warn(
                    "Missing '{}' in jobData; downstream call may fail. requestId={}",
                    USERID_FOR_EXTERNAL_CALLS, requestId
            );
        }

        Optional<UUID> caseIdUuidOptional = Optional.empty();
        if (proceed) {
            caseIdUuidOptional = parseUuid(caseIdString);
            if (caseIdUuidOptional.isEmpty()) {
                log.warn(
                        "CaseId '{}' is not a valid UUID → skipping. requestId={}",
                        caseIdString, requestId
                );
                proceed = false;
            }
        }

        try {
            JsonObjectBuilder updatedJobData = Json.createObjectBuilder(jobData);

            if (proceed) {
                log.info("calling progression for case");
                final Optional<LatestMaterialInfo> latest =
                        safeGetCourtDocuments(progressionClient, caseIdUuidOptional.get(), userId);
                log.info("finished progression for case");
                latest.ifPresent(info -> {
                    updatedJobData.add(PARTITION_RESULT_MATERIAL_ID, info.materialId());
                    updatedJobData.add(PARTITION_RESULT_MATERIAL_NAME, info.materialName());

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
                    .withJobData(updatedJobData.build())
                    .withExecutionStatus(ExecutionStatus.COMPLETED)
                    .build();

        } catch (Exception ex) {
            log.error(
                    "RESOLVE_MATERIAL_FOR_CASE_TASK failed. caseId={}, requestId={}",
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
