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
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_COUNT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SYNCHRONOUS_INVOCATION_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
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
    private ProgressionClient progressionClient;

    @Mock
    private JobManagerRetryProperties retryProperties;

    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    private ExecutionInfo executionInfo;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        task = new CheckCaseEligibilityTask(executionService, progressionClient, retryProperties);

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
        verifyNoInteractions(progressionClient, executionService);
    }

    @Test
    void shouldComplete_whenEligibilityInfoNotFound() {
        when(progressionClient.getProsecutionCaseEligibilityInfo(any(), any()))
                .thenReturn(Optional.empty());

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(executionService);
    }

    @Test
    void shouldComplete_whenNoDefendant() {
        ProsecutionCaseEligibilityInfo info =
                new ProsecutionCaseEligibilityInfo(
                        caseId.toString(),
                        List.of()
                );

        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, "cppuid-123"))
                .thenReturn(Optional.of(info));

        ExecutionInfo result = task.execute(executionInfo);
        //need to revisit as per multi defendant
        //assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(executionService);
    }

    @Test
    void shouldComplete_whenZeroDefendants() {

        final ProsecutionCaseEligibilityInfo info = new ProsecutionCaseEligibilityInfo(
                        caseId.toString(),
                        List.of()
                );

        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, "cppuid-123"))
                .thenReturn(Optional.of(info));
        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(executionService);
    }

    @Test
    void shouldStartNextTask_whenExactlyOneDefendant() {
        ProsecutionCaseEligibilityInfo info =
                new ProsecutionCaseEligibilityInfo(
                        caseId.toString(),
                        List.of("def-1")
                );

        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, "cppuid-123"))
                .thenReturn(Optional.of(info));
        ExecutionInfo result = task.execute(executionInfo);

        // current task completed
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        // next task scheduled
        verify(executionService).executeWith(captor.capture());
        ExecutionInfo nextTask = captor.getValue();

        assertThat(nextTask.getAssignedTaskName()).isEqualTo(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS);
        assertThat(nextTask.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
    }

    @Test
    void shouldRetry_whenProgressionClientThrowsException() {
        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, "cppuid-123"))
                .thenThrow(new RuntimeException("Downstream service failure"));

        final ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();

        verifyNoInteractions(executionService);
    }

    @Test
    void execute_returnsFollowOnExecutionInfo_whenSynchronousInvocationAndEligible() {
        final ProsecutionCaseEligibilityInfo info =
                new ProsecutionCaseEligibilityInfo(caseId.toString(), List.of("def-1"));
        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, "cppuid-123"))
                .thenReturn(Optional.of(info));

        final JsonObject syncJobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .add(CPPUID, "cppuid-123")
                .add(CTX_SYNCHRONOUS_INVOCATION_KEY, true)
                .build();

        final ExecutionInfo syncExecutionInfo = executionInfo()
                .withJobData(syncJobData)
                .withAssignedTaskName(CHECK_CASE_ELIGIBILITY)
                .withAssignedTaskStartTime(now())
                .withExecutionStatus(ExecutionStatus.STARTED)
                .withPriority(JobPriority.HIGH)
                .build();

        final ExecutionInfo result = task.execute(syncExecutionInfo);

        assertThat(result.getAssignedTaskName()).isEqualTo(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(result.getPriority()).isEqualTo(JobPriority.HIGH);
        assertThat(result.getJobData().getString(CTX_DEFENDANT_ID_KEY)).isEqualTo("def-1");

        verifyNoInteractions(executionService);
    }

    @Test
    void execute_completesWithoutDispatch_whenSynchronousInvocationAndNotEligible() {
        when(progressionClient.getProsecutionCaseEligibilityInfo(any(), any()))
                .thenReturn(Optional.empty());

        final JsonObject syncJobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .add(CPPUID, "cppuid-123")
                .add(CTX_SYNCHRONOUS_INVOCATION_KEY, true)
                .build();

        final ExecutionInfo syncExecutionInfo = executionInfo()
                .withJobData(syncJobData)
                .withAssignedTaskName(CHECK_CASE_ELIGIBILITY)
                .withAssignedTaskStartTime(now())
                .withExecutionStatus(ExecutionStatus.STARTED)
                .build();

        final ExecutionInfo result = task.execute(syncExecutionInfo);

        assertThat(result.getAssignedTaskName()).isEqualTo(CHECK_CASE_ELIGIBILITY);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        verifyNoInteractions(executionService);
    }

    /*
     * resolveEligibleCase / withDefendantContext are package-private, called both by execute()
     * (above) and internally reused by this same class — not exposed outside the jobmanager.caseflow
     * package. These tests cover them as a standalone unit, independent of execute()'s job-data
     * parsing/early-return wrapper.
     */

    @Test
    void resolveEligibleCase_empty_whenArgumentsNull() {
        assertThat(task.resolveEligibleCase(null, "cppuid-123")).isEmpty();
        assertThat(task.resolveEligibleCase(caseId, null)).isEmpty();
        verifyNoInteractions(progressionClient);
    }

    @Test
    void resolveEligibleCase_empty_whenNoProsecutionCase() {
        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, "cppuid-123"))
                .thenReturn(Optional.empty());

        assertThat(task.resolveEligibleCase(caseId, "cppuid-123")).isEmpty();
    }

    @Test
    void resolveEligibleCase_present_whenEligible() {
        final ProsecutionCaseEligibilityInfo info =
                new ProsecutionCaseEligibilityInfo(caseId.toString(), List.of("def-1", "def-2"));
        when(progressionClient.getProsecutionCaseEligibilityInfo(caseId, "cppuid-123"))
                .thenReturn(Optional.of(info));

        assertThat(task.resolveEligibleCase(caseId, "cppuid-123")).contains(info);
    }

    @Test
    void withDefendantContext_addsFirstDefendantIdAndCount() {
        final JsonObject base = createObjectBuilder().add("existing", "value").build();
        final ProsecutionCaseEligibilityInfo info =
                new ProsecutionCaseEligibilityInfo(caseId.toString(), List.of("def-1", "def-2"));

        final JsonObject enriched = task.withDefendantContext(base, info);

        assertThat(enriched.getString("existing")).isEqualTo("value");
        assertThat(enriched.getString(CTX_DEFENDANT_ID_KEY)).isEqualTo("def-1");
        assertThat(enriched.getInt(CTX_DEFENDANT_COUNT)).isEqualTo(2);
    }
}
