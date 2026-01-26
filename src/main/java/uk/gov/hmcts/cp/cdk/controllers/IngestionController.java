package uk.gov.hmcts.cp.cdk.controllers;

import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.services.IngestionProcessor;
import uk.gov.hmcts.cp.cdk.services.IngestionService;
import uk.gov.hmcts.cp.cdk.util.RequestUtils;
import uk.gov.hmcts.cp.openapi.api.cdk.IngestionApi;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;

import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion API controller.
 * Accepts ingestion requests and surfaces status.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class IngestionController implements IngestionApi {

    public static final MediaType VND_INGESTION =
            new MediaType("application", "vnd.casedocumentknowledge-service.ingestion-process+json");

    private final IngestionService service;
    private final IngestionProcessor ingestionProcessor;
    private final CQRSClientProperties cqrsClientProperties;


    @Override
    public ResponseEntity<IngestionStatusResponse> getIngestionStatus(final UUID caseId) {
        log.debug("getIngestionStatus caseId={}", caseId);
        final IngestionStatusResponse status = service.getStatus(caseId);
        return ResponseEntity.ok(status);
    }

    @Override
    public ResponseEntity<IngestionProcessResponse> startIngestionProcess(
            @RequestBody @Valid final IngestionProcessRequest ingestionProcessRequest
    ) {
        final String headerName = cqrsClientProperties.headers().cjsCppuid();
        final String cppuid = RequestUtils.requireHeader(headerName);

        IngestionProcessResponse resp = ingestionProcessor.startIngestionProcess(cppuid, ingestionProcessRequest);

        return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(VND_INGESTION).body(resp);
    }
}
