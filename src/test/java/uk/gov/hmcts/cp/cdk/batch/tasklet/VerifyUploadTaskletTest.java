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
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("VerifyUploadTasklet tests (no RetryTemplate)")
@ExtendWith(MockitoExtension.class)
class VerifyUploadTaskletTest {

    @Mock private DocumentIngestionStatusApi documentIngestionStatusApi;
    @Mock private CaseDocumentRepository caseDocumentRepository;
    @Mock private StepContribution contribution;
    @Mock private ChunkContext chunkContext;
    @Mock private StepExecution stepExecution;

    private ObjectMapper objectMapper;
    private VerifyUploadTasklet tasklet;
    private ExecutionContext stepExecutionContext;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        tasklet = new VerifyUploadTasklet(
                documentIngestionStatusApi,
                caseDocumentRepository,
                objectMapper
        );
        stepExecutionContext = new ExecutionContext();
    }

    @Test
    @DisplayName("COMPLETED when document status found (200)")
    void setsCompletedWhenDocumentFound() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        var body = new DocumentIngestionStatusReturnedSuccessfully()
                .documentId(UUID.randomUUID().toString())
                .documentName(documentName)
                .status(StatusEnum.INGESTION_SUCCESS)
                .reason("Document uploaded successfully")
                .lastUpdated(OffsetDateTime.parse("2024-01-15T10:30:00Z"));

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenReturn(ResponseEntity.ok(body));

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED)).isEqualTo(true);
        assertThat(stepExecutionContext.get("documentStatus")).isEqualTo(StatusEnum.INGESTION_SUCCESS.getValue());
        assertThat(stepExecutionContext.get("documentStatusTimestamp"))
                .isEqualTo(OffsetDateTime.parse("2024-01-15T10:30:00Z"));
        assertThat(stepExecutionContext.get("documentStatusReason")).isEqualTo("Document uploaded successfully");
        assertThat(stepExecutionContext.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON)).isNotNull();
        verify(contribution, times(1)).setExitStatus(ExitStatus.COMPLETED);
        verify(documentIngestionStatusApi, times(1)).documentStatus(documentName);
    }

    @Test
    @DisplayName("NOOP when document not found (404)")
    void setsNoopWhenDocumentNotFound() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_FOUND).build());

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED)).isEqualTo(false);
        verify(contribution, times(1)).setExitStatus(ExitStatus.NOOP);
        verify(documentIngestionStatusApi, times(1)).documentStatus(documentName);
    }

    @Test
    @DisplayName("Finishes when caseId missing")
    void finishesWhenNoCaseId() throws Throwable {
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(caseDocumentRepository, documentIngestionStatusApi);
        verify(contribution, never()).setExitStatus(any());
    }

    @Test
    @DisplayName("NOOP when no document for case")
    void setsNoopWhenNoDocumentInRepository() throws Throwable {
        UUID caseId = UUID.randomUUID();
        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.empty());

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED)).isEqualTo(false);
        verify(contribution, times(1)).setExitStatus(ExitStatus.NOOP);
        verifyNoInteractions(documentIngestionStatusApi);
    }

    @Test
    @DisplayName("FAILED when API throws")
    void setsFailedWhenExceptionOccurs() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenThrow(new RuntimeException("Network error"));

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED)).isEqualTo(false);
        assertThat(stepExecutionContext.getString("documentStatusError")).isEqualTo("Network error");
        verify(contribution, times(1)).setExitStatus(ExitStatus.FAILED);
    }

    @Test
    @DisplayName("Stores response JSON (example: METADATA_VALIDATED)")
    void storesResponseAsJson() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        var body = new DocumentIngestionStatusReturnedSuccessfully()
                .documentId("doc-123")
                .documentName(documentName)
                .status(StatusEnum.METADATA_VALIDATED)
                .lastUpdated(OffsetDateTime.parse("2024-01-15T10:30:00Z"));

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenReturn(ResponseEntity.ok(body));

        tasklet.execute(contribution, chunkContext);

        String jsonResponse = stepExecutionContext.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON);
        assertThat(jsonResponse).isNotNull();
        assertThat(jsonResponse).contains("doc-123");
        assertThat(jsonResponse).contains(StatusEnum.METADATA_VALIDATED.getValue());
        assertThat(jsonResponse).contains(documentName);
    }

    @Test
    @DisplayName("Stores individual fields (example: INGESTION_SUCCESS)")
    void storesIndividualStatusFields() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        var body = new DocumentIngestionStatusReturnedSuccessfully()
                .documentId("doc-456")
                .documentName(documentName)
                .status(StatusEnum.INGESTION_SUCCESS)
                .reason("Status reason text")
                .lastUpdated(OffsetDateTime.parse("2024-01-15T15:45:30Z"));

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenReturn(ResponseEntity.ok(body));

        tasklet.execute(contribution, chunkContext);

        assertThat(stepExecutionContext.get("documentStatus"))
                .isEqualTo(StatusEnum.INGESTION_SUCCESS.getValue());
        assertThat(stepExecutionContext.get("documentStatusTimestamp"))
                .isEqualTo(OffsetDateTime.parse("2024-01-15T15:45:30Z"));
        assertThat(stepExecutionContext.get("documentStatusReason"))
                .isEqualTo("Status reason text");
    }

    @Test
    @DisplayName("Does not store reason when null/blank")
    void doesNotStoreReasonWhenNullOrBlank() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        var body = new DocumentIngestionStatusReturnedSuccessfully()
                .documentId("doc-789")
                .documentName(documentName)
                .status(StatusEnum.INGESTION_FAILED)
                .reason(null)
                .lastUpdated(OffsetDateTime.parse("2024-01-15T10:30:00Z"));

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenReturn(ResponseEntity.ok(body));

        tasklet.execute(contribution, chunkContext);

        assertThat(stepExecutionContext.get("documentStatusReason")).isNull();
    }

    @Test
    @DisplayName("Calls API with correct document name")
    void callsApiWithCorrectDocumentName() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "test-doc-123.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        var body = new DocumentIngestionStatusReturnedSuccessfully()
                .documentId("doc-id")
                .documentName(documentName)
                .status(StatusEnum.INGESTION_SUCCESS)
                .reason("Success")
                .lastUpdated(OffsetDateTime.parse("2024-01-15T10:30:00Z"));

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenReturn(ResponseEntity.ok(body));

        tasklet.execute(contribution, chunkContext);

        verify(documentIngestionStatusApi, times(1)).documentStatus(documentName);
    }

    @ParameterizedTest(name = "Stores status correctly for {0}")
    @EnumSource(StatusEnum.class)
    @DisplayName("Completes for all known status enums")
    void completesForAllStatuses(StatusEnum statusEnum) throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "param-doc.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        var body = new DocumentIngestionStatusReturnedSuccessfully()
                .documentId("doc-param")
                .documentName(documentName)
                .status(statusEnum)
                .lastUpdated(OffsetDateTime.parse("2025-01-01T00:00:00Z"));

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenReturn(ResponseEntity.ok(body));

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED)).isEqualTo(true);
        assertThat(stepExecutionContext.get("documentStatus"))
                .isEqualTo(statusEnum.getValue());
        verify(contribution, times(1)).setExitStatus(ExitStatus.COMPLETED);
    }

    private CaseDocument createCaseDocument(UUID caseId, String documentName) {
        CaseDocument doc = new CaseDocument();
        doc.setDocId(UUID.randomUUID());
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
