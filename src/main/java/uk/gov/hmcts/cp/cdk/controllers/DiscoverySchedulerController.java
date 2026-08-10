package uk.gov.hmcts.cp.cdk.controllers;

import uk.gov.hmcts.cp.cdk.services.DiscoverySchedulerConfigurationService;
import uk.gov.hmcts.cp.cdk.services.DiscoveryTriggerService;
import uk.gov.hmcts.cp.openapi.api.cdk.DiscoverySchedulerApi;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoveryOperation;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoverySchedulerConfigurationRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoveryTriggerRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoveryTriggerResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.UpsertDiscoverySchedulerConfiguration200Response;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DiscoverySchedulerController implements DiscoverySchedulerApi {

    private static final MediaType VND_DISCOVERY_SCHEDULER_TRIGGER = MediaType.valueOf(
            "application/vnd.casedocumentknowledge-service.discovery-scheduler-trigger+json");

    private final DiscoverySchedulerConfigurationService service;
    private final DiscoveryTriggerService discoveryTriggerService;

    @Override
    public ResponseEntity<UpsertDiscoverySchedulerConfiguration200Response> upsertDiscoverySchedulerConfiguration(
            @RequestBody @Valid final DiscoverySchedulerConfigurationRequest discoverySchedulerConfigurationRequest) {
        log.debug("upsertDiscoverySchedulerConfiguration courtCentreId={} courtRoomId={} version={}",
                discoverySchedulerConfigurationRequest.getCourtCentreId(),
                discoverySchedulerConfigurationRequest.getCourtRoomId(),
                discoverySchedulerConfigurationRequest.getVersion());
        return ResponseEntity.ok(service.upsert(discoverySchedulerConfigurationRequest));
    }

    @Override
    public ResponseEntity<DiscoveryTriggerResponse> triggerDiscovery(
            @RequestBody @Valid final DiscoveryTriggerRequest discoveryTriggerRequest) {
        final DiscoveryOperation operation = discoveryTriggerRequest.getDiscoveryOperation();
        log.debug("Discovery trigger accepted discoveryOperation={}", operation);
        discoveryTriggerService.trigger(operation);

        final DiscoveryTriggerResponse response = new DiscoveryTriggerResponse()
                .discoveryOperation(operation)
                .message("Discovery run dispatched.")
                .correlationId(MDC.get("correlationId"));

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .contentType(VND_DISCOVERY_SCHEDULER_TRIGGER)
                .body(response);
    }
}
