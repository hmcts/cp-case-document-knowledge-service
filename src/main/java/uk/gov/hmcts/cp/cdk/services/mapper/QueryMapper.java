package uk.gov.hmcts.cp.cdk.services.mapper;

import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryCatalogueItem;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryVersionSummary;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface QueryMapper {

    default QueryCatalogueItem toCatalogueItem(final Query query) {
        final QueryCatalogueItem item = new QueryCatalogueItem();
        item.setQueryId(query.getQueryId());
        item.setLabel(query.getLabel());
        item.setOrder(query.getOrder());
        return item;
    }

    default QueryVersionSummary toVersionSummary(final Query query, final QueryVersion queryVersion) {
        final QueryVersionSummary summary = new QueryVersionSummary();
        summary.setQueryId(query.getQueryId());
        summary.setLabel(query.getLabel());
        summary.setUserQuery(queryVersion.getUserQuery());
        summary.setQueryPrompt(queryVersion.getQueryPrompt());
        summary.setEffectiveAt(queryVersion.getQueryVersionId().getEffectiveAt());
        return summary;
    }
}
