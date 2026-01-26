package uk.gov.hmcts.cp.cdk.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


import uk.gov.hmcts.cp.cdk.domain.AnswerId;
import uk.gov.hmcts.cp.cdk.domain.CaseDocument;
import uk.gov.hmcts.cp.cdk.domain.Answer;

import uk.gov.hmcts.cp.cdk.jobmanager.IngestionProperties;
import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;
import uk.gov.hmcts.cp.cdk.util.BrokerUtil;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;


/**
 * End-to-end tests for ingestion process endpoint:
 * - POST /ingestions/start
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IngestionProcessHttpLiveTest extends AbstractHttpLiveTest {

    // Custom VND types defined by OpenAPI contract
    private static final MediaType VND_TYPE_JSON =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process+json");
    public static final MediaType VND_TYPE_JSON_QUERIES =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.queries+json");
    public static final MediaType VND_TYPE_JSON_CATA =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.query-catalogue+json");

    // Stable IDs so test is idempotent across runs
    private static final UUID QID_CASE_SUMMARY =
            UUID.nameUUIDFromBytes("query-case-summary".getBytes(StandardCharsets.UTF_8));
    private static final UUID QID_EVIDENCE_BUNDLE =
            UUID.nameUUIDFromBytes("query-evidence-bundle".getBytes(StandardCharsets.UTF_8));
    private static final UUID QID_NEXT_STEPS =
            UUID.nameUUIDFromBytes("query-next-steps".getBytes(StandardCharsets.UTF_8));

    // Labels
    private static final String LABEL_CASE_SUMMARY = "Case Summary";
    private static final String LABEL_EVIDENCE_BUNDLE = "Evidence Bundle";
    private static final String LABEL_NEXT_STEPS = "Next Steps";


    @BeforeAll
    void seedQueriesAndLabels() {

        labelQuery(QID_CASE_SUMMARY, LABEL_CASE_SUMMARY);
        labelQuery(QID_EVIDENCE_BUNDLE, LABEL_EVIDENCE_BUNDLE);
        labelQuery(QID_NEXT_STEPS, LABEL_NEXT_STEPS);

        final HttpHeaders upsertHeaders = new HttpHeaders();
        upsertHeaders.setContentType(VND_TYPE_JSON_QUERIES);
        upsertHeaders.setAccept(List.of(VND_TYPE_JSON_QUERIES));

        final String effectiveAt = "2025-01-01T00:00:00Z";

        final String upsertBody = """
                {
                  "effectiveAt": "%s",
                  "queries": [
                    {
                      "queryId": "%s",
                      "userQuery": "Give me a concise case summary including parties, charges, hearing dates, and current status.",
                      "queryPrompt": "Summarise the case in bullet points. Focus on parties, charges, hearing dates, and procedural status."
                    },
                    {
                      "queryId": "%s",
                      "userQuery": "Summarise the key evidence and exhibits (IDs, types, and relevance).",
                      "queryPrompt": "List evidence/exhibits with IDs, types, short relevance notes; avoid speculation."
                    },
                    {
                      "queryId": "%s",
                      "userQuery": "What are the next procedural steps and likely timelines?",
                      "queryPrompt": "Outline upcoming procedural steps with indicative timelines based on current case status."
                    }
                  ]
                }
                """.formatted(effectiveAt, QID_CASE_SUMMARY, QID_EVIDENCE_BUNDLE, QID_NEXT_STEPS);

        final ResponseEntity<String> upsertResp = http.exchange(
                baseUrl + "/queries",
                HttpMethod.POST,
                new HttpEntity<>(upsertBody, upsertHeaders),
                String.class
        );

        UUID queryId = UUID.fromString("2a9ae797-7f70-4be5-927f-2dae65489e69");

        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass)) {

            // 1️⃣ Insert into queries table
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO queries (query_id, label, created_at, display_order) VALUES (?, ?, ?, ?)"
            )) {
                ps.setObject(1, queryId);
                ps.setString(2, "Chronology of the case");
                ps.setObject(3, OffsetDateTime.parse("2025-12-11T12:49:53.253475Z"));
                ps.setInt(4, 200);
                ps.executeUpdate();
            }

            // 2️⃣ Insert into query_versions table
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO query_versions (query_id, effective_at, user_query, query_prompt) " +
                            "VALUES (?, ?, ?, ?)"
            )) {
                ps.setObject(1, queryId);
                ps.setObject(2, OffsetDateTime.parse("2025-11-01T00:00:00Z"));
                ps.setString(3, "give me a chronology of the facts of the offences");
                ps.setString(4, "dummy");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // Contract returns 202 Accepted (or sometimes 200 OK) on upsert
        assertThat(upsertResp.getStatusCode()).isIn(HttpStatus.ACCEPTED, HttpStatus.OK);
    }

    private void labelQuery(final UUID queryId, final String label) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_TYPE_JSON_CATA);
        headers.setAccept(List.of(VND_TYPE_JSON_CATA));

        final String body = """
                { "label": "%s" ,
                 "order": %d }
                """.formatted(escapeJson(label), 200);

        final ResponseEntity<String> resp = http.exchange(
                baseUrl + "/query-catalogue/" + queryId + "/label",
                HttpMethod.PUT, // setQueryCatalogueLabel is typically PUT
                new HttpEntity<>(body, headers),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static String escapeJson(final String in) {
        return in.replace("\"", "\\\"");
    }

    @Test
    void start_ingestion_process_returns_started_phase() throws Exception {
        String auditResponse;
        try (BrokerUtil brokerUtil = new BrokerUtil()) {
            final HttpHeaders headers = new HttpHeaders();
            headers.setContentType(VND_TYPE_JSON);
            headers.setAccept(List.of(VND_TYPE_JSON));

            // Build request body — matches the OpenAPI schema
            final UUID courtCentreId = UUID.randomUUID();
            final UUID roomId = UUID.randomUUID();
            final String effectiveAt = "2025-05-01T12:00:00Z";
            final String date = "2025-10-23";

            final String requestBody = """
                    {
                      "courtCentreId": "%s",
                      "roomId": "%s",
                      "date": "%s",
                      "effectiveAt": "%s"
                    }
                    """.formatted(courtCentreId, roomId, date, effectiveAt);

            final HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            // Execute POST /ingestions/start
            final ResponseEntity<String> response = http.exchange(
                    baseUrl + "/ingestions/start",
                    HttpMethod.POST,
                    entity,
                    String.class
            );


            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).contains("\"phase\":\"STARTED\"");
            assertThat(response.getBody()).containsPattern("\"message\"\\s*:\\s*\"Ingestion.*request accepted.*\"");;
            assertThat(response.getBody()).contains("STARTED");

            // Validate audit message published (if applicable)
            auditResponse = brokerUtil.getMessageMatching(json ->
                    json.has("content") &&
                            courtCentreId.toString().equals(json.get("content").get("courtCentreId").asText()) &&
                            roomId.toString().equals(json.get("content").get("roomId").asText()) &&
                            date.equals(json.get("content").get("date").asText())
            );
        }

        assertNotNull(auditResponse, "Expected an audit event for ingestion process start");
    }

    private boolean isJobManagerEnabled() {

        String sysProp = System.getProperty("cdk.ingestion.feature.use-job-manager");
        System.out.println("value of sysProp :"+sysProp);
        if (sysProp != null) {
            return Boolean.parseBoolean(sysProp);
        }

        String env = System.getenv("CP_CDK_FEATURE_USE_JOB_MANAGER");
        System.out.println("value of env :"+env);
        if (env != null) {
            return Boolean.parseBoolean(env);
        }

        return  false;
    }


    @Test
    void start_ingestion_process_executes_all_tasks_successfully() throws Exception {
        // Arrange


        String auditResponse;
        try (BrokerUtil brokerUtil = new BrokerUtil()) {

            final HttpHeaders headers = new HttpHeaders();
            headers.setContentType(VND_TYPE_JSON);
            headers.setAccept(List.of(VND_TYPE_JSON));

            final UUID courtCentreId = UUID.randomUUID();
            final UUID roomId = UUID.randomUUID();
            final String effectiveAt = "2025-05-01T12:00:00Z";
            final String date = "2025-10-23";

            final String requestBody = """
        {
          "courtCentreId": "%s",
          "roomId": "%s",
          "date": "%s",
          "effectiveAt": "%s"
        }
        """.formatted(courtCentreId, roomId, date, effectiveAt);

            // Act
            final ResponseEntity<String> response = http.exchange(
                    baseUrl + "/ingestions/start",
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );

            // Assert HTTP
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).contains("\"phase\":\"STARTED\"");

            auditResponse = brokerUtil.getMessageMatching(json ->
                    json.has("content") &&
                            courtCentreId.toString().equals(json.get("content").get("courtCentreId").asText()) &&
                            roomId.toString().equals(json.get("content").get("roomId").asText())
            );
        }

        assertNotNull(auditResponse, "Expected audit event after full ingestion task chain");
        boolean jmenable = isJobManagerEnabled();
        Thread.sleep(60000);
        if (jmenable) {

            // ---- CaseDocument table validation using JDBC
            UUID caseId = UUID.fromString("2204cd6b-5759-473c-b0f7-178b3aa0c9b3");
            CaseDocument doc;
            try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT doc_id, case_id, material_id, doc_name, blob_uri, uploaded_at " +
                                 "FROM case_documents " +
                                 "WHERE case_id = ? " +
                                 "ORDER BY uploaded_at DESC " +
                                 "LIMIT 1"
                 )) {
                ps.setObject(1, caseId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Expected at least one CaseDocument for the case");

                    doc = new CaseDocument();
                    doc.setDocId((UUID) rs.getObject("doc_id"));
                    doc.setCaseId((UUID) rs.getObject("case_id"));
                    doc.setMaterialId((UUID) rs.getObject("material_id"));
                    doc.setDocName(rs.getString("doc_name"));
                    doc.setBlobUri(rs.getString("blob_uri"));
                    doc.setUploadedAt(rs.getObject("uploaded_at", OffsetDateTime.class));
                }
            }

            assertThat(doc.getBlobUri()).isNotBlank();
            assertThat(doc.getDocName()).isNotBlank();
            assertThat(doc.getMaterialId()).isNotNull();
            assertThat(doc.getUploadedAt()).isNotNull();

            // ---- Answer table validation using JDBC
            UUID queryId = UUID.fromString("2a9ae797-7f70-4be5-927f-2dae65489e69");
            Answer answer;

            try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT case_id, query_id, version, created_at, answer, doc_id " +
                                 "FROM answers " +
                                 "WHERE case_id = ? AND query_id = ? " +
                                 "ORDER BY created_at DESC, version DESC " +
                                 "LIMIT 1"
                 )) {
                ps.setObject(1, caseId);
                ps.setObject(2, queryId);

                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Expected at least one Answer for the case and query");

                    answer = new Answer();
                    AnswerId answerId = new AnswerId();
                    answerId.setCaseId((UUID) rs.getObject("case_id"));
                    answerId.setQueryId((UUID) rs.getObject("query_id"));
                    answerId.setVersion(rs.getInt("version"));
                    answer.setAnswerId(answerId);

                    answer.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
                    answer.setAnswerText(rs.getString("answer"));
                    answer.setDocId((UUID) rs.getObject("doc_id"));
                }
            }

            assertThat(answer.getAnswerText()).isNotBlank();
            assertThat(answer.getCreatedAt()).isNotNull();
            assertThat(answer.getDocId()).isNotNull();
        }
    }


}
