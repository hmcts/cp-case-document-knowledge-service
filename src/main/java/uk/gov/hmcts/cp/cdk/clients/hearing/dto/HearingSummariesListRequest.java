package uk.gov.hmcts.cp.cdk.clients.hearing.dto;

import java.util.List;

public class HearingSummariesListRequest {
    private List<HearingSummaries> hearingSummaries;

    // getters & setters
    public List<HearingSummaries> getHearingSummaries() {
        return hearingSummaries;
    }
    public void setHearingSummaries(List<HearingSummaries> hearingSummaries) {
        this.hearingSummaries = hearingSummaries;
    }
}