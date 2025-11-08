package uk.gov.hmcts.cp.cdk.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

public class DocumentServiceTest {


    @Test
    @DisplayName("getMaterialContentUrl returns valid URI when materialId and URL exist")
    void getMaterialContentUrl_success() {

        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID docId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID materialId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        String userId = "u-123";
        String urlStr = "https://example.com/download/" + materialId;

        CaseDocument doc = new CaseDocument();
        doc.setMaterialId(materialId);
        when(docRepo.findById(docId)).thenReturn(Optional.of(doc));
        when(progressionClient.getMaterialDownloadUrl(materialId, userId))
                .thenReturn(Optional.of(urlStr));

        DocumentService service = new DocumentService(docRepo, progressionClient);

        URI result = service.getMaterialContentUrl(docId, userId);


        assertThat(result.toString()).isEqualTo(urlStr);
        verify(docRepo).findById(docId);
        verify(progressionClient).getMaterialDownloadUrl(materialId, userId);
    }

    @Test
    @DisplayName("getMaterialContentUrl throws 404 when materialId not found")
    void getMaterialContentUrl_no_materialId_404() {

        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID docId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        String userId = "u-123";

        when(docRepo.findById(docId)).thenReturn(Optional.empty());

        DocumentService service = new DocumentService(docRepo, progressionClient);


        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getMaterialContentUrl(docId, userId));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(ex.getReason()).contains("No materialId found for document ID");
    }

    @Test
    @DisplayName("getMaterialContentUrl throws 404 when returned URL is invalid")
    void getMaterialContentUrl_invalid_uri_404() {

        CaseDocumentRepository docRepo = mock(CaseDocumentRepository.class);
        ProgressionClient progressionClient = mock(ProgressionClient.class);

        UUID docId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID materialId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        String userId = "u-123";
        String invalidUrl = "ht!tp://bad-url"; // malformed URL

        CaseDocument doc = new CaseDocument();
        doc.setMaterialId(materialId);
        when(docRepo.findById(docId)).thenReturn(Optional.of(doc));
        when(progressionClient.getMaterialDownloadUrl(materialId, userId))
                .thenReturn(Optional.of(invalidUrl));

        DocumentService service = new DocumentService(docRepo, progressionClient);


        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getMaterialContentUrl(docId, userId));
        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(ex.getReason()).contains("Invalid URI returned for materialId");
    }
}
