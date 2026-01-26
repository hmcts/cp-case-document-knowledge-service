package uk.gov.hmcts.cp.cdk.clients.rag;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
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


    @Override
    public ResponseEntity<@NotNull UserQueryAnswerRequestAccepted> answerUserQueryAsync(final AnswerUserQueryRequest answerUserQueryRequest) {
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

            log.info("RAG Async answer answerUserQueryRequest completed successfully");
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
    public ResponseEntity<@NotNull UserQueryAnswerReturnedSuccessfullyAsynchronously> answerUserQueryStatus(final String transactionId) {
        try {

            UserQueryAnswerReturnedSuccessfullyAsynchronously response = ragRestClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PATH_ANSWER_USER_QUERY_STATUS + "/{transactionId}")
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

            String safeTransactionIdForLog = transactionId == null
                    ? "null"
                    : transactionId.replaceAll("[\\r\\n]", "_");
            log.info("RAG Async answer status completed successfully for the transactionId: {}", safeTransactionIdForLog);
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
        RequestErrored body = new RequestErrored();
        body.setErrorMessage(exception.getMessage());
        return ResponseEntity.status(500).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<@NotNull RequestErrored> onGeneric(final Exception exception) {
        log.error("Unhandled error in /answer-user-query", exception);
        RequestErrored body = new RequestErrored();
        body.setErrorMessage("Internal server error");
        return ResponseEntity.status(500).body(body);
    }
}
