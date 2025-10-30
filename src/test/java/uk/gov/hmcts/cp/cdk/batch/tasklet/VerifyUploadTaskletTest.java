// src/test/java/uk/gov/hmcts/cp/cdk/batch/tasklet/VerifyUploadTaskletTest.java
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("VerifyUploadTasklet tests (polling, success => COMPLETED; else => NOOP)")
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
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        tasklet = new VerifyUploadTasklet(
                documentIngestionStatusApi,
                caseDocumentRepository,
                objectMapper
        );
        // Make polling deterministic & fast for unit tests
        // One quick pass, then stop.
        ReflectionTestUtils.setField(tasklet, "pollIntervalMs", 0L);
        ReflectionTestUtils.setField(tasklet, "maxWaitMs", 0L);

        stepExecutionContext = new ExecutionContext();
        when(contribution.getStepExecution()).thenReturn(stepExecution);
        when(stepExecution.getExecutionContext()).thenReturn(stepExecutionContext);
    }

    @Test
    @DisplayName("COMPLETED when status = INGESTION_SUCCESS (stores JSON + fields)")
    void completedWhenSuccess() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        var body = new DocumentIngestionStatusReturnedSuccessfully()
                .documentId(UUID.randomUUID().toString())
                .documentName(documentName)
                .status(StatusEnum.INGESTION_SUCCESS)
                .reason("OK")
                .lastUpdated(OffsetDateTime.parse("2024-01-15T10:30:00Z"));

        stepExecutionContext.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId)).thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName)).thenReturn(ResponseEntity.ok(body));

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);

        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(true);
        assertThat(stepExecutionContext.get("documentStatus")).isEqualTo(StatusEnum.INGESTION_SUCCESS.getValue());
        assertThat(stepExecutionContext.get("documentStatusTimestamp"))
                .isEqualTo(OffsetDateTime.parse("2024-01-15T10:30:00Z"));
        assertThat(stepExecutionContext.get("documentStatusReason")).isEqualTo("OK");
        assertThat(stepExecutionContext.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isNotNull();
        verify(contribution).setExitStatus(ExitStatus.COMPLETED);
        verify(documentIngestionStatusApi, atLeastOnce()).documentStatus(documentName);
    }

    @Test
    @DisplayName("NOOP when APIM returns 404 (polls, then gives up; verified=false)")
    void noopWhenNotFound() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "missing.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        stepExecutionContext.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId)).thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_FOUND).build());

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);

        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(false);
        verify(contribution).setExitStatus(ExitStatus.NOOP);
        // Could be called >1 time due to polling; assert at-least-once
        verify(documentIngestionStatusApi, atLeastOnce()).documentStatus(documentName);
    }

    @Test
    @DisplayName("FINISHED (no exit status) when caseId missing")
    void finishesWhenNoCaseId() throws Throwable {
        // no caseId set
        RepeatStatus rs = tasklet.execute(contribution, chunkContext);

        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);
        verifyNoInteractions(caseDocumentRepository, documentIngestionStatusApi);
        verify(contribution, never()).setExitStatus(any());
    }

    @Test
    @DisplayName("NOOP when no document found for case")
    void noopWhenNoDocument() throws Throwable {
        UUID caseId = UUID.randomUUID();

        stepExecutionContext.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId)).thenReturn(Optional.empty());

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);

        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(false);
        verify(contribution).setExitStatus(ExitStatus.NOOP);
        verifyNoInteractions(documentIngestionStatusApi);
    }

    @Test
    @DisplayName("NOOP when API throws (transient error path)")
    void noopWhenApiThrows() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "materialId_20240115.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        stepExecutionContext.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId)).thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName)).thenThrow(new RuntimeException("Network"));

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);

        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(false);
        // tasklet no longer stores an error string
        assertThat(stepExecutionContext.containsKey("documentStatusError")).isFalse();
        verify(contribution).setExitStatus(ExitStatus.NOOP);
        verify(documentIngestionStatusApi, atLeastOnce()).documentStatus(documentName);
    }

    @Test
    @DisplayName("Stores individual fields when success")
    void storesFieldsOnSuccess() throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "ok.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        var body = new DocumentIngestionStatusReturnedSuccessfully()
                .documentId("doc-456")
                .documentName(documentName)
                .status(StatusEnum.INGESTION_SUCCESS)
                .reason("Status reason text")
                .lastUpdated(OffsetDateTime.parse("2024-01-15T15:45:30Z"));

        stepExecutionContext.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId)).thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName)).thenReturn(ResponseEntity.ok(body));

        tasklet.execute(contribution, chunkContext);

        assertThat(stepExecutionContext.get("documentStatus"))
                .isEqualTo(StatusEnum.INGESTION_SUCCESS.getValue());
        assertThat(stepExecutionContext.get("documentStatusTimestamp"))
                .isEqualTo(OffsetDateTime.parse("2024-01-15T15:45:30Z"));
        assertThat(stepExecutionContext.get("documentStatusReason"))
                .isEqualTo("Status reason text");
        verify(documentIngestionStatusApi, atLeastOnce()).documentStatus(documentName);
    }

    @ParameterizedTest(name = "Status {0}: SUCCESS => COMPLETED; others => NOOP")
    @EnumSource(StatusEnum.class)
    @DisplayName("Handles all known status enums")
    void handlesAllStatuses(StatusEnum statusEnum) throws Throwable {
        UUID caseId = UUID.randomUUID();
        String documentName = "param-doc.pdf";
        CaseDocument caseDocument = createCaseDocument(caseId, documentName);

        var body = new DocumentIngestionStatusReturnedSuccessfully()
                .documentId("doc-param")
                .documentName(documentName)
                .status(statusEnum)
                .lastUpdated(OffsetDateTime.parse("2025-01-01T00:00:00Z"));

        stepExecutionContext.putString(BatchKeys.CTX_CASE_ID_KEY, caseId.toString());
        when(caseDocumentRepository.findFirstByCaseIdOrderByUploadedAtDesc(caseId)).thenReturn(Optional.of(caseDocument));
        when(documentIngestionStatusApi.documentStatus(documentName)).thenReturn(ResponseEntity.ok(body));

        RepeatStatus rs = tasklet.execute(contribution, chunkContext);

        assertThat(rs).isEqualTo(RepeatStatus.FINISHED);
        assertThat(stepExecutionContext.get("documentStatus")).isEqualTo(statusEnum.getValue());

        if (statusEnum == StatusEnum.INGESTION_SUCCESS) {
            assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(true);
            verify(contribution).setExitStatus(ExitStatus.COMPLETED);
            assertThat(stepExecutionContext.getString(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isNotBlank();
        } else {
            assertThat(stepExecutionContext.get(BatchKeys.CTX_UPLOAD_VERIFIED_KEY)).isEqualTo(false);
            verify(contribution).setExitStatus(ExitStatus.NOOP);
            assertThat(stepExecutionContext.containsKey(BatchKeys.CTX_DOCUMENT_STATUS_JSON_KEY)).isFalse();
        }

        verify(documentIngestionStatusApi, atLeastOnce()).documentStatus(documentName);
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
