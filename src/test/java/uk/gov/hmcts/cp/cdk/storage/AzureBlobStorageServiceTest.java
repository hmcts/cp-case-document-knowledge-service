// src/test/java/uk/gov/hmcts/cp/cdk/storage/AzureBlobStorageServiceTest.java
package uk.gov.hmcts.cp.cdk.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        service = new AzureBlobStorageService(container);
    }

    @Test
    @DisplayName("copyFromUrl copies server-side and sets content-type")
    void copyFromUrl_setsHeaders() {
        String src = "https://source.blob.core.windows.net/c/src.pdf?sv=..."; // SAS
        String path = "cases/123/idpc.pdf";
        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("https://dst/container/" + path);

        String out = service.copyFromUrl(src, path, "application/pdf");

        assertThat(out).endsWith(path);
        verify(block, times(1)).copyFromUrl(eq(src));
        verify(blob, times(1)).setHttpHeaders(argThat(h -> "application/pdf".equals(h.getContentType())));
    }

    @Test
    @DisplayName("copyFromUrl defaults blank content-type")
    void copyFromUrl_defaultsContentType() {
        String src = "https://source/blob?sv=...";
        String path = "f.bin";
        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("u");

        service.copyFromUrl(src, path, null);

        verify(blob).setHttpHeaders(argThat((BlobHttpHeaders h) ->
                "application/octet-stream".equals(h.getContentType())));
    }

    @Test
    @DisplayName("upload still works for streams")
    void upload_setsHeaders() {
        String path = "x/y.pdf";
        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.getBlobUrl()).thenReturn("u");

        service.upload(path, new ByteArrayInputStream("pdf".getBytes()), 3, "application/pdf");

        verify(blob).upload(any(), eq(3L), eq(true));
        verify(blob).setHttpHeaders(argThat(h -> "application/pdf".equals(h.getContentType())));
    }

    @Test
    @DisplayName("exists delegates")
    void exists_delegates() {
        String path = "z";
        when(container.getBlobClient(path)).thenReturn(blob);
        when(blob.exists()).thenReturn(true);
        assertThat(service.exists(path)).isTrue();
    }
}
