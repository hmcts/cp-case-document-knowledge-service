package uk.gov.hmcts.cp.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.domain.QueryVersionEntity;
import uk.gov.hmcts.cp.openapi.model.QueryStatusResponse;
import uk.gov.hmcts.cp.repo.IngestionStatusHistoryRepository;
import uk.gov.hmcts.cp.repo.QueryVersionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Service
public class QueryReadService {

    private final QueryVersionRepository queryRepo;
    private final IngestionStatusHistoryRepository stateRepo; // kept for DI compatibility and future use
    private final Clock clock;

    public QueryReadService(final QueryVersionRepository queryRepo,
                            final IngestionStatusHistoryRepository stateRepo,
                            final Clock clock) {
        this.queryRepo = Objects.requireNonNull(queryRepo, "queryRepo must not be null");
        this.stateRepo = Objects.requireNonNull(stateRepo, "stateRepo must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Returns queries as of the provided time; if null, returns the latest snapshot.
     */
    @Transactional(readOnly = true)
    public QueryStatusResponse listQueries(final Instant asOf) {
        final Instant effective = (asOf != null) ? asOf : Instant.now(clock);

        final List<QueryVersionEntity> items = (asOf == null)
                ? queryRepo.findLatestAll()
                : queryRepo.findLatestAsOf(effective);

        return new QueryStatusResponse()
                .asOf(OffsetDateTime.ofInstant(effective, ZoneOffset.UTC))
                .queries(items.stream().map(QueryMapper::toSummary).toList());
    }
}
