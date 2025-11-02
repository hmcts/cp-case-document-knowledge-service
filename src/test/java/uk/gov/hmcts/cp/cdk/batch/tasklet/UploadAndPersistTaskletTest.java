package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Upload And Persist Tasklet tests")
@ExtendWith(MockitoExtension.class)
class UploadAndPersistTaskletTest {

    @Mock private ProgressionClient progressionClient;
    @Mock private StorageService storageService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private CaseDocumentRepository caseDocumentRepository;

    @Mock private StepContribution stepContribution;
    @Mock private ChunkContext chunkContext;
    @Mock private StepExecution stepExecution;
    @Mock private ExecutionContext stepExecutionContext;
    @Mock private ExecutionContext jobExecutionContext;

    private UploadAndPersistTasklet tasklet;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tasklet = new UploadAndPersistTasklet(
                objectMapper, progressionClient, storageService, transactionManager, caseDocumentRepository
        );

        final JobExecution jobExecution = mock(JobExecution.class);

        // Wire StepExecution and contexts
        when(stepContribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        when(jobExecution.getExecutionContext()).thenReturn(jobExecutionContext);
    }

    @Test
    @DisplayName("Should return FINISHED when material-to-case mapping is null")
    void execute_WhenMaterialToCaseMapIsNull_ReturnsFinished() throws Exception {
        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(null);
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(null);

        final RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any(), anyString());
        verify(storageService, never()).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(caseDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Should return FINISHED when material-to-case mapping is empty")
    void execute_WhenMaterialToCaseMapIsEmpty_ReturnsFinished() throws Exception {
        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY))
                .thenReturn(Collections.emptyMap());
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(null);

        final RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any(), anyString());
        verify(storageService, never()).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(caseDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Should skip material when no caseId mapping found (invalid UUID)")
    void execute_WhenNoCaseIdMappingFound_SkipsMaterial() throws Exception {
        final Map<String, String> materialToCaseMap = Map.of("other-material", "case-1"); // invalid UUID key

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY))
                .thenReturn(materialToCaseMap);
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(null);

        final RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any(), anyString());
        verify(storageService, never()).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(caseDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Should skip material when download URL is empty")
    void execute_WhenDownloadUrlIsEmpty_SkipsMaterial() throws Exception {
        final UUID materialId = UUID.randomUUID();
        final String caseId = UUID.randomUUID().toString();
        final Map<String, String> materialToCaseMap = Map.of(materialId.toString(), caseId);

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(null); // force job ctx
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(eq(materialId), isNull())).thenReturn(Optional.empty());

        final RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient).getMaterialDownloadUrl(eq(materialId), isNull());
        verify(storageService, never()).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(caseDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Should successfully process material and save document (verifies blob name is used for size)")
    void execute_WithValidData_ProcessesMaterialAndSavesDocument() throws Exception {
        final UUID materialId = UUID.randomUUID();
        final String caseId = UUID.randomUUID().toString();
        final String downloadUrl = "https://example.com/document.pdf";
        final String returnedBlobUrl = "https://storage.blob.core.windows.net/container/cases/material.pdf";
        final long blobSize = 1024L;

        final Map<String, String> materialToCaseMap = Map.of(materialId.toString(), caseId);

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(materialToCaseMap);
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(null);
        when(progressionClient.getMaterialDownloadUrl(eq(materialId), isNull())).thenReturn(Optional.of(downloadUrl));
        when(storageService.copyFromUrl(anyString(), anyString(), anyString(), anyMap())).thenReturn(returnedBlobUrl);
        when(storageService.getBlobSize(anyString())).thenReturn(blobSize);

        final RepeatStatus result = tasklet.execute(stepContribution, chunkContext);
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient).getMaterialDownloadUrl(eq(materialId), isNull());

        // Capture the dest blob path (name)
        ArgumentCaptor<String> destBlobPathCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);

        verify(storageService).copyFromUrl(eq(downloadUrl), destBlobPathCaptor.capture(),
                eq("application/pdf"), metadataCaptor.capture());

        // Verify getBlobSize is called with the *blob name*, not the URL
        verify(storageService).getBlobSize(eq(destBlobPathCaptor.getValue()));

        final String blobPath = destBlobPathCaptor.getValue();
        assertThat(blobPath).contains("cases/");
        assertThat(blobPath).contains(materialId.toString());
        assertThat(blobPath).endsWith(".pdf");


        final Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata).containsKey("document_id").containsKey("metadata");

        @SuppressWarnings("unchecked")
        final Map<String, Object> metadataMap =
                new ObjectMapper().readValue(metadata.get("metadata"), Map.class);
        assertThat(metadataMap).containsEntry("case_id", caseId);
        assertThat(metadataMap).containsEntry("material_id", materialId.toString());
        assertThat(metadataMap).containsKey("uploaded_at");

        // Verify entity persisted with expected fields
        ArgumentCaptor<CaseDocument> documentCaptor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).saveAndFlush(documentCaptor.capture());

        final CaseDocument saved = documentCaptor.getValue();
        assertThat(saved.getCaseId().toString()).isEqualTo(caseId);
        assertThat(saved.getDocName()).contains(materialId.toString());
        assertThat(saved.getBlobUri()).isEqualTo(returnedBlobUrl);
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getSizeBytes()).isEqualTo(blobSize);
        assertThat(saved.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.UPLOADED);
        assertThat(saved.getUploadedAt()).isNotNull();
        assertThat(saved.getIngestionPhaseAt()).isNotNull();
    }

    @Test
    @DisplayName("Should throw RuntimeException when metadata creation fails")
    void execute_WhenMetadataCreationFails_ThrowsRuntimeException() throws Exception {
        final UUID materialId = UUID.randomUUID();
        final Map<String, String> materialToCaseMap = Map.of(materialId.toString(), UUID.randomUUID().toString());

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(eq(materialId), isNull())).thenReturn(Optional.of("url"));

        final ObjectMapper faultyMapper = mock(ObjectMapper.class);
        when(faultyMapper.writeValueAsString(any())).thenThrow(new RuntimeException("JSON serialization failed"));

        final UploadAndPersistTasklet faultyTasklet = new UploadAndPersistTasklet(
                faultyMapper, progressionClient, storageService, transactionManager, caseDocumentRepository
        );

        assertThatThrownBy(() -> faultyTasklet.execute(stepContribution, chunkContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to create blob metadata");
    }

    @Test
    @DisplayName("Should propagate exception when storage service fails")
    void execute_WhenStorageServiceThrowsException_ThrowsException() throws Exception {
        final UUID materialId = UUID.randomUUID();
        final String caseId = UUID.randomUUID().toString();
        final String downloadUrl = "https://example.com/document.pdf";
        final Map<String, String> materialToCaseMap = Map.of(materialId.toString(), caseId);

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(eq(materialId), isNull())).thenReturn(Optional.of(downloadUrl));
        when(storageService.copyFromUrl(eq(downloadUrl), anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("Storage service error"));

        assertThatThrownBy(() -> tasklet.execute(stepContribution, chunkContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Storage service error");
    }
}
