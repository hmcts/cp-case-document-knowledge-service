package uk.gov.hmcts.cp.cdk.domain;

public enum DocumentIngestionPhase {
    NOT_FOUND,
    UPLOADING,
    UPLOADED,
    INGESTING,
    INGESTED,
    FAILED
}
