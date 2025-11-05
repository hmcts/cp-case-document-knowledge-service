package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class VerifyUploadTaskletTest {

    @Mock private DocumentIngestionStatusApi documentIngestionStatusApi;
    @Mock private CaseDocumentRepository caseDocumentRepository;
    @Mock private StepContribution contribution;
    @Mock private ChunkContext chunkContext;
    @Mock private StepExecution stepExecution;
    @Mock private JobExecution jobExecution;

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
                objectMapper
        );
        ReflectionTestUtils.setField(tasklet, "pollIntervalMs", 0L);
        ReflectionTestUtils.setField(tasklet, "maxWaitMs", 0L);

        stepCtx = new ExecutionContext();
        jobCtx = new ExecutionContext();

        lenient().when(contribution.getStepExecution()).thenReturn(stepExecution);
        lenient().when(stepExecution.getExecutionContext()).thenReturn(stepCtx);
        lenient().when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        lenient().when(jobExecution.getExecutionContext()).thenReturn(jobCtx);
    }

    @Test
    @DisplayName("SUCCESS sets job-level verified flag and status JSON")
    void finishedWhenSuccess() throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        String documentName = "doc.pdf";

        stepCtx.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(BatchKeys.CTX_DOC_ID_KEY, docId.toString());

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
        assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isNotBlank();
    }

    @Test
    @DisplayName("HTTP 404 leads to verified=false and {} in step context")
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
    @DisplayName("Missing case document sets verified=false")
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
    @DisplayName("Invalid doc UUID sets verified=false")
    void finishedWhenInvalidDocId() throws Exception {
        stepCtx.putString(BatchKeys.CTX_CASE_ID_KEY, UUID.randomUUID().toString());
        stepCtx.putString(BatchKeys.CTX_DOC_ID_KEY, "bad-uuid");

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        assertThat(stepCtx.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(false);
        assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isEqualTo("{}");
    }

    @ParameterizedTest
    @EnumSource(value = StatusEnum.class, names = {"INGESTION_SUCCESS", "INGESTION_FAILED"})
    @DisplayName("Handles SUCCESS/FAILED status transitions")
    void handlesStatusMatrix(StatusEnum statusEnum) throws Exception {
        UUID caseId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        String documentName = "doc.pdf";

        stepCtx.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        stepCtx.putString(BatchKeys.CTX_DOC_ID_KEY, docId.toString());

        CaseDocument caseDocument = createCaseDocument(caseId, documentName, docId);
        when(caseDocumentRepository.findById(docId)).thenReturn(Optional.of(caseDocument));

        DocumentIngestionStatusReturnedSuccessfully body =
                new DocumentIngestionStatusReturnedSuccessfully()
                        .status(statusEnum)
                        .documentName(documentName)
                        .lastUpdated(OffsetDateTime.now())
                        .reason("r");
        when(documentIngestionStatusApi.documentStatus(documentName)).thenReturn(ResponseEntity.ok(body));

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);
        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);

        if (statusEnum == StatusEnum.INGESTION_SUCCESS) {
            String verifiedKey = BatchKeys.CTX_UPLOAD_VERIFIED_KEY + ":" + docId;
            assertThat(jobCtx.get(verifiedKey)).isEqualTo(true);
            assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isNotBlank();
        } else {
            assertThat(stepCtx.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(false);
            assertThat(stepCtx.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isNotNull();
        }
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
