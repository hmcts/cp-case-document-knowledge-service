package uk.gov.hmcts.cp.cdk.batch.partition;

import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_NEW_UPLOAD;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.MaterialDocumentMapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@StepScope
public class EligibleMaterialCasePartitioner implements Partitioner {

    @Value("#{jobExecutionContext['" + CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY + "']}")
    private Map<String, MaterialDocumentMapping> materialToCaseMap;

    @Override
    public Map<String, ExecutionContext> partition(final int gridSize) {
        final Map<String, MaterialDocumentMapping> safe =
                materialToCaseMap == null ? Collections.emptyMap() : materialToCaseMap;

        final Map<String, ExecutionContext> partitions = new LinkedHashMap<>(Math.max(safe.size(), 16));
        for (final Map.Entry<String, MaterialDocumentMapping> entry : safe.entrySet()) {
            partitions.put("material-" + entry.getKey(), newPartitionContext(entry.getValue()));
        }
        return partitions;
    }

    private static ExecutionContext newPartitionContext(final MaterialDocumentMapping materialDocumentMapping) {
        final ExecutionContext executionContext = new ExecutionContext();
        executionContext.putString(CTX_MATERIAL_ID_KEY, materialDocumentMapping.materialId());
        executionContext.putString(CTX_CASE_ID_KEY, materialDocumentMapping.caseId());
        executionContext.putString(CTX_DOC_ID_KEY, materialDocumentMapping.resolvedDocId());
        executionContext.putString(CTX_MATERIAL_NAME, materialDocumentMapping.materialName());
        executionContext.put(CTX_MATERIAL_NEW_UPLOAD, materialDocumentMapping.isNewUpload());
        return executionContext;
    }
}
