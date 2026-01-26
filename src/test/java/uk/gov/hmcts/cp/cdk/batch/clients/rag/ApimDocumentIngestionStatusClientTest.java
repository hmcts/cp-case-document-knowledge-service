package uk.gov.hmcts.cp.cdk.batch.clients.rag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.EMPTY;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.cdk.clients.rag.ApimDocumentIngestionStatusClient;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;

import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class ApimDocumentIngestionStatusClientTest {

    @Mock
    private RestClient restClient;
    @Mock
    private RagClientProperties ragClientProperties;
    @Mock
    private ApimAuthHeaderService apimAuthHeaderService;

    private ApimDocumentIngestionStatusClient client;
    @Mock
    private RestClient.RequestHeadersUriSpec uriSpec;
    @Mock
    RestClient.RequestHeadersSpec<?> headersSpec;
    @Mock
    RestClient.ResponseSpec responseSpec;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        client = new ApimDocumentIngestionStatusClient(restClient, ragClientProperties, apimAuthHeaderService);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(Function.class))).thenReturn(uriSpec);
        when(uriSpec.accept(any())).thenReturn(uriSpec);
        when(uriSpec.headers(any())).thenReturn(uriSpec);
    }

    @Test
    void shouldReturnResponse_whenBodyPresent() {
        // given
        final DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();
        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> responseEntity = ResponseEntity.ok(body);

        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(DocumentIngestionStatusReturnedSuccessfully.class))
                .thenReturn(responseEntity);

        // when
        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> result = client.documentStatus("doc1");

        // then
        assertThat(result.getStatusCode()).isEqualTo(OK);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void shouldReturnResponse_whenBodyIsNull() {
        // given
        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> responseEntity = ResponseEntity.ok().build();

        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(DocumentIngestionStatusReturnedSuccessfully.class)).thenReturn(responseEntity);

        // when
        ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> result = client.documentStatus("doc2");

        // then
        assertThat(result.getStatusCode()).isEqualTo(OK);
        assertThat(result.getBody());
    }

    @Test
    void shouldReturn404_whenHttpNotFound() {
        // given
        final HttpClientErrorException notFound = HttpClientErrorException.create(
                NOT_FOUND,
                "Not Found",
                EMPTY,
                "doc missing".getBytes(UTF_8),
                UTF_8
        );

        when(uriSpec.retrieve()).thenThrow(notFound);

        // when
        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> result = client.documentStatus("missing-doc");

        // then
        assertThat(result.getStatusCode()).isEqualTo(NOT_FOUND);
        assertThat(result.getBody());
    }


    @Test
    void shouldThrowException_forOtherHttpErrors() {
        // given
        final HttpServerErrorException serverError = HttpServerErrorException.create(
                INTERNAL_SERVER_ERROR,
                "Server error",
                EMPTY,
                "oops".getBytes(UTF_8),
                UTF_8
        );

        when(uriSpec.retrieve()).thenThrow(serverError);

        // when / then
        final HttpServerErrorException ex = assertThrows(
                HttpServerErrorException.class,
                () -> client.documentStatus("docX")
        );

        assertThat(ex.getStatusCode()).isEqualTo(INTERNAL_SERVER_ERROR);
    }
}