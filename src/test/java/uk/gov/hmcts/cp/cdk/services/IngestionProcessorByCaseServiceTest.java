package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_CASE_ELIGIBILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SYNCHRONOUS_INVOCATION_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.REQUEST_ID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.jobmanager.caseflow.CheckCaseEligibilityTask;
import uk.gov.hmcts.cp.cdk.jobmanager.caseflow.CheckIdpcAvailabilityAllDefendantsTask;
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessByCaseRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.time.ZonedDateTime;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("IngestionProcessorByCaseService tests")
class IngestionProcessorByCaseServiceTest {

    private static final String CPPUID_VALUE = "a085e359-6069-4694-8820-7810e7dfe762";

    @Mock
    private CheckCaseEligibilityTask checkCaseEligibilityTask;
    @Mock
    private CheckIdpcAvailabilityAllDefendantsTask checkIdpcAvailabilityAllDefendantsTask;
    @Captor
    private ArgumentCaptor<ExecutionInfo> executionInfoCaptor;

    private IngestionProcessorByCaseService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        service = new IngestionProcessorByCaseService(checkCaseEligibilityTask, checkIdpcAvailabilityAllDefendantsTask);
        caseId = UUID.randomUUID();
    }

    private IngestionProcessByCaseRequest request() {
        final IngestionProcessByCaseRequest req = new IngestionProcessByCaseRequest();
        req.setCaseId(caseId);
        return req;
    }

    /** What CheckCaseEligibilityTask.execute() returns when the case is eligible. */
    private ExecutionInfo eligibleResult() {
        final JsonObject jobData = Json.createObjectBuilder()
                .add(CPPUID, CPPUID_VALUE)
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .build();
        return executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
                .withExecutionStatus(ExecutionStatus.STARTED)
                .withPriority(JobPriority.HIGH)
                .build();
    }

    /** What CheckCaseEligibilityTask.execute() returns when the case is not eligible. */
    private ExecutionInfo notEligibleResult() {
        return executionInfo()
                .withAssignedTaskName(CHECK_CASE_ELIGIBILITY)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }

    /** What CheckCaseEligibilityTask.execute() returns when it caught an internal failure. */
    private ExecutionInfo failedResult() {
        return executionInfo()
                .withJobData(Json.createObjectBuilder().build())
                .withAssignedTaskName(CHECK_CASE_ELIGIBILITY)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .withShouldRetry(true)
                .build();
    }

    @Test
    @DisplayName("Returns STARTED and dispatches remaining workflow when a newer IDPC exists")
    void returnsStarted_whenNewerIdpcExists() {
        when(checkCaseEligibilityTask.execute(any())).thenReturn(eligibleResult());
        when(checkIdpcAvailabilityAllDefendantsTask.registerNewDocumentsAndDispatch(any())).thenReturn(2);

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.STARTED);
        assertThat(response.getMessage()).contains("requestId=");
        assertThat(response.getLastUpdated()).isNotNull();
    }

    @Test
    @DisplayName("Returns NOT_REQUIRED and dispatches nothing when the case is not eligible")
    void returnsNotRequired_whenNotEligible() {
        when(checkCaseEligibilityTask.execute(any())).thenReturn(notEligibleResult());

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.NOT_REQUIRED);
        assertThat(response.getMessage()).contains("no newer IDPC version is available");
        verifyNoInteractions(checkIdpcAvailabilityAllDefendantsTask);
    }

    @Test
    @DisplayName("Returns NOT_REQUIRED when eligible but no newer IDPC version exists")
    void returnsNotRequired_whenNoNewerIdpc() {
        when(checkCaseEligibilityTask.execute(any())).thenReturn(eligibleResult());
        when(checkIdpcAvailabilityAllDefendantsTask.registerNewDocumentsAndDispatch(any())).thenReturn(0);

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.NOT_REQUIRED);
        assertThat(response.getMessage()).contains("no newer IDPC version is available");
    }

    @Test
    @DisplayName("Returns FAILED when the IDPC availability check throws")
    void returnsFailed_whenIdpcCheckThrows() {
        when(checkCaseEligibilityTask.execute(any())).thenReturn(eligibleResult());
        when(checkIdpcAvailabilityAllDefendantsTask.registerNewDocumentsAndDispatch(any()))
                .thenThrow(new RuntimeException("downstream failure"));

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.FAILED);
        assertThat(response.getMessage()).contains("internal error");
    }

    @Test
    @DisplayName("Returns FAILED when the eligibility check throws")
    void returnsFailed_whenEligibilityCheckThrows() {
        when(checkCaseEligibilityTask.execute(any())).thenThrow(new RuntimeException("downstream failure"));

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.FAILED);
        verify(checkIdpcAvailabilityAllDefendantsTask, never()).registerNewDocumentsAndDispatch(any());
    }

    @Test
    @DisplayName("Returns FAILED when the eligibility check itself reports an internal failure")
    void returnsFailed_whenEligibilityCheckReportsInProgress() {
        when(checkCaseEligibilityTask.execute(any())).thenReturn(failedResult());

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.FAILED);
        verify(checkIdpcAvailabilityAllDefendantsTask, never()).registerNewDocumentsAndDispatch(any());
    }

    @Test
    @DisplayName("Invokes CheckCaseEligibilityTask.execute() directly at HIGH priority, flagged synchronous")
    void invokesEligibilityTaskDirectly_atHighPriority_flaggedSynchronous() {
        when(checkCaseEligibilityTask.execute(executionInfoCaptor.capture())).thenReturn(eligibleResult());
        when(checkIdpcAvailabilityAllDefendantsTask.registerNewDocumentsAndDispatch(any())).thenReturn(1);

        service.startIngestionProcess(CPPUID_VALUE, request());

        final ExecutionInfo submitted = executionInfoCaptor.getValue();

        assertThat(submitted.getAssignedTaskName()).isEqualTo(CHECK_CASE_ELIGIBILITY);
        assertThat(submitted.getPriority()).isEqualTo(JobPriority.HIGH);
        assertThat(submitted.getJobData().getBoolean(CTX_SYNCHRONOUS_INVOCATION_KEY)).isTrue();
        assertThat(submitted.getJobData().getString(CPPUID)).isEqualTo(CPPUID_VALUE);
        assertThat(submitted.getJobData().getString(CTX_CASE_ID_KEY)).isEqualTo(caseId.toString());
        assertThat(submitted.getJobData().containsKey(REQUEST_ID)).isTrue();
    }

    @Test
    @DisplayName("Passes CheckCaseEligibilityTask.execute()'s own result straight into the IDPC check, unmodified")
    void passesEligibilityResultThroughToIdpcCheck() {
        final ExecutionInfo eligibleResult = eligibleResult();
        when(checkCaseEligibilityTask.execute(any())).thenReturn(eligibleResult);
        when(checkIdpcAvailabilityAllDefendantsTask.registerNewDocumentsAndDispatch(any())).thenReturn(1);

        service.startIngestionProcess(CPPUID_VALUE, request());

        verify(checkIdpcAvailabilityAllDefendantsTask).registerNewDocumentsAndDispatch(eligibleResult);
    }
}
