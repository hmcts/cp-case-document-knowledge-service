package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_IDPC_AVAILABILITY;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_FROM_MATERIAL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckIdpcAvailabilityTaskTest {

    private CheckIdpcAvailabilityTask task;

    @Mock
    private ProgressionClient progressionClient;
    @Mock
    private ExecutionService executionService;
    @Mock
    private DocumentIdResolver documentIdResolver;
    @Mock
    private JobManagerRetryProperties retryProperties;

    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    private String caseId;
    private String userId;

    @BeforeEach
    void setUp() {
        task = new CheckIdpcAvailabilityTask(
                progressionClient,
                executionService,
                documentIdResolver,
                retryProperties
        );

        caseId = UUID.randomUUID().toString();
        userId = "cppuid-123";
    }

    // ---------------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------------

    private ExecutionInfo executionInfo(JsonObject jobData) {
        return ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_IDPC_AVAILABILITY)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();
    }

    @Test
    void shouldComplete_whenCaseIdMissing() {
        JsonObject jobData = createObjectBuilder()
                .add(CPPUID, userId)
                .build();

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue(); // 🔥 THIS CHANGED

        verifyNoInteractions(progressionClient, executionService, documentIdResolver);
    }
    @Test
    void shouldComplete_whenUserIdMissing() {
        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .build();

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED); // 🔥 CHANGED
        assertThat(result.isShouldRetry()).isFalse();

    }


    @Test
    void shouldComplete_whenNoLatestMaterial() {
        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocuments(any(), any()))
                .thenReturn(Optional.empty());

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();

        verify(executionService, never()).executeWith(any());
    }

    @Test
    void shouldSkipUpload_whenExistingDocIdPresent() {
        UUID materialId = UUID.randomUUID();

        LatestMaterialInfo materialInfo = new LatestMaterialInfo(
                List.of(caseId),
                "doc-type-1",
                "Document Type Description",
                materialId.toString(),
                "Material A",
                ZonedDateTime.now(),
                UUID.randomUUID().toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocuments(any(), any()))
                .thenReturn(Optional.of(materialInfo));
        when(documentIdResolver.resolveExistingDocId(any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();

        verify(executionService, never()).executeWith(any());
    }

    @Test
    void shouldScheduleRetrieveFromMaterial_whenNoExistingDocId() {
        UUID materialId = UUID.randomUUID();

        LatestMaterialInfo materialInfo = new LatestMaterialInfo(
                List.of(caseId),
                "doc-type-1",
                "Document Type Description",
                materialId.toString(),
                "Material A",
                ZonedDateTime.now(),
                UUID.randomUUID().toString()
        );

        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocuments(any(), any()))
                .thenReturn(Optional.of(materialInfo));
        when(documentIdResolver.resolveExistingDocId(any(), any()))
                .thenReturn(Optional.empty());

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();

        verify(executionService).executeWith(captor.capture());
        ExecutionInfo nextTask = captor.getValue();

        assertThat(nextTask.getAssignedTaskName()).isEqualTo(RETRIEVE_FROM_MATERIAL);
        assertThat(nextTask.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(nextTask.getJobData().getString(CTX_MATERIAL_ID_KEY))
                .matches("^[0-9a-fA-F-]{36}$");
        assertThat(nextTask.getJobData().getString(CTX_MATERIAL_NAME))
                .isEqualTo("Material A");
        assertThat(nextTask.getJobData().containsKey(CTX_DOC_ID_KEY))
                .isTrue();
    }

    @Test
    void shouldNotRetry_whenExceptionThrown() {
        JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId)
                .add(CPPUID, userId)
                .build();

        when(progressionClient.getCourtDocuments(any(), any()))
                .thenThrow(new RuntimeException("Error occurred"));

        ExecutionInfo result = task.execute(executionInfo(jobData));

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();
    }
}
