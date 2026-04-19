package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class CaseLevelAllDocumentsAnswerServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private CaseLevelAllDocumentsAnswerService service;

    private UUID caseId;
    private UUID queryId;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        queryId = UUID.randomUUID();
    }

    @Test
    void shouldUpsertSuccessfully() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(2);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        // when
        service.upsert(caseId, queryId, "answer", "llm-input");

        // then
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class));
        verify(jdbcTemplate, times(2)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void shouldPassCorrectParametersToUpsertQuery() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(5);
        final ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        // when
        service.upsert(caseId, queryId, "ans", "llm");

        // then
        verify(jdbcTemplate).update(contains("INSERT INTO case_level_all_documents_answers"), captor.capture());

        final MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("case_id")).isEqualTo(caseId);
        assertThat(params.getValue("query_id")).isEqualTo(queryId);
        assertThat(params.getValue("version")).isEqualTo(5);
        assertThat(params.getValue("answer")).isEqualTo("ans");
        assertThat(params.getValue("llm_input")).isEqualTo("llm");
    }

    @Test
    void shouldCallStatusUpdateAfterUpsert() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1);

        // when
        service.upsert(caseId, queryId, "answer", "input");

        // then
        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).update(contains("INSERT INTO case_level_all_documents_answers"), any(MapSqlParameterSource.class));
        inOrder.verify(jdbcTemplate).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void shouldThrowNPEIfVersionIsNull() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(null);

        // when / then
        assertThrows(NullPointerException.class, () -> service.upsert(caseId, queryId, "a", "b"));
    }

    @Test
    void shouldCallVersionQueryWithCorrectParams() {

        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1);
        final ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

        // when
        service.upsert(caseId, queryId, "a", "b");

        // then
        verify(jdbcTemplate).queryForObject(contains("SELECT COALESCE(MAX(version)"), captor.capture(), eq(Integer.class));

        final MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("case_id")).isEqualTo(caseId);
        assertThat(params.getValue("query_id")).isEqualTo(queryId);
    }
}