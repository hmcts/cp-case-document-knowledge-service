package uk.gov.hmcts.cp.cdk.batch.tasklet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_NEW_UPLOAD;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.USERID_FOR_EXTERNAL_CALLS;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.storage.StorageService;
import uk.gov.hmcts.cp.cdk.storage.UploadProperties;
import uk.gov.hmcts.cp.cdk.batch.verification.DocumentVerificationEnqueueService;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
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

@ExtendWith(MockitoExtension.class)
class UploadAndPersistTaskletTest {

    private static final String CONTENT_TYPE = "application/pdf";
    private static final String DATE_PATTERN = "yyyyMMdd";
    private static final String EXT = ".pdf";

    @Mock
    private ProgressionClient progressionClient;

    @Mock
    private StorageService storageService;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private UploadProperties uploadProperties;

    @Mock
    private DocumentVerificationEnqueueService documentVerificationEnqueueService;

    @Mock
    private StepContribution contribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private StepExecution stepExecution;

    @Mock
    private JobExecution jobExecution;

    @Captor
    private ArgumentCaptor<String> srcCap;

    @Captor
    private ArgumentCaptor<String> destCap;

    @Captor
    private ArgumentCaptor<String> contentTypeCap;

    @Captor
    private ArgumentCaptor<Map<String, String>> metaCap;

    private ObjectMapper objectMapper;
    private RetryTemplate retryTemplate;
    private UploadAndPersistTasklet tasklet;

    private ExecutionContext stepCtx;
    private ExecutionContext jobCtx;

    @BeforeEach
    void setUp() {
        this.objectMapper = new ObjectMapper();

        this.retryTemplate = new RetryTemplate();
        final SimpleRetryPolicy policy = new SimpleRetryPolicy(1); // single attempt (no retries)
        this.retryTemplate.setRetryPolicy(policy);
        this.retryTemplate.setBackOffPolicy(new NoBackOffPolicy());

        this.tasklet = new UploadAndPersistTasklet(
                this.objectMapper,
                this.progressionClient,
                this.storageService,
                this.caseDocumentRepository,
                this.uploadProperties,
                this.retryTemplate,
                this.documentVerificationEnqueueService
        );

        this.stepCtx = new ExecutionContext();
        this.jobCtx = new ExecutionContext();

        when(this.contribution.getStepExecution()).thenReturn(this.stepExecution);
        when(this.stepExecution.getExecutionContext()).thenReturn(this.stepCtx);
        when(this.stepExecution.getJobExecution()).thenReturn(this.jobExecution);
        when(this.jobExecution.getExecutionContext()).thenReturn(this.jobCtx);
    }

