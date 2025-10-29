package uk.gov.hmcts.cp.cdk.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.CopyStatusType;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;

import static org.mockito.Mockito.*;

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

        // Use the package-visible constructor with sane defaults for polling
        service = new AzureBlobStorageService(container);
    }

    @Test
    @DisplayName("copyFromUrl copies server-side and sets content-type")
    void copyFromUrl_setsHeaders() {
        final String src = "https://source.blob.core.windows.net/c/src.pdf?sv=..."; // SAS
        final String path = "cases/123/idpc.pdf";

        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("https://dst/container/" + path);

        final BlobProperties props = mock(BlobProperties.class);
        when(props.getCopyStatus()).thenReturn(CopyStatusType.SUCCESS);
        when(blob.getProperties()).thenReturn(props);

        final String out = service.copyFromUrl(src, path, "application/pdf",null);

        assertThat(out).endsWith(path);
        verify(block, times(1)).copyFromUrl(eq(src));
        verify(blob, times(1)).setHttpHeaders(argThat((BlobHttpHeaders h) ->
                "application/pdf".equals(h.getContentType())));
    }

    @Test
    @DisplayName("copyFromUrl defaults blank content-type")
    void copyFromUrl_defaultsContentType() {
        final String src = "https://source/blob?sv=...";
        final String path = "f.bin";

        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("u");

        final BlobProperties props = mock(BlobProperties.class);
        when(props.getCopyStatus()).thenReturn(CopyStatusType.SUCCESS);
        when(blob.getProperties()).thenReturn(props);

        service.copyFromUrl(src, path, null,null);

        verify(blob).setHttpHeaders(argThat((BlobHttpHeaders h) ->
                "application/octet-stream".equals(h.getContentType())));
    }

    @Test
    @DisplayName("upload still works for streams")
    void upload_setsHeaders() {
        final String path = "x/y.pdf";
        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("u");

        service.upload(path, new ByteArrayInputStream("pdf".getBytes()), 3, "application/pdf");

        verify(blob).upload(any(), eq(3L), eq(true));
        verify(blob).setHttpHeaders(argThat(h -> "application/pdf".equals(h.getContentType())));
    }

    @Test
    @DisplayName("exists delegates")
    void exists_delegates() {
        final String path = "z";
        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.exists()).thenReturn(true);
        assertThat(service.exists(path)).isTrue();
    }
}
