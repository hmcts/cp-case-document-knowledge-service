package uk.gov.hmcts.cp.cdk.services;

import static uk.gov.hmcts.cp.cdk.util.TaskUtils.buildAnswerParams;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnswerGenerationService {

    static final String NEXT_VERSION_SQL = """
            SELECT COALESCE(MAX(a.version), 0) + 1
            FROM answers a
            WHERE a.case_id = :caseId
              AND a.query_id = :queryId
              AND a.doc_id = :docId
            """;
    static final String SQL_UPSERT_ANSWER =
            "INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) " +
                    "VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) " +
                    "ON CONFLICT (case_id, query_id, version) DO UPDATE SET " +
                    "  answer = EXCLUDED.answer, " +
                    "  llm_input = EXCLUDED.llm_input, " +
                    "  doc_id = EXCLUDED.doc_id, " +
                    "  created_at = EXCLUDED.created_at";

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Transactional
    public void upsertAnswer(final UUID caseId, final UUID queryId, final String answer,
                             final String llmInput, final UUID docId) {

        final Integer version = getVersionNumber(caseId, queryId, docId);
        final MapSqlParameterSource params = buildAnswerParams(caseId, queryId, version, answer, llmInput, docId);

        namedParameterJdbcTemplate.update(SQL_UPSERT_ANSWER, params);
    }

    private Integer getVersionNumber(final UUID caseId, final UUID queryId, final UUID docId) {

        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("caseId", caseId)
                .addValue("queryId", queryId)
                .addValue("docId", docId);

        return namedParameterJdbcTemplate.queryForObject(NEXT_VERSION_SQL, params, Integer.class);
    }
}
