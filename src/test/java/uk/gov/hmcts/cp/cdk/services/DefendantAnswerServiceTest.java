package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.GLOBAL_UPDATE_CASE_QUERY_STATUS;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DefendantAnswerServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @InjectMocks
    private DefendantAnswerService service;
    @Captor
    private ArgumentCaptor<MapSqlParameterSource> paramCaptor;

    private UUID caseId;
    private UUID queryId;
    private UUID defendantId;
    private UUID docId;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        queryId = UUID.randomUUID();
        defendantId = UUID.randomUUID();
        docId = UUID.randomUUID();
    }

    @Test
    void shouldUpsertSuccessfully() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), paramCaptor.capture(), eq(Integer.class))).thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        // when
        service.upsert(caseId, queryId, defendantId, "answer", "llmInput", docId);

        // then
        verify(jdbcTemplate).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class));
        verify(jdbcTemplate).update(contains("INSERT INTO defendant_answers"), any(MapSqlParameterSource.class));
        verify(jdbcTemplate).update(eq(GLOBAL_UPDATE_CASE_QUERY_STATUS), any(MapSqlParameterSource.class));
    }

    @Test
    void shouldUseCorrectParametersForUpsert() {

        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(5);
        final ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        // when
        service.upsert(caseId, queryId, defendantId, "myAnswer", "myInput", docId);

        // then
        verify(jdbcTemplate).update(contains("INSERT INTO defendant_answers"), captor.capture());

        final MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("case_id")).isEqualTo(caseId);
        assertThat(params.getValue("query_id")).isEqualTo(queryId);
        assertThat(params.getValue("defendant_id")).isEqualTo(defendantId);
        assertThat(params.getValue("version")).isEqualTo(5);
        assertThat(params.getValue("answer")).isEqualTo("myAnswer");
        assertThat(params.getValue("llm_input")).isEqualTo("myInput");
        assertThat(params.getValue("doc_id")).isEqualTo(docId);
    }

    @Test
    void shouldCallStatusUpdateAfterUpsert() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1);

        // when

        service.upsert(caseId, queryId, defendantId, "answer", "input", docId);

        // then
        final InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).update(contains("INSERT INTO defendant_answers"), any(MapSqlParameterSource.class));
        inOrder.verify(jdbcTemplate).update(eq(GLOBAL_UPDATE_CASE_QUERY_STATUS), any(MapSqlParameterSource.class));
    }

    @Test
    void shouldFetchNextVersionCorrectly() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(7);

        // when
        service.upsert(caseId, queryId, defendantId, "answer", "input", docId);

        // then
        final ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        verify(jdbcTemplate).queryForObject(contains("SELECT COALESCE(MAX(version)"), captor.capture(), eq(Integer.class));

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("case_id")).isEqualTo(caseId);
        assertThat(params.getValue("query_id")).isEqualTo(queryId);
        assertThat(params.getValue("defendant_id")).isEqualTo(defendantId);
    }

    @Test
    void shouldHandleNullDocId() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(2);

        // when
        service.upsert(caseId, queryId, defendantId, "answer", "input", null);

        // then
        final ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        verify(jdbcTemplate).update(contains("INSERT INTO defendant_answers"), captor.capture());

        final MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("doc_id")).isNull();
    }
}