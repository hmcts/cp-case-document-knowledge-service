package uk.gov.hmcts.cp.cdk.batch.partition;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_IDS_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_CASE_ID;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@StepScope
public class CaseIdPartitioner implements Partitioner {

    @Value("#{jobExecutionContext['" + CTX_CASE_IDS_KEY + "']}")
    @SuppressWarnings("unchecked")
    private List<String> caseIds;

    @Override
    public Map<String, ExecutionContext> partition(final int gridSize) {
        final List<String> ids = caseIds == null ? List.of() : caseIds;
        final int initialCapacity = Math.max(ids.size(), 16);
        final Map<String, ExecutionContext> partitions = new LinkedHashMap<>(initialCapacity);

        if (ids.isEmpty()) {
            log.info("No case IDs found under '{}'; created 0 partitions.", CTX_CASE_IDS_KEY);
        } else {
            for (final String id : ids) {
                partitions.put("case-" + id, newPartitionContext(id));
            }
            log.info("Created {} partitions (one per caseId).", partitions.size());
        }
        return partitions;
    }

    private static ExecutionContext newPartitionContext(final String caseId) {
        final ExecutionContext ctx = new ExecutionContext();
        ctx.putString(PARTITION_CASE_ID, caseId);
        return ctx;
    }
}
