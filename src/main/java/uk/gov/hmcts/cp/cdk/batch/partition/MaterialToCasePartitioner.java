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
        for (Map.Entry<String, String> e : materialToCaseMap.entrySet()) {
            final ExecutionContext ctx = new ExecutionContext();
            ctx.putString(CTX_DOC_ID_KEY, e.getKey());
            ctx.putString(CTX_CASE_ID_KEY, e.getValue());
            partitions.put("case-" + (index++), ctx);
        }
        return partitions;
    }
}
