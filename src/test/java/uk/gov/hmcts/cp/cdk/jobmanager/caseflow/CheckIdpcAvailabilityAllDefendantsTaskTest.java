package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;

import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.services.IdpcAvailabilityService;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.ZonedDateTime;
import java.util.List;

import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckIdpcAvailabilityAllDefendantsTaskTest {

    private CheckIdpcAvailabilityAllDefendantsTask task;

    @Mock
    private IdpcAvailabilityService idpcAvailabilityService;
    @Mock
    private JobManagerRetryProperties retryProperties;

    private String caseId;
    private String userId;

    @BeforeEach
    void setUp() {
        task = new CheckIdpcAvailabilityAllDefendantsTask(idpcAvailabilityService, retryProperties);

        caseId = "case-123";
        userId = "cppuid-123";
    }

    private ExecutionInfo executionInfo(JsonObject jobData) {
        return ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();
    }

    @Test
    void shouldComplete_whenServiceSucceeds() {
        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(idpcAvailabilityService.registerNewDocumentsAndDispatch(any())).thenReturn(2);

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(idpcAvailabilityService).registerNewDocumentsAndDispatch(any());
    }

    @Test
    void shouldRetry_whenServiceThrows() {
        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(idpcAvailabilityService.registerNewDocumentsAndDispatch(any()))
                .thenThrow(new RuntimeException("Downstream service failure"));

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void shouldReturnRetryDurations() {
        final JobManagerRetryProperties.RetryConfig retryConfig = new JobManagerRetryProperties.RetryConfig();
        retryConfig.setMaxAttempts(3);
        retryConfig.setDelaySeconds(10);
        when(retryProperties.getDefaultRetry()).thenReturn(retryConfig);

        final List<Long> durations = task.getRetryDurationsInSecs().orElseThrow();

        assertThat(durations).isEqualTo(List.of(10L, 10L, 10L));
    }
}
