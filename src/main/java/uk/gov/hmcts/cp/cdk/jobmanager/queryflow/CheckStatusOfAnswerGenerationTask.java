package uk.gov.hmcts.cp.cdk.jobmanager.queryflow;

import static java.util.Objects.isNull;
import static uk.gov.hmcts.cp.cdk.jobmanager.TaskNames.CHECK_STATUS_OF_ANSWER_GENERATION;
import static uk.gov.hmcts.cp.cdk.jobmanager.queryflow.GenerateAnswerForQueryTask.CTX_RAG_TRANSACTION_ID;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.parseUuidOrNull;

import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedAsynchronouslyApi;
import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionStatus;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Task(CHECK_STATUS_OF_ANSWER_GENERATION)
public class CheckStatusOfAnswerGenerationTask implements ExecutableTask {

    private final ExecutionService taskExecutionService;
    private final DocumentInformationSummarisedAsynchronouslyApi documentInformationSummarisedAsynchronouslyApi;

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {

        final JsonObject jobData = executionInfo.getJobData();
        final UUID transactionId = parseUuidOrNull(jobData.getString(CTX_RAG_TRANSACTION_ID, null));

        final ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> userQueryAnswerResponse = documentInformationSummarisedAsynchronouslyApi.answerUserQueryStatus(transactionId.toString());

        if (isNull(userQueryAnswerResponse)
                || !userQueryAnswerResponse.getStatusCode().is2xxSuccessful()
                || isNull(userQueryAnswerResponse.getBody())
                || userQueryAnswerResponse.getBody().getStatus() == AnswerGenerationStatus.ANSWER_GENERATION_PENDING) {

            log.info("Answer Generation in progress for the transactionId={} → retrying", transactionId);
            return retry(executionInfo);
        }

        return ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.COMPLETED)
                .build();
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(List.of(5L, 10L, 30L, 60L, 120L));
    }

    private ExecutionInfo retry(final ExecutionInfo executionInfo) {
        return ExecutionInfo.executionInfo()
                .from(executionInfo)
                .withExecutionStatus(ExecutionStatus.INPROGRESS)
                .withShouldRetry(true)
                .build();
    }

}
