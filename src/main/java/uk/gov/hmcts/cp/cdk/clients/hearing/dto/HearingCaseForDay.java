package uk.gov.hmcts.cp.cdk.clients.hearing.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record HearingCaseForDay(
        UUID courtCentreId,
        UUID courtRoomId,
        LocalDate hearingDate,
        UUID hearingId,
        List<HearingCaseProsecutionCase> prosecutionCases
) {
}
