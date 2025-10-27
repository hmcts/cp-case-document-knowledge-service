package uk.gov.hmcts.cp.cdk.clients.progression.mapper;


import uk.gov.hmcts.cp.cdk.clients.progression.dto.ProgressionResponseDto;

public class ProgressionDtoMapper {
    public static Progression toDomain(ProgressionResponseDto dto) {
        return new Progression("foo");
    }
}

