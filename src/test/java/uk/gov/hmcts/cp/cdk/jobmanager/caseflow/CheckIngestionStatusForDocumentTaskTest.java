package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_INGESTION_STATUS_FOR_DOCUMENT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;

import uk.gov.hmcts.cp.cdk.batch.support.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;

class CheckIngestionStatusForDocumentTaskTest {

    @Mock
    private DocumentIngestionStatusApi documentIngestionStatusApi;
    @Mock
    private CaseDocumentRepository caseDocumentRepository;
    @Mock
    private QueryResolver queryResolver;
    @Mock
    private ExecutionService executionService;
    @Mock
    private JobManagerRetryProperties retryProperties;

    @Captor
    private ArgumentCaptor<ExecutionInfo> executionInfoCaptor;

    private CheckIngestionStatusForDocumentTask task;

    private UUID documentId;
    private JsonObject jobData;
    private ExecutionInfo executionInfo;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        task = new CheckIngestionStatusForDocumentTask(
                documentIngestionStatusApi,
                caseDocumentRepository,
                queryResolver,
                executionService,
                retryProperties
        );

        documentId = UUID.randomUUID();

        jobData = Json.createObjectBuilder()
                .add("docId", documentId.toString())
                .add("blobName", "blob-123")
                .build();

        executionInfo = ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_INGESTION_STATUS_FOR_DOCUMENT)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();
    }

    @Test
    void shouldCompleteImmediately_whenDocIdOrBlobNameMissing() {
        ExecutionInfo missing = ExecutionInfo.executionInfo()
                .withJobData(Json.createObjectBuilder().build())
                .withAssignedTaskName(CHECK_INGESTION_STATUS_FOR_DOCUMENT)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();

        ExecutionInfo result = task.execute(missing);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verifyNoInteractions(documentIngestionStatusApi, caseDocumentRepository, queryResolver, executionService);
    }

    @Test
    void shouldRetry_whenStatusNotAvailable() {
        when(documentIngestionStatusApi.documentStatus("blob-123")).thenReturn(null);

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void shouldRetry_whenStatusNotSuccess() {
        DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();
        body.setStatus(DocumentIngestionStatus.INGESTION_FAILED);

        when(documentIngestionStatusApi.documentStatus("blob-123"))
                .thenReturn(ResponseEntity.ok(body));

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }

    @Test
    void shouldComplete_whenQueriesEmptyOrAllNull() {
        DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();
        body.setStatus(DocumentIngestionStatus.INGESTION_SUCCESS);

        when(documentIngestionStatusApi.documentStatus("blob-123"))
                .thenReturn(ResponseEntity.ok(body));
        when(queryResolver.resolve()).thenReturn(List.of());

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        verify(caseDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldUpdateDocumentAndScheduleGenerateAnswerTasks_whenIngestionSuccess() {
        DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();
        body.setStatus(DocumentIngestionStatus.INGESTION_SUCCESS);

        when(documentIngestionStatusApi.documentStatus("blob-123"))
                .thenReturn(ResponseEntity.ok(body));

        CaseDocument doc = new CaseDocument();
        when(caseDocumentRepository.findById(documentId)).thenReturn(Optional.of(doc));

        UUID queryId1 = UUID.randomUUID();
        UUID queryId2 = UUID.randomUUID();

        Query q1 = new Query();
        q1.setQueryId(queryId1);

        Query q2 = new Query();
        q2.setQueryId(queryId2);

        when(queryResolver.resolve()).thenReturn(List.of(q1, q2));

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(doc.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.INGESTED);
        verify(caseDocumentRepository).saveAndFlush(doc);

        verify(executionService, times(2)).executeWith(executionInfoCaptor.capture());

        Set<String> scheduledQueryIds = executionInfoCaptor.getAllValues().stream()
                .map(e -> e.getJobData().getString(CTX_SINGLE_QUERY_ID))
                .collect(Collectors.toSet());

        assertThat(scheduledQueryIds)
                .containsExactlyInAnyOrder(queryId1.toString(), queryId2.toString());

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }
}
