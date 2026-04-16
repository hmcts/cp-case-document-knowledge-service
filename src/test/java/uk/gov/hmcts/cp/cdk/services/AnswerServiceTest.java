package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.domain.Answer;
import uk.gov.hmcts.cp.cdk.domain.AnswerId;
import uk.gov.hmcts.cp.cdk.domain.CaseLevelAllDocumentsAnswer;
import uk.gov.hmcts.cp.cdk.domain.CaseLevelLatestDocumentAnswer;
import uk.gov.hmcts.cp.cdk.domain.DefendantAnswer;
import uk.gov.hmcts.cp.cdk.domain.DefendantAnswerId;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryLevel;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.domain.QueryVersionId;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnswerServiceTest {

    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private QueryVersionRepository queryVersionRepository;
    @Mock
    private AnswerMapper mapper;
    @Mock
    private CaseLevelLatestDocumentAnswerRepository latestDocRepo;
    @Mock
    private CaseLevelAllDocumentsAnswerRepository allDocsRepo;
    @Mock
    private DefendantAnswerRepository defendantRepo;

    @InjectMocks
    private AnswerService service;

    private UUID queryId;
    private UUID caseId;
    private UUID defendantId;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        queryId = UUID.randomUUID();
        caseId = UUID.randomUUID();
        defendantId = UUID.randomUUID();
        now = OffsetDateTime.now();
    }

    @Test
    void getAnswer_shouldReturnMappedAnswer() {
        final UUID queryId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final Integer version = 1;
        OffsetDateTime createdAt = OffsetDateTime.now();

        final Answer answer = new Answer();
        answer.setCreatedAt(createdAt);

        final AnswerResponse response = new AnswerResponse();

        // Stub repository and mapper
        when(answerRepository.findByCaseAndVersion(caseId, queryId, version))
                .thenReturn(Optional.of(answer));

        final QueryVersion version1 = new QueryVersion();
        final QueryVersionId vid1 = new QueryVersionId();
        vid1.setEffectiveAt(createdAt.minusMinutes(1));
        version1.setQueryVersionId(vid1);
        version1.setQuery(new Query(queryId, "query-label", utcNow(), 1,true));
        version1.setUserQuery("user query text");

        when(queryVersionRepository.findAll()).thenReturn(List.of(version1));
        when(mapper.toAnswerResponse(answer, "user query text")).thenReturn(response);

        final AnswerResponse result = service.getAnswer(queryId, caseId, version, null);

        assertSame(response, result);
    }


    @Test
    void getAnswerWhenVersionIsNull_shouldReturnMappedAnswer() {
        final UUID queryId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final Integer version = null;
        final OffsetDateTime asOf = OffsetDateTime.now().minusMinutes(5);
        final OffsetDateTime createdAt = OffsetDateTime.now();

        final Answer answer = new Answer();
        answer.setCreatedAt(createdAt);

        final AnswerResponse response = new AnswerResponse();

        // Stub repository and mapper
        when(answerRepository.findLatestAsOfForCase(caseId, queryId, asOf)).thenReturn(Optional.of(answer));

        final QueryVersion version1 = new QueryVersion();
        final QueryVersionId vid1 = new QueryVersionId();
        vid1.setEffectiveAt(createdAt.minusMinutes(1));
        version1.setQueryVersionId(vid1);
        version1.setQuery(new Query(queryId, "query-label", utcNow(), 1,true));
        version1.setUserQuery("user query text");

        when(queryVersionRepository.findAll()).thenReturn(List.of(version1));
        when(mapper.toAnswerResponse(answer, "user query text")).thenReturn(response);


        final AnswerResponse result = service.getAnswer(queryId, caseId, version, asOf);

        assertSame(response, result);
    }

    @Test
    void getAnswer_shouldThrowNotFound_whenAnswerMissing() {
        final UUID queryId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();

        when(answerRepository.findByCaseAndVersion(caseId, queryId, 1))
                .thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getAnswer(queryId, caseId, 1, null));

        assertThat(ex.getStatusCode()).isEqualTo(NOT_FOUND);
        assertThat(ex.getReason().contains(queryId.toString())).isTrue();
    }

    @Test
    void getAnswer_shouldThrowIllegalArgumentException_whenMultipleCases() {
        final UUID queryId = UUID.randomUUID();

        when(answerRepository.countDistinctCasesForQuery(queryId)).thenReturn(5L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getAnswer(queryId, null, null, null));

        assertThat(ex.getMessage().contains("Multiple cases exist")).isTrue();
    }

    @Test
    void getAnswerWithLlm_shouldReturnMappedResponse() {
        final UUID queryId = UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();

        final Answer answer = new Answer();
        answer.setCreatedAt(OffsetDateTime.now());

        final AnswerWithLlmResponse response = new AnswerWithLlmResponse();

        when(answerRepository.findByCaseAndVersion(caseId, queryId, 1)).thenReturn(Optional.of(answer));
        when(queryVersionRepository.findAll()).thenReturn(List.of());
        when(mapper.toAnswerWithLlm(answer, "")).thenReturn(response);

        AnswerWithLlmResponse result = service.getAnswerWithLlm(queryId, caseId, 1, null);

        assertSame(response, result);
    }

    @Test
    void shouldThrow_whenQueryVersionNotFound() {
        when(queryVersionRepository.findLatestByQueryId(queryId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getAnswers(queryId, caseId, null, now));
    }

    @Test
    void shouldReturnAnswer_whenCaseLevel() {
        final QueryVersion queryVersion = mock(QueryVersion.class);
        final CaseLevelLatestDocumentAnswer answer = new CaseLevelLatestDocumentAnswer();
        answer.setAnswerId(new AnswerId(caseId, queryId, null));

        when(queryVersionRepository.findLatestByQueryId(queryId))
                .thenReturn(Optional.of(queryVersion));
        when(queryVersion.getLevel()).thenReturn(QueryLevel.CASE);

        when(latestDocRepo.findLatestAsOfForCase(any(), any(), any())).thenReturn(Optional.of(answer));

        // mapper
        when(mapper.toAnswerResponse(any(), any())).thenReturn(new AnswerResponse());

        final AnswersResponse response = service.getAnswers(queryId, caseId, null, now);

        assertThat(response).isNotNull();
        verify(latestDocRepo).findLatestAsOfForCase(eq(caseId), eq(queryId), any());
    }

    @Test
    void shouldReturnAnswer_whenCaseAllDocumentsLevel() {
        final QueryVersion queryVersion = mock(QueryVersion.class);
        final CaseLevelAllDocumentsAnswer answer = new CaseLevelAllDocumentsAnswer();
        answer.setAnswerId(new AnswerId(caseId, queryId, null));

        when(queryVersionRepository.findLatestByQueryId(queryId)).thenReturn(Optional.of(queryVersion));
        when(queryVersion.getLevel()).thenReturn(QueryLevel.CASE_ALL_DOCUMENTS);
        when(allDocsRepo.findLatestAsOfForCase(any(), any(), any())).thenReturn(Optional.of(answer));
        when(mapper.toAnswerResponse(any(), any())).thenReturn(new AnswerResponse());

        final AnswersResponse response = service.getAnswers(queryId, caseId, null, now);

        assertThat(response).isNotNull();
        verify(allDocsRepo).findLatestAsOfForCase(eq(caseId), eq(queryId), any());
    }

    @Test
    void shouldReturnAnswers_whenDefendantLevel() {
        final QueryVersion queryVersion = mock(QueryVersion.class);
        final DefendantAnswer defendantAnswer = new DefendantAnswer();
        defendantAnswer.setAnswerId(new DefendantAnswerId(caseId, queryId, defendantId, null));

        when(queryVersionRepository.findLatestByQueryId(queryId)).thenReturn(Optional.of(queryVersion));
        when(queryVersion.getLevel()).thenReturn(QueryLevel.DEFENDANT);
        when(defendantRepo.findAllAsOfForCase(any(), any(), any())).thenReturn(List.of(defendantAnswer));
        when(mapper.toAnswerResponse(any(), any())).thenReturn(new AnswerResponse());

        final AnswersResponse response = service.getAnswers(queryId, caseId, null, now);

        assertThat(response).isNotNull();
        verify(defendantRepo).findAllAsOfForCase(eq(caseId), eq(queryId), any());
    }

    @Test
    void shouldFallbackToResolveAnswer_whenNoAnswersFound() {
        final QueryVersion queryVersion = mock(QueryVersion.class);
        final Answer fallbackAnswer = new Answer();
        fallbackAnswer.setAnswerId(new AnswerId(caseId, queryId, null));

        when(queryVersionRepository.findLatestByQueryId(queryId)).thenReturn(Optional.of(queryVersion));
        when(queryVersion.getLevel()).thenReturn(QueryLevel.CASE);
        when(latestDocRepo.findLatestAsOfForCase(any(), any(), any())).thenReturn(Optional.empty());

        // spy to mock internal method
        final AnswerService spyService = spy(service);
        doReturn(fallbackAnswer).when(spyService).resolveAnswer(eq(queryId), eq(caseId), any(), any());

        when(mapper.toAnswerResponse(any(), any())).thenReturn(new AnswerResponse());

        final AnswersResponse response = spyService.getAnswers(queryId, caseId, null, now);

        assertThat(response).isNotNull();
        verify(spyService).resolveAnswer(eq(queryId), eq(caseId), any(), any());
    }
}