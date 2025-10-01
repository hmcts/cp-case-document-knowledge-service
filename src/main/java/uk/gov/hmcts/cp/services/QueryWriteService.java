package uk.gov.hmcts.cp.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.domain.IngestionStatus;
import uk.gov.hmcts.cp.domain.QueryEntity;
import uk.gov.hmcts.cp.domain.QueryVersionEntity;
import uk.gov.hmcts.cp.domain.QueryVersionKey;
import uk.gov.hmcts.cp.openapi.model.QueryStatusResponse;
import uk.gov.hmcts.cp.openapi.model.QuerySummary;
import uk.gov.hmcts.cp.openapi.model.QueryUpsertRequest;
import uk.gov.hmcts.cp.repo.QueryRepository;
import uk.gov.hmcts.cp.repo.QueryVersionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
/**
 * Service responsible for persisting query versions (write path).
 *
 * Suppress a couple of PMD rules here where the code legitimately creates one
 * entity per input (instantiating inside a loop). The changes are intentional
 * and necessary to persist each incoming version.
 */
@Service
@SuppressWarnings({
        "PMD.AvoidInstantiatingObjectsInLoops",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.UseExplicitType"
})
public class QueryWriteService {

    private final QueryRepository queryRepo;
    private final QueryVersionRepository qvRepo;
    private final Clock clock;

    public QueryWriteService(final QueryRepository queryRepo,
                             final QueryVersionRepository qvRepo,
                             final Clock clock) {
        this.queryRepo = Objects.requireNonNull(queryRepo, "queryRepo must not be null");
        this.qvRepo = Objects.requireNonNull(qvRepo, "qvRepo must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Upsert a batch of queries effective at the provided timestamp.
     * Returns an echo QueryStatusResponse (as-of the effective timestamp) containing the submitted queries.
     */
    @Transactional
    public QueryStatusResponse upsertQueries(final QueryUpsertRequest body) {
        final OffsetDateTime effectiveAtOffset = (body.getEffectiveAt() != null)
                ? body.getEffectiveAt()
                : OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        final Instant effective = effectiveAtOffset.toInstant();

        // persist each provided query/version
        final List<QuerySummary> summaries = body.getQueries();
        for (final QuerySummary q : summaries) {
            final UUID qid = (q.getQueryId() != null) ? q.getQueryId() : UUID.randomUUID();

            // ensure canonical queries row exists
            queryRepo.findById(qid).orElseGet(() ->
                    queryRepo.save(new QueryEntity(qid, Instant.now(clock)))
            );

            // map API enum -> domain enum if provided
            IngestionStatus domainStatus = IngestionStatus.UPLOADED;
            if (q.getStatus() != null) {
                domainStatus = IngestionStatus.valueOf(q.getStatus().name());
            }

            // build and save QueryVersionEntity (one-per-input)
            final QueryVersionKey key = new QueryVersionKey(qid, effective);
            final QueryVersionEntity entity = new QueryVersionEntity(key, q.getUserQuery(), q.getQueryPrompt(), domainStatus);
            qvRepo.save(entity);
        }

        // Build echo response
        final QueryStatusResponse response = new QueryStatusResponse().asOf(effectiveAtOffset);

        final List<QuerySummary> echo = new ArrayList<>(body.getQueries().size());
        for (final QuerySummary s : body.getQueries()) {
            final QuerySummary copy = new QuerySummary()
                    .queryId(s.getQueryId())
                    .userQuery(s.getUserQuery())
                    .queryPrompt(s.getQueryPrompt())
                    .status(s.getStatus());
            echo.add(copy);
        }

        response.queries(echo);
        return response;
    }
}
