package uk.gov.hmcts.cp.cdk.domain;

public enum DocumentIngestionPhase {
    NOT_FOUND,
    WAITING_FOR_UPLOAD,
    UPLOADING,
    UPLOADED,
    INGESTING,
    INGESTED,
    FAILED
}
