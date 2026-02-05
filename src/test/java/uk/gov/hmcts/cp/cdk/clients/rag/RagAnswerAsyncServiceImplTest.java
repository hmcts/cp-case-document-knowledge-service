package uk.gov.hmcts.cp.cdk.clients.rag;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.RequestErrored;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerRequestAccepted;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@ExtendWith(MockitoExtension.class)
class RagAnswerAsyncServiceImplTest {

    @Mock
    private RestClient ragRestClient;
    @Mock
    private RestClient.RequestBodyUriSpec postSpec;
    @Mock
    private RestClient.RequestBodySpec bodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;
    @Mock
    private RagClientProperties ragClientProperties;
    @Mock
    private ApimAuthHeaderService apimAuthHeaderService;

    private RagAnswerAsyncServiceImpl service;

    @Mock
    private RestClient.RequestHeadersUriSpec uriSpec;
    @Captor
    private ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor;

    @BeforeEach
    void setup() {
        service = new RagAnswerAsyncServiceImpl(ragRestClient, ragClientProperties, apimAuthHeaderService);
    }

    @Test
    void shouldReturn200ResponseWithResponseBody() {
        // given
        mockPostRequest();
        final AnswerUserQueryRequest request = new AnswerUserQueryRequest("uq", "qp");

        final String transactionId = randomUUID().toString();
        final UserQueryAnswerRequestAccepted apiResponse = new UserQueryAnswerRequestAccepted();
        apiResponse.setTransactionId(transactionId);

        when(bodySpec.retrieve().body(UserQueryAnswerRequestAccepted.class)).thenReturn(apiResponse);

        // when
        final var result = service.answerUserQueryAsync(request);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getTransactionId()).isEqualTo(transactionId);
    }

    @Test
    void shouldDefaultEmptyMetadataFilter() {
        // given
        mockPostRequest();
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest();
        req.setMetadataFilter(null);

        when(bodySpec.retrieve().body(UserQueryAnswerRequestAccepted.class))
                .thenReturn(new UserQueryAnswerRequestAccepted());
        // when
        service.answerUserQueryAsync(req);

        // then
        assertThat(req.getMetadataFilter()).isNotNull();
        assertThat(req.getMetadataFilter().isEmpty()).isTrue();
    }

    @Test
    void shouldReturnNullTransactionIdWhenNoneReturned() {
        mockPostRequest();
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest("query A", "prompt B");

        when(bodySpec.retrieve().body(UserQueryAnswerRequestAccepted.class))
                .thenReturn(new UserQueryAnswerRequestAccepted());

        final var result = service.answerUserQueryAsync(req);

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getTransactionId()).isNull();
    }

    @Test
    void shouldReturnNewResponseWhenNull() {
        mockPostRequest();
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest("query A1", null);

        when(bodySpec.retrieve().body(UserQueryAnswerRequestAccepted.class))
                .thenReturn(null);

        final var result = service.answerUserQueryAsync(req);

        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void shouldWrapHttpStatusExceptionIntoRagClientException() {
        mockPostRequest();
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest();

        when(bodySpec.retrieve()).thenThrow(
                new HttpClientErrorException(HttpStatus.BAD_REQUEST, "bad")
        );

        assertThrows(RagClientException.class, () -> service.answerUserQueryAsync(req));
    }

    @Test
    void shouldWrapGenericExceptionIntoRagClientException() {
        mockPostRequest();
        final AnswerUserQueryRequest req = new AnswerUserQueryRequest();

        when(bodySpec.retrieve()).thenThrow(new RuntimeException("error"));

        assertThrows(RagClientException.class, () -> service.answerUserQueryAsync(req));
    }

    @Mock
    RestClient.RequestHeadersSpec<?> headersSpec;


    @SuppressWarnings("unchecked")
    @Test
    void shouldGetAnswerStatus_answerGenerationPendingWhenAnswerNotReady() throws URISyntaxException {
        mockGetRequest();
        final String transactionId = randomUUID().toString();
        final UserQueryAnswerReturnedSuccessfullyAsynchronously apiResponse = new UserQueryAnswerReturnedSuccessfullyAsynchronously();
        apiResponse.setTransactionId(transactionId);
        apiResponse.setStatus(ANSWER_GENERATION_PENDING);

        when(responseSpec.body(UserQueryAnswerReturnedSuccessfullyAsynchronously.class)).thenReturn(apiResponse);

        // when
        final var result = service.answerUserQueryStatus(transactionId);

        verify(uriSpec).uri(uriCaptor.capture());
        final Function<UriBuilder, URI> uriFunction = uriCaptor.getValue();
        final URI uri = uriFunction.apply(UriComponentsBuilder.fromUri(new URI("http://localhost")));
        assertThat(uri.toString()).isEqualTo("http://localhost/answer-user-query-async-status/" + transactionId);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getTransactionId()).isNotNull();
        assertThat(result.getBody().getTransactionId()).isEqualTo(transactionId);
    }

    @Test
    void shouldGetAnswerStatus_whenAnswerGeneratedAndReady() {
        mockGetRequest();
        final String transactionId = randomUUID().toString();
        final List<Object> chunkedEntries = List.of("chunk1", "chunk2");
        final UserQueryAnswerReturnedSuccessfullyAsynchronously apiResponse = new UserQueryAnswerReturnedSuccessfullyAsynchronously();
        apiResponse.setTransactionId(transactionId);
        apiResponse.setStatus(ANSWER_GENERATED);
        apiResponse.setLlmResponse("llmResponse");
        apiResponse.setUserQuery("user query");
        apiResponse.setQueryPrompt("query prompt");
        apiResponse.setChunkedEntries(chunkedEntries);


        when(responseSpec.body(UserQueryAnswerReturnedSuccessfullyAsynchronously.class)).thenReturn(apiResponse);

        // when
        final var result = service.answerUserQueryStatus(transactionId);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getTransactionId()).isEqualTo(transactionId);
        assertThat(result.getBody().getStatus()).isEqualTo(ANSWER_GENERATED);
        assertThat(result.getBody().getLlmResponse()).isEqualTo("llmResponse");
        assertThat(result.getBody().getUserQuery()).isEqualTo("user query");
        assertThat(result.getBody().getQueryPrompt()).isEqualTo("query prompt");
        assertThat(result.getBody().getChunkedEntries()).isEqualTo(chunkedEntries);
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldReturn500ResponseForRagClientException() {
        final RagAnswerAsyncServiceImpl service = new RagAnswerAsyncServiceImpl(null, null, null);

        final RagClientException ex = new RagClientException("Boom!", null);

        final ResponseEntity<RequestErrored> response = service.onRagClient(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getErrorMessage()).isEqualTo("Boom!");
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldReturn500ResponseForGenericException() {
        final RagAnswerAsyncServiceImpl service = new RagAnswerAsyncServiceImpl(null, null, null);

        final Exception ex = new RuntimeException("We don't care about msg");

        final ResponseEntity<RequestErrored> response = service.onGeneric(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getErrorMessage()).isEqualTo("Internal server error");
    }

    @SuppressWarnings("unchecked")
    private void mockGetRequest() {
        doReturn(uriSpec).when(ragRestClient).get();
        when(uriSpec.uri(any(Function.class))).thenReturn(uriSpec);
        when(uriSpec.accept(any())).thenReturn(uriSpec);
        when(uriSpec.headers(any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
    }

    private void mockPostRequest() {
        // chain standard mock structure
        when(ragRestClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.accept(any())).thenReturn(bodySpec);
        when(bodySpec.headers(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(AnswerUserQueryRequest.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
    }


}