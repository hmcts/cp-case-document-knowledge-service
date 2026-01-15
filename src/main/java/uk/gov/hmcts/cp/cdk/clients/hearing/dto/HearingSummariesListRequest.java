package uk.gov.hmcts.cp.cdk.clients.hearing.dto;

import java.util.List;

public record HearingSummariesListRequest(List<HearingSummaries> hearingSummaries) {
}