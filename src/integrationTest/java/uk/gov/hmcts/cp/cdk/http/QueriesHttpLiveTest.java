package uk.gov.hmcts.cp.cdk.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class QueriesHttpLiveTest {

    public final MediaType VND_TYPE_JSON = MediaType.valueOf("application/vnd.casedocumentknowledge-service.queries+json");
    private final String baseUrl = System.getProperty(
            "app.baseUrl",
            "http://localhost:8082/casedocumentknowledge-service"
    );

    private final String jdbcUrl  = System.getProperty("it.db.url",  "jdbc:postgresql://localhost:55432/casedocumentknowledgeDatabase");
    private final String jdbcUser = System.getProperty("it.db.user", "casedocumentknowledge");
    private final String jdbcPass = System.getProperty("it.db.pass", "casedocumentknowledge");

    private final RestTemplate http = new RestTemplate();
    private UUID queryId;
    private static final String HEADER_NAME = "CJSCPPUID";
    private static final String HEADER_VALUE = "u-123";

    @BeforeEach
    void insertData() throws Exception {
        queryId = UUID.randomUUID();

        // Seed the catalogue row first (POST /queries only upserts DEFINITIONS, not catalogue)
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO queries (query_id, label, created_at) VALUES (?, ?, ?)"
             )) {
            ps.setObject(1, queryId);
            ps.setString(2, "Test Query");
            ps.setObject(3, OffsetDateTime.parse("2025-04-01T00:00:00Z"));
            ps.executeUpdate();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_TYPE_JSON);
        // Add your custom header
        headers.set(HEADER_NAME, HEADER_VALUE);

        // ---------- Snapshot t1 ----------
        Map<String, Object> body1 = new HashMap<>();
        body1.put("effectiveAt", "2025-05-01T12:00:00Z");
        Map<String, Object> q1 = new HashMap<>();
        q1.put("queryId", queryId.toString());
        q1.put("userQuery", "Q1 @ t1");
        q1.put("queryPrompt", "Prompt for Q1 @ t1");
        body1.put("queries", List.of(q1));
        ResponseEntity<String> r1 = http.postForEntity(
                baseUrl + "/queries",
                new HttpEntity<>(body1, headers),
                String.class
        );
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // ---------- Snapshot t2 ----------
        Map<String, Object> body2 = new HashMap<>();
        body2.put("effectiveAt", "2025-06-01T12:00:00Z");
        Map<String, Object> q2 = new HashMap<>();
        q2.put("queryId", queryId.toString());
        q2.put("userQuery", "Q1 @ t2");
        q2.put("queryPrompt", "Prompt for Q1 @ t2");
        body2.put("queries", List.of(q2));
        ResponseEntity<String> r2 = http.postForEntity(
                baseUrl + "/queries",
                new HttpEntity<>(body2, headers),
                String.class
        );
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement delqv = c.prepareStatement("DELETE FROM query_versions WHERE query_id = ?")) {
            delqv.setObject(1, queryId);
            delqv.executeUpdate();
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement del = c.prepareStatement("DELETE FROM queries WHERE query_id = ?")) {
            del.setObject(1, queryId);
            del.executeUpdate();
        }
    }

    @Test
    void queries_without_at_returns_latest_version() {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(VND_TYPE_JSON));
        // Add your custom header
        h.set(HEADER_NAME, HEADER_VALUE);

        ResponseEntity<String> res = http.exchange(
                baseUrl + "/queries?caseId=e9987338-ebae-480c-825e-aad78da3ef4f",
                HttpMethod.GET,
                new HttpEntity<>(h),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains(queryId.toString());
        assertThat(res.getBody()).contains("Q1 @ t2");
        assertThat(res.getBody()).contains("\"queryPrompt\":\"Prompt for Q1 @ t2\"");
    }

    @Test
    void queries_with_at_returns_as_of_version() {
        String at = "2025-05-15T00:00:00Z";



        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(VND_TYPE_JSON));
        // Add your custom header
        h.set(HEADER_NAME, HEADER_VALUE);

        ResponseEntity<String> res = http.exchange(
                baseUrl + "/queries?caseId=e9987338-ebae-480c-825e-aad78da3ef4f&at=" + at,
                HttpMethod.GET,
                new HttpEntity<>(h),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"asOf\":\"" + at + "\"");
        assertThat(res.getBody()).contains(queryId.toString());
        assertThat(res.getBody()).contains("Q1 @ t1");
        assertThat(res.getBody()).contains("\"queryPrompt\":\"Prompt for Q1 @ t1\"");
    }
}
