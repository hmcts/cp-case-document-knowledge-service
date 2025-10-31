package uk.gov.hmcts.cp.cdk.batch;

public final class BatchKeys {
    public static final String JOB_NAME = "caseIngestionJob";
    public static final String CTX_CASE_ID_KEY = "caseId";
    public static final String CTX_CASE_IDS_KEY = "caseIds";
    public static final String CTX_DOC_ID_KEY = "docId";
    public static final String CONTEXT_KEY_MATERIAL_TO_CASE_MAP_KEY = "materialToCaseMap";
    public static final String CTX_UPLOAD_VERIFIED_KEY = "uploadVerified";
    public static final String CTX_DOCUMENT_STATUS_JSON_KEY = "documentStatusResponseJson";

    // Centralise JobParameter keys here (no breaking changes to existing constants)
    public static final class Params {
        private Params() {}
        public static final String COURT_CENTRE_ID = "courtCentreId";
        public static final String ROOM_ID         = "roomId";
        public static final String DATE            = "date";
        public static final String CPPUID          = "cppuid";
        public static final String RUN_ID          = "run";
    }

    private BatchKeys() {}
}
