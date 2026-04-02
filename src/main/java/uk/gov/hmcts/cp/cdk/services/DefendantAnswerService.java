package uk.gov.hmcts.cp.cdk.services;

import static uk.gov.hmcts.cp.cdk.util.TaskUtils.GLOBAL_UPDATE_CASE_QUERY_STATUS;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.buildCaseStatusParams;

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
public class DefendantAnswerService {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String NEXT_VERSION_SQL = """
        SELECT COALESCE(MAX(version), 0) + 1
        FROM defendant_answers
        WHERE case_id = :case_id
          AND query_id = :query_id
          AND defendant_id = :defendant_id
    """;

    private static final String UPSERT_SQL = """
        INSERT INTO defendant_answers
        (case_id, query_id, defendant_id, version, created_at, answer, llm_input, doc_id)
        VALUES (:case_id, :query_id, :defendant_id, :version, NOW(), :answer, :llm_input, :doc_id)
        ON CONFLICT (case_id, query_id, defendant_id, version) DO UPDATE SET
            answer = EXCLUDED.answer,
            llm_input = EXCLUDED.llm_input,
            doc_id = EXCLUDED.doc_id,
            created_at = EXCLUDED.created_at
    """;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Transactional
    public void upsert(UUID caseId, UUID queryId, UUID defendantId,
                       String answer, String llmInput, UUID docId) {

        int version = getVersionNumberWithDefendentId(caseId, queryId, defendantId);

        var params = new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("defendant_id", defendantId)
                .addValue("version", version)
                .addValue("answer", answer)
                .addValue("llm_input", llmInput)
                .addValue("doc_id", docId);

        jdbc.update(UPSERT_SQL, params);

        final MapSqlParameterSource statusParams = buildCaseStatusParams(caseId, queryId, docId, version);
        jdbc.update(GLOBAL_UPDATE_CASE_QUERY_STATUS, statusParams);

    }

    private Integer getVersionNumberWithDefendentId(final UUID caseId, final UUID queryId, final UUID defendantId) {
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("defendant_id", defendantId);

        return namedParameterJdbcTemplate.queryForObject(NEXT_VERSION_SQL, params, Integer.class);
    }
}
