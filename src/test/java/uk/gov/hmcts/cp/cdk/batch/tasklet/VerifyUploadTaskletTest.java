package uk.gov.hmcts.cp.cdk.batch.tasklet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.retry.support.RetryTemplate;
import uk.gov.hmcts.cp.cdk.batch.BatchKeys;
import uk.gov.hmcts.cp.cdk.clients.documentstatus.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.cdk.clients.documentstatus.DocumentStatusResponse;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("VerifyUploadTasklet tests")
@ExtendWith(MockitoExtension.class)
class VerifyUploadTaskletTest {

    @Mock
    private DocumentIngestionStatusApi documentIngestionStatusApi;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private RetryTemplate retryTemplate;

    @Mock
    private StepContribution contribution;

    @Mock
    private ChunkContext chunkContext;

    @Mock
    private StepExecution stepExecution;

    private ObjectMapper objectMapper;
    private VerifyUploadTasklet tasklet;
    private ExecutionContext stepExecutionContext;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tasklet = new VerifyUploadTasklet(
                documentIngestionStatusApi,
                caseDocumentRepository,
                retryTemplate,
                objectMapper
        );
        stepExecutionContext = new ExecutionContext();
    }

    @Test
    @DisplayName("Sets COMPLETED when document status found in Azure Table Storage")
    void setsCompletedWhenDocumentFound() throws Throwable {
        // Given
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);
        DocumentStatusResponse statusResponse = new DocumentStatusResponse(
                UUID.randomUUID().toString(),
                documentName,
                "UPLOADED",
                "Document uploaded successfully",
                "2024-01-15T10:30:00Z"
        );

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(retryTemplate.execute(any())).thenReturn(Optional.of(statusResponse));

        // When
        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        // Then
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED)).isEqualTo(true);
        assertThat(stepExecutionContext.getString("documentStatus")).isEqualTo("UPLOADED");
        assertThat(stepExecutionContext.getString("documentStatusTimestamp")).isEqualTo("2024-01-15T10:30:00Z");
        assertThat(stepExecutionContext.getString("documentStatusReason")).isEqualTo("Document uploaded successfully");
        assertThat(stepExecutionContext.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON)).isNotNull();
        verify(contribution, times(1)).setExitStatus(ExitStatus.COMPLETED);
        verify(retryTemplate, times(1)).execute(any());
    }

    @Test
    @DisplayName("Sets NOOP when document not found in Azure Table Storage (404)")
    void setsNoopWhenDocumentNotFound() throws Throwable {
        // Given
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(retryTemplate.execute(any())).thenReturn(Optional.empty());

        // When
        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        // Then
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED)).isEqualTo(false);
        verify(contribution, times(1)).setExitStatus(ExitStatus.NOOP);
        verify(retryTemplate, times(1)).execute(any());
    }

    @Test
    @DisplayName("Finishes immediately when caseId absent in execution context")
    void finishesWhenNoCaseId() throws Throwable {
        // Given - no caseId in context
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);

        // When
        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        // Then
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(caseDocumentRepository);
        verifyNoInteractions(documentIngestionStatusApi);
        verifyNoInteractions(retryTemplate);
        verify(contribution, never()).setExitStatus(any());
    }

    @Test
    @DisplayName("Sets NOOP when no document found in repository for case")
    void setsNoopWhenNoDocumentInRepository() throws Throwable {
        // Given
        UUID caseId = UUID.randomUUID();
        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.empty());

        // When
        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        // Then
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED)).isEqualTo(false);
        verify(contribution, times(1)).setExitStatus(ExitStatus.NOOP);
        verifyNoInteractions(documentIngestionStatusApi);
        verifyNoInteractions(retryTemplate);
    }

    @Test
    @DisplayName("Sets FAILED when exception occurs during status check")
    void setsFailedWhenExceptionOccurs() throws Throwable {
        // Given
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);
        RuntimeException exception = new RuntimeException("Network error");

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(retryTemplate.execute(any())).thenThrow(exception);

        // When
        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        // Then
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED)).isEqualTo(false);
        assertThat(stepExecutionContext.getString("documentStatusError")).isEqualTo("Network error");
        verify(contribution, times(1)).setExitStatus(ExitStatus.FAILED);
    }

    @Test
    @DisplayName("Stores document status response as JSON in execution context")
    void storesResponseAsJson() throws Throwable {
        // Given
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);
        DocumentStatusResponse statusResponse = new DocumentStatusResponse(
                "doc-123",
                documentName,
                "INGESTED",
                null,
                "2024-01-15T10:30:00Z"
        );

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(retryTemplate.execute(any())).thenReturn(Optional.of(statusResponse));

        // When
        tasklet.execute(contribution, chunkContext);

        // Then
        String jsonResponse = stepExecutionContext.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON);
        assertThat(jsonResponse).isNotNull();
        assertThat(jsonResponse).contains("doc-123");
        assertThat(jsonResponse).contains("INGESTED");
        assertThat(jsonResponse).contains(documentName);
    }

    @Test
    @DisplayName("Stores individual status fields for easy access in next steps")
    void storesIndividualStatusFields() throws Throwable {
        // Given
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);
        DocumentStatusResponse statusResponse = new DocumentStatusResponse(
                "doc-456",
                documentName,
                "UPLOADED",
                "Status reason text",
                "2024-01-15T15:45:30Z"
        );

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(retryTemplate.execute(any())).thenReturn(Optional.of(statusResponse));

        // When
        tasklet.execute(contribution, chunkContext);

        // Then
        assertThat(stepExecutionContext.getString("documentStatus")).isEqualTo("UPLOADED");
        assertThat(stepExecutionContext.getString("documentStatusTimestamp")).isEqualTo("2024-01-15T15:45:30Z");
        assertThat(stepExecutionContext.getString("documentStatusReason")).isEqualTo("Status reason text");
    }

    @Test
    @DisplayName("Does not store reason field when reason is null or blank")
    void doesNotStoreReasonWhenNullOrBlank() throws Throwable {
        // Given
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);
        DocumentStatusResponse statusResponse = new DocumentStatusResponse(
                "doc-789",
                documentName,
                "UPLOADED",
                null,
                "2024-01-15T10:30:00Z"
        );

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));
        when(retryTemplate.execute(any())).thenReturn(Optional.of(statusResponse));

        // When
        tasklet.execute(contribution, chunkContext);

        // Then
        assertThat(stepExecutionContext.get("documentStatusReason")).isNull();
    }

    @Test
    @DisplayName("Calls documentIngestionStatusApi with correct document name")
    void callsApiWithCorrectDocumentName() throws Throwable {
        // Given
        UUID caseId = UUID.randomUUID();
        String documentName = "test-doc-123.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);
        DocumentStatusResponse statusResponse = new DocumentStatusResponse(
                "doc-id",
                documentName,
                "UPLOADED",
                "Success",
                "2024-01-15T10:30:00Z"
        );

        stepExecutionContext.putString("caseId", caseId.toString());
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId))
                .thenReturn(Optional.of(caseDocument));

        // Mock retry template to execute the callback
        when(retryTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.retry.RetryCallback<?, ?> callback = invocation.getArgument(0);
            try {
                return callback.doWithRetry(null);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        // Mock the API to return response when called
        when(documentIngestionStatusApi.checkDocumentStatus(documentName))
                .thenReturn(Optional.of(statusResponse));

        // When
        tasklet.execute(contribution, chunkContext);

        // Then
        verify(documentIngestionStatusApi, times(1)).checkDocumentStatus(documentName);
        verify(retryTemplate, times(1)).execute(any());
    }

    // Helper method to create test CaseDocument
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
