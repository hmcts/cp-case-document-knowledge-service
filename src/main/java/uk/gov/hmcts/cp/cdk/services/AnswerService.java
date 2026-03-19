package uk.gov.hmcts.cp.cdk.services;

import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.domain.Answer;
import uk.gov.hmcts.cp.cdk.domain.CaseLevelAllDocumentsAnswer;
import uk.gov.hmcts.cp.cdk.domain.CaseLevelLatestDocumentAnswer;
import uk.gov.hmcts.cp.cdk.domain.DefendantAnswer;
import uk.gov.hmcts.cp.cdk.domain.QueryLevel;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.repo.AnswerRepository;
import uk.gov.hmcts.cp.cdk.repo.CaseLevelAllDocumentsAnswerRepository;
import uk.gov.hmcts.cp.cdk.repo.CaseLevelLatestDocumentAnswerRepository;
import uk.gov.hmcts.cp.cdk.repo.DefendantAnswerRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryVersionRepository;
import uk.gov.hmcts.cp.cdk.services.mapper.AnswerMapper;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerWithLlmResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswersResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AnswerService {

    private static final long MULTIPLE_CASES_THRESHOLD = 1L;

    private final AnswerRepository answerRepository;
    private final QueryVersionRepository queryVersionRepository;
    private final AnswerMapper mapper;

    private final CaseLevelLatestDocumentAnswerRepository latestDocRepo;
    private final CaseLevelAllDocumentsAnswerRepository allDocsRepo;
    private final DefendantAnswerRepository defendantRepo;



    public AnswerService(
            final AnswerRepository answerRepository,
            final QueryVersionRepository queryVersionRepository,
            final AnswerMapper mapper,
            CaseLevelLatestDocumentAnswerRepository latestDocRepo,
            CaseLevelAllDocumentsAnswerRepository allDocsRepo,
            DefendantAnswerRepository defendantRepo

    ) {
        this.answerRepository = answerRepository;
        this.queryVersionRepository = queryVersionRepository;
        this.mapper = mapper;
        this.latestDocRepo = latestDocRepo;
        this.allDocsRepo = allDocsRepo;
        this.defendantRepo = defendantRepo;
    }

    public AnswersResponse getAnswers(UUID queryId, UUID caseId, Integer version, OffsetDateTime at) {

        QueryVersion latest = queryVersionRepository.findLatestByQueryId(queryId)
                .orElseThrow(() -> new IllegalArgumentException("No QueryVersion found for queryId " + queryId));

        QueryLevel level = latest.getLevel();

        List<?> answers;
        switch (level) {
            case CASE:
                answers = latestDocRepo.findLatestAsOfForCase(caseId, queryId, at)
                        .map(List::of)
                        .orElseGet(List::of);
                break;

            case CASE_ALL_DOCUMENTS:
                answers = allDocsRepo.findLatestAsOfForCase(caseId, queryId, at)
                        .map(List::of)
                        .orElseGet(List::of);
                break;

            case DEFENDANT:
                answers = defendantRepo.findAllAsOfForCase(caseId, queryId, at);
                break;

            default:
                throw new IllegalArgumentException("Unsupported QueryLevel: " + level);
        }

        List<AnswerResponse> answerResponses = mapToAnswerResponses(answers);

        return new AnswersResponse(at,answerResponses);
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
        final OffsetDateTime asOf = Optional.ofNullable(asOfOrNull).orElse(utcNow());

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
        return maybeAnswer.orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Query not found for case=" + caseIdOrNull + ", queryId=" + queryId
                )
        );
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


    private List<AnswerResponse> mapToAnswerResponses(List<?> answers) {
        return answers.stream()
                .map(answer -> {
                    UUID queryId;
                    OffsetDateTime createdAt;
                    String answerText;
                    Integer version;
                    UUID defendantId = null;
                    String status = null;

                    if (answer instanceof CaseLevelAllDocumentsAnswer caseAnswer) {
                        queryId = caseAnswer.getAnswerId().getQueryId();
                        createdAt = caseAnswer.getCreatedAt();
                        answerText = caseAnswer.getAnswerText();
                        version = caseAnswer.getAnswerId().getVersion();
                    } else if (answer instanceof CaseLevelLatestDocumentAnswer latestAnswer) {
                        queryId = latestAnswer.getAnswerId().getQueryId();
                        createdAt = latestAnswer.getCreatedAt();
                        answerText = latestAnswer.getAnswerText();
                        version = latestAnswer.getAnswerId().getVersion();
                    } else if (answer instanceof DefendantAnswer defAnswer) {
                        queryId = defAnswer.getAnswerId().getQueryId();
                        createdAt = defAnswer.getCreatedAt();
                        answerText = defAnswer.getAnswerText();
                        version = defAnswer.getAnswerId().getVersion();
                        defendantId = defAnswer.getAnswerId().getDefendantId();
                    } else {
                        throw new IllegalArgumentException("Unknown answer type: " + answer.getClass());
                    }

                    String userQueryText = resolveUserQueryText(queryId, createdAt);

                    AnswerResponse answerRes = new AnswerResponse();
                    answerRes.setQueryId(queryId);
                    answerRes.setCreatedAt(createdAt);
                    answerRes.setAnswer(answerText);
                    answerRes.setVersion(version);
                    answerRes.setDefendantId(defendantId.toString());
                    //dto.setStatus(status);
                    answerRes.setUserQuery(userQueryText);

                    return answerRes;
                })
                .toList();
    }
}
