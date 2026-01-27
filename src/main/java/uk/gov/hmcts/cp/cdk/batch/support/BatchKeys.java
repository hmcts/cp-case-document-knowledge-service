package uk.gov.hmcts.cp.cdk.batch.support;

public final class BatchKeys {
    public static final String JOB_NAME = "caseIngestionJob";
    public static final String CTX_CASE_ID_KEY = "caseId";
    public static final String CTX_CASE_IDS_KEY = "caseIds";
    public static final String CTX_DOC_ID_KEY = "docId";
    public static final String CTX_BLOB_NAME_KEY  ="blobName";
    public static final String CTX_MATERIAL_ID_KEY = "materialId";
    public static final String CTX_MATERIAL_NAME = "materialName";
    public static final String CTX_MATERIAL_NEW_UPLOAD = "materialNewUpload";
    public static final String CTX_UPLOAD_VERIFIED_KEY = "uploadVerified";
    public static final String CTX_DOCUMENT_STATUS_JSON_KEY = "documentStatusResponseJson";
    public static final String USERID_FOR_EXTERNAL_CALLS = "userIdForExternalCalls";

    public static final class Params {

        public static final String COURT_CENTRE_ID = "courtCentreId";
        public static final String ROOM_ID = "roomId";
        public static final String DATE = "date";
        public static final String CPPUID = "cppuid";
        public static final String RUN_ID = "run";

        private Params() {
        }
    }

    private BatchKeys() {
    }
}
