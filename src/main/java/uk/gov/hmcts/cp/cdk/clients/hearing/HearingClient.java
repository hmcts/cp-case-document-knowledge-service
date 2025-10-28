package uk.gov.hmcts.cp.cdk.clients.hearing;

import uk.gov.hmcts.cp.cdk.clients.hearing.dto.HearingSummariesInfo;

import java.time.LocalDate;
import java.util.List;

@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface HearingClient {
    List<HearingSummariesInfo> getHearingsAndCases(String defendantSurname,
                                                   String urn,
                                                   LocalDate hearingDate);
}