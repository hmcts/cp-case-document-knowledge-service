package uk.gov.hmcts.cp.cdk.clients.progression;


import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;

import java.util.Optional;
import java.util.UUID;

@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface ProgressionClient {

    /**
     * Retrieves the latest court document material information for a given case.
     *
     * @param caseId The unique case identifier.
     * @return An Optional containing the latest material info, or empty if none found.
     */
    Optional<LatestMaterialInfo> getCourtDocuments(UUID caseId, String userId);

    /**
     * Retrieves a signed download URL for a given material ID.
     *
     * @param materialId The unique material identifier.
     * @return An Optional containing the download URL, or empty if not found.
     */
    Optional<String> getMaterialDownloadUrl(UUID materialId, String userId);

    /**
     * Retrieves prosecution case information required to evaluate eligibility.
     *
     * @param caseId The unique case identifier.
     * @param userId The user identifier for authorisation.
     * @return Eligibility-relevant prosecution case info, or empty if unavailable.
     */
    Optional<ProsecutionCaseEligibilityInfo> getProsecutionCaseEligibilityInfo(
            UUID caseId,
            String userId
    );
}
