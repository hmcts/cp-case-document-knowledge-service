package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.time.ZonedDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.services.CaseEligibilityService;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckCaseEligibilityTaskTest {

    private CheckCaseEligibilityTask task;

    @Mock
    private ExecutionService executionService;

    @Mock
    private CaseEligibilityService caseEligibilityService;

    @Mock
    private JobManagerRetryProperties retryProperties;

    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    private ExecutionInfo executionInfo;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        task = new CheckCaseEligibilityTask(executionService, caseEligibilityService, retryProperties);

        caseId = UUID.randomUUID();

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .add(CPPUID, "cppuid-123")
                .build();

        executionInfo = executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_CASE_ELIGIBILITY)
                .withAssignedTaskStartTime(now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();
    }

    @Test
    void shouldComplete_whenCaseIdOrCppuidMissing() {
        ExecutionInfo input = executionInfo()
                .withJobData(createObjectBuilder().build())
                .build();

        ExecutionInfo result = task.execute(input);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(caseEligibilityService, executionService);
    }

    @Test
    void shouldComplete_whenNotEligible() {
        when(caseEligibilityService.resolveEligibleCase(any(), any()))
                .thenReturn(Optional.empty());

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(executionService);
    }

    @Test
    void shouldStartNextTask_whenEligible() {
        ProsecutionCaseEligibilityInfo info =
                new ProsecutionCaseEligibilityInfo(caseId.toString(), List.of("def-1"));

        JsonObject enrichedJobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .add(CPPUID, "cppuid-123")
                .add(CTX_DEFENDANT_ID_KEY, "def-1")
                .build();

        when(caseEligibilityService.resolveEligibleCase(caseId, "cppuid-123"))
                .thenReturn(Optional.of(info));
        when(caseEligibilityService.withDefendantContext(any(), any()))
                .thenReturn(enrichedJobData);

        ExecutionInfo result = task.execute(executionInfo);

        // current task completed
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        // next task scheduled
        verify(executionService).executeWith(captor.capture());
        ExecutionInfo nextTask = captor.getValue();

        assertThat(nextTask.getAssignedTaskName()).isEqualTo(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS);
        assertThat(nextTask.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(nextTask.getJobData().getString(CTX_DEFENDANT_ID_KEY)).isEqualTo("def-1");
    }

    @Test
    void shouldRetry_whenEligibilityServiceThrowsException() {
        when(caseEligibilityService.resolveEligibleCase(caseId, "cppuid-123"))
                .thenThrow(new RuntimeException("Downstream service failure"));

        final ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();

        verifyNoInteractions(executionService);
    }
}
