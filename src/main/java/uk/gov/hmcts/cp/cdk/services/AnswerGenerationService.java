package uk.gov.hmcts.cp.cdk.services;

import static uk.gov.hmcts.cp.cdk.util.TaskUtils.buildAnswerParams;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.buildCaseStatusParams;
import static uk.gov.hmcts.cp.cdk.util.TaskUtils.buildReservationParams;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerGenerationService {

    static final String NEXT_VERSION_SQL = """
            SELECT COALESCE(MAX(a.version), 0) + 1
            FROM answers a
            WHERE a.case_id = :case_id
              AND a.query_id = :query_id
            """;
    static final String SQL_UPSERT_ANSWER =
            "INSERT INTO answers(case_id, query_id, version, created_at, answer, llm_input, doc_id) " +
                    "VALUES (:case_id, :query_id, :version, NOW(), :answer, :llm_input, :doc_id) " +
                    "ON CONFLICT (case_id, query_id, version) DO UPDATE SET " +
                    "  answer = EXCLUDED.answer, " +
                    "  llm_input = EXCLUDED.llm_input, " +
                    "  doc_id = EXCLUDED.doc_id, " +
                    "  created_at = EXCLUDED.created_at";

    static final String SQL_UPDATE_CASE_QUERY_STATUS =
            "INSERT INTO case_query_status (case_id, query_id, status, status_at, doc_id, last_answer_version, last_answer_at)" +
                    "VALUES (:case_id, :query_id, 'ANSWER_AVAILABLE', NOW(), :doc_id, :version, NOW())" +
                    "ON CONFLICT (case_id, query_id) DO UPDATE SET" +
                    "  status = 'ANSWER_AVAILABLE'," +
                    "  status_at = EXCLUDED.status_at," +
                    "  last_answer_version = EXCLUDED.last_answer_version," +
                    "  last_answer_at = EXCLUDED.last_answer_at," +
                    "  doc_id = COALESCE(EXCLUDED.doc_id, case_query_status.doc_id);";
    
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Transactional
    public void upsertAnswer(final UUID caseId, final UUID queryId, final String answer,
                             final String llmInput, final UUID docId) {

        // 1. get version
        final Integer version = getVersionNumber(caseId, queryId);
        log.info("Next version={} found for the caseId={}, queryId={}", version, caseId, queryId);

        // 2. upsert answer
        final MapSqlParameterSource params = buildAnswerParams(caseId, queryId, version, answer, llmInput, docId);
        namedParameterJdbcTemplate.update(SQL_UPSERT_ANSWER, params);

        // 3. update case_query_status (replaces trigger)
        final MapSqlParameterSource statusParams = buildCaseStatusParams(caseId, queryId, docId, version);
        namedParameterJdbcTemplate.update(SQL_UPDATE_CASE_QUERY_STATUS, statusParams);
    }

    private Integer getVersionNumber(final UUID caseId, final UUID queryId) {
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId);
        return namedParameterJdbcTemplate.queryForObject(NEXT_VERSION_SQL, params, Integer.class);
    }
}
