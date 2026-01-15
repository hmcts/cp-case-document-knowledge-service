package uk.gov.hmcts.cp.cdk.services;

import static java.util.Objects.isNull;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.toUtc;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.domain.QueryVersionId;
import uk.gov.hmcts.cp.cdk.repo.CaseDocumentRepository;
import uk.gov.hmcts.cp.cdk.repo.QueriesAsOfRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryVersionRepository;
import uk.gov.hmcts.cp.cdk.services.mapper.QueryMapper;
import uk.gov.hmcts.cp.cdk.util.TimeUtils;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryDefinitionsResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryLifecycleStatus;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryStatusResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.QuerySummary;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryUpsertRequest;
import uk.gov.hmcts.cp.openapi.model.cdk.QueryVersionSummary;
import uk.gov.hmcts.cp.openapi.model.cdk.Scope;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Query read/write operations with as-of semantics and versioned definitions.
 */
@Service
@Slf4j
public class QueryService {

    private final QueryRepository queryRepository;
    private final QueryVersionRepository queryVersionRepository;
    private final QueriesAsOfRepository queriesAsOfRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final QueryMapper mapper;
    private final ProgressionClient progressionClient;

    public QueryService(
            final QueryRepository queryRepository,
            final QueryVersionRepository queryVersionRepository,
            final QueriesAsOfRepository queriesAsOfRepository,
            final CaseDocumentRepository caseDocumentRepository,
            final QueryMapper mapper,
            final ProgressionClient progressionClient
    ) {
        this.queryRepository = queryRepository;
        this.queryVersionRepository = queryVersionRepository;
        this.queriesAsOfRepository = queriesAsOfRepository;
        this.caseDocumentRepository = caseDocumentRepository;
        this.mapper = mapper;
        this.progressionClient = progressionClient;
    }

    /* ---------- helpers (use util) ---------- */

    private static QuerySummary mapDefinitionRowToSummary(final QueryVersionRepository.SnapshotDefinition row) {
        final QuerySummary querySummary = new QuerySummary();
        querySummary.setQueryId(row.queryId());
        querySummary.setLabel(row.label());
        querySummary.setUserQuery(row.userQuery());
        querySummary.setQueryPrompt(row.queryPrompt());
        querySummary.setEffectiveAt(toUtc(row.effectiveAt()));
        return querySummary;
    }

    private static QuerySummary mapCaseRowToSummary(final QueriesAsOfRepository.QueryAsOfView row) {
        final QuerySummary querySummary = new QuerySummary();
        querySummary.setQueryId(row.queryId());
        querySummary.setCaseId(row.caseId());
        querySummary.setLabel(row.label());
        querySummary.setUserQuery(row.userQuery());
        querySummary.setQueryPrompt(row.queryPrompt());
        querySummary.setEffectiveAt(toUtc(row.effectiveAt()));

        // status can be null; default it safely
        querySummary.setStatus(isNull(row.status())
                ? QueryLifecycleStatus.ANSWER_NOT_AVAILABLE
                : QueryLifecycleStatus.fromValue(row.status()));
        return querySummary;
    }

    private static QueryVersionSummary mapDefinitionRowToVersionSummary(final QueryVersionRepository.SnapshotDefinition row) {
        final QueryVersionSummary summary = new QueryVersionSummary();
        summary.setQueryId(row.queryId());
        summary.setLabel(row.label());
        summary.setUserQuery(row.userQuery());
        summary.setQueryPrompt(row.queryPrompt());
        summary.setEffectiveAt(toUtc(row.effectiveAt()));
        return summary;
    }

    /* ---------- list (with or without case) ---------- */

