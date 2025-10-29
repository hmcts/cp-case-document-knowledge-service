package uk.gov.hmcts.cp.cdk.clients.rag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
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
public class RagAnswerServiceImpl implements DocumentInformationSummarisedApi {

    private final RestClient ragRestClient;
    private final RagClientProperties props;

    @Override
    public ResponseEntity<UserQueryAnswerReturnedSuccessfully> answerUserQuery(
            @Valid AnswerUserQueryRequest request) {

        try {
            if (request.getMetadataFilters() == null) {
                request.setMetadataFilters(List.of());
            }
            UserQueryAnswerReturnedSuccessfully resp = ragRestClient
                    .post()
                    .uri(DocumentInformationSummarisedApi.PATH_ANSWER_USER_QUERY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        final Map<String, String> hdrs = props.getHeaders();
                        if (hdrs != null) hdrs.forEach(h::add);
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

        } catch (HttpStatusCodeException ex) {
            String body = Optional.ofNullable(ex.getResponseBodyAsString(StandardCharsets.UTF_8)).orElse("");
            String msg = "RAG API error: %d %s - %s".formatted(ex.getStatusCode().value(), ex.getStatusText(), body);
            log.warn(msg);
            throw new RagClientException(msg, ex);

        } catch (Exception ex) {
            String msg = "Failed to call RAG API";
            log.error(msg, ex);
            throw new RagClientException(msg, ex);
        }
    }

    @ExceptionHandler(RagClientException.class)
    public ResponseEntity<AnswerUserQuery500Response> onRagClient(RagClientException ex) {
        AnswerUserQuery500Response body = new AnswerUserQuery500Response();
        body.setErrorMessage(ex.getMessage());
        return ResponseEntity.status(500).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AnswerUserQuery500Response> onGeneric(Exception ex) {
        log.error("Unhandled error in /answer-user-query", ex);
        AnswerUserQuery500Response body = new AnswerUserQuery500Response();
        body.setErrorMessage("Internal server error");
        return ResponseEntity.status(500).body(body);
    }
}
