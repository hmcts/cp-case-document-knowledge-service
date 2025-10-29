package uk.gov.hmcts.cp.cdk.clients.rag;

import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RagAnswerServiceImpl implements RagAnswerService {

    private final RestClient restClient;
    private final RagClientProperties props;

    public RagAnswerServiceImpl(final RestClient ragRestClient, final RagClientProperties props) {
        this.restClient = ragRestClient;
        this.props = props;
    }

    @Override
    public UserQueryAnswerReturnedSuccessfully answerUserQuery(final String userQuery,
                                                               final String queryPrompt,
                                                               final List<MetadataFilter> metadataFilters) {
        try {
            final List<MetadataFilter> filters = (metadataFilters != null) ? metadataFilters : new ArrayList<>();

            final AnswerUserQueryRequest payload = new AnswerUserQueryRequest()
                    .userQuery(userQuery)
                    .queryPrompt(queryPrompt)
                    .metadataFilters(filters);

            return restClient
                    .post()
                    .uri(props.getAnswerQueryPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> {
                        final Map<String, String> hdrs = props.getHeaders();
                        if (hdrs != null) {
                            hdrs.forEach(httpHeaders::add);
                        }
                    })
                    .body(payload)
                    .retrieve()
                    .body(UserQueryAnswerReturnedSuccessfully.class);

        } catch (HttpStatusCodeException ex) {
            final String responseBody = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
            final String message = "RAG API error: %s %s - %s"
                    .formatted(ex.getStatusCode().value(), ex.getStatusText(), responseBody);
            throw new RagClientException(message, ex);
        } catch (Exception ex) {
            throw new RagClientException("Failed to call RAG API", ex);
        }
    }
}
