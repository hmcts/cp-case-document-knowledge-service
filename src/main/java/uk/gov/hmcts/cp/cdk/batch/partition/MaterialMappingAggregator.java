package uk.gov.hmcts.cp.cdk.batch.partition;

import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_CASE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.TaskLookupUtils.parseUuid;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.MaterialDocumentMapping;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.StepExecutionAggregator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MaterialMappingAggregator implements StepExecutionAggregator {

    private final DocumentIdResolver documentIdResolver;

    @Override
    public void aggregate(final StepExecution result, final Collection<StepExecution> executions) {
        final Map<String, MaterialDocumentMapping> merged =
                new LinkedHashMap<>(Math.max(executions.size(), 16));

        for (final StepExecution partitionStepExecution : executions) {
            final ExecutionContext executionContext = partitionStepExecution.getExecutionContext();

            final String caseIdStr = executionContext.getString(PARTITION_CASE_ID, null);
            final String materialIdStr = executionContext.getString(PARTITION_RESULT_MATERIAL_ID, null);
            final String materialName = executionContext.getString(PARTITION_RESULT_MATERIAL_NAME, null);

            if (caseIdStr == null || materialIdStr == null || materialName == null) {
                log.warn("Skipping partition result due to missing data: caseId='{}', materialId='{}', name='{}'",
                        caseIdStr, materialIdStr, materialName);
                continue;
            }

            final Optional<UUID> caseIdUuid = parseUuid(caseIdStr);
            final Optional<UUID> materialIdUuid = parseUuid(materialIdStr);

            if (caseIdUuid.isEmpty() || materialIdUuid.isEmpty()) {
                log.warn("Skipping invalid UUIDs: caseId='{}' (ok? {}), materialId='{}' (ok? {})",
                        caseIdStr, caseIdUuid.isPresent(), materialIdStr, materialIdUuid.isPresent());
                continue;
            }

            final Optional<UUID> existingDocUuid =
                    documentIdResolver.resolveExistingDocId(caseIdUuid.get(), materialIdUuid.get());

            final String existingDocId = existingDocUuid.map(UUID::toString).orElse(null);
            final String newDocId = existingDocId == null ? UUID.randomUUID().toString() : null;
            final String resolvedDocId = existingDocId != null ? existingDocId : newDocId;

            if (existingDocId != null) {
                log.info("Resolved existing docId={} for caseId={}, materialId={}.", existingDocId, caseIdStr, materialIdStr);
            } else {
                log.debug("No existing docId; generated new docId={} for caseId={}, materialId={}.",
                        newDocId, caseIdStr, materialIdStr);
            }

            merged.put(materialIdStr, buildMapping(materialIdStr, materialName, caseIdStr,
                    resolvedDocId, existingDocId, newDocId));
        }

        result.getExecutionContext().put(CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY, merged);
        log.info("Aggregated {} eligible entries into step EC key '{}'.",
                merged.size(), CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY);
    }

    private static MaterialDocumentMapping buildMapping(
            final String materialId,
            final String materialName,
            final String caseId,
            final String resolvedDocId,
            final String existingDocId,
            final String newDocId
    ) {
        return new MaterialDocumentMapping(materialId, materialName, caseId, resolvedDocId, existingDocId, newDocId);
    }
}
