package uk.gov.hmcts.cp.cdk.batch.partition;


import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;

import uk.gov.hmcts.cp.cdk.batch.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Creates one partition per query so we can run GenerateAnswersTasklet once per query.
 * It copies caseId/docId from the current per-case step context and adds CTX_SINGLE_QUERY_ID.
 */
@Component
@StepScope
public class QueryIdPartitioner implements Partitioner {

    public static final String CTX_SINGLE_QUERY_ID = "CTX_SINGLE_QUERY_ID";

    private final QueryResolver queryResolver;

    @Value("#{stepExecutionContext['" + CTX_CASE_ID_KEY + "']}")
    private String caseIdStr;

    @Value("#{stepExecutionContext['" + CTX_DOC_ID_KEY + "']}")
    private String docIdStr;

    public QueryIdPartitioner(final QueryResolver queryResolver) {
        this.queryResolver = queryResolver;
    }

    @Override
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    public Map<String, ExecutionContext> partition(final int gridSize) {
        final Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        final List<Query> queries = queryResolver.resolve();

        int partitionIndex = 0;
        for (final Query query : queries) {
            final UUID queryId = query.getQueryId();
            if (queryId == null) {
                continue;
            }

            final ExecutionContext context = new ExecutionContext();

            if (caseIdStr != null) {
                context.putString(CTX_CASE_ID_KEY, caseIdStr);
            }
            if (docIdStr != null) {
                context.putString(CTX_DOC_ID_KEY, docIdStr);
            }
            context.putString(CTX_SINGLE_QUERY_ID, queryId.toString());

            partitions.put(docIdStr + "-query-" + partitionIndex, context);
            partitionIndex++;
        }
        return partitions;
    }
}
