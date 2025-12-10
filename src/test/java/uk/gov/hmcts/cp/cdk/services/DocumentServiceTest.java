package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
public class DocumentServiceTest {

    @Mock
    private CaseDocumentRepository docRepo;
    @Mock
    private ProgressionClient progressionClient;
    @InjectMocks
    private DocumentService service;

    @Test
    @DisplayName("getMaterialContentUrl returns valid URI when materialId and URL exist")
    void getMaterialContentUrl_success() {
        final UUID docId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID materialId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final String userId = "u-123";
        final String urlStr = "https://example.com/download/" + materialId;
        final CaseDocument doc = new CaseDocument();
        doc.setMaterialId(materialId);

        when(docRepo.findById(docId)).thenReturn(Optional.of(doc));
        when(progressionClient.getMaterialDownloadUrl(materialId, userId))
                .thenReturn(Optional.of(urlStr));

        final URI result = service.getMaterialContentUrl(docId, userId);

        assertThat(result.toString()).isEqualTo(urlStr);
        verify(docRepo).findById(docId);
        verify(progressionClient).getMaterialDownloadUrl(materialId, userId);
    }

    @Test
    @DisplayName("getMaterialContentUrl throws 404 when materialId not found")
    void getMaterialContentUrl_no_materialId_404() {
        final UUID docId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final String userId = "u-123";
        when(docRepo.findById(docId)).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getMaterialContentUrl(docId, userId));

        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(ex.getReason()).contains("No materialId found for document ID");
    }

    @Test
    @DisplayName("getMaterialContentUrl throws 404 when returned URL is invalid")
    void getMaterialContentUrl_invalid_uri_404() {
        final UUID docId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID materialId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        final String userId = "u-123";
        final String invalidUrl = "ht!tp://bad-url"; // malformed URL
        final CaseDocument doc = new CaseDocument();
        doc.setMaterialId(materialId);

        when(docRepo.findById(docId)).thenReturn(Optional.of(doc));
        when(progressionClient.getMaterialDownloadUrl(materialId, userId))
                .thenReturn(Optional.of(invalidUrl));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getMaterialContentUrl(docId, userId));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(ex.getReason()).contains("Invalid URI returned for materialId");
    }
}
