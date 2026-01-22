package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.Optional;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_CASE_ELIGIBILITY)
public class CheckCaseEligibilityTask implements ExecutableTask {

    private final ExecutionService executionService;
    private final ProgressionClient progressionClient;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final var jobData = executionInfo.getJobData();

        final String caseIdStr = jobData.getString(CTX_CASE_ID_KEY, null);
        final String cppuid = jobData.getString(CPPUID, null);

        if (caseIdStr == null || cppuid == null) {
            log.warn("Missing caseId or cppuid, skipping eligibility check");
            return complete(executionInfo);
        }

        final UUID caseId = UUID.fromString(caseIdStr);

        final Optional<ProsecutionCaseEligibilityInfo> eligibilityInfo =
                progressionClient.getProsecutionCaseEligibilityInfo(caseId, cppuid);

        if (eligibilityInfo.isEmpty()) {
            log.info("No prosecution case data found for caseId={}, skipping eligibility", caseId);
            return complete(executionInfo);
        }

        final ProsecutionCaseEligibilityInfo info = eligibilityInfo.get();
        final int defendantCount = info.defendantCount();

        if (defendantCount != 1) {
            log.info(
                    "Case {} has {} defendants. Not eligible to proceed. Completing task.",
                    caseId,
                    defendantCount
            );
            return complete(executionInfo);
        }

        log.info(
                "Case {} has exactly 1 defendant. Proceeding to {}.",
                caseId, CHECK_IDPC_AVAILABILITY
        );

        JsonObjectBuilder updatedJobData = Json.createObjectBuilder(jobData);
        updatedJobData.add(CTX_DEFENDANT_ID_KEY, info.defendantIds().getFirst());

        ExecutionInfo nextTask = ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withAssignedTaskName(CHECK_IDPC_AVAILABILITY)
                .withJobData(updatedJobData.build())
                .withExecutionStatus(ExecutionStatus.STARTED)
                .build();

        executionService.executeWith(nextTask);

        return complete(executionInfo);
    }

    private ExecutionInfo complete(final ExecutionInfo executionInfo) {
        return ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }
}
