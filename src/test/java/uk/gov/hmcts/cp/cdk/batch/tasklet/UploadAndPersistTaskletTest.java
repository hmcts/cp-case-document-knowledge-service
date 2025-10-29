package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.storage.StorageService;

import java.time.OffsetDateTime;
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
        // Given
        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(List.of("material-1", "material-2"));
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(null);

        // When
        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        // Then
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any());
        verify(storageService, never()).copyFromUrl(any(), any(), any(), any());
        verify(caseDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return FINISHED when material-to-case mapping is empty")
    void execute_WhenMaterialToCaseMapIsEmpty_ReturnsFinished() throws Exception {
        // Given
        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(List.of("material-1", "material-2"));
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(Collections.emptyMap());

        // When
        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        // Then
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any());
        verify(storageService, never()).copyFromUrl(any(), any(), any(), any());
        verify(caseDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should skip material when no caseId mapping found")
    void execute_WhenNoCaseIdMappingFound_SkipsMaterial() throws Exception {
        // Given
        UUID materialId = UUID.randomUUID();
        String materialIdString = materialId.toString();
        Map<String, String> materialToCaseMap = Map.of("other-material", "case-1");

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(List.of(materialIdString));
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);

        // When
        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        // Then
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any());
        verify(storageService, never()).copyFromUrl(any(), any(), any(), any());
        verify(caseDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should skip material when download URL is empty")
    void execute_WhenDownloadUrlIsEmpty_SkipsMaterial() throws Exception {
        // Given
        UUID materialId = UUID.randomUUID();
        String materialIdString = materialId.toString();
        String caseId = "case-1";
        Map<String, String> materialToCaseMap = Map.of(materialIdString, caseId);

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(List.of(materialIdString));
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId))
                .thenReturn(Optional.empty());

        // When
        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        // Then
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient).getMaterialDownloadUrl(materialId);
        verify(storageService, never()).copyFromUrl(any(), any(), any(), any());
        verify(caseDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should successfully process material and save document")
    void execute_WithValidData_ProcessesMaterialAndSavesDocument() throws Exception {
        // Given
        UUID materialId = UUID.randomUUID();
        String materialIdString = materialId.toString();
        UUID caseId = UUID.randomUUID();
        String caseIdString = caseId.toString();
        String downloadUrl = "https://example.com/document.pdf";
        String blobUrl = "https://storage.blob.core.windows.net/container/cases/20241201/material.pdf";
        long blobSize = 1024L;

        Map<String, String> materialToCaseMap = Map.of(materialIdString, caseIdString);

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(List.of(materialIdString));
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId))
                .thenReturn(Optional.of(downloadUrl));
        when(storageService.copyFromUrl(any(), any(), any(), any()))
                .thenReturn(blobUrl);
        when(storageService.getBlobSize(any()))
                .thenReturn(blobSize);

        // When
        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        // Then
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);

        // Verify progression client call
        verify(progressionClient).getMaterialDownloadUrl(materialId);

        // Verify storage service calls
        ArgumentCaptor<String> destBlobPathCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);

        verify(storageService).copyFromUrl(eq(downloadUrl), destBlobPathCaptor.capture(),
                eq("application/pdf"), metadataCaptor.capture());
        verify(storageService).getBlobSize(destBlobPathCaptor.getValue());

        // Verify blob path format
        String expectedBlobPath = String.format("cases/%s/%s_%s.pdf",
                OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")),
                materialId,
                OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        assertThat(destBlobPathCaptor.getValue()).contains("cases/");
        assertThat(destBlobPathCaptor.getValue()).contains(materialId.toString());
        assertThat(destBlobPathCaptor.getValue()).endsWith(".pdf");

        // Verify metadata content
        Map<String, String> capturedMetadata = metadataCaptor.getValue();
        assertThat(capturedMetadata).containsKey("document_id");
        assertThat(capturedMetadata).containsKey("metadata");

        // Verify metadata JSON content
        String metadataJson = capturedMetadata.get("metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadataMap = objectMapper.readValue(metadataJson, Map.class);
        assertThat(metadataMap).containsEntry("case_id", caseIdString);
        assertThat(metadataMap).containsEntry("material_id", materialId.toString());
        assertThat(metadataMap).containsKey("uploaded_at");

        // Verify document repository save
        ArgumentCaptor<CaseDocument> documentCaptor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(documentCaptor.capture());

        CaseDocument savedDocument = documentCaptor.getValue();
        assertThat(savedDocument.getCaseId()).isEqualTo(caseId);
        assertThat(savedDocument.getDocName()).contains(materialId.toString());
        assertThat(savedDocument.getBlobUri()).isEqualTo(blobUrl);
        assertThat(savedDocument.getContentType()).isEqualTo("application/pdf");
        assertThat(savedDocument.getSizeBytes()).isEqualTo(blobSize);
        assertThat(savedDocument.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.UPLOADED);
        assertThat(savedDocument.getUploadedAt()).isNotNull();
        assertThat(savedDocument.getIngestionPhaseAt()).isNotNull();
    }

    @Test
    @DisplayName("Should process multiple materials successfully")
    void execute_WithMultipleMaterials_ProcessesAllMaterials() throws Exception {
        // Given
        UUID materialId1 = UUID.randomUUID();
        UUID materialId2 = UUID.randomUUID();
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        String caseId1String = caseId1.toString();
        String caseId2String = caseId2.toString();
        String downloadUrl1 = "https://example.com/document1.pdf";
        String downloadUrl2 = "https://example.com/document2.pdf";
        String blobUrl1 = "https://storage.blob.core.windows.net/container/cases/doc1.pdf";
        String blobUrl2 = "https://storage.blob.core.windows.net/container/cases/doc2.pdf";
        long blobSize = 2048L;

        Map<String, String> materialToCaseMap = Map.of(
                materialId1.toString(), caseId1String,
                materialId2.toString(), caseId2String
        );

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(List.of(materialId1.toString(), materialId2.toString()));
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId1))
                .thenReturn(Optional.of(downloadUrl1));
        when(progressionClient.getMaterialDownloadUrl(materialId2))
                .thenReturn(Optional.of(downloadUrl2));
        when(storageService.copyFromUrl(any(), any(), any(), any()))
                .thenReturn(blobUrl1)
                .thenReturn(blobUrl2);
        when(storageService.getBlobSize(any()))
                .thenReturn(blobSize);

        // When
        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        // Then
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);

        // Verify both materials were processed
        verify(progressionClient).getMaterialDownloadUrl(materialId1);
        verify(progressionClient).getMaterialDownloadUrl(materialId2);
        verify(storageService, times(2)).copyFromUrl(any(), any(), any(), any());
        verify(storageService, times(2)).getBlobSize(any());
        verify(caseDocumentRepository, times(2)).save(any(CaseDocument.class));
    }

    @Test
    @DisplayName("Should handle mixed scenarios - some materials succeed, some fail")
    void execute_WithMixedScenarios_HandlesSuccessAndFailure() throws Exception {
        // Given
        UUID materialId1 = UUID.randomUUID();
        UUID materialId2 = UUID.randomUUID();
        UUID materialId3 = UUID.randomUUID();
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        String caseId1String = caseId1.toString();
        String caseId2String = caseId2.toString();
        String downloadUrl1 = "https://example.com/document1.pdf";
        String blobUrl1 = "https://storage.blob.core.windows.net/container/cases/doc1.pdf";
        long blobSize = 1024L;

        Map<String, String> materialToCaseMap = Map.of(
                materialId1.toString(), caseId1String,
                materialId2.toString(), caseId2String
                // materialId3 has no mapping
        );

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(List.of(materialId1.toString(), materialId2.toString(), materialId3.toString()));
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId1))
                .thenReturn(Optional.of(downloadUrl1));
        when(progressionClient.getMaterialDownloadUrl(materialId2))
                .thenReturn(Optional.empty()); // No download URL
        when(storageService.copyFromUrl(any(), any(), any(), any()))
                .thenReturn(blobUrl1);
        when(storageService.getBlobSize(any()))
                .thenReturn(blobSize);

        // When
        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        // Then
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);

        // Verify only materials with mappings were processed
        verify(progressionClient).getMaterialDownloadUrl(materialId1);
        verify(progressionClient).getMaterialDownloadUrl(materialId2);
        verify(progressionClient, never()).getMaterialDownloadUrl(materialId3);
        verify(storageService, times(1)).copyFromUrl(any(), any(), any(), any());
        verify(storageService, times(1)).getBlobSize(any());
        verify(caseDocumentRepository, times(1)).save(any(CaseDocument.class));
    }

    @Test
    @DisplayName("Should throw RuntimeException when metadata creation fails")
    void execute_WhenMetadataCreationFails_ThrowsRuntimeException() throws Exception {
        // Given
        UUID materialId = UUID.randomUUID();
        String materialIdString = materialId.toString();
        String caseId = "case-1";
        String downloadUrl = "https://example.com/document.pdf";

        Map<String, String> materialToCaseMap = Map.of(materialIdString, caseId);

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(List.of(materialIdString));
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId))
                .thenReturn(Optional.of(downloadUrl));

        // Mock ObjectMapper to throw exception
        ObjectMapper faultyMapper = mock(ObjectMapper.class);
        when(faultyMapper.writeValueAsString(any())).thenThrow(new RuntimeException("JSON serialization failed"));

        // Create a new tasklet instance with the faulty mapper
        UploadAndPersistTasklet faultyTasklet = new UploadAndPersistTasklet(faultyMapper, progressionClient,
                storageService, transactionManager, caseDocumentRepository);

        // When & Then
        assertThatThrownBy(() -> faultyTasklet.execute(stepContribution, chunkContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to create blob metadata");
    }

    @Test
    @DisplayName("Should throw exception when storage service fails")
    void execute_WhenStorageServiceThrowsException_ThrowsException() throws Exception {
        // Given
        UUID materialId1 = UUID.randomUUID();
        UUID materialId2 = UUID.randomUUID();
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        String caseId1String = caseId1.toString();
        String caseId2String = caseId2.toString();
        String downloadUrl1 = "https://example.com/document1.pdf";
        String downloadUrl2 = "https://example.com/document2.pdf";
        String blobUrl2 = "https://storage.blob.core.windows.net/container/cases/doc2.pdf";
        long blobSize = 1024L;

        Map<String, String> materialToCaseMap = Map.of(
                materialId1.toString(), caseId1String,
                materialId2.toString(), caseId2String
        );

        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(List.of(materialId1.toString(), materialId2.toString()));
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(materialToCaseMap);
        when(progressionClient.getMaterialDownloadUrl(materialId1))
                .thenReturn(Optional.of(downloadUrl1));
        when(storageService.copyFromUrl(eq(downloadUrl1), any(), any(), any()))
                .thenThrow(new RuntimeException("Storage service error"));

        // When & Then
        assertThatThrownBy(() -> tasklet.execute(stepContribution, chunkContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Storage service error");

        // Verify only the first material was attempted before exception
        verify(progressionClient).getMaterialDownloadUrl(materialId1);
        verify(progressionClient, never()).getMaterialDownloadUrl(materialId2);
    }

    @Test
    @DisplayName("Should handle empty material IDs list")
    void execute_WithEmptyMaterialIdsList_ReturnsFinished() throws Exception {
        // Given
        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(Collections.emptyList());
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(Map.of("material-1", "case-1"));

        // When
        RepeatStatus result = tasklet.execute(stepContribution, chunkContext);

        // Then
        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient, never()).getMaterialDownloadUrl(any());
        verify(storageService, never()).copyFromUrl(any(), any(), any(), any());
        verify(caseDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should handle null material IDs list")
    void execute_WithNullMaterialIdsList_ThrowsException() throws Exception {
        // Given
        when(stepExecutionContext.get(BatchKeys.CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS))
                .thenReturn(null);
        when(jobExecutionContext.get(BatchKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP))
                .thenReturn(Map.of("material-1", "case-1"));

        // When & Then
        assertThatThrownBy(() -> tasklet.execute(stepContribution, chunkContext))
                .isInstanceOf(NullPointerException.class);
    }
}
