package uk.gov.hmcts.cp.cdk.clients.rag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;

import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class ApimDocumentIngestionStatusClientTest {

    @Mock
    private RestClient restClient;

    @Mock
    private RagClientProperties ragClientProperties;

    @Mock
    private ApimAuthHeaderService apimAuthHeaderService;

    // Fluent chain mocks
    @Mock
    private RestClient.RequestHeadersUriSpec uriSpec;
    @Mock
    private RestClient.RequestHeadersSpec<?> headersSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ApimDocumentIngestionStatusClient client;

    @BeforeEach
    void setUp() {
        client = new ApimDocumentIngestionStatusClient(restClient, ragClientProperties, apimAuthHeaderService);
    }

    @Test
    void shouldReturnResponse_whenSuccessWithBody() {
        final String docName = "doc1";
        final DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();

        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> response = ResponseEntity.ok(body);

        mockDocumentStatusChain();
        when(responseSpec.toEntity(any(Class.class))).thenReturn((ResponseEntity) response);

        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> result = client.documentStatus(docName);

        assertThat(result.getStatusCode()).isEqualTo(OK);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void shouldReturnResponse_whenSuccessWithEmptyBody() {
        final String docName = "doc1";

        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> response = ResponseEntity.ok(null);

        mockDocumentStatusChain();
        when(responseSpec.toEntity(any(Class.class))).thenReturn((ResponseEntity) response);

        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> result = client.documentStatus(docName);

        assertThat(result.getStatusCode()).isEqualTo(OK);
        assertThat(result.getBody()).isNull();
    }

    @Test
    void shouldReturnNotFound_when404() {
        final String docName = "doc1";
        final HttpStatusCodeException exception = mock(HttpStatusCodeException.class);
        when(exception.getStatusCode()).thenReturn(NOT_FOUND);
        when(exception.getResponseBodyAsString(UTF_8)).thenReturn("not found");

        mockDocumentStatusChain();
        when(responseSpec.toEntity(any(Class.class))).thenThrow(exception);

        ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> result =
                client.documentStatus(docName);

        assertThat(result.getStatusCode()).isEqualTo(NOT_FOUND);
    }

    @Test
    void shouldRethrow_whenNon404Error() {
        final String docName = "doc1";
        final HttpStatusCodeException exception = mock(HttpStatusCodeException.class);

        when(exception.getStatusCode()).thenReturn(INTERNAL_SERVER_ERROR);
        when(exception.getStatusText()).thenReturn("error");
        when(exception.getResponseBodyAsString(UTF_8)).thenReturn("boom");
        mockDocumentStatusChain();
        when(responseSpec.toEntity(any(Class.class))).thenThrow(exception);

        assertThrows(HttpStatusCodeException.class, () -> client.documentStatus(docName));
    }

    @Test
    void shouldReturnOk_whenSuccess() {
        final String ref = "ref1";
        final DocumentIngestionStatusReturnedSuccessfully body = new DocumentIngestionStatusReturnedSuccessfully();

        mockDocumentStatusChain();
        when(responseSpec.body(any(Class.class))).thenReturn(body);

        final ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> result = client.documentStatusByReference(ref);

        assertThat(result.getStatusCode()).isEqualTo(OK);
        assertThat(result.getBody()).isNotNull();
    }

    @Test
    void shouldWrapHttpException() {
        final String ref = "ref1";
        final HttpStatusCodeException exception = mock(HttpStatusCodeException.class);
        when(exception.getStatusCode()).thenReturn(BAD_REQUEST);
        when(exception.getStatusText()).thenReturn("Bad Request");
        when(exception.getResponseBodyAsString(UTF_8)).thenReturn("error");

        mockDocumentStatusChain();
        when(responseSpec.body(any(Class.class))).thenThrow(exception);

        assertThrows(RagClientException.class, () -> client.documentStatusByReference(ref));
    }

    @Test
    void shouldWrapGenericException() {
        final String ref = "ref1";

        mockDocumentStatusChain();
        when(responseSpec.body(any(Class.class))).thenThrow(new RuntimeException("boom"));

        assertThrows(RagClientException.class, () -> client.documentStatusByReference(ref));
    }

    private void mockDocumentStatusChain() {
        doReturn(uriSpec).when(restClient).get();
        when(uriSpec.uri(any(Function.class))).thenReturn(uriSpec);
        when(uriSpec.accept(any())).thenReturn(uriSpec);
        when(uriSpec.headers(any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
    }
}