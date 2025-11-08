package uk.gov.hmcts.cp.cdk.batch.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Azure Blob Storage Service tests")
class AzureBlobStorageServiceTest {

    private BlobContainerClient container;
    private BlobClient blob;
    private BlockBlobClient block;
    private AzureBlobStorageService service;

    @BeforeEach
    void setUp() {
        container = mock(BlobContainerClient.class);
        blob = mock(BlobClient.class);
        block = mock(BlockBlobClient.class);

        when(blob.getBlockBlobClient()).thenReturn(block);
        when(container.getBlobContainerName()).thenReturn("documents");

        // Use the package-visible constructor
        service = new AzureBlobStorageService(container);
    }

    @Test
    @DisplayName("copyFromUrl: deletes existing, copies server-side, sets content-type and metadata")
    void copyFromUrl_setsHeadersAndMetadata_andDeletesExisting() {
        final String src = "https://source.blob.core.windows.net/c/src.pdf?sv=..."; // SAS
        final String path = "cases/123/idpc.pdf";

        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/documents/" + path);

        final BlobProperties props = mock(BlobProperties.class);
        when(props.getCopyStatus()).thenReturn(CopyStatusType.SUCCESS);
        when(blob.getProperties()).thenReturn(props);

        final String out = service.copyFromUrl(
                src,
                path,
                "application/pdf",
                Map.of("Document_ID", "123", "X-Tag", "alpha") // keys should be normalized to lower-case
        );

        assertThat(out).endsWith(path);

        verify(blob, times(1)).deleteIfExists();
        verify(block, times(1)).copyFromUrl(eq(src));
        verify(blob, times(1)).setHttpHeaders(argThat((BlobHttpHeaders h) ->
                "application/pdf".equals(h.getContentType())));
        verify(blob, times(1)).setMetadata(argThat(m ->
                m.containsKey("document_id") && m.containsKey("x-tag")
                        && "123".equals(m.get("document_id"))
                        && "alpha".equals(m.get("x-tag"))
        ));
    }

    @Test
    @DisplayName("copyFromUrl: accepts dest as full URL and normalizes to blob name")
    void copyFromUrl_acceptsDestUrl() {
        final String src = "https://source.blob.core.windows.net/c/src.pdf?sv=...";
        // %2F ensures we exercise URL-decoding logic
        final String destUrl = "https://account.blob.core.windows.net/documents/cases%2F123%2Fidpc.pdf";

        when(container.getBlobClient(anyString())).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/documents/cases/123/idpc.pdf");

        final BlobProperties props = mock(BlobProperties.class);
        when(props.getCopyStatus()).thenReturn(CopyStatusType.SUCCESS);
        when(blob.getProperties()).thenReturn(props);

        service.copyFromUrl(src, destUrl, "application/pdf", null);

        final ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(container).getBlobClient(nameCaptor.capture());
        assertThat(nameCaptor.getValue()).isEqualTo("cases/123/idpc.pdf");
    }

    @Test
    @DisplayName("copyFromUrl: defaults blank content-type to application/octet-stream")
    void copyFromUrl_defaultsContentType() {
        final String src = "https://source/blob?sv=...";
        final String path = "f.bin";

        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("u");

        final BlobProperties props = mock(BlobProperties.class);
        when(props.getCopyStatus()).thenReturn(CopyStatusType.SUCCESS);
        when(blob.getProperties()).thenReturn(props);

        service.copyFromUrl(src, path, null, null);

        verify(blob).setHttpHeaders(argThat((BlobHttpHeaders h) ->
                "application/octet-stream".equals(h.getContentType())));
    }

    @Test
    @DisplayName("upload sets headers and overwrites")
    void upload_setsHeaders() {
        final String path = "x/y.pdf";
        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("u");

        service.upload(path, new ByteArrayInputStream("pdf".getBytes()), 3, "application/pdf");

        verify(blob).upload(any(), eq(3L), eq(true));
        verify(blob).setHttpHeaders(argThat(h -> "application/pdf".equals(h.getContentType())));
    }

    @Test
    @DisplayName("exists: accepts URL or name (normalizes to blob name)")
    void exists_normalizesUrl() {
        final String url = "https://account.blob.core.windows.net/documents/cases/1/a.pdf";
        when(container.getBlobClient("cases/1/a.pdf")).thenReturn(blob);
        when(blob.exists()).thenReturn(true);

        assertThat(service.exists(url)).isTrue();
        verify(container).getBlobClient(eq("cases/1/a.pdf"));
    }

    @Test
    @DisplayName("getBlobSize: accepts URL, normalizes, and returns size")
    void getBlobSize_normalizesUrl_andReturnsSize() {
        final String url = "https://account.blob.core.windows.net/documents/cases/2/b.pdf";

        when(container.getBlobClient("cases/2/b.pdf")).thenReturn(blob);
        when(blob.exists()).thenReturn(true);

        final BlobProperties props = mock(BlobProperties.class);
        when(props.getBlobSize()).thenReturn(42L);
        when(blob.getProperties()).thenReturn(props);

        assertThat(service.getBlobSize(url)).isEqualTo(42L);
    }

    @Test
    @DisplayName("getBlobSize: throws if blob does not exist")
    void getBlobSize_throwsWhenMissing() {
        final String name = "cases/3/c.pdf";
        when(container.getBlobClient(name)).thenReturn(blob);
        when(blob.exists()).thenReturn(false);

        assertThatThrownBy(() -> service.getBlobSize(name))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Blob not found");
    }
}
