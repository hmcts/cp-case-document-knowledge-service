package uk.gov.hmcts.cp.cdk.batch.support;

import uk.gov.hmcts.cp.cdk.batch.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.batch.clients.progression.dto.LatestMaterialInfo;

import java.util.Optional;
import java.util.UUID;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class TaskLookupUtils {

    /**
     * Parses the supplied string into a {@link UUID}.
     * Returns {@link Optional#empty()} for null, blank, or invalid values.
     */
    public static Optional<UUID> parseUuid(final String uuidString) {
        Optional<UUID> result;
        if (uuidString == null || uuidString.isBlank()) {
            result = Optional.empty();
        } else {
            try {
                result = Optional.of(UUID.fromString(uuidString));
            } catch (IllegalArgumentException exception) {
                result = Optional.empty();
            }
        }
        return result;
    }

    /**
     * Convenience wrapper that returns a {@code UUID} or {@code null} for invalid inputs.
     * Uses {@link #parseUuid(String)} to avoid null assignments and multiple returns.
     */
    public static UUID parseUuidOrNull(final String uuidString) {
        return parseUuid(uuidString).orElse(null);
    }

    /**
     * Calls Progression to fetch latest material info; never throws.
     * Returns {@link Optional#empty()} on any error.
     */
    public static Optional<LatestMaterialInfo> safeGetCourtDocuments(
            final ProgressionClient progressionClient,
            final UUID caseId,
            final String userId
    ) {
        Optional<LatestMaterialInfo> result;
        try {
            result = progressionClient.getCourtDocuments(caseId, userId);
        } catch (Exception exception) {
            log.error("Progression lookup failed for caseId={} (userId='{}'). Skipping.",
                    caseId, userId, exception);
            result = Optional.empty();
        }
        return result;
    }
}
