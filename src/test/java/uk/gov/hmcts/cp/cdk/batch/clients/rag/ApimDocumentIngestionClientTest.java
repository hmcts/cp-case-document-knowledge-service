package uk.gov.hmcts.cp.cdk.batch.clients.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.clients.rag.ApimDocumentIngestionClient;
import uk.gov.hmcts.cp.cdk.clients.rag.RagClientException;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
public class ApimDocumentIngestionClientTest {

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @InjectMocks
    private ApimDocumentIngestionClient client;

    @Test
    void shouldReturnResponseWhenApiCallSuccessful() {

        final DocumentUploadRequest request = new DocumentUploadRequest();
        final FileStorageLocationReturnedSuccessfully apiResponse = new FileStorageLocationReturnedSuccessfully();
        apiResponse.setDocumentReference("document-ref");
        apiResponse.setStorageUrl("http://localhost.blob.net/document-new");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(DocumentUploadRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(FileStorageLocationReturnedSuccessfully.class)).thenReturn(apiResponse);

        final ResponseEntity<@NotNull FileStorageLocationReturnedSuccessfully> response = client.initiateDocumentUpload(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(apiResponse);
    }

    @Test
    void shouldReturnEmptyResponseWhenApiReturnsNull() {

        final DocumentUploadRequest request = new DocumentUploadRequest();

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(DocumentUploadRequest.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(FileStorageLocationReturnedSuccessfully.class)).thenReturn(null);

        final ResponseEntity<FileStorageLocationReturnedSuccessfully> response = client.initiateDocumentUpload(request);

        assertNotNull(response.getBody());
        assertThat(response.getBody().getDocumentReference()).isNull();
        assertThat(response.getBody().getStorageUrl()).isNull();
    }

    @Test
    void shouldWrapHttpStatusCodeExceptionIntoRagClientException() {

        final DocumentUploadRequest request = new DocumentUploadRequest();

        HttpStatusCodeException exception =
                new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad request");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(exception);

        assertThrows(RagClientException.class, () -> client.initiateDocumentUpload(request));
    }

    @Test
    void shouldWrapGenericExceptionIntoRagClientException() {
        final DocumentUploadRequest request = new DocumentUploadRequest();

        when(restClient.post()).thenThrow(new RuntimeException("error"));

        assertThrows(RagClientException.class, () -> client.initiateDocumentUpload(request));
    }

}
