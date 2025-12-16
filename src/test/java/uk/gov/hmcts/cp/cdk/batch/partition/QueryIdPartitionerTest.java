package uk.gov.hmcts.cp.cdk.batch.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.batch.partition.QueryIdPartitioner.CTX_SINGLE_QUERY_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;

import uk.gov.hmcts.cp.cdk.batch.support.QueryResolver;
import uk.gov.hmcts.cp.cdk.domain.Query;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.ExecutionContext;

@ExtendWith(MockitoExtension.class)
class QueryIdPartitionerTest {

    @Mock
    private QueryResolver queryResolver;

    @Mock
    private Query query1;

    @Mock
    private Query query2;

    @InjectMocks
    private QueryIdPartitioner partitioner;

    @Test
    void partition_withNoQueries_returnsEmptyMap() {
        when(queryResolver.resolve()).thenReturn(List.of());

        final Map<String, ExecutionContext> result = partitioner.partition(1);

        assertThat(result).isEmpty();
    }

    @Test
    void partition_withNullQueryIds_skipsThem() {
        when(query1.getQueryId()).thenReturn(null);
        when(query2.getQueryId()).thenReturn(null);

        when(queryResolver.resolve()).thenReturn(List.of(query1, query2));

        final Map<String, ExecutionContext> result = partitioner.partition(1);

        assertThat(result).isEmpty();
    }

    @Test
    void partition_withValidQueryIds_createsExecutionContexts() throws Exception {
        final UUID id1 = UUID.randomUUID();
        when(query1.getQueryId()).thenReturn(id1);

        final UUID id2 = UUID.randomUUID();
        when(query2.getQueryId()).thenReturn(id2);

        when(queryResolver.resolve()).thenReturn(List.of(query1, query2));

        // simulate stepExecutionContext values
        setStepExecutionContextField("caseIdStr", "CASE-123");
        setStepExecutionContextField("docIdStr", "DOC-456");

        final Map<String, ExecutionContext> result = partitioner.partition(1);

        assertThat(result).hasSize(2);
        assertThat(result.get("DOC-456-query-0").getString(CTX_SINGLE_QUERY_ID)).isEqualTo(id1.toString());
        assertThat(result.get("DOC-456-query-0").getString(CTX_CASE_ID_KEY)).isEqualTo("CASE-123");
        assertThat(result.get("DOC-456-query-0").getString(CTX_DOC_ID_KEY)).isEqualTo("DOC-456");

        assertThat(result.get("DOC-456-query-1").getString(CTX_SINGLE_QUERY_ID)).isEqualTo(id2.toString());
    }

    @Test
    void partition_withNullStepExecutionContext_createsExecutionContextsWithoutCaseOrDoc() {
        final UUID id = UUID.randomUUID();
        when(query1.getQueryId()).thenReturn(id);
        when(queryResolver.resolve()).thenReturn(List.of(query1));

        final Map<String, ExecutionContext> result = partitioner.partition(1);

        assertThat(result).hasSize(1);
        ExecutionContext ctx = result.values().iterator().next();
        assertThat(ctx.getString("CTX_SINGLE_QUERY_ID")).isEqualTo(id.toString());
        assertThat(ctx.containsKey("CTX_CASE_ID_KEY")).isFalse();
        assertThat(ctx.containsKey("CTX_DOC_ID_KEY")).isFalse();
    }

    private void setStepExecutionContextField(String fieldName, String value) throws Exception {
        final Field field = QueryIdPartitioner.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(partitioner, value);
    }

}