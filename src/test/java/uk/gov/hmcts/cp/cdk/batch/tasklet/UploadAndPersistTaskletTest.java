package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("Upload And Persist Tasklet tests")
@ExtendWith(MockitoExtension.class)
class UploadAndPersistTaskletTest {

    @Mock
    private ProgressionClient progressionClient;

    @Mock
    private StorageService storageService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private StepContribution stepContribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private StepExecution stepExecution;

    @Mock
    private ExecutionContext stepExecutionContext;

    @Mock
    private ExecutionContext jobExecutionContext;

    private UploadAndPersistTasklet tasklet;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tasklet = new UploadAndPersistTasklet(objectMapper, progressionClient,
                storageService, transactionManager, caseDocumentRepository);

        when(stepContribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(stepExecution.getJobExecution()).thenReturn(mock(JobExecution.class));
        when(stepExecution.getJobExecution().getExecutionContext()).thenReturn(jobExecutionContext);
    }

    @Test
    @DisplayName("Should return FINISHED when material-to-case mapping is null")
    void execute_WhenMaterialToCaseMapIsNull_ReturnsFinished() throws Exception {
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP)).thenReturn(null);

        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any());
        verify(storageService, never()).copyFromUrl(any(), any(), any(), any());
        verify(caseDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return FINISHED when material-to-case mapping is empty")
    void execute_WhenMaterialToCaseMapIsEmpty_ReturnsFinished() throws Exception {
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(Collections.emptyMap());

        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any());
        verify(storageService, never()).copyFromUrl(any(), any(), any(), any());
        verify(caseDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should skip material when no caseId mapping found")
    void execute_WhenNoCaseIdMappingFound_SkipsMaterial() throws Exception {
        UUID materialId = UUID.randomUUID();
        Map<String, String> materialToCaseMap = Map.of("other-material", "case-1");

        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);

        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any());
        verify(storageService, never()).copyFromUrl(any(), any(), any(), any());
        verify(caseDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should skip material when download URL is empty")
    void execute_WhenDownloadUrlIsEmpty_SkipsMaterial() throws Exception {
        UUID materialId = UUID.randomUUID();
        String caseId = "case-1";
        Map<String, String> materialToCaseMap = Map.of(materialId.toString(), caseId);

        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId)).thenReturn(Optional.empty());

        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient).getMaterialDownloadUrl(materialId);
        verify(storageService, never()).copyFromUrl(any(), any(), any(), any());
        verify(caseDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should successfully process material and save document")
    void execute_WithValidData_ProcessesMaterialAndSavesDocument() throws Exception {
        UUID materialId = UUID.randomUUID();
        String caseId = UUID.randomUUID().toString();
        String downloadUrl = "https://example.com/document.pdf";
        String blobUrl = "https://storage.blob.core.windows.net/container/cases/material.pdf";
        long blobSize = 1024L;

        Map<String, String> materialToCaseMap = Map.of(materialId.toString(), caseId);

        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId)).thenReturn(Optional.of(downloadUrl));
        when(storageService.copyFromUrl(any(), any(), any(), any())).thenReturn(blobUrl);
        when(storageService.getBlobSize(any())).thenReturn(blobSize);

        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient).getMaterialDownloadUrl(materialId);

        ArgumentCaptor<String> destBlobPathCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);

        verify(storageService).copyFromUrl(eq(downloadUrl), destBlobPathCaptor.capture(),
                eq("application/pdf"), metadataCaptor.capture());
        verify(storageService).getBlobSize(destBlobPathCaptor.getValue());

        String blobPath = destBlobPathCaptor.getValue();
        assertThat(blobPath).contains("cases/");
        assertThat(blobPath).contains(materialId.toString());
        assertThat(blobPath).endsWith(".pdf");

        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata).containsKey("document_id").containsKey("metadata");

        @SuppressWarnings("unchecked")
        Map<String, Object> metadataMap = objectMapper.readValue(metadata.get("metadata"), Map.class);
        assertThat(metadataMap).containsEntry("case_id", caseId);
        assertThat(metadataMap).containsEntry("material_id", materialId.toString());
        assertThat(metadataMap).containsKey("uploaded_at");

        ArgumentCaptor<CaseDocument> documentCaptor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(documentCaptor.capture());

        CaseDocument savedDocument = documentCaptor.getValue();
        assertThat(savedDocument.getCaseId().toString()).isEqualTo(caseId);
        assertThat(savedDocument.getDocName()).contains(materialId.toString());
        assertThat(savedDocument.getBlobUri()).isEqualTo(blobUrl);
        assertThat(savedDocument.getContentType()).isEqualTo("application/pdf");
        assertThat(savedDocument.getSizeBytes()).isEqualTo(blobSize);
        assertThat(savedDocument.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.UPLOADED);
        assertThat(savedDocument.getUploadedAt()).isNotNull();
        assertThat(savedDocument.getIngestionPhaseAt()).isNotNull();
    }

    @Test
    @DisplayName("Should throw RuntimeException when metadata creation fails")
    void execute_WhenMetadataCreationFails_ThrowsRuntimeException() throws Exception {
        UUID materialId = UUID.randomUUID();
        Map<String, String> materialToCaseMap = Map.of(materialId.toString(), "case-1");
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId)).thenReturn(Optional.of("url"));

        ObjectMapper faultyMapper = mock(ObjectMapper.class);
        when(faultyMapper.writeValueAsString(any())).thenThrow(new RuntimeException("JSON serialization failed"));

        UploadAndPersistTasklet faultyTasklet = new UploadAndPersistTasklet(faultyMapper, progressionClient,
                storageService, transactionManager, caseDocumentRepository);

        assertThatThrownBy(() -> faultyTasklet.execute(stepContribution, chunkContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to create blob metadata");
    }

    @Test
    @DisplayName("Should throw exception when storage service fails")
    void execute_WhenStorageServiceThrowsException_ThrowsException() throws Exception {
        UUID materialId = UUID.randomUUID();
        String caseId = UUID.randomUUID().toString();
        String downloadUrl = "https://example.com/document.pdf";

        Map<String, String> materialToCaseMap = Map.of(materialId.toString(), caseId);

        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId)).thenReturn(Optional.of(downloadUrl));
        when(storageService.copyFromUrl(eq(downloadUrl), any(), any(), any()))
                .thenThrow(new RuntimeException("Storage service error"));

        assertThatThrownBy(() -> tasklet.execute(stepContribution, chunkContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Storage service error");
    }
}
