package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully.StatusEnum;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerifyUploadTaskletTest {

    @Mock private DocumentIngestionStatusApi documentIngestionStatusApi;
    @Mock private CaseDocumentRepository caseDocumentRepository;
    @Mock private StepContribution contribution;
    @Mock private ChunkContext chunkContext;
    @Mock private StepExecution stepExecution;
    @Mock private JobExecution jobExecution;

    /** No-op tx manager for REQUIRES_NEW updates inside the tasklet. */
    private final PlatformTransactionManager txManager = new NoopTxManager();

    private ObjectMapper objectMapper;
    private VerifyUploadTasklet tasklet;
    private ExecutionContext stepCtx;
    private ExecutionContext jobCtx;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        tasklet = new VerifyUploadTasklet(
                documentIngestionStatusApi,
                caseDocumentRepository,
                objectMapper,
                txManager
        );

        // Fast polling in tests: immediate attempts, quick timeout for 404 case
        ReflectionTestUtils.setField(tasklet, "pollIntervalMs", 0L);
        ReflectionTestUtils.setField(tasklet, "maxWaitMs", 25L);

        stepCtx = new ExecutionContext();
        jobCtx  = new ExecutionContext();

        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        // getJobExecution() is stubbed only in tests that actually need it
    }

    @Test
    @DisplayName("Already verified → skips poll, ensures {} in status JSON")
    void alreadyVerifiedSkipsPoll() throws Exception {
        stepCtx.put(BatchKeys.CTX_UPLOAD_VERIFIED_KEY, true); // simulate prior partition verification

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        // Status JSON ensured by ensureStatusJson()
        assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isEqualTo("{}");
        verifyNoInteractions(documentIngestionStatusApi);
    }

    @Test
    @DisplayName("SUCCESS → sets job-level verified flag, step verified=true, JSON present, persists INGESTED")
    void finishedWhenSuccess() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        String documentName = "doc.pdf";

        stepCtx.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(BatchKeys.CTX_DOC_ID_KEY, docId.toString());

        // Job execution only needed for SUCCESS to set job-level verified flag
        when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        when(jobExecution.getExecutionContext()).thenReturn(jobCtx);

        CaseDocument caseDocument = createCaseDocument(caseId, documentName, docId);
        when(caseDocumentRepository.findById(docId)).thenReturn(Optional.of(caseDocument));

        DocumentIngestionStatusReturnedSuccessfully body =
                new DocumentIngestionStatusReturnedSuccessfully()
                        .status(StatusEnum.INGESTION_SUCCESS)
                        .documentName(documentName)
                        .lastUpdated(OffsetDateTime.now());

        when(documentIngestionStatusApi.documentStatus(documentName)).thenReturn(ResponseEntity.ok(body));

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        String verifiedKey = BatchKeys.CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
        assertThat(jobCtx.get(verifiedKey)).isEqualTo(true);
        assertThat(stepCtx.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(true);
        assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isNotBlank();

        // Phase persisted to INGESTED
        verify(caseDocumentRepository, atLeastOnce()).saveAndFlush(any(CaseDocument.class));
    }

    @Test
    @DisplayName("HTTP 404 → times out → verified=false and {} in step context")
    void finishedWhenNotFound() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        String documentName = "doc.pdf";

        stepCtx.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(BatchKeys.CTX_DOC_ID_KEY, docId.toString());

        CaseDocument caseDocument = createCaseDocument(caseId, documentName, docId);
        when(caseDocumentRepository.findById(docId)).thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_FOUND).build());

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        assertThat(stepCtx.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(false);
        assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isEqualTo("{}");
    }

    @Test
    @DisplayName("Missing case document → verified=false and {}")
    void finishedWhenNoDocument() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        stepCtx.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(BatchKeys.CTX_DOC_ID_KEY, docId.toString());

        when(caseDocumentRepository.findById(docId)).thenReturn(Optional.empty());

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        assertThat(stepCtx.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(false);
        assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isEqualTo("{}");
    }

    @Test
    @DisplayName("Invalid doc UUID → verified=false and {}")
    void finishedWhenInvalidDocId() throws Exception {
        stepCtx.putString(BatchKeys.CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(BatchKeys.CTX_DOC_ID_KEY, "bad-uuid");

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        assertThat(stepCtx.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(false);
        assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isEqualTo("{}");
    }

    @Test
    @DisplayName("FAILED → terminal stop, JSON present, verified not set to true, persists FAILED")
    void finishedWhenFailed() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        String documentName = "doc.pdf";

        stepCtx.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(BatchKeys.CTX_DOC_ID_KEY, docId.toString());

        // No jobExecution stubs here (not needed -> avoids unnecessary stubbing)
        CaseDocument caseDocument = createCaseDocument(caseId, documentName, docId);
        when(caseDocumentRepository.findById(docId)).thenReturn(Optional.of(caseDocument));

        DocumentIngestionStatusReturnedSuccessfully body =
                new DocumentIngestionStatusReturnedSuccessfully()
                        .status(StatusEnum.INGESTION_FAILED)
                        .documentName(documentName)
                        .lastUpdated(OffsetDateTime.now())
                        .reason("boom");

        when(documentIngestionStatusApi.documentStatus(documentName)).thenReturn(ResponseEntity.ok(body));

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        // The tasklet does NOT set verified=true on FAILED; it also doesn't force false.
        assertThat(stepCtx.containsKey(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isFalse();
        assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isNotBlank();

        // Phase persisted to FAILED
        verify(caseDocumentRepository, atLeastOnce()).saveAndFlush(any(CaseDocument.class));
    }

    // ---------- helpers ----------

    private static class NoopTxManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(final TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(final TransactionStatus status) { /* no-op */ }
        @Override public void rollback(final TransactionStatus status) { /* no-op */ }
    }

    private CaseDocument createCaseDocument(UUID caseId, String documentName, UUID docid) {
        CaseDocument doc = new CaseDocument();
        doc.setDocId(docid);
        doc.setCaseId(caseId);
        doc.setDocName(documentName);
        doc.setBlobUri("https://storage.azure.net/blob/uri");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(1024L);
        doc.setUploadedAt(OffsetDateTime.now());
        doc.setIngestionPhase(DocumentIngestionPhase.UPLOADED);
        doc.setIngestionPhaseAt(OffsetDateTime.now());
        return doc;
    }
}
