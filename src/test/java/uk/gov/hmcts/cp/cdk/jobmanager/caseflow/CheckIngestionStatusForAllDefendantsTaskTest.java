package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_ALL_DOCUMENTS_INGESTION_STATUS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GENERATE_ANSWER_FOR_QUERY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_REFERENCE_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_LATEST_DEFENDANT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_QUERYIDS_ARRAY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.DocumentIngestionPhase;
import uk.gov.hmcts.cp.cdk.domain.QueryLevel;
import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryVersionRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.Instant;
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

class CheckIngestionStatusForAllDefendantsTaskTest {

    @Mock
    private DocumentIngestionStatusApi documentIngestionStatusApi;
    @Mock
    private CaseDocumentRepository caseDocumentRepository;
    @Mock
    private QueryVersionRepository queryVersionRepository;
    @Mock
    private ExecutionService executionService;
    @Mock
    private JobManagerRetryProperties retryProperties;

    @Captor
    private ArgumentCaptor<ExecutionInfo> executionInfoCaptor;

    private CheckIngestionStatusForAllDefendantsTask task;

    private UUID documentId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        task = new CheckIngestionStatusForAllDefendantsTask(
                documentIngestionStatusApi,
                caseDocumentRepository,
                queryVersionRepository,
                executionService,
                retryProperties
        );

