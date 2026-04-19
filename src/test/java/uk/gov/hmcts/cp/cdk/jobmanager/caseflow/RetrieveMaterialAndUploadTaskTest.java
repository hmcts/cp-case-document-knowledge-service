package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_DOCUMENT_INGESTION_STATUS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.RETRIEVE_FROM_MATERIAL;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_BLOB_NAME_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.Params.CPPUID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.storage.DocumentBlobMetadata;
import uk.gov.hmcts.cp.cdk.storage.StorageService;
import uk.gov.hmcts.cp.cdk.storage.UploadProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionInitiationApi;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.json.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

public class RetrieveMaterialAndUploadTaskTest {

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
    @Mock
    private DocumentIngestionInitiationApi documentIngestionInitiationApi;

    @Mock
    private IngestionProperties ingestionProperties;

    @Mock
    private IngestionProperties.Feature feature;



    @Captor
    private ArgumentCaptor<ExecutionInfo> executionInfoCaptor;

    private RetrieveMaterialAndUploadTask task;

    private UUID documentId;
    private JsonObject jobData;
    private ExecutionInfo executionInfo;
    @Mock
    private ResponseEntity<@NotNull FileStorageLocationReturnedSuccessfully> responseEntity;
    @Mock
    private FileStorageLocationReturnedSuccessfully storageLocation;
    @Captor
    private ArgumentCaptor<CaseDocument> caseDocumentCaptor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        task = new RetrieveMaterialAndUploadTask(
                progressionClient,
                storageService,
                caseDocumentRepository,
                uploadProperties,
                retryProperties,
                executionService,
                documentIngestionInitiationApi,
                ingestionProperties
        );

        documentId = randomUUID();

        jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, randomUUID().toString())
                .add(CTX_DEFENDANT_ID_KEY, randomUUID().toString())
                .add(CTX_MATERIAL_ID_KEY, randomUUID().toString())
                .add(CTX_DOC_ID_KEY, documentId.toString())
                .add(CTX_MATERIAL_NAME, "Material A")
                .add(CPPUID, "user-123")
                .add("requestId", "req-1")
                .build();

        executionInfo = executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(RETRIEVE_FROM_MATERIAL)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .build();
    }

    @Test
    void shouldReturnCompletedWhenMissingRequiredJobData() {
        when(progressionClient.getMaterialDownloadUrl(any(), any())).thenReturn(Optional.empty());

        final ExecutionInfo result = task.execute(executionInfo()
                .withJobData(createObjectBuilder()
                        .add(CPPUID, "user-123")
                        .add("requestId", "req-1")
                        .build())
                .withAssignedTaskName(RETRIEVE_FROM_MATERIAL)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .build());

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
    }

    @Test
    void shouldUploadDocumentAndScheduleNextTask() {

        when(ingestionProperties.getFeature()).thenReturn(feature);
        when(feature.isUseMultiDefendant()).thenReturn(false);
        when(uploadProperties.datePattern()).thenReturn("yyyyMMdd");
        when(uploadProperties.fileExtension()).thenReturn(".pdf");
        when(uploadProperties.contentType()).thenReturn("application/pdf");
        when(ingestionProperties.getFeature()).thenReturn(feature);
        when(feature.isUseMultiDefendant()).thenReturn(false);
        when(progressionClient.getMaterialDownloadUrl(any(), any()))
                .thenReturn(Optional.of("https://progression/download/url"));

        when(documentIngestionInitiationApi.initiateDocumentUpload(any(DocumentUploadRequest.class))).thenReturn(responseEntity);
        when(responseEntity.getBody()).thenReturn(storageLocation);
        when(storageLocation.getStorageUrl()).thenReturn("https://storage.blob/document-id_120326.pdf?dalkherlncnl%=");
        when(storageLocation.getDocumentReference()).thenReturn("document-id");
        when(storageService.copyFromUrl(any(), any())).thenReturn(new DocumentBlobMetadata("https://storage.blob/blob1", "document-id_120326.pdf", 12345L));

        ExecutionInfo result;
        result = task.execute(executionInfo);
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);

        // Verify next task scheduled
        verify(executionService).executeWith(executionInfoCaptor.capture());
        ExecutionInfo nextTask = executionInfoCaptor.getValue();

        assertThat(nextTask.getAssignedTaskName()).isEqualTo(CHECK_DOCUMENT_INGESTION_STATUS);
        assertThat(nextTask.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(nextTask.getJobData().getString(CTX_DOC_ID_KEY)).isEqualTo(documentId.toString());
        assertThat(nextTask.getJobData().containsKey(CTX_BLOB_NAME_KEY)).isTrue();
    }

    @Test
    void shouldCompleteImmediately_whenMissingUserId() {
        JsonObject badJobData = createObjectBuilder(jobData)
                .remove(CPPUID)
                .build();
        ExecutionInfo badExecutionInfo = executionInfo().withJobData(badJobData).build();
        ExecutionInfo result = task.execute(badExecutionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        verifyNoInteractions(storageService, caseDocumentRepository, executionService);
    }

    @Test
    void shouldRetryWhenDownloadUrlEmpty() {
        when(progressionClient.getMaterialDownloadUrl(any(), any())).thenReturn(Optional.empty());

        final ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void shouldRetryOnException() {
        when(progressionClient.getMaterialDownloadUrl(any(), any())).thenThrow(new RuntimeException("boom"));

        final ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void shouldHandleNullBlobMetadata() {
        when(progressionClient.getMaterialDownloadUrl(any(), any())).thenReturn(Optional.of("url"));
        when(uploadProperties.datePattern()).thenReturn("yyyyMMdd");
        when(caseDocumentRepository.findSupersededDocuments(any(), any())).thenReturn(List.of());
        when(documentIngestionInitiationApi.initiateDocumentUpload(any()))
                .thenReturn(ResponseEntity.ok(new FileStorageLocationReturnedSuccessfully("storage-url", "doc-ref")));

        when(storageService.copyFromUrl(any(), any())).thenReturn(null);
        when(caseDocumentRepository.findById(any())).thenReturn(Optional.empty());
        when(ingestionProperties.getFeature()).thenReturn(feature);
        when(feature.isUseMultiDefendant()).thenReturn(true);

        final ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
    }

    @Test
    void shouldSaveDocumentUploadedWhenCopyUrlSuccessful() {
        when(progressionClient.getMaterialDownloadUrl(any(), any())).thenReturn(Optional.of("url"));
        when(uploadProperties.datePattern()).thenReturn("yyyyMMdd");
        when(caseDocumentRepository.findSupersededDocuments(any(), any())).thenReturn(List.of());
        when(documentIngestionInitiationApi.initiateDocumentUpload(any()))
                .thenReturn(ResponseEntity.ok(new FileStorageLocationReturnedSuccessfully("storage-url", "doc-ref")));

        when(storageService.copyFromUrl(any(), any())).thenReturn(new DocumentBlobMetadata("https://storage.blob/blob1", "document-id_120326.pdf", 12345L));
        when(caseDocumentRepository.findById(any())).thenReturn(Optional.of(new CaseDocument()));
        when(ingestionProperties.getFeature()).thenReturn(feature);
        when(feature.isUseMultiDefendant()).thenReturn(true);

        final ExecutionInfo result = task.execute(executionInfo);

        verify(caseDocumentRepository).saveAndFlush(caseDocumentCaptor.capture());
        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);

        final CaseDocument savedCaseDocument = caseDocumentCaptor.getValue();
        assertThat(savedCaseDocument.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.UPLOADED);
        assertThat(savedCaseDocument.getBlobUri()).isEqualTo("https://storage.blob/blob1");
    }

    @Test
    void shouldFallbackToCaseLevelSupersededDocs() {
        final UUID caseId = randomUUID();
        final UUID defendantId = randomUUID();
        when(caseDocumentRepository.findSupersededDocuments(caseId, defendantId)).thenReturn(List.of());

        when(caseDocumentRepository.findSupersededDocuments(caseId)).thenReturn(List.of(randomUUID()));
        when(progressionClient.getMaterialDownloadUrl(any(), any())).thenReturn(Optional.of("url"));
        when(uploadProperties.datePattern()).thenReturn("yyyyMMdd");
        when(documentIngestionInitiationApi.initiateDocumentUpload(any()))
                .thenReturn(ResponseEntity.ok(
                        new FileStorageLocationReturnedSuccessfully("storage-url", "doc-ref")
                ));
        when(ingestionProperties.getFeature()).thenReturn(feature);
        when(feature.isUseMultiDefendant()).thenReturn(true);

        when(storageService.copyFromUrl(any(), any())).thenReturn(new DocumentBlobMetadata("url", "name", 1L));

        final ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
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
