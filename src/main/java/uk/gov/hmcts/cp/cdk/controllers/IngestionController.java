package uk.gov.hmcts.cp.cdk.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.cdk.services.IngestionService;
import uk.gov.hmcts.cp.openapi.api.cdk.IngestionApi;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;

import java.util.UUID;


@RestController
public class IngestionController implements IngestionApi {

    private final IngestionService service;

    public IngestionController(final IngestionService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<IngestionStatusResponse> getIngestionStatus(final UUID caseId) {
        return ResponseEntity.ok(service.getStatus(caseId));
    }
}

