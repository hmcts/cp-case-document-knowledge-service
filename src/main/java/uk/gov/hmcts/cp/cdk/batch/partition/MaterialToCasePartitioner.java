package uk.gov.hmcts.cp.cdk.batch.partition;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static uk.gov.hmcts.cp.cdk.batch.BatchKeys.*;

@Component("eligibleCasePartitioner")
@StepScope
public class MaterialToCasePartitioner implements Partitioner {

    @Value("#{jobExecutionContext['" +
            CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY +
            "']}")
    private Map<String, String> materialToCaseMap;

    @Override
    public Map<String, ExecutionContext> partition(final int gridSize) {
        final Map<String, String> safe =
                (materialToCaseMap == null) ? Collections.emptyMap() : materialToCaseMap;

        final Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : safe.entrySet()) {
            final ExecutionContext ctx = new ExecutionContext(); // NOPMD: loop allocation is fine here
            ctx.putString(CTX_MATERIAL_ID_KEY, entry.getKey());
            ctx.putString(CTX_CASE_ID_KEY, entry.getValue());
            ctx.putString(CTX_DOC_ID_KEY, UUID.randomUUID().toString());
            ctx.putString(CTX_MATERIAL_NAME, "TODO");
            partitions.put("case-" + entry.getValue(), ctx);
        }
        return partitions;
    }
}
