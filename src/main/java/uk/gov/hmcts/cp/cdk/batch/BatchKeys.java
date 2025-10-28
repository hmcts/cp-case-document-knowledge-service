package uk.gov.hmcts.cp.cdk.batch;

import java.util.UUID;

public final class BatchKeys {
    private BatchKeys() {}

    public static final String JOB_NAME = "caseIngestionJob";
    public static final String CTX_CASE_IDS = "caseIds";
    public static final String CTX_ELIGIBLE_CASE_IDS = "eligibleCaseIds";
    public static final String CTX_DOC_ID = "docId";
    public static final String CONTEXT_KEY_ELIGIBLE_MATERIAL_IDS = "eligibleMaterialIds";

    public static final String BLOB_TEMPLATE = "cases/%s/idpc.pdf";

    public static String blobPath(final UUID caseId) {
        return BLOB_TEMPLATE.formatted(caseId);
    }
}
