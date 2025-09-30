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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class QueryWriteService {

    private final QueryRepository queryRepo;
    private final QueryVersionRepository qvRepo;
    private final Clock clock;

    public QueryWriteService(QueryRepository queryRepo,
                             QueryVersionRepository qvRepo,
                             Clock clock) {
        this.queryRepo = queryRepo;
        this.qvRepo = qvRepo;
        this.clock = clock;
    }

    /**
     * Upsert a batch of queries effective at the provided timestamp.
     * Returns an echo QueryStatusResponse (as-of the effective timestamp) containing the submitted queries.
     */
    @Transactional
    public QueryStatusResponse upsertQueries(QueryUpsertRequest body) {
        final OffsetDateTime effectiveAtOffset = (body.getEffectiveAt() != null)
                ? body.getEffectiveAt()
                : OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        final Instant effective = effectiveAtOffset.toInstant();

        // persist each provided query/version
        for (var q : body.getQueries()) {
            UUID qid = (q.getQueryId() != null) ? q.getQueryId() : UUID.randomUUID();

            // ensure canonical queries row exists
            queryRepo.findById(qid).orElseGet(() ->
                    queryRepo.save(new QueryEntity(qid, Instant.now(clock)))
            );

            // map API enum -> domain enum if provided
            IngestionStatus domainStatus = IngestionStatus.UPLOADED;
            if (q.getStatus() != null) {
                // OpenAPI generated enum has same names; safe mapping via name()
                domainStatus = IngestionStatus.valueOf(q.getStatus().name());
            }

            // build and save QueryVersionEntity
            QueryVersionKey key = new QueryVersionKey(qid, effective);
            QueryVersionEntity entity = new QueryVersionEntity(key, q.getUserQuery(), q.getQueryPrompt(), domainStatus);
            qvRepo.save(entity);
        }

        // Build echo response
        QueryStatusResponse response = new QueryStatusResponse().asOf(effectiveAtOffset);

        List<QuerySummary> echo = body.getQueries().stream()
                .map(s -> new QuerySummary()
                        .queryId(s.getQueryId())
                        .userQuery(s.getUserQuery())
                        .queryPrompt(s.getQueryPrompt())
                        .status(s.getStatus()))
                .collect(Collectors.toList());

        response.queries(echo);
        return response;
    }
}
