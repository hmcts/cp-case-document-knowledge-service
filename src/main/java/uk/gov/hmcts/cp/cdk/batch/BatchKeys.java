package uk.gov.hmcts.cp.cdk.batch;

import java.util.UUID;

public final class BatchKeys {
    public static final String JOB_NAME = "caseIngestionJob";
    public static final String CTX_CASE_IDS = "caseIds";
    public static final String CTX_ELIGIBLE_CASE_IDS = "eligibleCaseIds";
    public static final String CTX_DOC_ID = "docId";
    public static final String CONTEXT_KEY_MATERIAL_TO_CASE_MAP = "materialToCaseMap";
    public static final String BLOB_TEMPLATE = "cases/%s/idpc.pdf";

    private BatchKeys() {
    }

    public static String blobPath(final UUID caseId) {
        return BLOB_TEMPLATE.formatted(caseId);
    }
}
