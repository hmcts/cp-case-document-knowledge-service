package uk.gov.hmcts.cp.cdk.batch.clients.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.cdk.batch.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * RestClient-backed proxy that IMPLEMENTS the OpenAPI interface and forwards requests to APIM.
 */
@Slf4j
@RequiredArgsConstructor
@ConditionalOnMissingBean(ApimDocumentIngestionStatusClient.class)
public class ApimDocumentIngestionStatusClient implements DocumentIngestionStatusApi {

    private final RestClient restClient;           // should be preconfigured with baseUrl, timeouts, etc.
    private final RagClientProperties props;

    @Override
    public ResponseEntity<DocumentIngestionStatusReturnedSuccessfully> documentStatus(String documentName) {
        try {
            return restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(DocumentIngestionStatusApi.PATH_DOCUMENT_STATUS)
                            .queryParam("document-name", documentName)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        final Map<String, String> hdrs = props.getHeaders();
                        if (hdrs != null) hdrs.forEach(h::add);
                    })
                    .retrieve()
                    .toEntity(DocumentIngestionStatusReturnedSuccessfully.class);

        } catch (HttpStatusCodeException ex) {
            // Gracefully map 404 to the OpenAPI's 404 response (empty body is fine for the tasklet).
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.info("Document not found in APIM for name='{}' (404). Body={}",
                        documentName, ex.getResponseBodyAsString(StandardCharsets.UTF_8));
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            String body = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.warn("APIM error on document-status: {} {} - {}", ex.getStatusCode(), ex.getStatusText(), body, ex);
            throw ex;
        }
    }
}
