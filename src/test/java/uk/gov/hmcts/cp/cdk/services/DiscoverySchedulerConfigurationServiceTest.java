package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.cdk.repo.DiscoverySchedulerConfigurationRepository;
import uk.gov.hmcts.cp.cdk.services.mapper.DiscoverySchedulerConfigurationMapper;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoverySchedulerConfigurationRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.UpsertDiscoverySchedulerConfiguration200Response;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@DisplayName("Discovery Scheduler Configuration Service tests")
class DiscoverySchedulerConfigurationServiceTest {

    @Mock
    private DiscoverySchedulerConfigurationRepository repository;
    @Mock
    private DiscoverySchedulerConfigurationMapper mapper;
    @InjectMocks
    private DiscoverySchedulerConfigurationService service;

    @Test
    @DisplayName("upsert persists a new version")
    void upsert_persists_new_version() {
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();
        final DiscoverySchedulerConfigurationRequest request = new DiscoverySchedulerConfigurationRequest()
                .courtCentreId(courtCentreId)
                .courtRoomId(courtRoomId)
                .uploadedDate(LocalDate.of(2026, 6, 16))
                .version(1)
                .isActive(true);

        when(repository.existsByCourtCentreIdAndCourtRoomIdAndVersion(courtCentreId, courtRoomId, 1)).thenReturn(false);

        final DiscoverySchedulerConfiguration entity = new DiscoverySchedulerConfiguration();
        entity.setId(UUID.randomUUID());
        entity.setCourtCentreId(courtCentreId);
        entity.setCourtRoomId(courtRoomId);
        entity.setVersion(1);
        entity.setActive(true);
        when(mapper.toEntity(request)).thenReturn(entity);

        final UpsertDiscoverySchedulerConfiguration200Response mappedResponse =
                new UpsertDiscoverySchedulerConfiguration200Response().message("saved");
        when(mapper.toResponse(org.mockito.ArgumentMatchers.anyString())).thenReturn(mappedResponse);

        final UpsertDiscoverySchedulerConfiguration200Response response = service.upsert(request);

        assertThat(response).isSameAs(mappedResponse);
        verify(repository).saveAndFlush(entity);
    }

    @Test
    @DisplayName("upsert rejects duplicate courtCentre/courtRoom/version")
    void upsert_rejects_duplicate() {
        final UUID courtCentreId = UUID.randomUUID();
        final UUID courtRoomId = UUID.randomUUID();
        final DiscoverySchedulerConfigurationRequest request = new DiscoverySchedulerConfigurationRequest()
                .courtCentreId(courtCentreId)
                .courtRoomId(courtRoomId)
                .uploadedDate(LocalDate.of(2026, 6, 16))
                .version(1)
                .isActive(true);

        when(repository.existsByCourtCentreIdAndCourtRoomIdAndVersion(courtCentreId, courtRoomId, 1)).thenReturn(true);

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.upsert(request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(repository, never()).saveAndFlush(any());
    }
}
