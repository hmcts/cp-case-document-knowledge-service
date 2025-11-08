package uk.gov.hmcts.cp.cdk.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.cdk.services.QueryCatalogueService;
import uk.gov.hmcts.cp.openapi.api.cdk.QueryCatalogueApi;
import uk.gov.hmcts.cp.openapi.model.cdk.LabelUpdateRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.ListQueryCatalogue200Response;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryCatalogueItem;

import java.util.List;
import java.util.UUID;

/**
 * Query Catalogue API controller.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class QueryCatalogueController implements QueryCatalogueApi {

    private final QueryCatalogueService service;

    @Override
    public ResponseEntity<ListQueryCatalogue200Response> listQueryCatalogue() {
        log.debug("listQueryCatalogue");
        final List<QueryCatalogueItem> items = service.list();
        final ListQueryCatalogue200Response resp = new ListQueryCatalogue200Response();
        resp.setItems(items);
        return ResponseEntity.ok(resp);
    }

    @Override
    public ResponseEntity<QueryCatalogueItem> getQueryCatalogueItem(final UUID queryId) {
        log.debug("getQueryCatalogueItem queryId={}", queryId);
        return ResponseEntity.ok(service.get(queryId));
    }

    @Override
    public ResponseEntity<QueryCatalogueItem> setQueryCatalogueLabel(
            final UUID queryId,
            final LabelUpdateRequest labelUpdateRequest
    ) {
        log.debug("setQueryCatalogueLabel queryId={}, label={}", queryId,
                labelUpdateRequest != null ? labelUpdateRequest.getLabel() : null);
        return ResponseEntity.ok(service.updateLabel(queryId, labelUpdateRequest));
    }
}