        documentId = randomUUID();
    }

    @Test
    void shouldComplete_whenDocIdMissing() {
        final JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, randomUUID().toString())
                .add(CTX_DEFENDANT_ID_KEY, randomUUID().toString())
                .build();

        final ExecutionInfo result = task.execute(executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build());

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();
    }

    @Test
    void shouldUpdateAndTriggerAllQueryTypes_whenIngestionSuccess_andLatestDefendant() {
        DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();
        body.setStatus(DocumentIngestionStatus.INGESTION_SUCCESS);

        when(documentIngestionStatusApi.documentStatusByReference("ref-123"))
                .thenReturn(ResponseEntity.ok(body));

        CaseDocument doc = new CaseDocument();
        when(caseDocumentRepository.findById(documentId)).thenReturn(Optional.of(doc));

        UUID caseQueryId = randomUUID();
        UUID caseAllDocsQueryId = randomUUID();
        UUID defendantQueryId = randomUUID();

        QueryVersionRepository.SnapshotDefinition caseDef =
                new QueryVersionRepository.SnapshotDefinition(caseQueryId, "lable1", "query1", "prompt1", Instant.now(), QueryLevel.CASE.toString());

        QueryVersionRepository.SnapshotDefinition caseAllDocsDef =
                new QueryVersionRepository.SnapshotDefinition(caseAllDocsQueryId, "lable2", "query2", "prompt2", Instant.now(), QueryLevel.CASE_ALL_DOCUMENTS.toString());

        QueryVersionRepository.SnapshotDefinition defendantDef =
                new QueryVersionRepository.SnapshotDefinition(defendantQueryId, "lable3", "query3", "prompt3", Instant.now(), QueryLevel.DEFENDANT.toString());

        when(queryVersionRepository.snapshotDefinitionsAsOf(any()))
                .thenReturn(List.of(caseDef, caseAllDocsDef, defendantDef));

        JsonObject jobData = Json.createObjectBuilder()
                .add("docId", documentId.toString())
                .add("blobName", "blob-123")
                .add("caseId", randomUUID().toString())
                .add(CTX_DOC_REFERENCE_KEY, "ref-123")
                .add(CTX_LATEST_DEFENDANT, true)
                .add(CTX_DEFENDANT_ID_KEY, randomUUID().toString())
                .build();

        ExecutionInfo executionInfo = executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(doc.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.INGESTED);
        verify(caseDocumentRepository).saveAndFlush(doc);

        // executions triggered
        verify(executionService, times(3)).executeWith(executionInfoCaptor.capture());

        List<ExecutionInfo> executions = executionInfoCaptor.getAllValues();

        Set<String> taskNames = executions.stream()
                .map(ExecutionInfo::getAssignedTaskName)
                .collect(Collectors.toSet());

        assertThat(taskNames).contains(
                GENERATE_ANSWER_FOR_QUERY,
                CHECK_ALL_DOCUMENTS_INGESTION_STATUS
        );


        assertThat(executions.stream()
                .anyMatch(e -> caseQueryId.toString().equals(
                        e.getJobData().getString(CTX_SINGLE_QUERY_ID, null))))
                .isTrue();

        assertThat(executions.stream()
                .anyMatch(e -> defendantQueryId.toString().equals(
                        e.getJobData().getString(CTX_SINGLE_QUERY_ID, null))))
                .isTrue();

        // CASE_ALL_DOCUMENTS triggered
        assertThat(executions.stream()
                .anyMatch(e -> e.getAssignedTaskName().equals(CHECK_ALL_DOCUMENTS_INGESTION_STATUS)
                        && e.getJobData().containsKey(CTX_QUERYIDS_ARRAY)))
                .isTrue();

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void shouldUpdateIngestionPhase_whenIngestionFailedDueToFileExceedingSizeLimit() {
        final DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();
        body.setStatus(DocumentIngestionStatus.FILE_SIZE_OVER_LIMIT);

        when(documentIngestionStatusApi.documentStatusByReference("ref-123")).thenReturn(ResponseEntity.ok(body));

        final CaseDocument doc = new CaseDocument();
        when(caseDocumentRepository.findById(documentId)).thenReturn(Optional.of(doc));

        final UUID caseQueryId = randomUUID();
        final UUID caseAllDocsQueryId = randomUUID();
        final UUID defendantQueryId = randomUUID();

        QueryVersionRepository.SnapshotDefinition caseDef =
                new QueryVersionRepository.SnapshotDefinition(caseQueryId, "lable1", "query1", "prompt1", Instant.now(), QueryLevel.CASE.toString());

        QueryVersionRepository.SnapshotDefinition caseAllDocsDef =
                new QueryVersionRepository.SnapshotDefinition(caseAllDocsQueryId, "lable2", "query2", "prompt2", Instant.now(), QueryLevel.CASE_ALL_DOCUMENTS.toString());

        QueryVersionRepository.SnapshotDefinition defendantDef =
                new QueryVersionRepository.SnapshotDefinition(defendantQueryId, "lable3", "query3", "prompt3", Instant.now(), QueryLevel.DEFENDANT.toString());

        when(queryVersionRepository.snapshotDefinitionsAsOf(any()))
                .thenReturn(List.of(caseDef, caseAllDocsDef, defendantDef));

        final JsonObject jobData = Json.createObjectBuilder()
                .add("docId", documentId.toString())
                .add("blobName", "blob-123")
                .add("caseId", randomUUID().toString())
                .add(CTX_DOC_REFERENCE_KEY, "ref-123")
                .add(CTX_LATEST_DEFENDANT, true)
                .add(CTX_DEFENDANT_ID_KEY, randomUUID().toString())
                .build();

        final ExecutionInfo executionInfo = executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(doc.getIngestionPhase()).isEqualTo(DocumentIngestionPhase.EXCEEDED_FILE_SIZE_LIMIT);
        verify(caseDocumentRepository).saveAndFlush(doc);
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void shouldRetry_whenApiThrowsException() {
        when(documentIngestionStatusApi.documentStatusByReference("ref-123"))
                .thenThrow(new RuntimeException("downstream failure"));

        JsonObject jobData = Json.createObjectBuilder()
                .add("docId", documentId.toString())
                .add("blobName", "blob-123")
                .add(CTX_DOC_REFERENCE_KEY, "ref-123")
                .add(CTX_DEFENDANT_ID_KEY, randomUUID().toString())
                .build();

        ExecutionInfo executionInfo = executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_INGESTION_STATUS_FOR_ALL_DEFENDANTS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();

        ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();

        verifyNoInteractions(caseDocumentRepository, executionService);
    }

    @Test
    void shouldReturnRetryDurations() {
        final JobManagerRetryProperties.RetryConfig retryConfig = new JobManagerRetryProperties.RetryConfig();
        retryConfig.setMaxAttempts(3);
        retryConfig.setDelaySeconds(10);
        when(retryProperties.getVerifyDocumentStatus()).thenReturn(retryConfig);

        final List<Long> durations = task.getRetryDurationsInSecs().orElseThrow();

        assertThat(durations).isEqualTo(List.of(10L, 10L, 10L));
    }
}