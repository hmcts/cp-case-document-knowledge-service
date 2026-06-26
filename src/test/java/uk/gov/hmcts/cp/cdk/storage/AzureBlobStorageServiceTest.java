package uk.gov.hmcts.cp.cdk.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Mock
    private BlobClient destClient;

    private AzureBlobStorageService service;

    @BeforeEach
    void setUp() {
        when(storageProperties.copyPollIntervalMs()).thenReturn(100L);
        when(storageProperties.copyTimeoutSeconds()).thenReturn(10L);

        service = new AzureBlobStorageService(containerClient, storageProperties);
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldThrow_whenSourceUrlBlank() {
        assertThrows(IllegalArgumentException.class, () -> service.copyFromUrl(" ", "dest"));
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldReturnEmpty_whenBlobAlreadyExists() {
        try (MockedConstruction<BlobClientBuilder> mocked =
                     mockConstruction(BlobClientBuilder.class,
                             (mock, context) -> {
                                 when(mock.endpoint(anyString())).thenReturn(mock);
                                 when(mock.buildClient()).thenReturn(destClient);
                             })) {

            BlobStorageException exception = mock(BlobStorageException.class);
            when(exception.getStatusCode()).thenReturn(412);
            when(destClient.beginCopy(any())).thenThrow(exception);

            assertThrows(BlobStorageException.class,
                    () -> service.copyFromUrl("http://source", "http://dest"));
            verify(destClient).beginCopy(any());
        }
    }

    @Test
    void shouldThrow_whenCopyFailed() {
        try (MockedConstruction<BlobClientBuilder> mocked =
                     mockConstruction(BlobClientBuilder.class,
                             (mock, context) -> {
                                 when(mock.endpoint(anyString())).thenReturn(mock);
                                 when(mock.buildClient()).thenReturn(destClient);
                             })) {

            when(destClient.beginCopy(any())).thenReturn(poller);
            when(poller.waitForCompletion(any())).thenReturn(pollResponse);
            when(pollResponse.getValue()).thenReturn(copyInfo);
            when(copyInfo.getCopyStatus()).thenReturn(CopyStatusType.FAILED);

            assertThrows(IllegalStateException.class, () -> service.copyFromUrl("http://source", "http://dest"));
        }
    }

    @Test
    void shouldThrow_whenCopyAborted() {
        try (MockedConstruction<BlobClientBuilder> mocked =
                     mockConstruction(BlobClientBuilder.class,
                             (mock, context) -> {
                                 when(mock.endpoint(anyString())).thenReturn(mock);
                                 when(mock.buildClient()).thenReturn(destClient);
                             })) {

            when(destClient.beginCopy(any())).thenReturn(poller);
            when(poller.waitForCompletion(any())).thenReturn(pollResponse);
            when(pollResponse.getValue()).thenReturn(copyInfo);
            when(copyInfo.getCopyStatus()).thenReturn(CopyStatusType.ABORTED);

            assertThrows(IllegalStateException.class,
                    () -> service.copyFromUrl("http://source", "path"));
        }
    }

    @MockitoSettings(strictness = Strictness.LENIENT)
    @Test
    void shouldThrowTimeoutException() {
        try (MockedConstruction<BlobClientBuilder> mocked =
                     mockConstruction(BlobClientBuilder.class,
                             (mock, context) -> {
                                 when(mock.endpoint(anyString())).thenReturn(mock);
                                 when(mock.buildClient()).thenReturn(destClient);
                             })) {

            when(destClient.beginCopy(any())).thenReturn(poller);
            when(poller.waitForCompletion(any())).thenReturn(pollResponse);
            when(pollResponse.getValue()).thenReturn(copyInfo);
            when(poller.waitForCompletion(any())).thenThrow(new RuntimeException(new TimeoutException()));

            assertThrows(IllegalStateException.class, () -> service.copyFromUrl("http://source", "path"));
        }

    }

    @Test
    void shouldReturnMetadata_whenCopySuccessful() {
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

}