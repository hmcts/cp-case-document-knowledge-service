package uk.gov.hmcts.cp.cdk.controllers;

import uk.gov.hmcts.cp.cdk.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.services.QueryService;
import uk.gov.hmcts.cp.cdk.util.RequestUtils;
import uk.gov.hmcts.cp.openapi.api.cdk.QueriesApi;
import uk.gov.hmcts.cp.openapi.model.cdk.ListQueryVersions200Response;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryDefinitionsResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryStatusResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.QuerySummary;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryUpsertRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryVersionSummary;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Queries API controller.
 * Centralises header extraction and avoids repetitive try/catch blocks.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class QueriesController implements QueriesApi {

    private final QueryService service;
    private final CQRSClientProperties cqrsClientProperties;

    /**
     * Convenience endpoint that calls listQueries with no caseId.
     */
    public ResponseEntity<QueryStatusResponse> listQueriesNoCase(
            @RequestParam(value = "at", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final OffsetDateTime asOf
    ) {
        return listQueries(null, asOf);
    }

    @Override
    public ResponseEntity<QueryStatusResponse> listQueries(
            final UUID caseId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final OffsetDateTime asOf
    ) {
        final String headerName = cqrsClientProperties.headers().cjsCppuid();
        final String cppuid = RequestUtils.requireHeader(headerName);

        log.debug("listQueries caseId={}, asOf={}, {}={}", caseId, asOf, headerName, cppuid);
        final QueryStatusResponse body = service.listForCaseAsOf(caseId, asOf, cppuid);
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<QueryDefinitionsResponse> upsertQueries(final QueryUpsertRequest request) {
        if (request == null || request.getQueries() == null || request.getQueries().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "definitions must not be empty");
        }
        log.debug("upsertQueries definitions={}", request.getQueries().size());
        final QueryDefinitionsResponse body = service.upsertDefinitions(request);
        return ResponseEntity.accepted().body(body);
    }

    @Override
    public ResponseEntity<QuerySummary> getQuery(
            final UUID queryId,
            final UUID caseId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final OffsetDateTime asOf
    ) {
        log.debug("getQuery queryId={}, caseId={}, asOf={}", queryId, caseId, asOf);
        final QuerySummary body = service.getOneForCaseAsOf(caseId, queryId, asOf);
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<ListQueryVersions200Response> listQueryVersions(final UUID queryId) {
        log.debug("listQueryVersions queryId={}", queryId);
        final List<QueryVersionSummary> versions = service.listVersions(queryId);
        final ListQueryVersions200Response payload = new ListQueryVersions200Response();
        payload.setQueryId(queryId);
        payload.setVersions(versions);
        return ResponseEntity.ok(payload);
    }
}
