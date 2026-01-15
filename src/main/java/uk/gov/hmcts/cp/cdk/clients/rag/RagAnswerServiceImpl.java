package uk.gov.hmcts.cp.cdk.clients.rag;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQuery500Response;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@ConditionalOnMissingBean(RagAnswerServiceImpl.class)
public class RagAnswerServiceImpl implements DocumentInformationSummarisedApi {

    private final RestClient ragRestClient;
    private final RagClientProperties ragClientProperties;
    private final ApimAuthHeaderService apimAuthHeaderService;

    @Override
    public ResponseEntity<UserQueryAnswerReturnedSuccessfully> answerUserQuery(
            @Valid final AnswerUserQueryRequest request) {
        try {
            if (request.getMetadataFilter() == null) {
                request.setMetadataFilter(List.of());
            }

            UserQueryAnswerReturnedSuccessfully response = ragRestClient
                    .post()
                    .uri(PATH_ANSWER_USER_QUERY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> {
                        apimAuthHeaderService.applyCommonHeaders(httpHeaders, ragClientProperties.getHeaders());
                        apimAuthHeaderService.applyAuthHeaders(httpHeaders, ragClientProperties);
                    })
                    .body(request)
                    .retrieve()
                    .body(UserQueryAnswerReturnedSuccessfully.class);

            if (response == null) {
                response = new UserQueryAnswerReturnedSuccessfully();
            }
            if (response.getUserQuery() == null) {
                response.setUserQuery(request.getUserQuery());
            }
            if (response.getQueryPrompt() == null) {
                response.setQueryPrompt(request.getQueryPrompt());
            }

            log.info("RAG answer request completed successfully");
            return ResponseEntity.ok(response);

        } catch (final HttpStatusCodeException exception) {
            String responseBody = Optional.ofNullable(exception.getResponseBodyAsString(StandardCharsets.UTF_8)).orElse("");
            String message = "RAG API error: %d %s - %s".formatted(
                    exception.getStatusCode().value(), exception.getStatusText(), responseBody);
            log.warn(message);
            throw new RagClientException(message, exception);

        } catch (final Exception exception) {
            String message = "Failed to call RAG API";
            log.error(message, exception);
            throw new RagClientException(message, exception);
        }
    }

    @ExceptionHandler(RagClientException.class)
    public ResponseEntity<AnswerUserQuery500Response> onRagClient(final RagClientException exception) {
        AnswerUserQuery500Response body = new AnswerUserQuery500Response();
        body.setErrorMessage(exception.getMessage());
        return ResponseEntity.status(500).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AnswerUserQuery500Response> onGeneric(final Exception exception) {
        log.error("Unhandled error in /answer-user-query", exception);
        AnswerUserQuery500Response body = new AnswerUserQuery500Response();
        body.setErrorMessage("Internal server error");
        return ResponseEntity.status(500).body(body);
    }
}
