package uk.gov.hmcts.cp.cdk.clients.progression.dto;

import java.util.List;

public record ProsecutionCaseEligibilityInfo(
        String prosecutionCaseId,
        List<String> defendantIds
) {
    public int defendantCount() {
        return defendantIds == null ? 0 : defendantIds.size();
    }

    public boolean hasMultipleDefendants() {
        return defendantCount() > 1;
    }
}
