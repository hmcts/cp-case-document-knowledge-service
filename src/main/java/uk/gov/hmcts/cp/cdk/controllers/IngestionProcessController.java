// src/main/java/uk/gov/hmcts/cp/cdk/controllers/IngestionProcessApiController.java
package uk.gov.hmcts.cp.cdk.controllers;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.services.IngestionProcessService;
import uk.gov.hmcts.cp.openapi.api.cdk.IngestionProcessApi;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.batch.core.job.parameters.JobParametersInvalidException;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;

@RestController
public class IngestionProcessController implements IngestionProcessApi {

    static final public MediaType VND_INGESTION =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process+json");

    private final IngestionProcessService service;

    public IngestionProcessController(final IngestionProcessService service) {
        this.service = Objects.requireNonNull(service);
    }

    @Override
    public ResponseEntity<IngestionProcessResponse> startIngestionProcess(
            @RequestBody @Valid final IngestionProcessRequest ingestionProcessRequest) {
        try {
            final IngestionProcessResponse resp = service.startIngestionProcess(ingestionProcessRequest);
            return ResponseEntity.ok().contentType(VND_INGESTION).body(resp);
        } catch (NoSuchJobException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (JobParametersInvalidException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (JobExecutionAlreadyRunningException | JobInstanceAlreadyCompleteException | JobRestartException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unexpected error starting ingestion process.", e);
        }
    }
}
