package uk.gov.hmcts.cp.cdk.controllers;

import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.services.DocumentService;
import uk.gov.hmcts.cp.cdk.util.RequestUtils;
import uk.gov.hmcts.cp.openapi.api.cdk.DocumentApi;
import uk.gov.hmcts.cp.openapi.model.cdk.GetMaterialContentUrl200Response;

import java.net.URI;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Document API controller.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DocumentController implements DocumentApi {

    private final DocumentService service;
    private final CQRSClientProperties cqrsClientProperties;

    @Override
    public ResponseEntity<GetMaterialContentUrl200Response> getMaterialContentUrl(final UUID docId) throws ResponseStatusException {
        final String headerName = cqrsClientProperties.headers().cjsCppuid();
        final String cppuid = RequestUtils.requireHeader(headerName);

        log.debug("getMaterialContentUrl docId={}, {}={}", docId, headerName, cppuid);

        final URI responseUrl = service.getMaterialContentUrl(docId, cppuid);
        final GetMaterialContentUrl200Response payload = new GetMaterialContentUrl200Response();
        payload.setUrl(responseUrl);

        return ResponseEntity.ok(payload);
    }
}
