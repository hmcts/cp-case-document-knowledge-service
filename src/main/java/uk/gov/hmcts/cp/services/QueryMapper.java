package uk.gov.hmcts.cp.services;

import uk.gov.hmcts.cp.domain.QueryVersionEntity;
import uk.gov.hmcts.cp.openapi.model.QuerySummary;

import static uk.gov.hmcts.cp.openapi.model.IngestionStatus.fromValue;

/**
 * Small stateless mapper between domain entity and generated OpenAPI models.
 */
public final class QueryMapper {

    private QueryMapper() { /* no instances */ }

    public static QuerySummary toSummary(QueryVersionEntity e) {
        if (e == null) {
            return null;
        }

        var summary = new QuerySummary()
                .queryId(e.getQueryId())
                .userQuery(e.getUserQuery())
                .queryPrompt(e.getQueryPrompt());

        if (e.getStatus() != null) {
            summary.status(fromValue(e.getStatus().name()));
        }

        return summary;
    }
}
