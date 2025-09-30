package uk.gov.hmcts.cp.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.domain.IngestionStatusHistoryEntity;
import uk.gov.hmcts.cp.openapi.model.IngestionStatus;
import uk.gov.hmcts.cp.repo.IngestionStatusHistoryRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * Service to read latest ingestion status (optionally as-of an instant) and map it
 * safely to the OpenAPI model enum. Defaults to UPLOADED on missing/unknown values.
 */
@Service
@RequiredArgsConstructor
public class IngestionStatusService {

    private final IngestionStatusHistoryRepository repo;

    /**
     * Latest ingestion status (or latest at/before {@code asOf}) mapped to the OpenAPI enum.
     * Defaults to UPLOADED if there’s no history or mapping fails.
     */
    public IngestionStatus latestStatus(final Instant asOf) {
        final Optional<IngestionStatusHistoryEntity> row;
        if (asOf == null) {
            row = repo.findTopByOrderByChangedAtDesc();
        } else {
            row = repo.findTopByChangedAtLessThanEqualOrderByChangedAtDesc(asOf);
        }

        return row
                .map(IngestionStatusHistoryEntity::getStatus) // could be enum or String
                .map(this::toApiStatusSafely)
                .orElse(IngestionStatus.UPLOADED);
    }

    private IngestionStatus toApiStatusSafely(final Object statusValue) {
        final IngestionStatus mapped;
        if (statusValue == null) {
            mapped = IngestionStatus.UPLOADED;
        } else {
            final String name = statusValue.toString();
            IngestionStatus tmp;
            try {
                tmp = IngestionStatus.valueOf(name);
            } catch (final IllegalArgumentException ex) {
                tmp = IngestionStatus.UPLOADED;
            }
            mapped = tmp;
        }
        return mapped;
    }
}
