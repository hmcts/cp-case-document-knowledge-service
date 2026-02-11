package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_INGESTION_STATUS_FOR_DOCUMENT;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_FROM_MATERIAL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_BLOB_NAME_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.storage.StorageService;
import uk.gov.hmcts.cp.cdk.storage.UploadProperties;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RetrieveFromMaterialAndUploadTaskTest {

    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ProgressionClient progressionClient;
    @Mock
    private StorageService storageService;
    @Mock
    private CaseDocumentRepository caseDocumentRepository;
    @Mock
    private UploadProperties uploadProperties;
    @Mock
    private ExecutionService executionService;
    @Mock
    private JobManagerRetryProperties retryProperties;

    @Captor
    private ArgumentCaptor<ExecutionInfo> executionInfoCaptor;

    private RetrieveFromMaterialAndUploadTask task;

    private UUID caseId;
    private UUID materialId;
    private UUID documentId;
    private JsonObject jobData;
    private ExecutionInfo executionInfo;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        task = new RetrieveFromMaterialAndUploadTask(
                objectMapper,
                progressionClient,
                storageService,
                caseDocumentRepository,
                uploadProperties,
                retryProperties,
                executionService
        );

        caseId = UUID.randomUUID();
        materialId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        jobData = Json.createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .add(CTX_MATERIAL_ID_KEY, materialId.toString())
                .add(CTX_DOC_ID_KEY, documentId.toString())
                .add(CTX_MATERIAL_NAME, "Material A")
                .add(CPPUID, "user-123")
                .add("requestId", "req-1")
                .build();

        executionInfo = ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(RETRIEVE_FROM_MATERIAL)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .build();
    }

    @Test
    void shouldUploadDocumentAndScheduleNextTask() throws Exception {

        when(uploadProperties.datePattern()).thenReturn("yyyyMMdd");
        when(uploadProperties.fileExtension()).thenReturn(".pdf");
        when(uploadProperties.contentType()).thenReturn("application/pdf");
        when(progressionClient.getMaterialDownloadUrl(any(), any()))
                .thenReturn(Optional.of("https://progression/download/url"));
        when(storageService.copyFromUrl(any(), any(), any())).thenReturn("https://storage.blob/blob1");
        when(storageService.getBlobSize(any())).thenReturn(12345L);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"dummy\":\"metadata\"}");

        ExecutionInfo result;
        result = task.execute(executionInfo);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        // as update happening now
        //verify(caseDocumentRepository).saveAndFlush(any(CaseDocument.class));

        // Verify next task scheduled
        verify(executionService).executeWith(executionInfoCaptor.capture());
        ExecutionInfo nextTask = executionInfoCaptor.getValue();

        assertThat(nextTask.getAssignedTaskName()).isEqualTo(CHECK_INGESTION_STATUS_FOR_DOCUMENT);
        assertThat(nextTask.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(nextTask.getJobData().getString(CTX_DOC_ID_KEY)).isEqualTo(documentId.toString());
        assertThat(nextTask.getJobData().containsKey(CTX_BLOB_NAME_KEY)).isTrue();
    }

    @Test
    void shouldCompleteImmediately_whenMissingUserId() {
        JsonObject badJobData = Json.createObjectBuilder(jobData)
                .remove(CPPUID)
                .build();
        ExecutionInfo badExecutionInfo = ExecutionInfo.executionInfo().withJobData(badJobData).build();
        ExecutionInfo result = task.execute(badExecutionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(storageService, caseDocumentRepository, executionService);
    }
}
