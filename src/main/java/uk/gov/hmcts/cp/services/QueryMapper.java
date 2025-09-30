package uk.gov.hmcts.cp.services;

import uk.gov.hmcts.cp.domain.QueryVersionEntity;
import uk.gov.hmcts.cp.openapi.model.QuerySummary;

/**
 * Small stateless mapper between domain entity and generated OpenAPI models.
 */
public final class QueryMapper {

    private QueryMapper() { /* no instances */ }

    public static QuerySummary toSummary(final QueryVersionEntity entity) {
        final QuerySummary result;
        if (entity == null) {
            result = null;
        } else {
            final QuerySummary summary = new QuerySummary()
                    .queryId(entity.getQueryId())
                    .userQuery(entity.getUserQuery())
                    .queryPrompt(entity.getQueryPrompt());

            if (entity.getStatus() != null) {
                summary.status(uk.gov.hmcts.cp.openapi.model.IngestionStatus.fromValue(
                        entity.getStatus().name()
                ));
            }
            result = summary;
        }
        return result;
    }
}
