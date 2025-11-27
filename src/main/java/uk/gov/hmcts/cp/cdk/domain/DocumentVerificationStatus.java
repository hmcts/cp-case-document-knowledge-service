package uk.gov.hmcts.cp.cdk.domain;

/**
 * Lifecycle status of a document verification task.
 * Mirrors document_verification_status_enum.
 */
public enum DocumentVerificationStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED
}
