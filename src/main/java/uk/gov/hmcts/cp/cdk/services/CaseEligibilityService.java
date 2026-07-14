package uk.gov.hmcts.cp.cdk.services;

import static jakarta.json.Json.createObjectBuilder;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_COUNT;
import static uk.gov.hmcts.cp.cdk.jobmanager.support.JobManagerKeys.CTX_DEFENDANT_ID_KEY;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProsecutionCaseEligibilityInfo;

import java.util.Optional;
import java.util.UUID;

import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Shared case-eligibility business logic for the ingestion workflow.
 *
 * <p>Used by both {@code CheckCaseEligibilityTask} (scheduled ingestion, invoked asynchronously via
 * the JobManager) and {@link IngestionProcessorByCaseService} (manual "Process IDPC" ingestion,
 * invoked synchronously) so the eligibility rule is defined exactly once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseEligibilityService {

    /** A case must have at least one defendant to be eligible for ingestion. */
    public static final int SINGLE_DEFENDANT_COUNT = 1;

    private final ProgressionClient progressionClient;

    /**
     * Resolves prosecution-case eligibility for the given case.
     *
     * @return the eligibility info only when the case exists and has at least one defendant;
     *         otherwise {@link Optional#empty()} (not eligible / nothing to ingest).
     */
    public Optional<ProsecutionCaseEligibilityInfo> resolveEligibleCase(final UUID caseId, final String cppuid) {
        if (caseId == null || cppuid == null) {
            log.warn("Missing caseId or cppuid, skipping eligibility check");
            return Optional.empty();
        }

        final Optional<ProsecutionCaseEligibilityInfo> eligibilityInfo =
                progressionClient.getProsecutionCaseEligibilityInfo(caseId, cppuid);

        if (eligibilityInfo.isEmpty()) {
            log.info("No prosecution case data found for caseId={}, not eligible", caseId);
            return Optional.empty();
        }

        final ProsecutionCaseEligibilityInfo info = eligibilityInfo.get();
        if (info.defendantCount() < SINGLE_DEFENDANT_COUNT) {
            log.info("Case {} has no defendants. Not eligible to proceed.", caseId);
            return Optional.empty();
        }

        log.info("Case {} has {} defendants and is eligible for ingestion.", caseId, info.defendantCount());
        return eligibilityInfo;
    }

    /**
     * Enriches the supplied job data with the defendant context required by the downstream
     * IDPC-availability step.
     */
    public JsonObject withDefendantContext(final JsonObject jobData, final ProsecutionCaseEligibilityInfo info) {
        return createObjectBuilder(jobData)
                .add(CTX_DEFENDANT_ID_KEY, info.defendantIds().getFirst())
                .add(CTX_DEFENDANT_COUNT, info.defendantCount())
                .build();
    }
}
