package uk.gov.hmcts.cp.cdk.services;


import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.cdk.services.AnswerGenerationService.NEXT_VERSION_SQL;
import static uk.gov.hmcts.cp.cdk.services.AnswerGenerationService.SQL_UPSERT_ANSWER;

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
public class AnswerGenerationServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @InjectMocks
    private AnswerGenerationService service;

    @Captor
    private ArgumentCaptor<MapSqlParameterSource> paramCaptor;

    @Captor
    private ArgumentCaptor<String> sqlCaptor;

    private UUID caseId;
    private UUID queryId;
    private UUID docId;

    @BeforeEach
    void setUp() {
        caseId = randomUUID();
        queryId = randomUUID();
        docId = randomUUID();
    }

    @Test
    void shouldCallVersionQueryAndThenUpsert() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(3);

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(1);

        // when
        service.upsertAnswer(caseId, queryId, "answer", "llmInput", docId);

        // then
        final InOrder inOrder = inOrder(jdbcTemplate);

        // 1. version query happens first
        inOrder.verify(jdbcTemplate).queryForObject(
                eq(NEXT_VERSION_SQL),
                any(MapSqlParameterSource.class),
                eq(Integer.class)
        );

        // 2. upsert happens next
        inOrder.verify(jdbcTemplate).update(
                eq(SQL_UPSERT_ANSWER),
                any(MapSqlParameterSource.class)
        );
    }

    @Test
    void shouldPassCorrectParamsToVersionQuery() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1);

        // when
        service.upsertAnswer(caseId, queryId, "answer", "llmInput", docId);

        // then
        verify(jdbcTemplate).queryForObject(anyString(), paramCaptor.capture(), eq(Integer.class));

        final MapSqlParameterSource params = paramCaptor.getValue();

        assertThat(params.getValue("caseId")).isEqualTo(caseId);
        assertThat(params.getValue("queryId")).isEqualTo(queryId);
        assertThat(params.getValue("docId")).isEqualTo(docId);
    }

    @Test
    void shouldPassCorrectParamsToUpsert() {
        // given
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(5);

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(1);

        // when
        service.upsertAnswer(caseId, queryId, "my-answer", "my-llm-input", docId);

        // then
        verify(jdbcTemplate).update(eq(SQL_UPSERT_ANSWER), paramCaptor.capture());

        final MapSqlParameterSource params = paramCaptor.getValue();

        assertThat(params.getValue("case_id")).isEqualTo(caseId);
        assertThat(params.getValue("query_id")).isEqualTo(queryId);
        assertThat(params.getValue("doc_id")).isEqualTo(docId);
        assertThat(params.getValue("answer")).isEqualTo("my-answer");
        assertThat(params.getValue("llm_input")).isEqualTo("my-llm-input");
        assertThat(params.getValue("version")).isEqualTo(5);
    }

    @Test
    void shouldPropagateException_whenVersionQueryFails() {
        // given
        when(jdbcTemplate.queryForObject(eq(NEXT_VERSION_SQL), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenThrow(new RuntimeException("DB error"));

        // when & then
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.upsertAnswer(caseId, queryId, "a", "b", docId));

        assertThat(ex.getMessage()).isEqualTo("DB error");

        // ensure upsert never called
        verify(jdbcTemplate, never()).update(eq(SQL_UPSERT_ANSWER), any(MapSqlParameterSource.class));
    }

    @Test
    void shouldPropagateException_whenUpsertFails() {
        // given
        when(jdbcTemplate.queryForObject(eq(NEXT_VERSION_SQL), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(2);

        when(jdbcTemplate.update(eq(SQL_UPSERT_ANSWER), any(MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("Insert failed"));

        // when & then
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.upsertAnswer(caseId, queryId, "a", "b", docId));

        assertThat(ex.getMessage()).isEqualTo("Insert failed");
    }

    @Test
    void shouldUseCorrectSqlStatements() {
        // given
        when(jdbcTemplate.queryForObject(sqlCaptor.capture(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1);

        when(jdbcTemplate.update(eq(SQL_UPSERT_ANSWER), any(MapSqlParameterSource.class))).thenReturn(1);

        // when
        service.upsertAnswer(caseId, queryId, "answer", "llmInput", docId);

        // then
        String versionSql = sqlCaptor.getValue();

        assertThat(versionSql.contains("MAX(a.version)")).isTrue();
        assertThat(versionSql.contains("a.case_id = :caseId")).isTrue();
        assertThat(versionSql.contains("a.query_id = :queryId")).isTrue();
        assertThat(versionSql.contains("a.doc_id = :docId")).isTrue();

        verify(jdbcTemplate).update(eq(SQL_UPSERT_ANSWER), any(MapSqlParameterSource.class));
    }
}