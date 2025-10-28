package uk.gov.hmcts.cp.cdk.clients.hearing;

import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;

import java.time.LocalDate;
import java.util.List;

public interface HearingClient {
    List<HearingSummariesInfo> getHearingsAndCases(String caseId, String roomId, LocalDate date);
}
