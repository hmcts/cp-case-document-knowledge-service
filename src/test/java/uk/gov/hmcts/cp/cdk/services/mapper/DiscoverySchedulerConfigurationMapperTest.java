package uk.gov.hmcts.cp.cdk.services.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoverySchedulerConfigurationRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.UpsertDiscoverySchedulerConfiguration200Response;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Discovery Scheduler Configuration Mapper tests")
class DiscoverySchedulerConfigurationMapperTest {

    private final DiscoverySchedulerConfigurationMapper mapper = new DiscoverySchedulerConfigurationMapper() {
    };

    @Test
    @DisplayName("toEntity maps all request fields and assigns a new id")
    void toEntity_maps_all_fields() {
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();
        final DiscoverySchedulerConfigurationRequest request = new DiscoverySchedulerConfigurationRequest()
                .courtCentreId(courtCentreId)
                .courtRoomId(courtRoomId)
                .uploadedDate(LocalDate.of(2026, 6, 16))
                .version(1)
                .isActive(true);

        final DiscoverySchedulerConfiguration entity = mapper.toEntity(request);

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getCourtCentreId()).isEqualTo(courtCentreId);
        assertThat(entity.getCourtRoomId()).isEqualTo(courtRoomId);
        assertThat(entity.getUploadedDate()).isEqualTo(LocalDate.of(2026, 6, 16));
        assertThat(entity.getVersion()).isEqualTo(1);
        assertThat(entity.isActive()).isTrue();
    }

    @Test
    @DisplayName("toResponse maps message")
    void toResponse_maps_message() {
        final UpsertDiscoverySchedulerConfiguration200Response response = mapper.toResponse("saved");

        assertThat(response.getMessage()).isEqualTo("saved");
    }
}
