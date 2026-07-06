package uk.gov.hmcts.cp.cdk.controllers;

import uk.gov.hmcts.cp.cdk.services.DiscoverySchedulerConfigurationService;
import uk.gov.hmcts.cp.openapi.api.cdk.DiscoverySchedulerApi;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoverySchedulerConfigurationRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.UpsertDiscoverySchedulerConfiguration200Response;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DiscoverySchedulerController implements DiscoverySchedulerApi {

    private final DiscoverySchedulerConfigurationService service;

    @Override
    public ResponseEntity<UpsertDiscoverySchedulerConfiguration200Response> upsertDiscoverySchedulerConfiguration(
            @RequestBody @Valid final DiscoverySchedulerConfigurationRequest discoverySchedulerConfigurationRequest) {
        log.debug("upsertDiscoverySchedulerConfiguration courtCentreId={} courtRoomId={} version={}",
                discoverySchedulerConfigurationRequest.getCourtCentreId(),
                discoverySchedulerConfigurationRequest.getCourtRoomId(),
                discoverySchedulerConfigurationRequest.getVersion());
        return ResponseEntity.ok(service.upsert(discoverySchedulerConfigurationRequest));
    }
}
