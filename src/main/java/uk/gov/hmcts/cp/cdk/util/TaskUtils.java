package uk.gov.hmcts.cp.cdk.util;

import static java.util.Objects.nonNull;

import uk.gov.hmcts.cp.cdk.clients.progression.ProgressionClient;
import uk.gov.hmcts.cp.cdk.clients.progression.dto.LatestMaterialInfo;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

@Slf4j
@UtilityClass
public class TaskUtils {

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

    // ---------- ExecutionContext helpers ----------

    /**
     * Reads a string from step context first, falling back to job context.
     * Returns null if not found. Logs nothing (safe, common path).
     */
    public static String getStringFromContexts(final ExecutionContext stepContext,
                                               final ExecutionContext jobContext,
                                               final String key) {
        String value = null;
        if (nonNull(stepContext) && stepContext.containsKey(key)) {
            value = stepContext.getString(key);
        } else if (nonNull(jobContext) && jobContext.containsKey(key)) {
            value = jobContext.getString(key);
        }
        return value;
    }

    /**
     * Reads a boolean flag from a context key. Accepts Boolean or String values.
     * Returns false if absent or of unexpected type; logs on unexpected types.
     */
    public static boolean getBooleanFromContext(final ExecutionContext context, final String key) {
        boolean result = false;
        if (context != null && context.containsKey(key)) {
            final Object value = context.get(key);
            if (value instanceof Boolean boolValue) {
                result = boolValue;
            } else if (value instanceof String stringValue) {
                result = Boolean.parseBoolean(stringValue);
            } else {
                log.warn("Unexpected type for boolean context key '{}': {}", key,
                        value == null ? "null" : value.getClass().getName());
            }
        }
        return result;
    }

    // ---------- Safe external lookups ----------

    public static Optional<LatestMaterialInfo> safeGetCourtDocuments(final ProgressionClient progressionClient,
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
}
