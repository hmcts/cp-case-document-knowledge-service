package uk.gov.hmcts.cp.cdk.services.mapper;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.domain.QueryVersionId;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryCatalogueItem;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryVersionSummary;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class QueryMapperTest {
    private final QueryMapper mapper = new QueryMapper() {
    };

    @Test
    void testToAnswerResponse() {
        // Given
        final String queryLabel = "query1";
        final UUID queryId = randomUUID();
        final Query query = new Query(queryId, queryLabel, utcNow(), 2);

        // When
        final QueryCatalogueItem response = mapper.toCatalogueItem(query);

        // Then
        assertThat(response.getQueryId()).isEqualTo(queryId);
        assertThat(response.getLabel()).isEqualTo(queryLabel);
        assertThat(response.getOrder()).isEqualTo(2);
    }

    @Test
    void testToAnswerWithLlm() {
        // Given
        final String queryLabel = "query1";
        final String userQuery = "user query";
        final String queryPrompt = "query prompt";
        final UUID queryId = randomUUID();
        final Query query = new Query(queryId, queryLabel, utcNow(), 2);
        final QueryVersionId queryVersionId = new QueryVersionId(queryId, utcNow());
        final QueryVersion queryVersion = new QueryVersion(queryVersionId, query, userQuery, queryPrompt);

        // When
        final QueryVersionSummary response = mapper.toVersionSummary(query, queryVersion);

        // Then
        assertThat(response.getQueryId()).isEqualTo(queryId);
        assertThat(response.getQueryPrompt()).isEqualTo(queryPrompt);
        assertThat(response.getLabel()).isEqualTo(queryLabel);
        assertThat(response.getUserQuery()).isEqualTo(userQuery);
        assertThat(response.getEffectiveAt()).isEqualTo(queryVersionId.getEffectiveAt());
    }
}