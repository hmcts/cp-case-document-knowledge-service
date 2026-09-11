package uk.gov.hmcts.cp.cdk.clients.rag;

import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.DEPENDENCY_RAG;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.OPERATION_ANSWER_USER_QUERY_ASYNC;
import static uk.gov.hmcts.cp.cdk.metrics.CdkMeters.OPERATION_ANSWER_USER_QUERY_STATUS;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.cdk.correlation.CorrelationScope;
import uk.gov.hmcts.cp.cdk.metrics.ExternalCallMetrics;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedAsynchronouslyApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.RequestErrored;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerRequestAccepted;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnMissingBean(RagAnswerAsyncServiceImpl.class)
public class RagAnswerAsyncServiceImpl implements DocumentInformationSummarisedAsynchronouslyApi {

    private final RestClient ragRestClient;
    private final RagClientProperties ragClientProperties;
    private final ApimAuthHeaderService apimAuthHeaderService;
    private final ExternalCallMetrics externalCallMetrics;


    @Override
    public ResponseEntity<@NotNull UserQueryAnswerRequestAccepted> answerUserQueryAsync(final AnswerUserQueryRequest answerUserQueryRequest) {
        return externalCallMetrics.record(DEPENDENCY_RAG, OPERATION_ANSWER_USER_QUERY_ASYNC,
                () -> answerUserQueryAsyncCall(answerUserQueryRequest));
    }

    @SuppressWarnings("PMD.UnusedLocalVariable") // the try-with-resources variable is used for its close()
    private ResponseEntity<@NotNull UserQueryAnswerRequestAccepted> answerUserQueryAsyncCall(
            final AnswerUserQueryRequest answerUserQueryRequest) {
        try {
            if (answerUserQueryRequest.getMetadataFilter() == null) {
                answerUserQueryRequest.setMetadataFilter(List.of());
            }

            UserQueryAnswerRequestAccepted response = ragRestClient
                    .post()
                    .uri(PATH_ANSWER_USER_QUERY_ASYNC)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> {
                        apimAuthHeaderService.applyCommonHeaders(httpHeaders, ragClientProperties.getHeaders());
                        apimAuthHeaderService.applyAuthHeaders(httpHeaders, ragClientProperties);
                    })
                    .body(answerUserQueryRequest)
                    .retrieve()
                    .body(UserQueryAnswerRequestAccepted.class);

            if (response == null) {
                response = new UserQueryAnswerRequestAccepted();
            }

            try (CorrelationScope scope = CorrelationScope.withIdentifiers(null, null, response.getTransactionId())) {
                log.info("RAG Async answer answerUserQueryRequest completed successfully");
            }
            return ResponseEntity.ok(response);

        } catch (final HttpStatusCodeException exception) {
            final String responseBody = Optional.of(exception.getResponseBodyAsString(StandardCharsets.UTF_8)).orElse("");
            final String message = "RAG Async API error: %d %s - %s".formatted(exception.getStatusCode().value(), exception.getStatusText(), responseBody);
            log.warn(message);
            throw new RagClientException(message, exception);

        } catch (final Exception exception) {
            final String message = "Failed to call RAG Async API";
            log.error(message, exception);
            throw new RagClientException(message, exception);
        }
    }

    @Override
    public ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> answerUserQueryStatus(final String transactionId, final Boolean withChunkedEntries) {
        return externalCallMetrics.record(DEPENDENCY_RAG, OPERATION_ANSWER_USER_QUERY_STATUS,
                () -> answerUserQueryStatusCall(transactionId, withChunkedEntries));
    }

    @SuppressWarnings("PMD.UnusedLocalVariable") // the try-with-resources variable is used for its close()
    private ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> answerUserQueryStatusCall(
            final String transactionId, final Boolean withChunkedEntries) {
        try {

            UserQueryAnswerReturnedSuccessfullyAsynchronously response = ragRestClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PATH_ANSWER_USER_QUERY_STATUS)
                            .queryParam("withChunkedEntries", withChunkedEntries)
                            .build(transactionId))
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> {
                        apimAuthHeaderService.applyCommonHeaders(httpHeaders, ragClientProperties.getHeaders());
                        apimAuthHeaderService.applyAuthHeaders(httpHeaders, ragClientProperties);
                    })
                    .retrieve()
                    .body(UserQueryAnswerReturnedSuccessfullyAsynchronously.class);

            if (response == null) {
                response = new UserQueryAnswerReturnedSuccessfullyAsynchronously();
            }

            final String safeTransactionIdForLog = transactionId == null
                    ? null
                    : transactionId
                    .replace('\n', '_')
                    .replace('\r', '_');
            try (CorrelationScope scope = CorrelationScope.withIdentifiers(null, null, safeTransactionIdForLog)) {
                log.info("RAG Async answer status completed successfully");
            }
            return ResponseEntity.ok(response);

        } catch (final HttpStatusCodeException exception) {
            final String responseBody = Optional.of(exception.getResponseBodyAsString(StandardCharsets.UTF_8)).orElse("");
            final String message = "RAG Async answer status API error: %d %s - %s".formatted(exception.getStatusCode().value(), exception.getStatusText(), responseBody);
            log.warn(message);
            throw new RagClientException(message, exception);

        } catch (final Exception exception) {
            final String message = "Failed to call RAG Async answer status API";
            log.error(message, exception);
            throw new RagClientException(message, exception);
        }
    }

    @ExceptionHandler(RagClientException.class)
    public ResponseEntity<@NotNull RequestErrored> onRagClient(final RagClientException exception) {
        final RequestErrored body = new RequestErrored();
        body.setErrorMessage(exception.getMessage());
        return ResponseEntity.status(500).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<@NotNull RequestErrored> onGeneric(final Exception exception) {
        log.error("Unhandled error in /answer-user-query", exception);
        final RequestErrored body = new RequestErrored();
        body.setErrorMessage("Internal server error");
        return ResponseEntity.status(500).body(body);
    }
}
