package uk.gov.hmcts.cp.cdk.clients.progression;


import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;

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
}
