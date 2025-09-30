package uk.gov.hmcts.cp.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.domain.IngestionStatusHistoryEntity;
import uk.gov.hmcts.cp.openapi.model.IngestionStatus;
import uk.gov.hmcts.cp.repo.IngestionStatusHistoryRepository;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class IngestionStatusService {

    private final IngestionStatusHistoryRepository repo;

    /**
     * Latest ingestion status (or latest at/before {@code asOf}) mapped to the OpenAPI enum.
     * Defaults to UPLOADED if there’s no history or mapping fails.
     */
    public IngestionStatus latestStatus(Instant asOf) {
        var row = (asOf == null)
                ? repo.findTopByOrderByChangedAtDesc()
                : repo.findTopByChangedAtLessThanEqualOrderByChangedAtDesc(asOf);

        return row
                .map(IngestionStatusHistoryEntity::getStatus) // could be enum or String
                .map(this::toApiStatusSafely)
                .orElse(IngestionStatus.UPLOADED);
    }

    private IngestionStatus toApiStatusSafely(Object statusValue) {
        if (statusValue == null) return IngestionStatus.UPLOADED;
        String name = statusValue.toString(); // handles enum.name() or raw String
        try {
            return IngestionStatus.valueOf(name);
        } catch (IllegalArgumentException ex) {
            // Unknown value in DB; be safe and degrade gracefully
            return IngestionStatus.UPLOADED;
        }
    }
}
