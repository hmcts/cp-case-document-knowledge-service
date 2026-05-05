package uk.gov.hmcts.cp.cdk.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.CopyStatusType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
class AzureBlobStorageServiceTest {

    @Mock
    private BlobContainerClient containerClient;
    @Mock
    private BlobClient blobClient;
    @Mock
    private StorageProperties storageProperties;

    @Mock
    private SyncPoller<BlobCopyInfo, Void> poller;
    @Mock
    private PollResponse<BlobCopyInfo> pollResponse;
    @Mock
    private BlobCopyInfo copyInfo;
    @Mock
    private BlobProperties blobProperties;

    private AzureBlobStorageService service;

    @BeforeEach
    void setUp() {
        when(storageProperties.copyPollIntervalMs()).thenReturn(100L);
        when(storageProperties.copyTimeoutSeconds()).thenReturn(10L);

        when(containerClient.getBlobContainerName()).thenReturn("container");

        service = new AzureBlobStorageService(containerClient, storageProperties);
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldThrow_whenSourceUrlBlank() {
        assertThrows(IllegalArgumentException.class, () -> service.copyFromUrl(" ", "dest", Map.of()));
    }

    @Test
    void shouldReturnEmpty_whenBlobAlreadyExists() {
        mockBlobExists(true);

        final String result = service.copyFromUrl("http://source", "path", Map.of());

        assertEquals("", result);
        verify(blobClient, never()).beginCopy(any());
    }

    @Test
    void shouldCopySuccessfully() {
        mockBlobExists(false);
        mockSuccessfulCopy(CopyStatusType.SUCCESS);

        when(blobClient.getBlobUrl()).thenReturn("http://blob");

        final String result = service.copyFromUrl("http://source", "path", Map.of());

        assertEquals("http://blob", result);
    }

    @Test
    void shouldThrow_whenCopyFailed() {
        mockBlobExists(false);
        mockSuccessfulCopy(CopyStatusType.FAILED);

        assertThrows(IllegalStateException.class, () -> service.copyFromUrl("http://source", "path", Map.of()));
    }

    @Test
    void shouldThrow_whenCopyAborted() {
        mockBlobExists(false);
        mockSuccessfulCopy(CopyStatusType.ABORTED);

        assertThrows(IllegalStateException.class,
                () -> service.copyFromUrl("http://source", "path", Map.of()));
    }

    @Test
    void shouldThrowTimeoutException() {
        mockBlobExists(false);

        when(blobClient.beginCopy(any())).thenReturn(poller);
        when(poller.waitForCompletion(any())).thenThrow(new RuntimeException(new TimeoutException()));

        assertThrows(IllegalStateException.class, () -> service.copyFromUrl("http://source", "path", Map.of()));
    }

    @Test
    void shouldApplyMetadata_whenProvided() {
        mockBlobExists(false);
        mockSuccessfulCopy(CopyStatusType.SUCCESS);

        service.copyFromUrl("http://source", "path", Map.of("KEY", "value"));

        verify(blobClient).beginCopy(argThat(options -> options.getMetadata().containsKey("key")));
    }

    @Test
    void shouldReturnMetadata_whenCopySuccessful() {
        final BlobClient destClient = mock(BlobClient.class);

        try (MockedConstruction<BlobClientBuilder> mocked =
                     mockConstruction(BlobClientBuilder.class,
                             (mock, context) -> {
                                 when(mock.endpoint(anyString())).thenReturn(mock);
                                 when(mock.buildClient()).thenReturn(destClient);
                             })) {

            when(destClient.beginCopy(any())).thenReturn(poller);
            when(poller.waitForCompletion(any())).thenReturn(pollResponse);
            when(pollResponse.getValue()).thenReturn(copyInfo);
            when(copyInfo.getCopyStatus()).thenReturn(CopyStatusType.SUCCESS);

            when(destClient.getBlobUrl()).thenReturn("url");
            when(destClient.getProperties()).thenReturn(blobProperties);
            when(blobProperties.getBlobSize()).thenReturn(123L);

            final DocumentBlobMetadata result = service.copyFromUrl("src", "http://container/blob");

            assertThat(result).isNotNull();
            assertThat(result.blobUrl()).isEqualTo("url");
            assertThat(result.blobSize()).isEqualTo(123L);
        }
    }

    @Test
    void shouldThrowBlobStorageException_whenAlreadyExists() {
        final BlobClient destClient = mock(BlobClient.class);

        try (MockedConstruction<BlobClientBuilder> mocked = mockConstruction(BlobClientBuilder.class,
                             (mock, context) -> {
                                 when(mock.endpoint(anyString())).thenReturn(mock);
                                 when(mock.buildClient()).thenReturn(destClient);
                             })) {
            final BlobStorageException exception = mock(BlobStorageException.class);
            when(exception.getStatusCode()).thenReturn(412);
            doThrow(exception).when(destClient).beginCopy(any());

            final BlobStorageException blobStorageException = assertThrows(BlobStorageException.class,
                    () -> service.copyFromUrl("src", "http://container/blob"));

            assertThat(blobStorageException.getStatusCode()).isEqualTo(412);
        }
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldReturnTrue_whenBlobExists() {
        when(containerClient.getBlobClient("path")).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);

        assertThat(service.exists("path")).isTrue();
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldReturnSize_whenExists() {
        when(containerClient.getBlobClient("path")).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getBlobSize()).thenReturn(999L);

        final long size = service.getBlobSize("path");

        assertThat(size).isEqualTo(999L);
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldThrow_whenBlobNotFound() {
        when(containerClient.getBlobClient("path")).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.getBlobSize("path"));
    }

    private void mockBlobExists(final boolean exists) {
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(exists);
    }

    private void mockSuccessfulCopy(final CopyStatusType status) {
        when(containerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.beginCopy(any())).thenReturn(poller);
        when(poller.waitForCompletion(any())).thenReturn(pollResponse);
        when(pollResponse.getValue()).thenReturn(copyInfo);
        when(copyInfo.getCopyStatus()).thenReturn(status);
    }
}