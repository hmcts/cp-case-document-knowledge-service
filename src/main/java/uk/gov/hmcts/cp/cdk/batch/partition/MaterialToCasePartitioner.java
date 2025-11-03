package uk.gov.hmcts.cp.cdk.batch.partition;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.CTX_DOC_ID_KEY;

public class MaterialToCasePartitioner implements Partitioner {

    private final Map<String, String> materialToCaseMap;

    public MaterialToCasePartitioner(final Map<String, String> materialToCaseMap) {
        this.materialToCaseMap = materialToCaseMap == null ? Map.of() : Map.copyOf(materialToCaseMap);
    }

    @Override
    public Map<String, ExecutionContext> partition(final int gridSize) {
        final Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        int index = 0;
        for (final Map.Entry<String, String> entry : materialToCaseMap.entrySet()) {
            final ExecutionContext ctx = new ExecutionContext();// NOPMD: AvoidInstantiatingObjectsInLoops
            ctx.putString(CTX_DOC_ID_KEY, entry.getKey());
            ctx.putString(CTX_CASE_ID_KEY, entry.getValue());
            partitions.put("case-" + (index++), ctx);
        }
        return partitions;
    }
}