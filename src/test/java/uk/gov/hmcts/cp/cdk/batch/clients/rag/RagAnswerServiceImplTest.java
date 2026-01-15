package uk.gov.hmcts.cp.cdk.batch.clients.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.cdk.clients.rag.RagAnswerServiceImpl;
import uk.gov.hmcts.cp.cdk.clients.rag.RagClientException;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQuery500Response;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfully;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class RagAnswerServiceImplTest {

    @Mock
    RestClient ragRestClient;
    @Mock
    RestClient.RequestBodyUriSpec postSpec;
    @Mock
    RestClient.RequestBodySpec bodySpec;
    @Mock
    RestClient.ResponseSpec responseSpec;
    @Mock
    RagClientProperties ragClientProperties;
    @Mock
    ApimAuthHeaderService apimAuthHeaderService;

    RagAnswerServiceImpl service;

    @BeforeEach
    void setup() {
        service = new RagAnswerServiceImpl(ragRestClient, ragClientProperties, apimAuthHeaderService);

        // chain standard mock structure
        when(ragRestClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.accept(any())).thenReturn(bodySpec);
        when(bodySpec.headers(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(AnswerUserQueryRequest.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    void shouldReturn200ResponseWithResponseBody() {
        // given
        final AnswerUserQueryRequest request = new AnswerUserQueryRequest("uq", "qp");

        final UserQueryAnswerReturnedSuccessfully apiResponse = new UserQueryAnswerReturnedSuccessfully();
        apiResponse.setUserQuery("xxx");
        apiResponse.setQueryPrompt("yyy");

        when(bodySpec.retrieve().body(UserQueryAnswerReturnedSuccessfully.class))
                .thenReturn(apiResponse);

        // when
        final var result = service.answerUserQuery(request);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getUserQuery()).isEqualTo("xxx");
        assertThat(result.getBody().getQueryPrompt()).isEqualTo("yyy");
    }

    @Test
    void shouldDefaultEmptyMetadataFilter() {
        // given
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest();
        req.setMetadataFilter(null);

        when(bodySpec.retrieve().body(UserQueryAnswerReturnedSuccessfully.class))
                .thenReturn(new UserQueryAnswerReturnedSuccessfully());
        // when
        service.answerUserQuery(req);

        // then
        assertThat(req.getMetadataFilter()).isNotNull();
        assertThat(req.getMetadataFilter().isEmpty()).isTrue();
    }

    @Test
    void shouldFillMissingFieldsFromRequest() {
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest("query A", "prompt B");

        when(bodySpec.retrieve().body(UserQueryAnswerReturnedSuccessfully.class))
                .thenReturn(new UserQueryAnswerReturnedSuccessfully());

        final var result = service.answerUserQuery(req);

        assertThat(result.getBody().getUserQuery()).isEqualTo("query A");
        assertThat(result.getBody().getQueryPrompt()).isEqualTo("prompt B");
    }

    @Test
    void shouldReturnNewResponseWhenNull() {
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest("query A1", null);

        when(bodySpec.retrieve().body(UserQueryAnswerReturnedSuccessfully.class))
                .thenReturn(null);

        final var result = service.answerUserQuery(req);

        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void shouldWrapHttpStatusExceptionIntoRagClientException() {
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest();

        when(bodySpec.retrieve()).thenThrow(
                new HttpClientErrorException(HttpStatus.BAD_REQUEST, "bad")
        );

        assertThrows(RagClientException.class, () -> service.answerUserQuery(req));
    }

    @Test
    void shouldWrapGenericExceptionIntoRagClientException() {
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest();

        when(bodySpec.retrieve()).thenThrow(new RuntimeException("error"));

        assertThrows(RagClientException.class, () -> service.answerUserQuery(req));
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldReturn500ResponseForRagClientException() {
        final RagAnswerServiceImpl service = new RagAnswerServiceImpl(null, null, null);

        final RagClientException ex = new RagClientException("Boom!", null);

        final ResponseEntity<AnswerUserQuery500Response> response = service.onRagClient(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getErrorMessage()).isEqualTo("Boom!");
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldReturn500ResponseForGenericException() {
        final RagAnswerServiceImpl service = new RagAnswerServiceImpl(null, null, null);

        final Exception ex = new RuntimeException("We don't care about msg");

        final ResponseEntity<AnswerUserQuery500Response> response = service.onGeneric(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getErrorMessage()).isEqualTo("Internal server error");
    }
}