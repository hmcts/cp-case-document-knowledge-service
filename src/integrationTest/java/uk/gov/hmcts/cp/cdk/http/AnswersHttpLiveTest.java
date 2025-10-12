package uk.gov.hmcts.cp.cdk.http;

import static java.util.UUID.fromString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import uk.gov.hmcts.cp.cdk.util.BrokerUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * End-to-end tests for Answers & Queries endpoints.
 * Assumes endpoints:
 *   - GET /answers/{caseId}/{queryId}                  (latest or specific ?version=)
 *   - GET /answers/{caseId}/{queryId}/with-llm         (latest with LLM payload)
 *   - GET /queries?caseId={caseId}&at={isoInstant}     (as-of queries view for a case)
 */
public class AnswersHttpLiveTest {

    private final String baseUrl = System.getProperty(
            "app.baseUrl",
            "http://localhost:8082/casedocumentknowledge-service"
    );

    private final String jdbcUrl = System.getProperty("it.db.url", "jdbc:postgresql://localhost:55432/casedocumentknowledgeDatabase");
    private final String jdbcUser = System.getProperty("it.db.user", "casedocumentknowledge");
    private final String jdbcPass = System.getProperty("it.db.pass", "casedocumentknowledge");

    private final RestTemplate http = new RestTemplate();

    private UUID caseId;
    private UUID queryId;

    @BeforeEach
    void seedDb() throws Exception {
        caseId = UUID.randomUUID();
        queryId = UUID.randomUUID();

        final OffsetDateTime v1Eff = OffsetDateTime.parse("2025-06-01T12:00:00Z");
        final OffsetDateTime v2Eff = OffsetDateTime.parse("2025-06-02T12:00:00Z");
        // Make the latest answer created_at slightly after v2 definition to ensure "latest"
        final OffsetDateTime a1At = v1Eff;                 // 2025-06-01T12:00:00Z
        final OffsetDateTime a2At = v2Eff.plusSeconds(1);  // 2025-06-02T12:00:01Z

        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass)) {
            // 1) catalogue seed
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO queries (query_id, label) VALUES (?, 'Answer Tests Query')")) {
                ps.setObject(1, queryId);
                ps.executeUpdate();
            }
            // 2) definitions (v1, v2)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO query_versions (query_id, effective_at, user_query, query_prompt) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, queryId);
                ps.setObject(2, v1Eff);
                ps.setString(3, "User query v1");
                ps.setString(4, "Prompt v1");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO query_versions (query_id, effective_at, user_query, query_prompt) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, queryId);
                ps.setObject(2, v2Eff);
                ps.setString(3, "User query v2");
                ps.setString(4, "Prompt v2");
                ps.executeUpdate();
            }
            // 3) answers (v1, v2 latest)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO answers (case_id, query_id, version, created_at, answer, llm_input) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.setInt(3, 1);
                ps.setObject(4, a1At);
                ps.setString(5, "Answer v1");
                ps.setString(6, "LLM input v1");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO answers (case_id, query_id, version, created_at, answer, llm_input) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.setInt(3, 2);
                ps.setObject(4, a2At);
                ps.setString(5, "Answer v2");
                ps.setString(6, "LLM input v2");
                ps.executeUpdate();
            }
            // 4) per-case status (so /queries returns a concrete lifecycle status)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO case_query_status " +
                            "  (case_id, query_id, status, status_at, last_answer_version, last_answer_at) " +
                            "VALUES " +
                            "  (?, ?, ?::query_lifecycle_status_enum, ?, ?, ?) " +
                            "ON CONFLICT (case_id, query_id) DO UPDATE SET " +
                            "  status = EXCLUDED.status, " +
                            "  status_at = EXCLUDED.status_at, " +
                            "  last_answer_version = EXCLUDED.last_answer_version, " +
                            "  last_answer_at = EXCLUDED.last_answer_at"
            )) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.setString(3, "ANSWER_AVAILABLE");  // enum literal; cast handled in SQL
                ps.setObject(4, a2At);
                ps.setInt(5, 2);
                ps.setObject(6, a2At);
                ps.executeUpdate();
            }

        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass)) {
            // answers & status first (FKs), then versions, then catalogue
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM case_query_status WHERE case_id = ? AND query_id = ?")) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM answers WHERE case_id = ? AND query_id = ?")) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM query_versions WHERE query_id = ?")) {
                ps.setObject(1, queryId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM queries WHERE query_id = ?")) {
                ps.setObject(1, queryId);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void get_latest_answer_without_version_param() throws Exception {
        String auditResponse;
        try (BrokerUtil brokerUtil = new BrokerUtil()) {

            final HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            final ResponseEntity<String> response = http.exchange(
                    baseUrl + "/answers/" + caseId + "/" + queryId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"version\":2");
            assertThat(response.getBody()).contains("\"answer\":\"Answer v2\"");
            // This endpoint typically omits LLM input:
            assertThat(response.getBody()).doesNotContain("llmInput");

            String auditRequest = brokerUtil.getMessageMatching(json ->
                    json.has("content")
                            && caseId.equals(fromString(json.get("content").get("caseId").asText()))
                            && queryId.equals(fromString(json.get("content").get("queryId").asText()))
                            && "application/json".equals(json.get("content").get("_metadata").get("name").asText())
                            && "audit.events.audit-recorded".equals(json.get("_metadata").get("name").asText())
            );
            assertNotNull(auditRequest);

            auditResponse = brokerUtil.getMessageMatching(json ->
                    json.has("content")
                            && "Answer v2".equals(json.get("content").get("answer").asText())
                            && !json.get("content").has("llmInput")
                            && "application/json".equals(json.get("content").get("_metadata").get("name").asText())
                            && "audit.events.audit-recorded".equals(json.get("_metadata").get("name").asText())
            );
        }
        assertNotNull(auditResponse);
    }

    @Test
    void get_specific_answer_version() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        final ResponseEntity<String> response = http.exchange(
                baseUrl + "/answers/" + caseId + "/" + queryId + "?version=1",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"version\":1");
        assertThat(response.getBody()).contains("\"answer\":\"Answer v1\"");
    }

    @Test
    void get_answer_with_llm_latest() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        final ResponseEntity<String> response = http.exchange(
                baseUrl + "/answers/" + caseId + "/" + queryId + "/with-llm",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"version\":2");
        assertThat(response.getBody()).contains("\"answer\":\"Answer v2\"");
        assertThat(response.getBody()).contains("\"llmInput\":\"LLM input v2\"");
    }

    @Test
    void list_queries_as_of_for_case_returns_latest_definition_and_status() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        // Choose an as-of after v2 to ensure v2 definition is selected
        final String asOf = "2025-06-03T00:00:00Z";

        final ResponseEntity<String> response = http.exchange(
                baseUrl + "/queries?caseId=" + caseId + "&at=" + asOf,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        final String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"asOf\":\"" + asOf + "\"");
        assertThat(body).contains("\"caseId\":\"" + caseId + "\"");
        assertThat(body).contains("\"queryId\":\"" + queryId + "\"");
        assertThat(body).contains("\"label\":\"Answer Tests Query\"");
        assertThat(body).contains("\"userQuery\":\"User query v2\"");
        assertThat(body).contains("\"status\":\"ANSWER_AVAILABLE\"");
    }
}
