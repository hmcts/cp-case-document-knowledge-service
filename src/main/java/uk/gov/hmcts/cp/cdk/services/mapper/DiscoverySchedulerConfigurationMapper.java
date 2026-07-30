package uk.gov.hmcts.cp.cdk.services.mapper;

import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoverySchedulerConfigurationRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.UpsertDiscoverySchedulerConfiguration200Response;

import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DiscoverySchedulerConfigurationMapper {

    default DiscoverySchedulerConfiguration toEntity(final DiscoverySchedulerConfigurationRequest request) {
        final DiscoverySchedulerConfiguration entity = new DiscoverySchedulerConfiguration();
        entity.setId(UUID.randomUUID());
        entity.setCourtCentreId(request.getCourtCentreId());
        entity.setCourtRoomId(request.getCourtRoomId());
        entity.setUploadedDate(request.getUploadedDate());
        entity.setVersion(request.getVersion());
        entity.setActive(Boolean.TRUE.equals(request.getIsActive()));
        return entity;
    }

    default UpsertDiscoverySchedulerConfiguration200Response toResponse(final String message) {
        return new UpsertDiscoverySchedulerConfiguration200Response().message(message);
    }
}
