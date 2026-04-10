package uk.gov.hmcts.cp.cdk.jobmanager.caseflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_ALL_DOCUMENTS_INGESTION_STATUS;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GENERATE_ANSWER_FOR_QUERY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOCIDS_ARRAY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_QUERYIDS_ARRAY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;

import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class CheckAllDocumentsIngestionStatusTaskTest {

    @Mock
    private DocumentIdResolver documentIdResolver;

    @Mock
    private ExecutionService executionService;

    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    private CheckAllDocumentsIngestionStatusTask task;

    private UUID doc1;
    private UUID doc2;
    private UUID query1;
    private UUID query2;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        task = new CheckAllDocumentsIngestionStatusTask(
                documentIdResolver,
                executionService,
                null
        );

        doc1 = UUID.randomUUID();
        doc2 = UUID.randomUUID();
        query1 = UUID.randomUUID();
        query2 = UUID.randomUUID();
    }

    @Test
    void shouldRetry_whenNotAllDocsIngested() {

        JsonArray docArray = Json.createArrayBuilder()
                .add(doc1.toString())
                .add(doc2.toString())
                .build();

        JsonObject jobData = Json.createObjectBuilder()
                .add(CTX_DOCIDS_ARRAY, docArray)
                .build();

        ExecutionInfo input = ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_ALL_DOCUMENTS_INGESTION_STATUS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();

        when(documentIdResolver.findIngestionStatusForAllDocs(anyList()))
                .thenReturn(false);

        ExecutionInfo result = task.execute(input);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();

        verifyNoInteractions(executionService);
    }

    @Test
    void shouldExecuteQueries_whenAllDocsIngested() {

        JsonArray docArray = Json.createArrayBuilder()
                .add(doc1.toString())
                .add(doc2.toString())
                .build();

        JsonArray queryArray = Json.createArrayBuilder()
                .add(query1.toString())
                .add(query2.toString())
                .build();

        JsonObject jobData = Json.createObjectBuilder()
                .add(CTX_DOCIDS_ARRAY, docArray)
                .add(CTX_QUERYIDS_ARRAY, queryArray)
                .build();

        ExecutionInfo input = ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_ALL_DOCUMENTS_INGESTION_STATUS)
                .withAssignedTaskStartTime(ZonedDateTime.now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();

        when(documentIdResolver.findIngestionStatusForAllDocs(anyList()))
                .thenReturn(true);

        ExecutionInfo result = task.execute(input);

        verify(executionService, times(2)).executeWith(captor.capture());

        List<ExecutionInfo> executions = captor.getAllValues();

        List<String> queryIdsTriggered = executions.stream()
                .map(e -> e.getJobData().getString(CTX_SINGLE_QUERY_ID))
                .collect(Collectors.toList());

        assertThat(queryIdsTriggered)
                .containsExactlyInAnyOrder(query1.toString(), query2.toString());

        executions.forEach(e -> {
            assertThat(e.getAssignedTaskName()).isEqualTo(GENERATE_ANSWER_FOR_QUERY);
            assertThat(e.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        });

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }
}