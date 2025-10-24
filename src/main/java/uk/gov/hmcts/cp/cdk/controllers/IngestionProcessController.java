package uk.gov.hmcts.cp.cdk.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.cdk.services.IngestionProcessService;
import uk.gov.hmcts.cp.cdk.services.IngestionService;
import uk.gov.hmcts.cp.openapi.api.cdk.IngestionProcessApi;

import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessPhase;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;

import java.util.UUID;

@RestController
public class IngestionProcessController  implements IngestionProcessApi {

    private final IngestionProcessService service;

    public IngestionProcessController(final IngestionProcessService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<IngestionProcessResponse> startIngestionProcess(final IngestionProcessRequest request) {

        return ResponseEntity.ok(service.getIngestionStatus(request));
    }


}