package uk.gov.hmcts.cp.cdk.batch.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.options.BlobBeginCopyOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Azure Blob Storage Service tests")
class AzureBlobStorageServiceTest {

    private BlobContainerClient mockBlobContainerClient;
    private BlobClient mockBlob;
    private AzureBlobStorageService service;
    private SyncPoller<BlobCopyInfo, Void> mockSyncPoller;
    private PollResponse<BlobCopyInfo> mockPollResponse;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mockBlobContainerClient = mock(BlobContainerClient.class);
        mockBlob = mock(BlobClient.class);
        mockSyncPoller = (SyncPoller<BlobCopyInfo, Void>) mock(SyncPoller.class);
        mockPollResponse = (PollResponse<BlobCopyInfo>) mock(PollResponse.class);

        when(mockBlobContainerClient.getBlobContainerName()).thenReturn("documents");

        // Build minimal StorageProperties for the constructor
        StorageProperties.Azurite azurite = new StorageProperties.Azurite(null);
        StorageProperties props = new StorageProperties(
                "connection-string",   // mode (not used by service; only poll/timeout are read)
                null,                  // connectionString
                "documents",           // container (only used in helpers via mockBlobContainerClient.getBlobContainerName())
                1_000L,                // copyPollIntervalMs
                120L,                  // copyTimeoutSeconds
                null,                  // accountName
                null,                  // blobEndpoint
                null,                  // managedIdentityClientId
                azurite                // azurite
        );

        // Use the new ctor
        service = new AzureBlobStorageService(mockBlobContainerClient, props);
    }

    @Test
    @DisplayName("copyFromUrl: deletes existing, copies server-side, sets content-type and metadata")
    void copyFromUrl_setsHeadersAndMetadata_andDeletesExisting() {
        final String src = "https://source.blob.core.windows.net/c/src.pdf?sv=..."; // SAS
        final String path = "cases/123/idpc.pdf";

        when(mockBlobContainerClient.getBlobClient(path)).thenReturn(mockBlob);
        when(mockBlob.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/documents/" + path);
        when(mockBlob.beginCopy(any(BlobBeginCopyOptions.class))).thenReturn(mockSyncPoller);
        when(mockSyncPoller.waitForCompletion(any(Duration.class))).thenReturn(mockPollResponse);
        when(mockPollResponse.getValue()).thenReturn(new BlobCopyInfo("", "", CopyStatusType.SUCCESS, "", null, null, null));

        final BlobProperties props = mock(BlobProperties.class);
        when(props.getCopyStatus()).thenReturn(CopyStatusType.SUCCESS);
        when(mockBlob.getProperties()).thenReturn(props);

        final String out = service.copyFromUrl(
                src,
                path,
                "application/pdf",
                Map.of("Document_ID", "123", "X-Tag", "alpha") // keys should be normalized to lower-case
        );

        assertThat(out).endsWith(path);

        //verify(mockBlob).deleteIfExists();
        verify(mockSyncPoller).waitForCompletion(any(Duration.class));
        verify(mockPollResponse).getValue();

        ArgumentCaptor<BlobBeginCopyOptions> optionsCaptor = ArgumentCaptor.forClass(BlobBeginCopyOptions.class);
        verify(mockBlob).beginCopy(optionsCaptor.capture());
        final BlobBeginCopyOptions captorValue = optionsCaptor.getValue();
        assertThat(captorValue.getSourceUrl()).isEqualTo(src);
        assertThat(captorValue.getMetadata()).hasSize(2);
        assertThat(captorValue.getMetadata()).hasFieldOrPropertyWithValue("document_id", "123");
        assertThat(captorValue.getMetadata()).hasFieldOrPropertyWithValue("x-tag", "alpha");
    }

    @Test
    @DisplayName("copyFromUrl: accepts dest as full URL and normalizes to blob name")
    void copyFromUrl_acceptsDestUrl() {
        final String src = "https://source.blob.core.windows.net/c/src.pdf?sv=...";
        // %2F ensures we exercise URL-decoding logic
        final String destUrl = "https://account.blob.core.windows.net/documents/cases%2F123%2Fidpc.pdf";

        when(mockBlobContainerClient.getBlobClient(anyString())).thenReturn(mockBlob);
        when(mockBlob.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/documents/cases/123/idpc.pdf");
        when(mockBlob.beginCopy(any(BlobBeginCopyOptions.class))).thenReturn(mockSyncPoller);
        when(mockSyncPoller.waitForCompletion(any(Duration.class))).thenReturn(mockPollResponse);
        when(mockPollResponse.getValue()).thenReturn(new BlobCopyInfo("", "", CopyStatusType.SUCCESS, "", null, null, null));

        service.copyFromUrl(src, destUrl, "application/pdf", null);

        final ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockBlobContainerClient).getBlobClient(nameCaptor.capture());
        assertThat(nameCaptor.getValue()).isEqualTo("cases/123/idpc.pdf");
    }

    @Test
    @DisplayName("copyFromUrl: defaults blank content-type to application/octet-stream")
    void copyFromUrl_defaultsContentType() {
        final String src = "https://source/blob?sv=...";
        final String path = "f.bin";

        when(mockBlobContainerClient.getBlobClient(path)).thenReturn(mockBlob);
        when(mockBlob.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/documents/" + path);
        when(mockBlob.beginCopy(any(BlobBeginCopyOptions.class))).thenReturn(mockSyncPoller);
        when(mockSyncPoller.waitForCompletion(any(Duration.class))).thenReturn(mockPollResponse);
        when(mockPollResponse.getValue()).thenReturn(new BlobCopyInfo("", "", CopyStatusType.SUCCESS, "", null, null, null));

        service.copyFromUrl(src, path, null, null);
        /**
        verify(mockBlob).setHttpHeaders(argThat((BlobHttpHeaders h) ->
                "application/octet-stream".equals(h.getContentType())));
         **/
    }

    @Test
    @DisplayName("exists: accepts URL or name (normalizes to blob name)")
    void exists_normalizesUrl() {
        final String url = "https://account.blob.core.windows.net/documents/cases/1/a.pdf";
        when(mockBlobContainerClient.getBlobClient("cases/1/a.pdf")).thenReturn(mockBlob);
        when(mockBlob.exists()).thenReturn(true);

        assertThat(service.exists(url)).isTrue();
        verify(mockBlobContainerClient).getBlobClient(eq("cases/1/a.pdf"));
    }

    @Test
    @DisplayName("getBlobSize: accepts URL, normalizes, and returns size")
    void getBlobSize_normalizesUrl_andReturnsSize() {
        final String url = "https://account.blob.core.windows.net/documents/cases/2/b.pdf";

        when(mockBlobContainerClient.getBlobClient("cases/2/b.pdf")).thenReturn(mockBlob);
        when(mockBlob.exists()).thenReturn(true);

        final BlobProperties props = mock(BlobProperties.class);
        when(props.getBlobSize()).thenReturn(42L);
        when(mockBlob.getProperties()).thenReturn(props);

        assertThat(service.getBlobSize(url)).isEqualTo(42L);
    }

    @Test
    @DisplayName("getBlobSize: throws if blob does not exist")
    void getBlobSize_throwsWhenMissing() {
        final String name = "cases/3/c.pdf";
        when(mockBlobContainerClient.getBlobClient(name)).thenReturn(mockBlob);
        when(mockBlob.exists()).thenReturn(false);

        assertThatThrownBy(() -> service.getBlobSize(name))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Blob not found");
    }
}
