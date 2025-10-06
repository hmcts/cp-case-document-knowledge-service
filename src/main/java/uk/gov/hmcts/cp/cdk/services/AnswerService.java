package uk.gov.hmcts.cp.cdk.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.cp.cdk.domain.Answer;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.repo.AnswerRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryVersionRepository;
import uk.gov.hmcts.cp.cdk.services.mapper.AnswerMapper;
import uk.gov.hmcts.cp.cdk.util.TimeUtils;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerWithLlmResponse;

import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AnswerService {

    private static final long MULTIPLE_CASES_THRESHOLD = 1L;

    private final AnswerRepository answerRepository;
    private final QueryVersionRepository queryVersionRepository;
    private final AnswerMapper mapper;

    public AnswerService(
            final AnswerRepository answerRepository,
            final QueryVersionRepository queryVersionRepository,
            final AnswerMapper mapper
    ) {
        this.answerRepository = answerRepository;
        this.queryVersionRepository = queryVersionRepository;
        this.mapper = mapper;
    }

    public AnswerResponse getAnswer(
            final UUID queryId,
            final UUID caseIdOrNull,
            final Integer versionOrNull,
            final OffsetDateTime asOfOrNull
    ) {
        final Answer answerEntity = resolveAnswer(queryId, caseIdOrNull, versionOrNull, asOfOrNull);
        final String userQueryText = resolveUserQueryText(queryId, answerEntity.getCreatedAt());
        return mapper.toAnswerResponse(answerEntity, userQueryText);
    }

    public AnswerWithLlmResponse getAnswerWithLlm(
            final UUID queryId,
            final UUID caseIdOrNull,
            final Integer versionOrNull,
            final OffsetDateTime asOfOrNull
    ) {
        final Answer answerEntity = resolveAnswer(queryId, caseIdOrNull, versionOrNull, asOfOrNull);
        final String userQueryText = resolveUserQueryText(queryId, answerEntity.getCreatedAt());
        return mapper.toAnswerWithLlm(answerEntity, userQueryText);
    }

    private Answer resolveAnswer(
            final UUID queryId,
            final UUID caseIdOrNull,
            final Integer versionOrNull,
            final OffsetDateTime asOfOrNull
    ) {
        final OffsetDateTime asOf = Optional.ofNullable(asOfOrNull).orElse(TimeUtils.utcNow());

        final Optional<Answer> maybeAnswer;
        if (caseIdOrNull != null && versionOrNull != null) {
            maybeAnswer = answerRepository.findByCaseAndVersion(caseIdOrNull, queryId, versionOrNull);
        } else if (caseIdOrNull != null) {
            maybeAnswer = answerRepository.findLatestAsOfForCase(caseIdOrNull, queryId, asOf);
        } else {
            final long caseCount = answerRepository.countDistinctCasesForQuery(queryId);
            if (caseCount > MULTIPLE_CASES_THRESHOLD) {
                throw new IllegalArgumentException(
                        "Multiple cases exist for this queryId; supply caseId as query parameter to disambiguate."
                );
            }
            maybeAnswer = answerRepository.findLatestAsOfAnyCase(queryId, asOf);
        }

        return maybeAnswer.orElseThrow(() -> new NoSuchElementException("Answer not found"));
    }

    private String resolveUserQueryText(final UUID queryId, final OffsetDateTime createdAt) {
        // latest query definition at answer creation time
        return queryVersionRepository.findAll().stream()
                .filter(version -> version.getQuery().getQueryId().equals(queryId))
                .filter(version -> !version.getQueryVersionId().getEffectiveAt().isAfter(createdAt))
                .max(java.util.Comparator.comparing((QueryVersion v) -> v.getQueryVersionId().getEffectiveAt()))
                .map(QueryVersion::getUserQuery)
                .orElse("");
    }
}
