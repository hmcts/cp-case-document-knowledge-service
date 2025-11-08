package uk.gov.hmcts.cp.cdk.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import uk.gov.hmcts.cp.cdk.util.BrokerUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
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
 * End-to-end tests for ingestion status read endpoint:
 * - GET /ingestions/status?caseId=...
 */
public class IngestionStatusHttpLiveTest extends AbstractHttpLiveTest {

    public final MediaType VND_TYPE_JSON = MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion+json");
    private UUID caseId;

    @BeforeEach
    void seedCaseDocuments() throws Exception {
        caseId = randomUUID();

        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass)) {
            try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO case_documents (doc_id, case_id, material_id,source, doc_name,blob_uri, uploaded_at, ingestion_phase, ingestion_phase_at)
                        VALUES (?, ?, ?,'IDPC', 'doc1','blob://one', ?, 'INGESTING', ?)
                    """)) {
                ps.setObject(1, randomUUID());
                ps.setObject(2, caseId);
                ps.setObject(3, randomUUID());
                ps.setObject(4, OffsetDateTime.parse("2025-05-01T12:00:00Z"));
                ps.setObject(5, OffsetDateTime.parse("2025-05-01T12:00:00Z"));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO case_documents (doc_id, case_id, material_id,source, doc_name,blob_uri, uploaded_at, ingestion_phase, ingestion_phase_at)
                        VALUES (?, ?, ?,'IDPC','doc2' ,'blob://two', ?, 'INGESTED', ?)
                    """)) {
                ps.setObject(1, randomUUID());
                ps.setObject(2, caseId);
                ps.setObject(3, randomUUID());
                ps.setObject(4, OffsetDateTime.parse("2025-05-01T12:05:00Z"));
                ps.setObject(5, OffsetDateTime.parse("2025-05-01T12:05:00Z"));
                ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement ps = c.prepareStatement("DELETE FROM case_documents WHERE case_id = ?")) {
            ps.setObject(1, caseId);
            ps.executeUpdate();
        }
    }

    @Test
    void latest_status_is_returned_from_view() throws Exception {
        String auditResponse;
        try (BrokerUtil brokerUtil = new BrokerUtil()) {

            final HttpHeaders h = new HttpHeaders();
            h.setAccept(List.of(VND_TYPE_JSON));

            final ResponseEntity<String> res = http.exchange(
                    baseUrl + "/ingestions/status?caseId=" + caseId,
                    HttpMethod.GET,
                    new HttpEntity<>(h),
                    String.class
            );

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).contains("\"caseId\":\"" + caseId + "\"");
            assertThat(res.getBody()).contains("\"phase\":\"INGESTED\"");
            assertThat(res.getBody()).contains("\"lastUpdated\":\"2025-05-01T12:05:00Z\"");

            String auditRequest = brokerUtil.getMessageMatching(json ->
                    json.has("content") && caseId.equals(UUID.fromString(json.get("content").get("caseId").asText()))
            );
            assertNotNull(auditRequest);

            auditResponse = brokerUtil.getMessageMatching(json ->
                    json.has("content") &&
                            "INGESTED".equals(json.get("content").get("phase").asText())
                            && caseId.equals(UUID.fromString(json.get("content").get("scope").get("caseId").asText()))
            );
            assertNotNull(auditResponse);
        }
        assertNotNull(auditResponse);
    }
}
