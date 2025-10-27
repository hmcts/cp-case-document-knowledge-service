package uk.gov.hmcts.cp.cdk.clients.progression;


import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProgressionResponseDto;

public interface ProgressionClient {
    ProgressionResponseDto getProgression(String caseId);
}
