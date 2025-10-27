package uk.gov.hmcts.cp.cdk.controllers;

import jakarta.validation.Valid;
import org.springframework.batch.core.job.parameters.JobParametersInvalidException;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.services.IngestionService;
import uk.gov.hmcts.cp.openapi.api.cdk.IngestionApi;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionProcessResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.IngestionStatusResponse;

import java.util.UUID;


@RestController
public class IngestionController implements IngestionApi {

    private final IngestionService service;

    static final public MediaType VND_INGESTION =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process+json");

    public IngestionController(final IngestionService service) {
        this.service = service;

    }

    @Override
    public ResponseEntity<IngestionStatusResponse> getIngestionStatus(final UUID caseId) {
        return ResponseEntity.ok(service.getStatus(caseId));
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

