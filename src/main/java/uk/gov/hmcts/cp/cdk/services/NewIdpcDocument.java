package uk.gov.hmcts.cp.cdk.services;

/**
 * A newer IDPC document, not yet ingested, that {@link IdpcAvailabilityService} has identified and
 * persisted a placeholder record for. Carries only plain fields — no JobManager/task-framework
 * types — so callers (JobManager tasks or the synchronous manual-ingestion service) can build
 * whatever job data / dispatch mechanism they need from it.
 */
public record NewIdpcDocument(
        String docId,
        String materialId,
        String materialName,
        String defendantId,
        String courtDocumentId,
        boolean latestDefendant
) {
}
