package uk.gov.hmcts.cp.cdk.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.cp.cdk.batch.clients.common.CQRSClientProperties;
import uk.gov.hmcts.cp.cdk.services.QueryService;
import uk.gov.hmcts.cp.openapi.api.cdk.QueriesApi;
import uk.gov.hmcts.cp.openapi.model.cdk.*;

import java.net.URI;
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
    private final CQRSClientProperties cqrsClientProperties;

    public QueriesController(final QueryService service,final CQRSClientProperties cqrsClientProperties) {
                this.service = service;
                this.cqrsClientProperties =cqrsClientProperties;
    }

    public ResponseEntity<QueryStatusResponse> listQueriesNoCase(
            @RequestParam(value = "at", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final OffsetDateTime asOf
    ) {
        return listQueries(null, asOf);
    }


    @Override
    public ResponseEntity<QueryStatusResponse> listQueries(
            final UUID caseId,
            final OffsetDateTime asOf
    ) {

        try {
            final String headerName = cqrsClientProperties.headers().cjsCppuid();

            final ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attrs == null) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "No request context available with cppuid");
            }

            final HttpServletRequest req = attrs.getRequest();
            final String cppuid = req.getHeader(headerName);

            if (cppuid == null || cppuid.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Missing required header: " + headerName);
            }
        final QueryStatusResponse body = service.listForCaseAsOf(caseId, asOf,cppuid);
        return ResponseEntity.ok(body);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error accepting ingestion request.", e);
        }
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
    @Override
    public ResponseEntity<GetMaterialContentUrl200Response> getMaterialContentUrl(final UUID docId) {

        try {
            final String headerName = cqrsClientProperties.headers().cjsCppuid();

            final ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attrs == null) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "No request context available with cppuid");
            }

            final HttpServletRequest req = attrs.getRequest();
            final String cppuid = req.getHeader(headerName);

            if (cppuid == null || cppuid.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Missing required header: " + headerName);
            }
            final URI responseUrl = service.getMaterialContentUrl(docId, cppuid);

            final GetMaterialContentUrl200Response payload = new GetMaterialContentUrl200Response();
            payload.setUrl(responseUrl);
            return ResponseEntity.ok(payload);
        }
        catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error while getting download url", e);
        }
    }

}
