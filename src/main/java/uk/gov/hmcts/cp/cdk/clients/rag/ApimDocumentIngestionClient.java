package uk.gov.hmcts.cp.cdk.clients.rag;

import static uk.gov.hmcts.cp.openapi.api.DocumentIngestionStatusApi.PATH_DOCUMENT_STATUS_BY_REFERENCE;

import uk.gov.hmcts.cp.cdk.clients.common.ApimAuthHeaderService;
import uk.gov.hmcts.cp.cdk.clients.common.RagClientProperties;
import uk.gov.hmcts.cp.openapi.api.DocumentIngestionInitiationApi;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Slf4j
@RequiredArgsConstructor
@ConditionalOnMissingBean(ApimDocumentIngestionClient.class)
public class ApimDocumentIngestionClient implements DocumentIngestionInitiationApi {

    private final RestClient restClient;
    private final RagClientProperties ragClientProperties;
    private final ApimAuthHeaderService apimAuthHeaderService;

    @Override
    public ResponseEntity<@NotNull FileStorageLocationReturnedSuccessfully> initiateDocumentUpload(final DocumentUploadRequest documentUploadRequest) {

        try {
            FileStorageLocationReturnedSuccessfully response = restClient
                    .post()
                    .uri(PATH_INITIATE_DOCUMENT_UPLOAD)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> {
                        apimAuthHeaderService.applyCommonHeaders(httpHeaders, ragClientProperties.getHeaders());
                        apimAuthHeaderService.applyAuthHeaders(httpHeaders, ragClientProperties);
                    })
                    .body(documentUploadRequest)
                    .retrieve()
                    .body(FileStorageLocationReturnedSuccessfully.class);


            if (response == null) {
                response = new FileStorageLocationReturnedSuccessfully();
            }

            log.info("APIM document-status: initiate document upload completed successfully");

            return ResponseEntity.ok(response);

        } catch (final HttpStatusCodeException exception) {
            final String responseBody = Optional.of(exception.getResponseBodyAsString(StandardCharsets.UTF_8)).orElse("");
            final String message = "APIM API error: %d %s - %s".formatted(exception.getStatusCode().value(), exception.getStatusText(), responseBody);
            log.warn(message);
            throw new RagClientException(message, exception);

        } catch (final Exception exception) {
            final String message = "Failed to call APIM API";
            log.error(message, exception);
            throw new RagClientException(message, exception);
        }
    }
}
