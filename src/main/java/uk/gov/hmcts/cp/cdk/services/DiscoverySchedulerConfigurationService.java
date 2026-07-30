package uk.gov.hmcts.cp.cdk.services;

import uk.gov.hmcts.cp.cdk.domain.DiscoverySchedulerConfiguration;
import uk.gov.hmcts.cp.cdk.repo.DiscoverySchedulerConfigurationRepository;
import uk.gov.hmcts.cp.cdk.services.mapper.DiscoverySchedulerConfigurationMapper;
import uk.gov.hmcts.cp.openapi.model.cdk.DiscoverySchedulerConfigurationRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.UpsertDiscoverySchedulerConfiguration200Response;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@Transactional(readOnly = true)
public class DiscoverySchedulerConfigurationService {

    private final DiscoverySchedulerConfigurationRepository repository;
    private final DiscoverySchedulerConfigurationMapper mapper;

    public DiscoverySchedulerConfigurationService(final DiscoverySchedulerConfigurationRepository repository,
                                                   final DiscoverySchedulerConfigurationMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public UpsertDiscoverySchedulerConfiguration200Response upsert(final DiscoverySchedulerConfigurationRequest request) {
        if (repository.existsByCourtCentreIdAndCourtRoomIdAndVersion(
                request.getCourtCentreId(), request.getCourtRoomId(), request.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Discovery scheduler configuration already exists for this court centre, court room and version");
        }

        final DiscoverySchedulerConfiguration entity = mapper.toEntity(request);
        repository.saveAndFlush(entity);

        log.info("Discovery scheduler configuration saved courtCentreId={} courtRoomId={} version={} active={}",
                entity.getCourtCentreId(), entity.getCourtRoomId(), entity.getVersion(), entity.isActive());

        return mapper.toResponse("Discovery scheduler configuration saved");
    }
}
