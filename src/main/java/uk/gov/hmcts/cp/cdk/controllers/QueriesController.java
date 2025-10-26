package uk.gov.hmcts.cp.cdk.controllers;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.cdk.services.QueryService;
import uk.gov.hmcts.cp.openapi.api.cdk.QueriesApi;
import uk.gov.hmcts.cp.openapi.model.cdk.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Queries API implementation (Spring Boot 4 / Java 21).
 * - Implements contract-defined endpoints from {@link QueriesApi}.
 * - Adds a convenience GET /queries handler for when the "caseId" query param is absent.
 */
@RestController
public class QueriesController implements QueriesApi {

    private final QueryService service;

    public QueriesController(final QueryService service) {
        this.service = service;
    }

    /**
     * Convenience endpoint: allow GET /queries without "caseId" (treat as null).
     * Disambiguated from the contract mapping via {@code params="!caseId"}.
     */
    //@GetMapping(value = "/queries", produces = MediaType.APPLICATION_JSON_VALUE, params = "!caseId")
    public ResponseEntity<QueryStatusResponse> listQueriesNoCase(
            @RequestParam(value = "at", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final OffsetDateTime asOf
    ) {
        return listQueries(null, asOf);
    }

    // ========== Contract endpoints (from QueriesApi) ==========

    @Override
    public ResponseEntity<QueryStatusResponse> listQueries(
            final UUID caseId,
            final OffsetDateTime asOf
    ) {
        final QueryStatusResponse body = service.listForCaseAsOf(caseId, asOf);
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<QueryDefinitionsResponse> upsertQueries(final QueryUpsertRequest queryUpsertRequest) {
        final QueryDefinitionsResponse body = service.upsertDefinitions(queryUpsertRequest);
        return ResponseEntity.accepted().body(body);
    }

    @Override
    public ResponseEntity<QuerySummary> getQuery(
            final UUID queryId,
            final UUID caseId,
            final OffsetDateTime asOf
    ) {
        final QuerySummary body = service.getOneForCaseAsOf(caseId, queryId, asOf);
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<ListQueryVersions200Response> listQueryVersions(final UUID queryId) {
        final List<QueryVersionSummary> versions = service.listVersions(queryId);
        final ListQueryVersions200Response payload = new ListQueryVersions200Response();
        payload.setQueryId(queryId);
        payload.setVersions(versions);
        return ResponseEntity.ok(payload);
    }
}
