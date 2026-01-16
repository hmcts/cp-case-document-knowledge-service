package uk.gov.hmcts.cp.cdk.jobmanager.queryflow;

import static jakarta.json.Json.createObjectBuilder;
import static java.time.ZonedDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION;
import static uk.gov.hmcts.cp.cdk.jobmanager.queryflow.GenerateAnswerForQueryTask.CTX_RAG_TRANSACTION_ID;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.COMPLETED;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.INPROGRESS;
import static uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus.STARTED;

import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedAsynchronouslyApi;
import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;

import java.util.UUID;

import jakarta.json.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class CheckStatusOfAnswerGenerationTaskTest {

    @Mock
    private DocumentInformationSummarisedAsynchronouslyApi api;

    @Mock
    private UserQueryAnswerReturnedSuccessfullyAsynchronously body;

    private CheckStatusOfAnswerGenerationTask task;

    private ExecutionInfo executionInfo;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        task = new CheckStatusOfAnswerGenerationTask(api);
        transactionId = UUID.randomUUID();
        final JsonObject jobData = createObjectBuilder().add(CTX_RAG_TRANSACTION_ID, transactionId.toString()).build();

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
    void shouldComplete_whenAnswerGenerationIsFinished() {
        when(body.getStatus()).thenReturn(ANSWER_GENERATED);

        final ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> response = ResponseEntity.ok(body);

        when(api.answerUserQueryStatus(transactionId.toString())).thenReturn(response);

        final ExecutionInfo result = task.execute(executionInfo);

        assertThat(result.getExecutionStatus()).isEqualTo(COMPLETED);
        assertThat(result.isShouldRetry()).isFalse();
    }


    private void assertRetry(ExecutionInfo result) {
        assertThat(result.getExecutionStatus()).isEqualTo(INPROGRESS);

        assertThat(result.isShouldRetry()).isTrue();
    }
}