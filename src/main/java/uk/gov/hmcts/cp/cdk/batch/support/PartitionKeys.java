package uk.gov.hmcts.cp.cdk.batch.support;

public final class PartitionKeys {

    public static final String PARTITION_CASE_ID = "partition.caseId";
    public static final String PARTITION_RESULT_MATERIAL_ID = "partition.result.materialId";
    public static final String PARTITION_RESULT_MATERIAL_NAME = "partition.result.materialName";
    public static final String CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY = "materialToCaseMap";

    private PartitionKeys() {
    }
}
