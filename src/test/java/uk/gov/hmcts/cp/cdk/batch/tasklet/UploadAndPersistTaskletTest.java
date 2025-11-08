package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.UploadProperties;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.*;

@ExtendWith(MockitoExtension.class)
class UploadAndPersistTaskletTest {

    private ProgressionClient progressionClient;
    private StorageService storageService;
    private CaseDocumentRepository caseDocumentRepository;

    private StepContribution stepContribution;
    private ChunkContext chunkContext;
    private StepExecution stepExecution;
    private JobExecution jobExecution;

    private ExecutionContext stepCtx;
    private ExecutionContext jobCtx;

    private ObjectMapper objectMapper;
    private UploadProperties uploadProperties;
    private UploadAndPersistTasklet tasklet;

    @BeforeEach
    void setUp() {
        progressionClient = mock(ProgressionClient.class);
        storageService = mock(StorageService.class);
        caseDocumentRepository = mock(CaseDocumentRepository.class);

        stepContribution = mock(StepContribution.class);
        chunkContext = mock(ChunkContext.class);
        stepExecution = mock(StepExecution.class);
        jobExecution = mock(JobExecution.class);

        stepCtx = new ExecutionContext();
        jobCtx = new ExecutionContext();

        objectMapper = JsonMapper.builder().build();
        uploadProperties = new UploadProperties(
                "cases",         // blobPrefix (not used by current tasklet)
                "yyyyMMdd",      // datePattern
                ".pdf",          // fileExtension
                "application/pdf"
        );

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(1)); // single attempt to simplify tests
        retryTemplate.setBackOffPolicy(new NoBackOffPolicy());

        tasklet = new UploadAndPersistTasklet(
                objectMapper, progressionClient, storageService, caseDocumentRepository, uploadProperties, retryTemplate
        );

        // lenient stubs to avoid UnnecessaryStubbingException in tests that override behavior
        lenient().when(stepContribution.getStepExecution()).thenReturn(stepExecution);
        lenient().when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        lenient().when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        lenient().when(jobExecution.getExecutionContext()).thenReturn(jobCtx);

