package uk.gov.hmcts.cp.cdk.jobmanager.queryflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.time.ZonedDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.GENERATE_ANSWER_FOR_QUERY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_RAG_TRANSACTION_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo.executionInfo;

import uk.gov.hmcts.cp.cdk.domain.QueryDefinitionLatest;
import uk.gov.hmcts.cp.cdk.repo.QueryDefinitionLatestRepository;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedAsynchronouslyApi;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerRequestAccepted;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.util.Optional;
import java.util.UUID;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class GenerateAnswerForQueryTaskTest {

    private GenerateAnswerForQueryTask task;

    @Mock
    private QueryDefinitionLatestRepository queryDefinitionLatestRepository;
    @Mock
    private DocumentInformationSummarisedAsynchronouslyApi api;
    @Mock
    private ExecutionService executionService;
    @Mock
    private QueryDefinitionLatest qdl;
    @Mock
    private UserQueryAnswerRequestAccepted body;
    @Mock
    private uk.gov.hmcts.cp.cdk.metrics.AnswerGenerationMetrics answerGenerationMetrics;
    @Captor
    private ArgumentCaptor<ExecutionInfo> captor;

    private UUID caseId;
    private UUID docId;
    private UUID queryId;
    private ExecutionInfo executionInfo;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        docId = UUID.randomUUID();
        queryId = UUID.randomUUID();
        task = new GenerateAnswerForQueryTask(queryDefinitionLatestRepository, api, executionService,
                answerGenerationMetrics);

        final JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .add(CTX_DOC_ID_KEY, docId.toString())
                .add(CTX_SINGLE_QUERY_ID, queryId.toString())
                .build();

        executionInfo = executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(GENERATE_ANSWER_FOR_QUERY)
                .withAssignedTaskStartTime(now())
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .build();
    }

    @Test
    @DisplayName("AC-004: missing identifiers is a terminal abandonment path — records outcome=failed")
    void shouldComplete_whenAnyIdentifierIsMissing() {
        final ExecutionInfo input = executionInfo().withJobData(Json.createObjectBuilder().build()).build();
        final ExecutionInfo result = task.execute(input);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        verifyNoInteractions(api, executionService, queryDefinitionLatestRepository);
        verify(answerGenerationMetrics).recordFailed(null);
    }

    @Test
    @DisplayName("AC-004: no QueryDefinitionLatest found is a terminal abandonment path — records outcome=failed")
    void shouldComplete_whenQueryDefinitionNotFound() {
        when(queryDefinitionLatestRepository.findByQueryId(queryId)).thenReturn(Optional.empty());

        final ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        verifyNoInteractions(api, executionService);
        verify(answerGenerationMetrics).recordFailed(any());
    }

    @Test
    void shouldStartAsyncRagAndScheduleNextTask() {
        when(qdl.getUserQuery()).thenReturn("user query");
        when(qdl.getQueryPrompt()).thenReturn("prompt");
        when(queryDefinitionLatestRepository.findByQueryId(queryId)).thenReturn(Optional.of(qdl));

        when(body.getTransactionId()).thenReturn("txn-123");
        ResponseEntity<@NotNull UserQueryAnswerRequestAccepted> response = ResponseEntity.ok(body);
        when(api.answerUserQueryAsync(any())).thenReturn(response);

        final ExecutionInfo result = task.execute(executionInfo);

        // current task completed
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        // next task scheduled
        verify(executionService).executeWith(captor.capture());
        final ExecutionInfo nextTask = captor.getValue();

        assertThat(nextTask.getAssignedTaskName()).isEqualTo(CHECK_STATUS_OF_ANSWER_GENERATION);
        assertThat(nextTask.getExecutionStatus()).isEqualTo(ExecutionStatus.STARTED);
        assertThat(nextTask.getJobData().getString(CTX_RAG_TRANSACTION_ID)).isEqualTo("txn-123");
        verifyNoInteractions(answerGenerationMetrics);
    }

    @Test
    @DisplayName("AC-004: a RAG-start failure records outcome=failed — this task has no retry budget "
            + "configured, so willBeRetried(...) is always false and this INPROGRESS/shouldRetry outcome "
            + "can never actually be granted a retry")
    void shouldRetry_whenApiReturnsNullBody() {
        when(queryDefinitionLatestRepository.findByQueryId(queryId)).thenReturn(Optional.of(qdl));
        final ResponseEntity<@NotNull UserQueryAnswerRequestAccepted> response = ResponseEntity.ok(null);
        when(api.answerUserQueryAsync(any())).thenReturn(response);

        final ExecutionInfo result = task.execute(executionInfo);

        assertRetry(result);
        verifyNoInteractions(executionService);
        verify(answerGenerationMetrics).recordFailed(any());
    }

    @Test
    @DisplayName("AC-004: a RAG-start failure (generic exception) also records outcome=failed")
    void shouldRetry_whenApiThrowsException() {
        when(queryDefinitionLatestRepository.findByQueryId(queryId)).thenReturn(Optional.of(qdl));
        when(api.answerUserQueryAsync(any())).thenThrow(new RuntimeException("boom"));

        final ExecutionInfo result = task.execute(executionInfo);

        assertRetry(result);
        verifyNoInteractions(executionService);
        verify(answerGenerationMetrics).recordFailed(any());
    }

    private void assertRetry(ExecutionInfo result) {
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.INPROGRESS);
        assertThat(result.isShouldRetry()).isTrue();
    }
}