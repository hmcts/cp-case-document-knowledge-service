package uk.gov.hmcts.cp.cdk.clients.hearing;

import java.time.LocalDate;
import java.util.List;

public interface HearingClient {
    List<String> getHearingsAndCases(String caseId, String roomId, LocalDate date);
}