    @Test
    @DisplayName("Missing USERID_FOR_EXTERNAL_CALLS → skip with no external calls")
    void missingUserIdSkips() {
        this.stepCtx.putString(CTX_MATERIAL_ID_KEY, UUID.randomUUID().toString());
        this.stepCtx.putString(CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        this.stepCtx.putString(CTX_DOC_ID_KEY, UUID.randomUUID().toString());
        this.stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        this.stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        final RepeatStatus repeatStatus = this.tasklet.execute(this.contribution, this.chunkContext);
        assertThat(repeatStatus).isEqualTo(RepeatStatus.FINISHED);

        verify(this.progressionClient, never()).getMaterialDownloadUrl(any(UUID.class), anyString());
        verify(this.storageService, never()).copyFromUrl(anyString(), anyString(), anyMap());
        verify(this.caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
        verify(this.documentVerificationEnqueueService, never())
                .enqueue(any(UUID.class), any(UUID.class), anyString());
    }

    @Test
    @DisplayName("Invalid/missing UUIDs → skip")
    void badUuidsSkip() {
        this.jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        this.stepCtx.putString(CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        this.stepCtx.putString(CTX_DOC_ID_KEY, "bad-uuid"); // invalid
        this.stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        final RepeatStatus repeatStatus = this.tasklet.execute(this.contribution, this.chunkContext);
        assertThat(repeatStatus).isEqualTo(RepeatStatus.FINISHED);

        verify(this.progressionClient, never()).getMaterialDownloadUrl(any(UUID.class), anyString());
        verify(this.caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
        verify(this.documentVerificationEnqueueService, never())
                .enqueue(any(UUID.class), any(UUID.class), anyString());
    }

    @Test
    @DisplayName("Existing upload (newUpload=false) → skip all IO")
    void existingDocSkipsUpload() {
        this.jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        this.stepCtx.putString(CTX_MATERIAL_ID_KEY, UUID.randomUUID().toString());
        this.stepCtx.putString(CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        this.stepCtx.putString(CTX_DOC_ID_KEY, UUID.randomUUID().toString());
        this.stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        this.stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, false);

        final RepeatStatus repeatStatus = this.tasklet.execute(this.contribution, this.chunkContext);
        assertThat(repeatStatus).isEqualTo(RepeatStatus.FINISHED);

        verify(this.progressionClient, never()).getMaterialDownloadUrl(any(UUID.class), anyString());
        verify(this.storageService, never()).copyFromUrl(anyString(), anyString(),anyMap());
        verify(this.caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
        verify(this.documentVerificationEnqueueService, never())
                .enqueue(any(UUID.class), any(UUID.class), anyString());
    }

    @Test
    @DisplayName("Progression returns empty URL → skip persist and storage")
    void emptyDownloadUrlSkips() {
        this.jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        final UUID materialId = UUID.randomUUID();
        this.stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        this.stepCtx.putString(CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        this.stepCtx.putString(CTX_DOC_ID_KEY, UUID.randomUUID().toString());
        this.stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        this.stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        when(this.progressionClient.getMaterialDownloadUrl(materialId, "user-1")).thenReturn(Optional.empty());

        final RepeatStatus repeatStatus = this.tasklet.execute(this.contribution, this.chunkContext);
        assertThat(repeatStatus).isEqualTo(RepeatStatus.FINISHED);

        verify(this.storageService, never()).copyFromUrl(anyString(), anyString(),anyMap());
        verify(this.caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
        verify(this.documentVerificationEnqueueService, never())
                .enqueue(any(UUID.class), any(UUID.class), anyString());
    }

    @Test
    @DisplayName("copyFromUrl fails (returns null) → skip persist and enqueue")
    void storageCopyFailsSkipsPersist() {
        this.jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        when(this.uploadProperties.contentType()).thenReturn(CONTENT_TYPE);
        when(this.uploadProperties.datePattern()).thenReturn(DATE_PATTERN);
        when(this.uploadProperties.fileExtension()).thenReturn(EXT);

        final UUID materialId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        this.stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        this.stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        this.stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        this.stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        this.stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        when(this.progressionClient.getMaterialDownloadUrl(materialId, "user-1"))
                .thenReturn(Optional.of("http://example.test/doc.pdf"));
        when(this.storageService.copyFromUrl(anyString(), anyString(), anyMap()))
                .thenReturn(null);

        final RepeatStatus repeatStatus = this.tasklet.execute(this.contribution, this.chunkContext);
        assertThat(repeatStatus).isEqualTo(RepeatStatus.FINISHED);

        verify(this.caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
        verify(this.documentVerificationEnqueueService, never())
                .enqueue(any(UUID.class), any(UUID.class), anyString());
    }

    @Test
    @DisplayName("getBlobSize throws → persists with size -1 and enqueues verification task")
    void blobSizeThrowsStillPersistsWithMinusOne() {
        this.jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        when(this.uploadProperties.contentType()).thenReturn(CONTENT_TYPE);
        when(this.uploadProperties.datePattern()).thenReturn(DATE_PATTERN);
        when(this.uploadProperties.fileExtension()).thenReturn(EXT);

        final UUID materialId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        this.stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        this.stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        this.stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        this.stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        this.stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        when(this.progressionClient.getMaterialDownloadUrl(materialId, "user-1"))
                .thenReturn(Optional.of("http://example.test/doc.pdf"));
        when(this.storageService.copyFromUrl(
                this.srcCap.capture(),
                this.destCap.capture(),
                this.metaCap.capture())
        ).thenReturn("blob://somewhere/path.pdf");
        when(this.storageService.getBlobSize(anyString()))
                .thenThrow(new RuntimeException("boom"));

        final RepeatStatus repeatStatus = this.tasklet.execute(this.contribution, this.chunkContext);
        assertThat(repeatStatus).isEqualTo(RepeatStatus.FINISHED);

        final ArgumentCaptor<CaseDocument> docCap = ArgumentCaptor.forClass(CaseDocument.class);
        verify(this.caseDocumentRepository).saveAndFlush(docCap.capture());
        final CaseDocument saved = docCap.getValue();

        assertThat(saved.getDocId()).isEqualTo(docId);
        assertThat(saved.getCaseId()).isEqualTo(caseId);
        assertThat(saved.getMaterialId()).isEqualTo(materialId);
        assertThat(saved.getBlobUri()).isEqualTo("blob://somewhere/path.pdf");
        assertThat(saved.getContentType()).isEqualTo(CONTENT_TYPE);
        assertThat(saved.getSizeBytes()).isEqualTo(-1L);
        assertThat(saved.getDocName()).endsWith(EXT);
        assertThat(saved.getIngestionPhase().name()).isEqualTo("UPLOADED");
        assertThat(saved.getUploadedAt()).isNotNull();
        assertThat(saved.getIngestionPhaseAt()).isNotNull();

        verify(this.documentVerificationEnqueueService)
                .enqueue(caseId, docId, saved.getDocName());
    }

    @Test
    @SneakyThrows
    @DisplayName("Happy path → copy, size, persist; metadata correct; enqueues verification task")
    void happyPathPersistsOnce() {
        this.jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        when(this.uploadProperties.contentType()).thenReturn(CONTENT_TYPE);
        when(this.uploadProperties.datePattern()).thenReturn(DATE_PATTERN);
        when(this.uploadProperties.fileExtension()).thenReturn(EXT);

        final UUID materialId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        final String materialName = "IDPC";

        this.stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        this.stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        this.stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        this.stepCtx.putString(CTX_MATERIAL_NAME, materialName);
        this.stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        when(this.progressionClient.getMaterialDownloadUrl(materialId, "user-1"))
                .thenReturn(Optional.of("http://example.test/doc.pdf"));

        when(this.storageService.copyFromUrl(
                this.srcCap.capture(),
                this.destCap.capture(),
                this.metaCap.capture())
        ).thenReturn("blob://container/" + docId + "_20250101.pdf");
        when(this.storageService.getBlobSize(anyString())).thenReturn(1234L);

        final RepeatStatus repeatStatus = this.tasklet.execute(this.contribution, this.chunkContext);
        assertThat(repeatStatus).isEqualTo(RepeatStatus.FINISHED);

        final Map<String, String> meta = this.metaCap.getValue();
        assertThat(meta).containsKeys("document_id", "metadata");
        assertThat(meta.get("document_id")).isEqualTo(docId.toString());

        final Map<String, Object> metaJson =
                this.objectMapper.readValue(meta.get("metadata"), new TypeReference<Map<String, Object>>() {
                });
        assertThat(metaJson).containsKeys("case_id", "material_id", "material_name", "uploaded_at");
        assertThat(metaJson.get("case_id")).isEqualTo(caseId.toString());
        assertThat(metaJson.get("material_id")).isEqualTo(materialId.toString());
        assertThat(metaJson.get("material_name")).isEqualTo(materialName);
        assertThat(metaJson.get("uploaded_at")).isInstanceOf(String.class);

        final String dest = this.destCap.getValue();
        assertThat(dest).startsWith(docId.toString() + "_").endsWith(EXT);

        final ArgumentCaptor<CaseDocument> docCap = ArgumentCaptor.forClass(CaseDocument.class);
        verify(this.caseDocumentRepository).saveAndFlush(docCap.capture());
        final CaseDocument saved = docCap.getValue();

        assertThat(saved.getDocId()).isEqualTo(docId);
        assertThat(saved.getCaseId()).isEqualTo(caseId);
        assertThat(saved.getMaterialId()).isEqualTo(materialId);
        assertThat(saved.getBlobUri()).isEqualTo("blob://container/" + docId + "_20250101.pdf");
        assertThat(saved.getContentType()).isEqualTo(CONTENT_TYPE);
        assertThat(saved.getSizeBytes()).isEqualTo(1234L);
        assertThat(saved.getDocName()).isEqualTo(dest);
        assertThat(saved.getIngestionPhase().name()).isEqualTo("UPLOADED");
        assertThat(saved.getUploadedAt()).isNotNull();
        assertThat(saved.getIngestionPhaseAt()).isNotNull();

        verify(this.documentVerificationEnqueueService)
                .enqueue(caseId, docId, saved.getDocName());
    }
}
