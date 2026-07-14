package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.REQUEST_ID;

import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessByCaseRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;

import java.util.List;
import java.util.Optional;
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
    private CaseEligibilityService caseEligibilityService;
    @Mock
    private IdpcAvailabilityService idpcAvailabilityService;
    @Captor
    private ArgumentCaptor<ExecutionInfo> executionInfoCaptor;

    private IngestionProcessorByCaseService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        service = new IngestionProcessorByCaseService(caseEligibilityService, idpcAvailabilityService);
        caseId = UUID.randomUUID();
    }

    private IngestionProcessByCaseRequest request() {
        final IngestionProcessByCaseRequest req = new IngestionProcessByCaseRequest();
        req.setCaseId(caseId);
        return req;
    }

    private ProsecutionCaseEligibilityInfo eligibleCase() {
        return new ProsecutionCaseEligibilityInfo(caseId.toString(), List.of("def-1"));
    }

    private JsonObject enrichedJobData() {
        return Json.createObjectBuilder()
                .add(CPPUID, CPPUID_VALUE)
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .build();
    }

    @Test
    @DisplayName("Returns STARTED and dispatches remaining workflow when a newer IDPC exists")
    void returnsStarted_whenNewerIdpcExists() {
        when(caseEligibilityService.resolveEligibleCase(caseId, CPPUID_VALUE))
                .thenReturn(Optional.of(eligibleCase()));
        when(caseEligibilityService.withDefendantContext(any(), any())).thenReturn(enrichedJobData());
        when(idpcAvailabilityService.registerNewDocumentsAndDispatch(any())).thenReturn(2);

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.STARTED);
        assertThat(response.getMessage()).contains("requestId=");
        assertThat(response.getLastUpdated()).isNotNull();
    }

    @Test
    @DisplayName("Returns NOT_REQUIRED and dispatches nothing when the case is not eligible")
    void returnsNotRequired_whenNotEligible() {
        when(caseEligibilityService.resolveEligibleCase(caseId, CPPUID_VALUE))
                .thenReturn(Optional.empty());

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.NOT_REQUIRED);
        assertThat(response.getMessage()).contains("not eligible for ingestion");
        verifyNoInteractions(idpcAvailabilityService);
    }

    @Test
    @DisplayName("Returns NOT_REQUIRED when eligible but no newer IDPC version exists")
    void returnsNotRequired_whenNoNewerIdpc() {
        when(caseEligibilityService.resolveEligibleCase(caseId, CPPUID_VALUE))
                .thenReturn(Optional.of(eligibleCase()));
        when(caseEligibilityService.withDefendantContext(any(), any())).thenReturn(enrichedJobData());
        when(idpcAvailabilityService.registerNewDocumentsAndDispatch(any())).thenReturn(0);

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.NOT_REQUIRED);
        assertThat(response.getMessage()).contains("no newer IDPC version is available");
    }

    @Test
    @DisplayName("Returns FAILED when the IDPC availability check throws")
    void returnsFailed_whenIdpcCheckThrows() {
        when(caseEligibilityService.resolveEligibleCase(caseId, CPPUID_VALUE))
                .thenReturn(Optional.of(eligibleCase()));
        when(caseEligibilityService.withDefendantContext(any(), any())).thenReturn(enrichedJobData());
        when(idpcAvailabilityService.registerNewDocumentsAndDispatch(any()))
                .thenThrow(new RuntimeException("downstream failure"));

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.FAILED);
        assertThat(response.getMessage()).contains("internal error");
    }

    @Test
    @DisplayName("Returns FAILED when the eligibility check throws")
    void returnsFailed_whenEligibilityCheckThrows() {
        when(caseEligibilityService.resolveEligibleCase(caseId, CPPUID_VALUE))
                .thenThrow(new RuntimeException("downstream failure"));

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.FAILED);
        verify(idpcAvailabilityService, never()).registerNewDocumentsAndDispatch(any());
    }

    @Test
    @DisplayName("Dispatches the IDPC step at HIGH priority carrying case context")
    void dispatchesAtHighPriority() {
        when(caseEligibilityService.resolveEligibleCase(caseId, CPPUID_VALUE))
                .thenReturn(Optional.of(eligibleCase()));
        when(caseEligibilityService.withDefendantContext(any(), any())).thenReturn(enrichedJobData());
        when(idpcAvailabilityService.registerNewDocumentsAndDispatch(any())).thenReturn(1);

        service.startIngestionProcess(CPPUID_VALUE, request());

        verify(idpcAvailabilityService).registerNewDocumentsAndDispatch(executionInfoCaptor.capture());
        final ExecutionInfo executionInfo = executionInfoCaptor.getValue();

        assertThat(executionInfo.getPriority()).isEqualTo(JobPriority.HIGH);
        assertThat(executionInfo.getAssignedTaskName()).isEqualTo(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS);
        assertThat(executionInfo.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(executionInfo.getAssignedTaskStartTime()).isNotNull();
        assertThat(executionInfo.getJobData().getString(CTX_CASE_ID_KEY)).isEqualTo(caseId.toString());
    }

    @Test
    @DisplayName("Builds the base job data with cppuid, requestId and caseId before enrichment")
    void buildsBaseJobData() {
        final ArgumentCaptor<JsonObject> jobDataCaptor = ArgumentCaptor.forClass(JsonObject.class);
        when(caseEligibilityService.resolveEligibleCase(caseId, CPPUID_VALUE))
                .thenReturn(Optional.of(eligibleCase()));
        when(caseEligibilityService.withDefendantContext(jobDataCaptor.capture(), eq(eligibleCase())))
                .thenReturn(enrichedJobData());
        when(idpcAvailabilityService.registerNewDocumentsAndDispatch(any())).thenReturn(1);

        service.startIngestionProcess(CPPUID_VALUE, request());

        final JsonObject baseJobData = jobDataCaptor.getValue();
        assertThat(baseJobData.getString(CPPUID)).isEqualTo(CPPUID_VALUE);
        assertThat(baseJobData.getString(CTX_CASE_ID_KEY)).isEqualTo(caseId.toString());
        assertThat(baseJobData.containsKey(REQUEST_ID)).isTrue();
    }
}
