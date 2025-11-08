package uk.gov.hmcts.cp.cdk.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class DocumentService {

    private final CaseDocumentRepository caseDocumentRepository;
    private final ProgressionClient progressionClient;

    public DocumentService(

            final CaseDocumentRepository caseDocumentRepository,
            final ProgressionClient progressionClient
    ) {

        this.caseDocumentRepository = caseDocumentRepository;
        this.progressionClient =progressionClient;
    }

    @Transactional(readOnly = true)
    public URI getMaterialContentUrl(final UUID docId, final String userId) {

        final UUID materialId = caseDocumentRepository.findById(docId)
                .map(CaseDocument::getMaterialId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No materialId found for document ID: " + docId));

        final Optional<String> optUrl = progressionClient.getMaterialDownloadUrl(materialId, userId);

        final String url = optUrl.orElseThrow(() -> new  ResponseStatusException(HttpStatus.NOT_FOUND,
                "No download URL returned for materialId: " + materialId));

        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Invalid URI returned for materialId: " + materialId + " -> " + url, e);
        }
    }
}
