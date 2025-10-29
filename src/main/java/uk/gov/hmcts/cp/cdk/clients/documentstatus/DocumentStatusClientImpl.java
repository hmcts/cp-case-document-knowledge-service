package uk.gov.hmcts.cp.cdk.clients.documentstatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Optional;

@Component
public class DocumentStatusClientImpl implements DocumentIngestionStatusApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentStatusClientImpl.class);

    private final RestClient restClient;
    private final String statusPath;

    public DocumentStatusClientImpl(final DocumentStatusClientConfig config) {
        this.statusPath = config.statusPath();
        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .build();
    }

    @Override
    public Optional<DocumentStatusResponse> checkDocumentStatus(final String documentName) {
        if (documentName == null || documentName.isBlank()) {
            LOGGER.warn("Document name is null or blank, cannot check status");
            return Optional.empty();
        }

        try {
            final URI uri = UriComponentsBuilder.fromPath(statusPath)
                    .queryParam("document-name", documentName)
                    .build()
                    .toUri();

            LOGGER.debug("Checking document status for: {}", documentName);

            final DocumentStatusResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(DocumentStatusResponse.class);

            if (response != null) {
                LOGGER.debug("Document status found for {}: documentId={}, status={}, timestamp={}", 
                        documentName, response.documentId(), response.status(), response.timestamp());
                return Optional.of(response);
            }

            return Optional.empty();

        } catch (HttpClientErrorException.NotFound e) {
            LOGGER.debug("Document not found: {}", documentName);
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            final String message = String.format("Client error checking document status for %s: %s", 
                    documentName, e.getStatusCode());
            LOGGER.error(message, e);
            throw new DocumentStatusCheckException(message, e);
        } catch (HttpServerErrorException e) {
            final String message = String.format("Server error checking document status for %s: %s", 
                    documentName, e.getStatusCode());
            LOGGER.error(message, e);
            throw new DocumentStatusCheckException(message, e);
        } catch (Exception e) {
            final String message = String.format("Unexpected error checking document status for %s", documentName);
            LOGGER.error(message, e);
            throw new DocumentStatusCheckException(message, e);
        }
    }
}

