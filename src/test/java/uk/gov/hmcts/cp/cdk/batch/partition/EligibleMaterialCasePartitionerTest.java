package uk.gov.hmcts.cp.cdk.batch.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_CASE_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_DOC_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_ID_KEY;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_NAME;
import static uk.gov.hmcts.cp.cdk.batch.support.BatchKeys.CTX_MATERIAL_NEW_UPLOAD;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.MaterialDocumentMapping;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.ExecutionContext;


@ExtendWith(MockitoExtension.class)
class EligibleMaterialCasePartitionerTest {


    private EligibleMaterialCasePartitioner partitioner;

    @BeforeEach
    void setUp() {
        partitioner = new EligibleMaterialCasePartitioner();
    }

    @Test
    void partition_returnsEmpty_whenMaterialMapIsNull() {
        partitioner.materialToCaseMap = null;

        final Map<String, ExecutionContext> partitions = partitioner.partition(4);

        assertThat(partitions).isEmpty();
    }

    @Test
    void partition_createsPartitions_forEachMaterial() {
        final MaterialDocumentMapping mapping1 = new MaterialDocumentMapping(
                "material1", "Material One", "case1", "doc1", null, "doc1");
        final MaterialDocumentMapping mapping2 = new MaterialDocumentMapping(
                "material2", "Material Two", "case2", "doc2", null, "doc2");

        final Map<String, MaterialDocumentMapping> materialMap = new LinkedHashMap<>();
        materialMap.put("material1", mapping1);
        materialMap.put("material2", mapping2);

        partitioner.materialToCaseMap = materialMap;

        final Map<String, ExecutionContext> partitions = partitioner.partition(2);

        assertThat(partitions).hasSize(2);
        assertThat(partitions).containsKeys("material-material1", "material-material2");

        ExecutionContext ec1 = partitions.get("material-material1");
        assertThat(ec1.getString(CTX_MATERIAL_ID_KEY)).isEqualTo("material1");
        assertThat(ec1.getString(CTX_CASE_ID_KEY)).isEqualTo("case1");
        assertThat(ec1.getString(CTX_DOC_ID_KEY)).isEqualTo("doc1");
        assertThat(ec1.getString(CTX_MATERIAL_NAME)).isEqualTo("Material One");
        assertThat(ec1.get(CTX_MATERIAL_NEW_UPLOAD)).isEqualTo(true);

        ExecutionContext ec2 = partitions.get("material-material2");
        assertThat(ec2.getString(CTX_MATERIAL_ID_KEY)).isEqualTo("material2");
        assertThat(ec2.getString(CTX_CASE_ID_KEY)).isEqualTo("case2");
        assertThat(ec2.getString(CTX_DOC_ID_KEY)).isEqualTo("doc2");
        assertThat(ec2.getString(CTX_MATERIAL_NAME)).isEqualTo("Material Two");
        assertThat(ec2.get(CTX_MATERIAL_NEW_UPLOAD)).isEqualTo(true);
    }
}