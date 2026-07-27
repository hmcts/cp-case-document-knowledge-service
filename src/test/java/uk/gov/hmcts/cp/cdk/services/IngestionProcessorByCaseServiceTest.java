package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_MATERIAL_AND_UPLOAD;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;

import uk.gov.hmcts.cp.cdk.domain.CaseQueryStatus;
import uk.gov.hmcts.cp.cdk.domain.QueryLifecycleStatus;
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.CaseQueryStatusRepository;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessByCaseRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private IdpcAvailabilityService idpcAvailabilityService;
    @Mock
    private ExecutionService executionService;
    @Mock
    private CaseQueryStatusRepository caseQueryStatusRepository;
    @Mock
    private CaseDocumentRepository caseDocumentRepository;
    @Captor
    private ArgumentCaptor<ExecutionInfo> executionInfoCaptor;

    private final RetrieveMaterialAndUploadJobDataService retrievalJobDataService = new RetrieveMaterialAndUploadJobDataService();

    private IngestionProcessorByCaseService service;
    private UUID caseId;
    private UUID latestDocId;

    @BeforeEach
    void setUp() {
        service = new IngestionProcessorByCaseService(
                idpcAvailabilityService, retrievalJobDataService, executionService,
                caseQueryStatusRepository, caseDocumentRepository);
        caseId = UUID.randomUUID();
        latestDocId = UUID.randomUUID();
    }

    private CaseQueryStatus answerAvailable() {
        final CaseQueryStatus status = new CaseQueryStatus();
        status.setCaseId(caseId);
        status.setQueryId(UUID.randomUUID());
        status.setDocId(latestDocId);
        status.setStatus(QueryLifecycleStatus.ANSWER_AVAILABLE);
        return status;
    }

    private CaseQueryStatus answerNotAvailable() {
        final CaseQueryStatus status = new CaseQueryStatus();
        status.setCaseId(caseId);
        status.setQueryId(UUID.randomUUID());
        status.setDocId(latestDocId);
        status.setStatus(QueryLifecycleStatus.ANSWER_NOT_AVAILABLE);
        return status;
    }

    private IngestionProcessByCaseRequest request() {
        final IngestionProcessByCaseRequest req = new IngestionProcessByCaseRequest();
        req.setCaseId(caseId);
        return req;
    }

    private NewIdpcDocument newDocument(final String defendantId) {
        return new NewIdpcDocument(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "Material.pdf",
                defendantId,
                UUID.randomUUID().toString(),
                false
        );
    }

    @Test
    @DisplayName("Returns STARTED and dispatches RETRIEVE_MATERIAL_AND_UPLOAD when a newer IDPC exists")
    void returnsStarted_whenNewerIdpcExists() {
        when(idpcAvailabilityService.retrieveDocuments(caseId, CPPUID_VALUE))
                .thenReturn(List.of(newDocument("def-1"), newDocument("def-2")));

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.STARTED);
        assertThat(response.getMessage()).contains("requestId=");
        assertThat(response.getLastUpdated()).isNotNull();
        verify(executionService, times(2)).executeWith(any());
    }

    @Test
    @DisplayName("Returns NOT_REQUIRED when no newer IDPC version exists and an answer already exists for the latest document")
    void returnsNotRequired_whenNoNewerIdpcAndAnswerExists() {
        when(idpcAvailabilityService.retrieveDocuments(caseId, CPPUID_VALUE))
                .thenReturn(List.of());
        when(caseDocumentRepository.findLatestDocId(caseId))
                .thenReturn(Optional.of(latestDocId));
        when(caseQueryStatusRepository.findByCaseIdAndDocId(caseId, latestDocId))
                .thenReturn(List.of(answerAvailable()));

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.NOT_REQUIRED);
        assertThat(response.getMessage()).contains("no newer IDPC version is available");
        verifyNoInteractions(executionService);
    }

    @Test
    @DisplayName("Returns STARTED when no newer IDPC version exists but no answer exists yet for the latest document")
    void returnsStarted_whenNoNewerIdpcAndNoAnswerExists() {
        when(idpcAvailabilityService.retrieveDocuments(caseId, CPPUID_VALUE))
                .thenReturn(List.of());
        when(caseDocumentRepository.findLatestDocId(caseId))
                .thenReturn(Optional.of(latestDocId));
        when(caseQueryStatusRepository.findByCaseIdAndDocId(caseId, latestDocId))
                .thenReturn(List.of(answerNotAvailable()));

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.STARTED);
        assertThat(response.getMessage()).contains("previous answers are still in the process of generating");
        verifyNoInteractions(executionService);
    }

    @Test
    @DisplayName("Returns STARTED when no newer IDPC version exists and no case document is recorded yet")
    void returnsStarted_whenNoNewerIdpcAndNoCaseQueryStatus() {
        when(idpcAvailabilityService.retrieveDocuments(caseId, CPPUID_VALUE))
                .thenReturn(List.of());
        when(caseDocumentRepository.findLatestDocId(caseId))
                .thenReturn(Optional.empty());

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.STARTED);
        assertThat(response.getMessage()).contains("previous answers are still in the process of generating");
        verifyNoInteractions(executionService);
    }

    @Test
    @DisplayName("Returns STARTED when no newer IDPC version exists but the latest document's answer belongs to a different document")
    void returnsStarted_whenLatestDocumentAnswerBelongsToDifferentDoc() {
        when(idpcAvailabilityService.retrieveDocuments(caseId, CPPUID_VALUE))
                .thenReturn(List.of());
        when(caseDocumentRepository.findLatestDocId(caseId))
                .thenReturn(Optional.of(latestDocId));
        when(caseQueryStatusRepository.findByCaseIdAndDocId(caseId, latestDocId))
                .thenReturn(List.of());

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.STARTED);
        assertThat(response.getMessage()).contains("previous answers are still in the process of generating");
        verifyNoInteractions(executionService);
    }

    @Test
    @DisplayName("Returns FAILED when the IDPC availability check throws")
    void returnsFailed_whenIdpcCheckThrows() {
        when(idpcAvailabilityService.retrieveDocuments(caseId, CPPUID_VALUE))
                .thenThrow(new RuntimeException("downstream failure"));

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.FAILED);
        assertThat(response.getMessage()).contains("internal error");
        verifyNoInteractions(executionService);
    }

    @Test
    @DisplayName("Returns FAILED when dispatching the retrieval task throws")
    void returnsFailed_whenDispatchThrows() {
        when(idpcAvailabilityService.retrieveDocuments(caseId, CPPUID_VALUE))
                .thenReturn(List.of(newDocument("def-1")));
        doThrow(new RuntimeException("job queue unavailable"))
                .when(executionService).executeWith(any());

        final IngestionProcessResponse response = service.startIngestionProcess(CPPUID_VALUE, request());

        assertThat(response.getPhase()).isEqualTo(IngestionProcessPhase.FAILED);
    }

    @Test
    @DisplayName("Dispatches RETRIEVE_MATERIAL_AND_UPLOAD at HIGH priority with correct job data")
    void dispatchesAtHighPriority_withCorrectJobData() {
        final NewIdpcDocument doc = newDocument("def-1");
        when(idpcAvailabilityService.retrieveDocuments(caseId, CPPUID_VALUE))
                .thenReturn(List.of(doc));

        service.startIngestionProcess(CPPUID_VALUE, request());

        verify(executionService).executeWith(executionInfoCaptor.capture());
        final ExecutionInfo executionInfo = executionInfoCaptor.getValue();

        assertThat(executionInfo.getPriority()).isEqualTo(JobPriority.HIGH);
        assertThat(executionInfo.getAssignedTaskName()).isEqualTo(RETRIEVE_MATERIAL_AND_UPLOAD);
        assertThat(executionInfo.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(executionInfo.getAssignedTaskStartTime()).isNotNull();
        assertThat(executionInfo.getJobData().getString(CTX_CASE_ID_KEY)).isEqualTo(caseId.toString());
        assertThat(executionInfo.getJobData().getString(CTX_DOC_ID_KEY)).isEqualTo(doc.docId());
        assertThat(executionInfo.getJobData().getString(CTX_DEFENDANT_ID_KEY)).isEqualTo("def-1");
    }
}
