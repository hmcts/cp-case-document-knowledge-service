package uk.gov.hmcts.cp.cdk.services;

import static jakarta.json.Json.createObjectBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_MATERIAL_AND_UPLOAD;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOCIDS_ARRAY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_LATEST_DEFENDANT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.jobmanager.support.JobPriority;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
@DisplayName("IdpcAvailabilityService tests")
class IdpcAvailabilityServiceTest {

    public static final int EXPECTED_SIZE = 50;

    @Mock
    private ProgressionClient progressionClient;
    @Mock
    private ExecutionService executionService;
    @Mock
    private DocumentIdResolver documentIdResolver;
    @Mock
    private CaseDocumentRepository caseDocumentRepository;
    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    private IdpcAvailabilityService service;
    private String caseId;
    private String userId;

    @BeforeEach
    void setUp() {
        service = new IdpcAvailabilityService(
                progressionClient,
                executionService,
                documentIdResolver,
                caseDocumentRepository
        );

        caseId = UUID.randomUUID().toString();
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
    @DisplayName("Returns zero and dispatches nothing when there are no materials")
    void returnsZero_whenNoMaterials() {
        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of());

        int result = service.registerNewDocumentsAndDispatch(executionInfo(jobData));

        assertThat(result).isZero();
        verifyNoInteractions(executionService);
    }

    @Test
    @DisplayName("Skips defendants whose document already exists")
    void skipsExistingDocuments() {
        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId),
                "doc-type",
                "desc",
                materialId.toString(),
                "Material",
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));
        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));

        int result = service.registerNewDocumentsAndDispatch(executionInfo(jobData));

        assertThat(result).isZero();
        verifyNoInteractions(executionService);
    }

    @Test
    @DisplayName("Registers and dispatches a retrieval task per new defendant document")
    void schedulesTasks_forMultipleDefendants() {
        UUID materialId = UUID.randomUUID();
        UUID def1 = UUID.randomUUID();
        UUID def2 = UUID.randomUUID();

        LatestMaterialInfo m1 = new LatestMaterialInfo(
                List.of(caseId), "doc", "desc",
                materialId.toString(), "Material1",
                ZonedDateTime.now().minusMinutes(1),
                UUID.randomUUID().toString(),
                def1.toString()
        );

        LatestMaterialInfo m2 = new LatestMaterialInfo(
                List.of(caseId), "doc", "desc",
                materialId.toString(), "Material2",
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                def2.toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(m1, m2));

        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        int result = service.registerNewDocumentsAndDispatch(executionInfo(jobData));

        assertThat(result).isEqualTo(2);

        verify(executionService, times(2)).executeWith(captor.capture());

        List<ExecutionInfo> executions = captor.getAllValues();

        for (ExecutionInfo exec : executions) {
            assertThat(exec.getAssignedTaskName()).isEqualTo(RETRIEVE_MATERIAL_AND_UPLOAD);
            assertThat(exec.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
            assertThat(exec.getJobData().containsKey(CTX_DOC_ID_KEY)).isTrue();
            assertThat(exec.getJobData().containsKey(CTX_DOCIDS_ARRAY)).isTrue();
        }

        assertThat(executions.stream()
                .anyMatch(e -> e.getJobData().getBoolean(CTX_LATEST_DEFENDANT)))
                .isTrue();
    }

    @Test
    @DisplayName("Propagates priority from the incoming ExecutionInfo to the dispatched retrieval job")
    void propagatesPriorityToDispatchedJob() {
        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId), "doc", "desc",
                materialId.toString(), "Material",
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));
        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        ExecutionInfo highPriorityExecutionInfo = ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_IDPC_AVAILABILITY_ALL_DEFENDANTS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.STARTED)
                .withPriority(JobPriority.HIGH)
                .build();

        service.registerNewDocumentsAndDispatch(highPriorityExecutionInfo);

        verify(executionService).executeWith(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo(JobPriority.HIGH);
    }

    @Test
    @DisplayName("shouldNotTruncateMaterialName_whenExactly50Characters")
    void shouldNotTruncateMaterialName_whenExactly50Characters() {
        String materialName = "12345678901234567890123456789012345678901234567890"; // 50 chars

        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId),
                "doc",
                "desc",
                materialId.toString(),
                materialName,
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));

        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.registerNewDocumentsAndDispatch(executionInfo(jobData));

        verify(executionService).executeWith(captor.capture());

        assertThat(
                captor.getValue().getJobData().getString(CTX_MATERIAL_NAME)
        ).isEqualTo(materialName);
    }

    @Test
    @DisplayName("shouldTruncateMaterialNameAndPreservePdfExtension")
    void shouldTruncateMaterialNameAndPreservePdfExtension() {
        String materialName =
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.pdf";

        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId),
                "doc",
                "desc",
                materialId.toString(),
                materialName,
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));

        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.registerNewDocumentsAndDispatch(executionInfo(jobData));

        verify(executionService).executeWith(captor.capture());

        String actualName =
                captor.getValue().getJobData().getString(CTX_MATERIAL_NAME);

        assertThat(actualName)
                .endsWith(".pdf")
                .hasSize(EXPECTED_SIZE);
    }

    @Test
    @DisplayName("shouldTruncateMaterialNameWithoutExtension")
    void shouldTruncateMaterialNameWithoutExtension() {
        String materialName =
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";

        UUID materialId = UUID.randomUUID();
        UUID defendantId = UUID.randomUUID();

        LatestMaterialInfo info = new LatestMaterialInfo(
                List.of(caseId),
                "doc",
                "desc",
                materialId.toString(),
                materialName,
                ZonedDateTime.now(),
                UUID.randomUUID().toString(),
                defendantId.toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocumentsForAllDefendants(any(), any()))
                .thenReturn(List.of(info));

        when(documentIdResolver.resolveExistingDocIdForDefendant(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.registerNewDocumentsAndDispatch(executionInfo(jobData));

        verify(executionService).executeWith(captor.capture());

        String actualName =
                captor.getValue().getJobData().getString(CTX_MATERIAL_NAME);

        assertThat(actualName)
                .hasSize(EXPECTED_SIZE)
                .doesNotEndWith(".pdf");
    }
}