    @Transactional(readOnly = true)
    public QueryStatusResponse listForCaseAsOf(final UUID caseId, final OffsetDateTime asOfParam, final String userId) {
        final OffsetDateTime asOf = Optional.ofNullable(asOfParam).orElseGet(TimeUtils::utcNow);
        final QueryStatusResponse response = new QueryStatusResponse().asOf(asOf);

        final List<QuerySummary> summaries;
        if (caseId == null) {
            final List<QueryVersionRepository.SnapshotDefinition> rows = queryVersionRepository.snapshotDefinitionsAsOf(asOf);
            summaries = rows.stream().map(QueryService::mapDefinitionRowToSummary).toList();
        } else {
            final List<QueriesAsOfRepository.QueryAsOfView> queryAsOfViewRows = queriesAsOfRepository.listForCaseAsOf(caseId, asOf);
            summaries = queryAsOfViewRows.stream().map(QueryService::mapCaseRowToSummary).toList();

            //Retrieval of casedocument to populate isIdpcAvailable info as part of DD-40778
            final Optional<LatestMaterialInfo> courtDocuments = progressionClient.getCourtDocuments(caseId, userId);
            log.info("courtDocuments retrieved  for : . caseId={} ",
                    caseId);
            final boolean isIdpcAvailable = courtDocuments
                    .map(LatestMaterialInfo::caseIds)
                    .map(ids -> ids.stream().anyMatch(id -> id.equals(caseId.toString())))
                    .orElse(false);

            final Scope scope = new Scope();
            scope.setCaseId(caseId);
            scope.setIsIdpcAvailable(isIdpcAvailable);
            response.setScope(scope);

        }

        response.setQueries(summaries);
        return response;
    }

    @Transactional(readOnly = true)
    public QuerySummary getOneForCaseAsOf(final UUID caseId,
                                          final UUID queryId,
                                          final OffsetDateTime asOfParam) {
        final OffsetDateTime asOf = Optional.ofNullable(asOfParam).orElseGet(TimeUtils::utcNow);

        try {
            final QueriesAsOfRepository.QueryAsOfView oneForCaseAsOf = queriesAsOfRepository.getOneForCaseAsOf(caseId, queryId, asOf);

            if (oneForCaseAsOf == null) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Query not found for case=" + caseId + ", queryId=" + queryId
                );
            }

            return mapCaseRowToSummary(oneForCaseAsOf);
        } catch (IncorrectResultSizeDataAccessException e) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Query not found for case=" + caseId + ", queryId=" + queryId,
                    e
            );
        }
    }

    /* ---------- upsert definitions ---------- */

    @Transactional
    public QueryDefinitionsResponse upsertDefinitions(final QueryUpsertRequest request) {
        if (request == null || request.getQueries() == null || request.getQueries().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "queries list must not be empty");
        }

        final OffsetDateTime effectiveAt = Optional.ofNullable(request.getEffectiveAt()).orElseGet(TimeUtils::utcNow);

        request.getQueries().forEach(item -> {
            final UUID queryId = item.getQueryId();
            if (queryId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "queryId must not be null");
            }
            final Query query = queryRepository.findById(queryId).orElseThrow(
                    () -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Unknown queryId " + queryId + " (seed the catalogue first)"
                    )
            );

            final String userQuery = item.getUserQuery();
            if (userQuery == null || userQuery.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userQuery must not be blank for " + queryId);
            }
            final String queryPrompt = item.getQueryPrompt();
            if (queryPrompt == null || queryPrompt.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "queryPrompt must not be blank for " + queryId);
            }

            final QueryVersion version = new QueryVersion();
            version.setQuery(query);
            version.setQueryVersionId(new QueryVersionId(queryId, effectiveAt));
            version.setUserQuery(userQuery);
            version.setQueryPrompt(queryPrompt);
            queryVersionRepository.save(version);
        });

        final List<QueryVersionRepository.SnapshotDefinition> rows = queryVersionRepository.snapshotDefinitionsAsOf(effectiveAt);
        final List<QueryVersionSummary> versions = rows.stream()
                .map(QueryService::mapDefinitionRowToVersionSummary)
                .toList();

        return new QueryDefinitionsResponse().asOf(effectiveAt).queries(versions);
    }

    /* ---------- version list ---------- */

    @Transactional(readOnly = true)
    public List<QueryVersionSummary> listVersions(final UUID queryId) {
        final Query query = queryRepository.findById(Objects.requireNonNull(queryId, "queryId must not be null"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Query not found: " + queryId));

        return queryVersionRepository.findAllVersions(queryId)
                .stream()
                .map(version -> mapper.toVersionSummary(query, version))
                .toList();
    }


}
