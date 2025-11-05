package uk.gov.hmcts.cp.cdk.batch.partition;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.*;

public class MaterialToCasePartitioner implements Partitioner {

    private final Map<String, String> materialToCaseMap;

    public MaterialToCasePartitioner(final Map<String, String> materialToCaseMap) {
        this.materialToCaseMap = materialToCaseMap == null ? Map.of() : Map.copyOf(materialToCaseMap);
    }

    @Override
    public Map<String, ExecutionContext> partition(final int gridSize) {
        final Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : materialToCaseMap.entrySet()) {
            final ExecutionContext ctx = new ExecutionContext();// NOPMD: AvoidInstantiatingObjectsInLoops
            ctx.putString(CTX_MATERIAL_ID_KEY, entry.getKey());
            ctx.putString(CTX_CASE_ID_KEY, entry.getValue());
            ctx.putString(CTX_DOC_ID_KEY, UUID.randomUUID().toString());
            partitions.put("case-"  +  entry.getValue(), ctx);
        }
        return partitions;
    }
}