        // default user id
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "la-user-1");
    }

    @Test
    @DisplayName("Finishes when StepExecution is null")
    void finishesWhenStepExecutionNull() throws Exception {
        when(stepContribution.getStepExecution()).thenReturn(null);

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(progressionClient, storageService, caseDocumentRepository);
    }

    @Test
    @DisplayName("Skips when userId missing")
    void skipsWhenUserIdMissing() throws Exception {
        jobCtx.remove(USERID_FOR_EXTERNAL_CALLS);

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(progressionClient, storageService, caseDocumentRepository);
    }

    @Test
    @DisplayName("Skips when partition context missing CTX_MATERIAL_ID_KEY / CTX_CASE_ID_KEY / CTX_DOC_ID_KEY")
    void skipsWhenPartitionKeysMissing() throws Exception {
        // nothing set in stepCtx
        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(progressionClient, storageService, caseDocumentRepository);
    }

    @Test
    @DisplayName("Skips when material/case/doc UUIDs invalid")
    void skipsWhenInvalidUuids() throws Exception {
        stepCtx.putString(CTX_MATERIAL_ID_KEY, "not-a-uuid");
        stepCtx.putString(CTX_CASE_ID_KEY, "also-not-a-uuid");
        stepCtx.putString(CTX_DOC_ID_KEY, "also-not-a-uuid");
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(progressionClient, storageService, caseDocumentRepository);
    }

    @Test
    @DisplayName("Skips when no download URL (empty Optional)")
    void skipsWhenNoDownloadUrl() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");

        when(progressionClient.getMaterialDownloadUrl(eq(materialId), eq("la-user-1")))
                .thenReturn(Optional.empty());

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient).getMaterialDownloadUrl(eq(materialId), eq("la-user-1"));
        verifyNoInteractions(storageService);
        verify(caseDocumentRepository).existsByDocId(eq(docId));
        verify(caseDocumentRepository).existsByCaseIdAndMaterialIdAndIngestionPhaseIn(eq(caseId), eq(materialId), anyCollection());
        verify(caseDocumentRepository, never()).saveAndFlush(any());
        verifyNoMoreInteractions(caseDocumentRepository);
    }


    @Test
    @DisplayName("Skips when getMaterialDownloadUrl throws")
    void skipsWhenGetMaterialDownloadUrlThrows() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");

        when(progressionClient.getMaterialDownloadUrl(eq(materialId), eq("la-user-1")))
                .thenThrow(new RuntimeException("boom"));

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(progressionClient).getMaterialDownloadUrl(eq(materialId), eq("la-user-1"));
        verifyNoInteractions(storageService);
        verify(caseDocumentRepository).existsByDocId(eq(docId));
        verify(caseDocumentRepository).existsByCaseIdAndMaterialIdAndIngestionPhaseIn(eq(caseId), eq(materialId), anyCollection());
        verify(caseDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Happy path: copies blob, persists CaseDocument, sets step context outputs")
    void happyPathPersistsAndSetsContext() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        String downloadUrl = "https://example.com/material.pdf";
        String returnedBlobUrl = "https://storage/account/container/blobname.pdf";
        long blobSize = 2048L;

        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");

        when(progressionClient.getMaterialDownloadUrl(eq(materialId), eq("la-user-1")))
                .thenReturn(Optional.of(downloadUrl));
        when(storageService.copyFromUrl(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(returnedBlobUrl);
        when(storageService.getBlobSize(anyString())).thenReturn(blobSize);

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        // capture arguments to copyFromUrl
        ArgumentCaptor<String> srcUrlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> destPathCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);

        verify(storageService).copyFromUrl(
                srcUrlCaptor.capture(),
                destPathCaptor.capture(),
                eq("application/pdf"),
                metadataCaptor.capture()
        );

        assertThat(srcUrlCaptor.getValue()).isEqualTo(downloadUrl);

        String destBlobPath = destPathCaptor.getValue();
        assertThat(destBlobPath).contains(docId.toString()); // <-- robust: blob name is based on docId
        assertThat(destBlobPath).endsWith(".pdf");
        assertThat(destBlobPath).doesNotContain("/");

        verify(storageService).getBlobSize(eq(destBlobPath));

        // verify metadata keys and payload
        Map<String, String> meta = metadataCaptor.getValue();
        assertThat(meta).containsKeys("document_id", "metadata");
        assertThat(meta.get("document_id")).isEqualTo(docId.toString());
        // basic shape check for embedded JSON
        Map<String, Object> metaJson = objectMapper.readValue(
                meta.get("metadata"),
                new TypeReference<>() {
                }
        );
        assertThat(metaJson).containsKeys("case_id", "material_id", "material_name", "uploaded_at");
        assertThat(metaJson.get("case_id")).isEqualTo(caseId.toString());
        assertThat(metaJson.get("material_id")).isEqualTo(materialId.toString());

        // verify persistence
        ArgumentCaptor<CaseDocument> docCaptor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).saveAndFlush(docCaptor.capture());
        CaseDocument saved = docCaptor.getValue();

        assertThat(saved.getCaseId()).isEqualTo(caseId);
        assertThat(saved.getMaterialId()).isEqualTo(materialId);
        assertThat(saved.getDocId()).isEqualTo(docId);
        assertThat(saved.getDocName()).contains(docId.toString());
        assertThat(saved.getBlobUri()).isEqualTo(returnedBlobUrl);
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getSizeBytes()).isEqualTo(blobSize);
        assertThat(saved.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.UPLOADED);
        assertThat(saved.getUploadedAt()).isNotNull();
        assertThat(saved.getIngestionPhaseAt()).isNotNull();

        // step context outputs added by tasklet
        assertThat(stepCtx.get("uploaded_blob_path")).isEqualTo(destBlobPath);
        assertThat(stepCtx.get("uploaded_blob_url")).isEqualTo(returnedBlobUrl);
        assertThat(stepCtx.get("uploaded_size_bytes")).isEqualTo(blobSize);

        // original keys preserved
        assertThat(stepCtx.getString(CTX_DOC_ID_KEY)).isEqualTo(saved.getDocId().toString());
        assertThat(stepCtx.getString(CTX_CASE_ID_KEY)).isEqualTo(caseId.toString());
    }

    @Test
    @DisplayName("Skips and does not persist if copyFromUrl throws")
    void skipsIfCopyThrows() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        String downloadUrl = "https://example.com/material.pdf";

        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");

        when(progressionClient.getMaterialDownloadUrl(eq(materialId), eq("la-user-1")))
                .thenReturn(Optional.of(downloadUrl));
        when(storageService.copyFromUrl(eq(downloadUrl), anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("copy failed"));

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        verify(storageService, never()).getBlobSize(anyString());
        verify(caseDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Persists with size=-1 when getBlobSize throws")
    void persistsWithMinusOneIfSizeFails() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        String downloadUrl = "https://example.com/material.pdf";
        String returnedBlobUrl = "https://storage/account/container/blobname.pdf";

        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");

        when(progressionClient.getMaterialDownloadUrl(eq(materialId), eq("la-user-1")))
                .thenReturn(Optional.of(downloadUrl));
        when(storageService.copyFromUrl(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(returnedBlobUrl);
        when(storageService.getBlobSize(anyString())).thenThrow(new RuntimeException("size fail"));

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        ArgumentCaptor<CaseDocument> docCaptor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).saveAndFlush(docCaptor.capture());
        CaseDocument saved = docCaptor.getValue();

        assertThat(saved.getCaseId()).isEqualTo(caseId);
        assertThat(saved.getSizeBytes()).isEqualTo(-1L);
        assertThat(saved.getBlobUri()).isEqualTo(returnedBlobUrl);
        assertThat(saved.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.UPLOADED);
    }

    // ---------------- NEW IDEMPOTENCY TESTS ----------------

    @Test
    @DisplayName("Idempotency: skips immediately when docId already persisted")
    void skipsWhenDocIdAlreadyPersisted() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");

        when(caseDocumentRepository.existsByDocId(eq(docId))).thenReturn(true);

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(caseDocumentRepository).existsByDocId(eq(docId));
        verifyNoInteractions(progressionClient, storageService);
        verify(caseDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Idempotency: skips when (caseId, materialId) already in UPLOADED/INGESTED/INGESTING")
    void skipsWhenCaseMaterialAlreadyInFinalOrOngoingPhase() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");

        when(caseDocumentRepository.existsByCaseIdAndMaterialIdAndIngestionPhaseIn(
                eq(caseId), eq(materialId), anyCollection()))
                .thenReturn(true);

        RepeatStatus status = tasklet.execute(stepContribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(caseDocumentRepository).existsByCaseIdAndMaterialIdAndIngestionPhaseIn(eq(caseId), eq(materialId), anyCollection());
        verifyNoInteractions(progressionClient, storageService);
        verify(caseDocumentRepository, never()).saveAndFlush(any());
    }
}
