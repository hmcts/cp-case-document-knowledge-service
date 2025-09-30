package uk.gov.hmcts.cp.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.openapi.api.QueriesApi;
import uk.gov.hmcts.cp.openapi.model.QueryStatusResponse;
import uk.gov.hmcts.cp.openapi.model.QueryUpsertRequest;
import uk.gov.hmcts.cp.services.QueryReadService;
import uk.gov.hmcts.cp.services.QueryWriteService;

import java.time.OffsetDateTime;

@RestController
@Validated
public class QueriesApiController implements QueriesApi {

    private final QueryReadService readService;
    private final QueryWriteService writeService;

    public QueriesApiController(QueryReadService readService, QueryWriteService writeService) {
        this.readService = readService;
        this.writeService = writeService;
    }

    @Override
    public ResponseEntity<QueryStatusResponse> listQueries(OffsetDateTime at) {
        return ResponseEntity.ok(readService.listQueries(at != null ? at.toInstant() : null));
    }

    @Override
    public ResponseEntity<QueryStatusResponse> upsertQueries(QueryUpsertRequest body) {
        // persist and return 202 with the as-of snapshot echo
        QueryStatusResponse resp = writeService.upsertQueries(body);
        return ResponseEntity.accepted().body(resp);
    }
}
