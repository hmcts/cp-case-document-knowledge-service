package uk.gov.hmcts.cp.cdk.jobmanager.support;

public final class JobManagerKeys {

    public static final String CTX_CASE_ID_KEY = "caseId";
    public static final String CTX_DOC_ID_KEY = "docId";
    public static final String CTX_BLOB_NAME_KEY  ="blobName";
    public static final String CTX_MATERIAL_ID_KEY = "materialId";
    public static final String CTX_DEFENDANT_ID_KEY = "defendantId";
    public static final String CTX_COURTDOCUMENT_ID_KEY = "courtDocumentId";
    public static final String CTX_MATERIAL_NAME = "materialName";
    public static final String CTX_SINGLE_QUERY_ID = "CTX_SINGLE_QUERY_ID";
    public static final String CTX_RAG_TRANSACTION_ID = "ragTransactionId";


    public static final class Params {

        public static final String COURT_CENTRE_ID = "courtCentreId";
        public static final String ROOM_ID = "roomId";
        public static final String DATE = "date";
        public static final String CPPUID = "cppuid";


        private Params() {
        }
    }

    private JobManagerKeys() {
    }
}
