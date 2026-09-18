package uk.gov.hmcts.cp.cdk.clients.rag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;

import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class ApimDocumentIngestionClientTest {

    @Mock
    private RestClient restClient;
    @Mock
    private RagClientProperties ragClientProperties;
    @Mock
    private ApimAuthHeaderService apimAuthHeaderService;
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;
    @InjectMocks
    private ApimDocumentIngestionClient client;

    private DocumentUploadRequest request;

    @BeforeEach
    void setup() {
        request = new DocumentUploadRequest();
    }

    @Test
    void shouldReturnSuccessfulResponse() {
        // given
        mockPostRequest();
        final FileStorageLocationReturnedSuccessfully apiResponse = new FileStorageLocationReturnedSuccessfully();

        when(responseSpec.body(FileStorageLocationReturnedSuccessfully.class)).thenReturn(apiResponse);

        // when
        final ResponseEntity<FileStorageLocationReturnedSuccessfully> response = client.initiateDocumentUpload(request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(apiResponse);

        verify(apimAuthHeaderService).applyCommonHeaders(any(), any());
        verify(apimAuthHeaderService).applyAuthHeaders(any(), eq(ragClientProperties));
    }

    @Test
    void shouldReturnEmptyObjectWhenResponseIsNull() {
        // given
        mockPostRequest();
        when(responseSpec.body(FileStorageLocationReturnedSuccessfully.class)).thenReturn(null);

        // when
        final ResponseEntity<FileStorageLocationReturnedSuccessfully> response = client.initiateDocumentUpload(request);

        // then
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void shouldThrowRagClientExceptionOnHttpStatusCodeException() {
        // given
        mockPostRequest();
        final HttpStatusCodeException exception = mock(HttpStatusCodeException.class);
        when(exception.getStatusCode()).thenReturn(BAD_REQUEST);
        when(exception.getStatusText()).thenReturn("Bad Request");
        when(exception.getResponseBodyAsString(UTF_8)).thenReturn("error-body");
        when(responseSpec.body(FileStorageLocationReturnedSuccessfully.class)).thenThrow(exception);

        // when
        final RagClientException thrown = assertThrows(RagClientException.class, () -> client.initiateDocumentUpload(request));

        // then
        assertThat(thrown.getMessage().contains("APIM API error")).isTrue();
        assertThat(thrown.getMessage().contains("400")).isTrue();
        assertThat(thrown.getMessage().contains("Bad Request")).isTrue();
        assertThat(thrown.getMessage().contains("error-body")).isTrue();
    }


    @Test
    void shouldThrowRagClientExceptionOnGenericException() {
        // given
        mockPostRequest();
        when(responseSpec.body(FileStorageLocationReturnedSuccessfully.class)).thenThrow(new RuntimeException("boom"));

        // when
        final RagClientException thrown = assertThrows(RagClientException.class, () -> client.initiateDocumentUpload(request));

        // then
        assertThat(thrown.getMessage()).isEqualTo("Failed to call APIM API");
        assertThat(thrown.getCause() instanceof RuntimeException).isTrue();
    }

    @Test
    void shouldInvokeRestClientWithCorrectConfiguration() {

        // given
        mockPostRequest();
        when(responseSpec.body(FileStorageLocationReturnedSuccessfully.class)).thenReturn(new FileStorageLocationReturnedSuccessfully());

        // when
        client.initiateDocumentUpload(request);

        // then
        verify(restClient).post();
        verify(requestBodyUriSpec).uri(anyString());
        verify(requestBodySpec).contentType(APPLICATION_JSON);
        verify(requestBodySpec).accept(APPLICATION_JSON);
        verify(requestBodySpec).headers(any());
        verify(requestBodySpec).body(request);
        verify(requestBodySpec).retrieve();
        verify(responseSpec).body(FileStorageLocationReturnedSuccessfully.class);
    }

    private void mockPostRequest() {
        // chain standard mock structure
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenAnswer(invocation -> {
            final Consumer<HttpHeaders> consumer = invocation.getArgument(0);
            final HttpHeaders headers = new HttpHeaders();
            consumer.accept(headers);
            return requestBodySpec;
        });
        when(requestBodySpec.body(any(DocumentUploadRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(ragClientProperties.getHeaders()).thenReturn(emptyMap());
    }

}