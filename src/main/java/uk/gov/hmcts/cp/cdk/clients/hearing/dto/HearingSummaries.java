package uk.gov.hmcts.cp.cdk.clients.hearing.dto;

import java.util.List;

public class HearingSummaries {
    private List<ProsecutionCaseSummaries> prosecutionCaseSummaries;

    // getters & setters
    public List<ProsecutionCaseSummaries> getProsecutionCaseSummaries() {
        return prosecutionCaseSummaries;
    }

    public void setProsecutionCaseSummaries(List<ProsecutionCaseSummaries> prosecutionCaseSummaries) {
        this.prosecutionCaseSummaries = prosecutionCaseSummaries;
    }
}