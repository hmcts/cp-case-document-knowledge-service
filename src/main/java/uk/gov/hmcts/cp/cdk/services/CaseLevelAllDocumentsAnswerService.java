package uk.gov.hmcts.cp.cdk.services;


import static uk.gov.hmcts.cp.cdk.util.TaskUtils.GLOBAL_UPDATE_CASE_QUERY_STATUS;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CaseLevelAllDocumentsAnswerService {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String NEXT_VERSION_SQL = """
        SELECT COALESCE(MAX(version), 0) + 1
        FROM case_level_all_documents_answers
        WHERE case_id = :case_id
          AND query_id = :query_id
    """;

    private static final String UPSERT_SQL = """
        INSERT INTO case_level_all_documents_answers
        (case_id, query_id, version, created_at, answer, llm_input)
        VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input)
        ON CONFLICT (case_id, query_id, version) DO UPDATE SET
            answer = EXCLUDED.answer,
            llm_input = EXCLUDED.llm_input,
            created_at = EXCLUDED.created_at
    """;

    @Transactional
    public void upsert(final UUID caseId, final UUID queryId, final String answer, final String llmInput) {

        final int version = getVersionNumber(caseId, queryId);

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("version", version)
                .addValue("answer", answer)
                .addValue("llm_input", llmInput);

        jdbc.update(UPSERT_SQL, params);

        final MapSqlParameterSource statusParams = new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("version", version)
                .addValue("doc_id", null);
        jdbc.update(GLOBAL_UPDATE_CASE_QUERY_STATUS, statusParams);
    }

    private Integer getVersionNumber(final UUID caseId, final UUID queryId) {
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId);
        return jdbc.queryForObject(NEXT_VERSION_SQL, params, Integer.class);
    }
}