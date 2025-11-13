package uk.gov.hmcts.cp.cdk.services;

import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.repo.QueryRepository;
import uk.gov.hmcts.cp.cdk.services.mapper.QueryMapper;
import uk.gov.hmcts.cp.openapi.model.cdk.LabelUpdateRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryCatalogueItem;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class QueryCatalogueService {

    private final QueryRepository queryRepository;
    private final QueryMapper mapper;

    public QueryCatalogueService(final QueryRepository queryRepository, final QueryMapper mapper) {
        this.queryRepository = queryRepository;
        this.mapper = mapper;
    }

    public List<QueryCatalogueItem> list() {
        return queryRepository.findAll()
                .stream()
                .map(mapper::toCatalogueItem)
                .toList();
    }

    public QueryCatalogueItem get(final UUID queryId) {
        final Query query = queryRepository.findById(queryId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Query not found: " + queryId));
        return mapper.toCatalogueItem(query);
    }

    /**
     * Idempotent: create the Query if it does not exist, else update its label.
     * Persists FIRST (saveAndFlush) then maps to DTO.
     */
    @Transactional
    public QueryCatalogueItem updateLabel(final UUID queryId, final LabelUpdateRequest body) {
        if (body == null || body.getLabel() == null || body.getLabel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label must not be blank");
        }

        final String newLabel = body.getLabel().trim();
        final Integer newOrder = body.getOrder();
        Query query = queryRepository.findById(queryId).orElseGet(() -> {
            final Query created = new Query();
            created.setQueryId(queryId);
            created.setDisplayOrder(newOrder);
            return created;
        });

        query.setLabel(newLabel);
        query.setDisplayOrder(newOrder);
        query = queryRepository.saveAndFlush(query);

        return mapper.toCatalogueItem(query);
    }
}
