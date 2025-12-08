package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static uk.gov.hmcts.cp.cdk.util.TimeUtils.utcNow;

import uk.gov.hmcts.cp.cdk.domain.Answer;
import uk.gov.hmcts.cp.cdk.domain.Query;
import uk.gov.hmcts.cp.cdk.domain.QueryVersion;
import uk.gov.hmcts.cp.cdk.domain.QueryVersionId;
import uk.gov.hmcts.cp.cdk.repo.AnswerRepository;
import uk.gov.hmcts.cp.cdk.repo.QueryVersionRepository;
import uk.gov.hmcts.cp.cdk.services.mapper.AnswerMapper;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerResponse;
import uk.gov.hmcts.cp.openapi.model.cdk.AnswerWithLlmResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AnswerServiceTest {

    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private QueryVersionRepository queryVersionRepository;
    @Mock
    private AnswerMapper mapper;

    @InjectMocks
    private AnswerService service;

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
        version1.setQuery(new Query(queryId, "query-label", utcNow(), 1));
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
        version1.setQuery(new Query(queryId, "query-label", utcNow(), 1));
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
}