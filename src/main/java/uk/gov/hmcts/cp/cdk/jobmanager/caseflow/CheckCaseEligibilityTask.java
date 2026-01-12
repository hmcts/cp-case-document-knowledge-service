package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY;

import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_CASE_ELIGIBILITY)
public class CheckCaseEligibilityTask implements ExecutableTask {

    private final ExecutionService taskExecutionService;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        ExecutionInfo nextTask = ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withAssignedTaskName(CHECK_IDPC_AVAILABILITY)
                .withExecutionStatus(ExecutionStatus.STARTED)
                .build();

        taskExecutionService.executeWith(nextTask);

        log.info(
                "Chained CHECK_CASE_ELIGIBILITY → CHECK_IDPC_AVAILABILITY for caseId={}",
                executionInfo.getJobData().getString("caseId", "unknown")
        );

        return ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }
}
