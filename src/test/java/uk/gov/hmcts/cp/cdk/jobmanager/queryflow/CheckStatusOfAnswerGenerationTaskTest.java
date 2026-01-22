package uk.gov.hmcts.cp.cdk.jobmanager.queryflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.time.ZonedDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION;
import static uk.gov.hmcts.cp.cdk.jobmanager.queryflow.CheckStatusOfAnswerGenerationTask.SQL_UPSERT_ANSWER;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_RAG_TRANSACTION_ID;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_SINGLE_QUERY_ID;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.STARTED;

import uk.gov.hmcts.cp.cdk.jobmanager.JobManagerRetryProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedAsynchronouslyApi;
import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@ExtendWith(MockitoExtension.class)
class CheckStatusOfAnswerGenerationTaskTest {

    @Mock
    private DocumentInformationSummarisedAsynchronouslyApi api;
    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private JobManagerRetryProperties retryProperties;

    @Mock
    private UserQueryAnswerReturnedSuccessfullyAsynchronously body;

    @Captor
    private ArgumentCaptor<String> sqlCaptor;

    @Captor
    private ArgumentCaptor<SqlParameterSource[]> paramCaptor;


    private CheckStatusOfAnswerGenerationTask task;

    private ExecutionInfo executionInfo;
    private UUID transactionId;
    private UUID caseId;
    private UUID queryId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        task = new CheckStatusOfAnswerGenerationTask(jdbc, api, objectMapper,retryProperties);
        transactionId = UUID.randomUUID();
        caseId = UUID.randomUUID();
        queryId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        final JsonObject jobData = createObjectBuilder()
                .add(CTX_CASE_ID_KEY, caseId.toString())
                .add(CTX_DOC_ID_KEY, documentId.toString())
                .add(CTX_SINGLE_QUERY_ID, queryId.toString())
                .add(CTX_RAG_TRANSACTION_ID, transactionId.toString())
                .build();

        executionInfo = ExecutionInfo.executionInfo()
                .withJobData(jobData)
                .withAssignedTaskName(CHECK_STATUS_OF_ANSWER_GENERATION)
                .withExecutionStatus(STARTED)
                .withAssignedTaskStartTime(now())
                .build();
    }

    @Test
    void shouldRetry_whenResponseIsNull() {
        when(api.answerUserQueryStatus(transactionId.toString())).thenReturn(null);

        final ExecutionInfo result = task.execute(executionInfo);

        assertRetry(result);
    }

    @Test
    void shouldRetry_whenHttpStatusIsNot2xx() {
        final ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        when(api.answerUserQueryStatus(transactionId.toString())).thenReturn(response);

        final ExecutionInfo result = task.execute(executionInfo);

        assertRetry(result);
    }

    @Test
    void shouldRetry_whenResponseBodyIsNull() {
        final ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> response = ResponseEntity.ok(null);

        when(api.answerUserQueryStatus(transactionId.toString())).thenReturn(response);

        final ExecutionInfo result = task.execute(executionInfo);

        assertRetry(result);
    }

    @Test
    void shouldRetry_whenAnswerGenerationIsPending() {
        when(body.getStatus()).thenReturn(AnswerGenerationStatus.ANSWER_GENERATION_PENDING);

        final ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> response = ResponseEntity.ok(body);

        when(api.answerUserQueryStatus(transactionId.toString())).thenReturn(response);

        final ExecutionInfo result = task.execute(executionInfo);

        assertRetry(result);
    }

    @Test
    void shouldSaveAnswerToCdkDatabase_andCompleteJob_whenAnswerGenerationSuccessful() {
        when(body.getStatus()).thenReturn(ANSWER_GENERATED);
        when(body.getLlmResponse()).thenReturn("llmResponse");
        when(body.getChunkedEntries()).thenReturn(List.of("chunk1", "chunk2"));

        final ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> response = ResponseEntity.ok(body);

        when(api.answerUserQueryStatus(transactionId.toString())).thenReturn(response);

        final ExecutionInfo result = task.execute(executionInfo);

        verify(jdbc).batchUpdate(sqlCaptor.capture(), paramCaptor.capture());
        assertThat(sqlCaptor.getValue()).isEqualTo(SQL_UPSERT_ANSWER);
        assertThat(paramCaptor.getValue()[0]).isNotNull();

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();
    }

    private void assertRetry(ExecutionInfo result) {
        assertThat(result.getExecutionStatus()).isEqualTo(INPROGRESS);

        assertThat(result.isShouldRetry()).isTrue();
    }
}