package uk.gov.hmcts.cp.cdk.batch.partition;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_CASE_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_ID;
import static uk.gov.hmcts.cp.cdk.batch.support.PartitionKeys.PARTITION_RESULT_MATERIAL_NAME;

import uk.gov.hmcts.cp.cdk.clients.progression.dto.MaterialDocumentMapping;
import uk.gov.hmcts.cp.cdk.repo.DocumentIdResolver;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.item.ExecutionContext;

@ExtendWith(MockitoExtension.class)
class MaterialMappingAggregatorTest {

    @Mock
    private DocumentIdResolver documentIdResolver;
    @Mock
    private JobExecution jobExecution;
    @InjectMocks
    private MaterialMappingAggregator aggregator;

    @Test
    void aggregate_withEmptyExecutions_createsEmptyMap() {
        final StepExecution mainStepExecution = new StepExecution("mainStep", jobExecution, 1L);

        aggregator.aggregate(mainStepExecution, Collections.emptyList());

        final ExecutionContext resultEC = mainStepExecution.getExecutionContext();
        assertThat(resultEC.containsKey(CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY)).isTrue();
        final Map<?, ?> merged = (Map<?, ?>) resultEC.get(CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY);
        assertThat(merged).isEmpty();
    }

    @Test
    void aggregate_skipsPartitionsWithMissingData() {
        StepExecution mainStepExecution = new StepExecution("mainStep", jobExecution, 1L);

        StepExecution invalid1 = createPartitionExecution(null, "mat1", "Material 1");
        StepExecution invalid2 = createPartitionExecution("case1", null, "Material 2");
        StepExecution invalid3 = createPartitionExecution("case1", "mat2", null);

        aggregator.aggregate(mainStepExecution, List.of(invalid1, invalid2, invalid3));

        final Map<?, ?> merged = (Map<?, ?>) mainStepExecution.getExecutionContext().get(CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY);
        assertThat(merged).isEmpty();
    }

    @Test
    void aggregate_skipsPartitionsWithInvalidUUIDs() {
        final StepExecution mainStepExecution = new StepExecution("mainStep", jobExecution, 1L);
        final StepExecution partition = createPartitionExecution("invalid-case", "invalid-mat", "Material X");

        aggregator.aggregate(mainStepExecution, List.of(partition));

        final Map<?, ?> merged = (Map<?, ?>) mainStepExecution.getExecutionContext().get(CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY);
        assertThat(merged).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregate_generatesNewDocId_whenNoExistingDocId() {
        final StepExecution mainStepExecution = new StepExecution("mainStep", jobExecution, 1L);

        final UUID caseId = randomUUID();
        final UUID materialId = randomUUID();
        final StepExecution partition = createPartitionExecution(caseId.toString(), materialId.toString(), "Material A");

        when(documentIdResolver.resolveExistingDocId(caseId, materialId)).thenReturn(Optional.empty());

        aggregator.aggregate(mainStepExecution, List.of(partition));

        Map<String, MaterialDocumentMapping> merged = (Map<String, MaterialDocumentMapping>) mainStepExecution.getExecutionContext().get(CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY);

        assertThat(merged).hasSize(1);
        MaterialDocumentMapping mapping = (MaterialDocumentMapping) merged.get(materialId.toString());
        assertThat(mapping.materialId()).isEqualTo(materialId.toString());
        assertThat(mapping.materialName()).isEqualTo("Material A");
        assertThat(mapping.caseId()).isEqualTo(caseId.toString());
        assertThat(mapping.resolvedDocId()).isNotNull();
        assertThat(mapping.existingDocId()).isNull();
        assertThat(mapping.newDocId()).isNotNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void aggregate_usesExistingDocId_whenPresent() {
        final StepExecution mainStepExecution = new StepExecution("mainStep", jobExecution, 1L);

        final UUID caseId = randomUUID();
        final UUID materialId = randomUUID();
        final UUID existingDocId = randomUUID();

        final StepExecution partition = createPartitionExecution(caseId.toString(), materialId.toString(), "Material B");

        when(documentIdResolver.resolveExistingDocId(caseId, materialId)).thenReturn(Optional.of(existingDocId));

        aggregator.aggregate(mainStepExecution, List.of(partition));

        final Map<String, MaterialDocumentMapping> merged = (Map<String, MaterialDocumentMapping>) mainStepExecution.getExecutionContext().get(CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY);

        final MaterialDocumentMapping mapping = merged.get(materialId.toString());
        assertThat(mapping.resolvedDocId()).isEqualTo(existingDocId.toString());
        assertThat(mapping.existingDocId()).isEqualTo(existingDocId.toString());
        assertThat(mapping.newDocId()).isNull();
    }

    private StepExecution createPartitionExecution(String caseId, String materialId, String materialName) {
        StepExecution stepExecution = new StepExecution("partition", jobExecution, 1L);
        ExecutionContext ec = new ExecutionContext();
        if (caseId != null) ec.putString(PARTITION_CASE_ID, caseId);
        if (materialId != null) ec.putString(PARTITION_RESULT_MATERIAL_ID, materialId);
        if (materialName != null) ec.putString(PARTITION_RESULT_MATERIAL_NAME, materialName);
        stepExecution.setExecutionContext(ec);
        return stepExecution;
    }

}