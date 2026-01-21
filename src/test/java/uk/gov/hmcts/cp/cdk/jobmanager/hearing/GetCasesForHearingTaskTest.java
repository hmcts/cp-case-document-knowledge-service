package uk.gov.hmcts.cp.cdk.jobmanager.hearing;

import static jakarta.json.Json.createObjectBuilder;
import static java.time.ZonedDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GET_CASES_FOR_HEARING;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.COURT_CENTRE_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.DATE;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.ROOM_ID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.clients.hearing.HearingClient;
import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.LocalDate;
import java.util.List;

import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetCasesForHearingTaskTest {

    private GetCasesForHearingTask task;

    @Mock
    private HearingClient hearingClient;

    @Mock
    private ExecutionService executionService;

    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    private ExecutionInfo executionInfo;

    @BeforeEach
    void setUp() {
        task = new GetCasesForHearingTask(hearingClient, executionService);

        JsonObject jobData = createObjectBuilder()
                .add(COURT_CENTRE_ID, "court-1")
                .add(ROOM_ID, "room-1")
                .add(DATE, "2026-01-21")
                .add(CPPUID, "cppuid-123")
                .add("requestId", "req-1")
                .build();

        executionInfo = executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(GET_CASES_FOR_HEARING)
                .withAssignedTaskStartTime(now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();
    }

    @Test
    void shouldComplete_whenRequiredFieldsAreMissing() {
        ExecutionInfo input = executionInfo()
                .withJobData(createObjectBuilder().build())
                .build();

        ExecutionInfo result = task.execute(input);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(hearingClient, executionService);
    }

    @Test
    void shouldComplete_whenDateIsInvalid() {
        JsonObject jobData = createObjectBuilder(executionInfo.getJobData())
                .add(DATE, "not-a-date")
                .build();

        ExecutionInfo input = executionInfo().withJobData(jobData).build();

        ExecutionInfo result = task.execute(input);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(hearingClient, executionService);
    }

    @Test
    void shouldComplete_whenNoCasesReturned() {
        when(hearingClient.getHearingsAndCases(
                any(), any(), any(LocalDate.class), any()
        )).thenReturn(List.of());

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(executionService);
    }

    @Test
    void shouldCreateEligibilityTaskForEachCase() {
        HearingSummariesInfo case1 = new HearingSummariesInfo("case-1");
        HearingSummariesInfo case2 = new HearingSummariesInfo("case-2");

        when(hearingClient.getHearingsAndCases(
                any(), any(), any(LocalDate.class), any()
        )).thenReturn(List.of(case1, case2));

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        verify(executionService, times(2)).executeWith(captor.capture());

        List<ExecutionInfo> scheduledTasks = captor.getAllValues();
        assertThat(scheduledTasks).hasSize(2);

        assertThat(scheduledTasks)
                .allSatisfy(task -> {
                    assertThat(task.getAssignedTaskName()).isEqualTo(CHECK_CASE_ELIGIBILITY);
                    assertThat(task.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
                    assertThat(task.getJobData().getString(CPPUID))
                            .isEqualTo("cppuid-123");
                });

        assertThat(
                scheduledTasks.stream()
                        .map(t -> t.getJobData().getString(CTX_CASE_ID_KEY))
                        .toList()
        ).containsExactlyInAnyOrder("case-1", "case-2");
    }


    @Test
    void shouldRetry_whenClientThrowsException() {
        when(hearingClient.getHearingsAndCases(
                any(), any(), any(LocalDate.class), any()
        )).thenThrow(new RuntimeException("boom"));

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
        verifyNoInteractions(executionService);
    }
}
