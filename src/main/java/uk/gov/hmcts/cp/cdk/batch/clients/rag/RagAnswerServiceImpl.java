package uk.gov.hmcts.cp.cdk.batch.clients.rag;

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
import uk.gov.hmcts.cp.cdk.batch.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentInformationSummarisedApi;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQuery500Response;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implements the OpenAPI contract and delegates to the upstream RAG endpoint.
 * Exposes POST /answer-user-query per {@link DocumentInformationSummarisedApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnMissingBean(RagAnswerServiceImpl.class)
public class RagAnswerServiceImpl implements DocumentInformationSummarisedApi {

    private final RestClient ragRestClient;
    private final RagClientProperties props;

    @Override
    public ResponseEntity<UserQueryAnswerReturnedSuccessfully> answerUserQuery(
            @Valid final AnswerUserQueryRequest request) {

        try {
            if (request.getMetadataFilters() == null) {
                request.setMetadataFilters(List.of());
            }

            UserQueryAnswerReturnedSuccessfully resp = ragRestClient
                    .post()
                    .uri(PATH_ANSWER_USER_QUERY) // Unqualified: inherited from interface
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        final Map<String, String> hdrs = props.getHeaders();
                        if (hdrs != null) {
                            hdrs.forEach(headers::add);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(UserQueryAnswerReturnedSuccessfully.class);

            if (resp == null) {
                resp = new UserQueryAnswerReturnedSuccessfully();
            }
            if (resp.getUserQuery() == null) {
                resp.setUserQuery(request.getUserQuery());
            }
            if (resp.getQueryPrompt() == null) {
                resp.setQueryPrompt(request.getQueryPrompt());
            }

            return ResponseEntity.ok(resp);

        } catch (final HttpStatusCodeException exception) {
            final String body = Optional.ofNullable(
                    exception.getResponseBodyAsString(StandardCharsets.UTF_8)
            ).orElse("");
            final String message = "RAG API error: %d %s - %s".formatted(
                    exception.getStatusCode().value(), exception.getStatusText(), body
            );
            log.warn(message);
            throw new RagClientException(message, exception);

        } catch (final Exception exception) {
            final String message = "Failed to call RAG API";
            log.error(message, exception);
            throw new RagClientException(message, exception);
        }
    }

    @ExceptionHandler(RagClientException.class)
    public ResponseEntity<AnswerUserQuery500Response> onRagClient(final RagClientException exception) {
        final AnswerUserQuery500Response body = new AnswerUserQuery500Response();
        body.setErrorMessage(exception.getMessage());
        return ResponseEntity.status(500).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AnswerUserQuery500Response> onGeneric(final Exception exception) {
        log.error("Unhandled error in /answer-user-query", exception);
        final AnswerUserQuery500Response body = new AnswerUserQuery500Response();
        body.setErrorMessage("Internal server error");
        return ResponseEntity.status(500).body(body);
    }
}
