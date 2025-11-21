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

import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.storage.StorageService;
import uk.gov.hmcts.cp.cdk.batch.storage.UploadProperties;
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
        objectMapper = new ObjectMapper();

        retryTemplate = new RetryTemplate();
        final SimpleRetryPolicy policy = new SimpleRetryPolicy(1); // single attempt
        retryTemplate.setRetryPolicy(policy);
        retryTemplate.setBackOffPolicy(new NoBackOffPolicy());

        tasklet = new UploadAndPersistTasklet(
                objectMapper,
                progressionClient,
                storageService,
                caseDocumentRepository,
                uploadProperties,
                retryTemplate
        );

        stepCtx = new ExecutionContext();
        jobCtx = new ExecutionContext();

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        when(jobExecution.getExecutionContext()).thenReturn(jobCtx);
        // Note: do not stub uploadProperties in @BeforeEach — only in paths that reach uploads.
    }

    @Test
    @DisplayName("Missing USERID_FOR_EXTERNAL_CALLS → skip with no external calls")
    void missingUserIdSkips() {
        stepCtx.putString(CTX_MATERIAL_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(CTX_DOC_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient, never()).getMaterialDownloadUrl(any(UUID.class), anyString());
        verify(storageService, never()).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
    }

    @Test
    @DisplayName("Invalid/missing UUIDs → skip")
    void badUuidsSkip() {
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        stepCtx.putString(CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(CTX_DOC_ID_KEY, "bad-uuid"); // invalid
        stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient, never()).getMaterialDownloadUrl(any(UUID.class), anyString());
        verify(caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
    }

    @Test
    @DisplayName("Existing upload (newUpload=false) → skip all IO")
    void existingDocSkipsUpload() {
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        stepCtx.putString(CTX_MATERIAL_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(CTX_DOC_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, false);

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(progressionClient, never()).getMaterialDownloadUrl(any(UUID.class), anyString());
        verify(storageService, never()).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
    }

    @Test
    @DisplayName("Progression returns empty URL → skip persist and storage")
    void emptyDownloadUrlSkips() {
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        final UUID materialId = UUID.randomUUID();
        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(CTX_DOC_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        when(progressionClient.getMaterialDownloadUrl(materialId, "user-1")).thenReturn(Optional.empty());

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(storageService, never()).copyFromUrl(anyString(), anyString(), anyString(), anyMap());
        verify(caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
    }

    @Test
    @DisplayName("copyFromUrl fails (returns null) → skip persist")
    void storageCopyFailsSkipsPersist() {
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        when(uploadProperties.contentType()).thenReturn(CONTENT_TYPE);
        when(uploadProperties.datePattern()).thenReturn(DATE_PATTERN);
        when(uploadProperties.fileExtension()).thenReturn(EXT);

        final UUID materialId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        when(progressionClient.getMaterialDownloadUrl(materialId, "user-1"))
                .thenReturn(Optional.of("http://example.test/doc.pdf"));
        when(storageService.copyFromUrl(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(null);

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        verify(caseDocumentRepository, never()).saveAndFlush(any(CaseDocument.class));
    }

    @Test
    @DisplayName("getBlobSize throws → persists with size -1 via recovery")
    void blobSizeThrowsStillPersistsWithMinusOne() {
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        when(uploadProperties.contentType()).thenReturn(CONTENT_TYPE);
        when(uploadProperties.datePattern()).thenReturn(DATE_PATTERN);
        when(uploadProperties.fileExtension()).thenReturn(EXT);

        final UUID materialId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, "IDPC");
        stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        when(progressionClient.getMaterialDownloadUrl(materialId, "user-1"))
                .thenReturn(Optional.of("http://example.test/doc.pdf"));
        when(storageService.copyFromUrl(srcCap.capture(), destCap.capture(), contentTypeCap.capture(), metaCap.capture()))
                .thenReturn("blob://somewhere/path.pdf");
        // Simulate failure by throwing (cannot return null for primitive long)
        when(storageService.getBlobSize(anyString())).thenThrow(new RuntimeException("boom"));

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        final ArgumentCaptor<CaseDocument> docCap = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).saveAndFlush(docCap.capture());
        final CaseDocument saved = docCap.getValue();

        assertThat(saved.getDocId()).isEqualTo(docId);
        assertThat(saved.getCaseId()).isEqualTo(caseId);
        assertThat(saved.getMaterialId()).isEqualTo(materialId);
        assertThat(saved.getBlobUri()).isEqualTo("blob://somewhere/path.pdf");
        assertThat(saved.getContentType()).isEqualTo(CONTENT_TYPE);
        assertThat(saved.getSizeBytes()).isEqualTo(-1L); // recovery sentinel
        assertThat(saved.getDocName()).endsWith(EXT);
        assertThat(saved.getIngestionPhase().name()).isEqualTo("UPLOADED");
        assertThat(saved.getUploadedAt()).isNotNull();
        assertThat(saved.getIngestionPhaseAt()).isNotNull();
    }

    @Test
    @SneakyThrows
    @DisplayName("Happy path → copy, size, persist; metadata is correct JSON")
    void happyPathPersistsOnce() {
        jobCtx.putString(USERID_FOR_EXTERNAL_CALLS, "user-1");
        when(uploadProperties.contentType()).thenReturn(CONTENT_TYPE);
        when(uploadProperties.datePattern()).thenReturn(DATE_PATTERN);
        when(uploadProperties.fileExtension()).thenReturn(EXT);

        final UUID materialId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final UUID docId = UUID.randomUUID();
        final String materialName = "IDPC";

        stepCtx.putString(CTX_MATERIAL_ID_KEY, materialId.toString());
        stepCtx.putString(CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(CTX_DOC_ID_KEY, docId.toString());
        stepCtx.putString(CTX_MATERIAL_NAME, materialName);
        stepCtx.put(CTX_MATERIAL_NEW_UPLOAD, true);

        when(progressionClient.getMaterialDownloadUrl(materialId, "user-1"))
                .thenReturn(Optional.of("http://example.test/doc.pdf"));

        when(storageService.copyFromUrl(srcCap.capture(), destCap.capture(), contentTypeCap.capture(), metaCap.capture()))
                .thenReturn("blob://container/" + docId + "_20250101.pdf");
        when(storageService.getBlobSize(anyString())).thenReturn(1234L);

        final RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        final Map<String, String> meta = metaCap.getValue();
        assertThat(meta).containsKeys("document_id", "metadata");
        assertThat(meta.get("document_id")).isEqualTo(docId.toString());

        final Map<String, Object> metaJson =
                objectMapper.readValue(meta.get("metadata"), new TypeReference<Map<String, Object>>() {
                });
        assertThat(metaJson).containsKeys("case_id", "material_id", "material_name", "uploaded_at");
        assertThat(metaJson.get("case_id")).isEqualTo(caseId.toString());
        assertThat(metaJson.get("material_id")).isEqualTo(materialId.toString());
        assertThat(metaJson.get("material_name")).isEqualTo(materialName);
        assertThat(metaJson.get("uploaded_at")).isInstanceOf(String.class);

        final String dest = destCap.getValue();
        assertThat(dest).startsWith(docId.toString() + "_").endsWith(EXT);

        final ArgumentCaptor<CaseDocument> docCap = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).saveAndFlush(docCap.capture());
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
    }
}
