package uk.gov.hmcts.cp.cdk.util;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;
import uk.gov.hmcts.cp.cdk.domain.QueryLevel;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

@Slf4j
@UtilityClass
public class TaskUtils {

    public static final String EMPTY_STRING = "";

    public static final String GLOBAL_UPDATE_CASE_QUERY_STATUS =
            "INSERT INTO case_query_status (case_id, query_id, status, status_at, doc_id, last_answer_version, last_answer_at)" +
                    "VALUES (:case_id, :query_id, 'ANSWER_AVAILABLE', NOW(), :doc_id, :version, NOW())" +
                    "ON CONFLICT (case_id, query_id) DO UPDATE SET" +
                    "  status = 'ANSWER_AVAILABLE'," +
                    "  status_at = EXCLUDED.status_at," +
                    "  last_answer_version = EXCLUDED.last_answer_version," +
                    "  last_answer_at = EXCLUDED.last_answer_at," +
                    "  doc_id = COALESCE(EXCLUDED.doc_id, case_query_status.doc_id);";

    // ---------- UUID helpers ----------

    public static Optional<UUID> parseUuid(final String uuidString) {
        Optional<UUID> result;
        if (uuidString == null || uuidString.isBlank()) {
            result = Optional.empty();
        } else {
            try {
                result = Optional.of(UUID.fromString(uuidString));
            } catch (IllegalArgumentException exception) {
                log.warn("Invalid UUID '{}'", uuidString);
                result = Optional.empty();
            }
        }
        return result;
    }

    public static UUID parseUuidOrNull(final String uuidString) {
        return parseUuid(uuidString).orElse(null);
    }

    // ---------- Date helpers ----------

    /**
     * Parses ISO-8601 date (yyyy-MM-dd). Empty if null/blank/invalid; logs on invalid.
     */
    public static Optional<LocalDate> parseIsoDate(final String dateString) {
        Optional<LocalDate> result;
        if (dateString == null || dateString.isBlank()) {
            result = Optional.empty();
        } else {
            try {
                result = Optional.of(LocalDate.parse(dateString));
            } catch (DateTimeParseException ex) {
                log.warn("Invalid ISO date '{}'", dateString, ex);
                result = Optional.empty();
            }
        }
        return result;
    }

    /**
     * Null-returning companion to {@link #parseIsoDate(String)}.
     */
    public static LocalDate parseIsoDateOrNull(final String dateString) {
        return parseIsoDate(dateString).orElse(null);
    }

    // ---------- JDBC param builders ----------

    public static MapSqlParameterSource buildReservationParams(final UUID caseId,
                                                               final UUID queryId,
                                                               final UUID documentId) {
        return new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("doc_id", documentId);
    }

    public static MapSqlParameterSource buildAnswerParams(final UUID caseId,
                                                          final UUID queryId,
                                                          final Integer version,
                                                          final String answer,
                                                          final String llmInput,
                                                          final UUID documentId) {
        return new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("version", version)
                .addValue("answer", answer)
                .addValue("llm_input", llmInput)
                .addValue("doc_id", documentId);
    }

    public static MapSqlParameterSource buildCaseStatusParams(final UUID caseId,
                                                              final UUID queryId,
                                                              final UUID documentId,
                                                              final Integer version
    ) {
        return new MapSqlParameterSource()
                .addValue("case_id", caseId)
                .addValue("query_id", queryId)
                .addValue("doc_id", documentId)
                .addValue("version", version);
    }

    // ---------- Safe external lookups ----------

    public static Optional<LatestMaterialInfo> getCourtDocuments(final ProgressionClient progressionClient,
                                                                 final UUID caseId,
                                                                 final String userId) {
        Optional<LatestMaterialInfo> result;
        try {
            result = progressionClient.getCourtDocuments(caseId, userId);
        } catch (Exception exception) {
            log.error("Progression lookup failed for caseId={} (userId='{}')", caseId, userId, exception);
            result = Optional.empty();
        }
        return result;
    }

    public static String normalise(final String value, final int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public static  QueryLevel parseQueryLevel(String levelStr) {
        try {
            return levelStr == null ? null : QueryLevel.valueOf(levelStr.toUpperCase());
        } catch (Exception e) {
            log.warn("Invalid query level: {}", levelStr);
            return null;
        }
    }

}
