package uk.gov.hmcts.cp.cdk.clients.rag;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;

import java.nio.charset.StandardCharsets;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Slf4j
@RequiredArgsConstructor
@ConditionalOnMissingBean(ApimDocumentIngestionStatusClient.class)
public class ApimDocumentIngestionStatusClient implements DocumentIngestionStatusApi {

    private final RestClient restClient;
    private final RagClientProperties ragClientProperties;
    private final ApimAuthHeaderService apimAuthHeaderService;

    @Override
    public ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> documentStatus(final String documentName) {
        try {
            ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> response = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(PATH_DOCUMENT_STATUS)
                            .queryParam("document-name", documentName)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> {
                        apimAuthHeaderService.applyCommonHeaders(httpHeaders, ragClientProperties.getHeaders());
                        apimAuthHeaderService.applyAuthHeaders(httpHeaders, ragClientProperties);
                    })
                    .retrieve()
                    .toEntity(DocumentIngestionStatusReturnedSuccessfully.class);
            if (response.getBody() != null) {
                log.info(
                        "APIM document-status success: name='{}' status={} body={}",
                        documentName,
                        response.getStatusCode(),
                        response.getBody()
                );
            } else {
                log.info(
                        "APIM document-status success: name='{}' status={} (empty body)",
                        documentName,
                        response.getStatusCode()
                );
            }

            return response;

        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.info("Document not found in APIM. name='{}' body={}",
                        documentName, exception.getResponseBodyAsString(StandardCharsets.UTF_8));
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            String body = exception.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.warn("APIM error on document-status: {} {} - {}", exception.getStatusCode(), exception.getStatusText(), body, exception);
            throw exception;
        }
    }
}
