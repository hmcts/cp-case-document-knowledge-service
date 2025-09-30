package uk.gov.hmcts.cp.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class QueriesHttpLiveTest {

    // Same baseUrl convention as your ActuatorHttpLiveTest
    private final String baseUrl = System.getProperty(
            "app.baseUrl",
            "http://localhost:8082/casedocumentknowledge-service"
    );

    // Connect to the DB published by docker-compose (for cleanup)
    private final String jdbcUrl = System.getProperty("it.db.url", "jdbc:postgresql://localhost:55432/appdb");
    private final String jdbcUser = System.getProperty("it.db.user", "app");
    private final String jdbcPass = System.getProperty("it.db.pass", "app");

    private final RestTemplate http = new RestTemplate();
    private UUID queryId;

    @BeforeEach
    void insertData() throws Exception {
        queryId = UUID.randomUUID();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // t1 payload
        Map<String, Object> body1 = new HashMap<>();
        body1.put("effectiveAt", "2025-05-01T12:00:00Z");
        Map<String, Object> q1 = new HashMap<>();
        q1.put("queryId", queryId.toString());
        q1.put("userQuery", "Q1 @ t1");
        q1.put("queryPrompt", "Prompt for Q1 @ t1");
        q1.put("status", "INGESTED");
        body1.put("queries", List.of(q1));

        HttpEntity<Map<String, Object>> req1 = new HttpEntity<>(body1, headers);
        ResponseEntity<String> r1 = http.postForEntity(baseUrl + "/queries", req1, String.class);
        if (!r1.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Failed to post t1: " + r1);
        }

        // t2 payload
        Map<String, Object> body2 = new HashMap<>();
        body2.put("effectiveAt", "2025-06-01T12:00:00Z");
        Map<String, Object> q2 = new HashMap<>();
        q2.put("queryId", queryId.toString());
        q2.put("userQuery", "Q1 @ t2");
        q2.put("queryPrompt", "Prompt for Q1 @ t2");
        q2.put("status", "INGESTED");
        body2.put("queries", List.of(q2));

        HttpEntity<Map<String, Object>> req2 = new HttpEntity<>(body2, headers);
        ResponseEntity<String> r2 = http.postForEntity(baseUrl + "/queries", req2, String.class);
        if (!r2.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Failed to post t2: " + r2);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement del = c.prepareStatement("DELETE FROM queries WHERE query_id = ?")) {
            del.setObject(1, queryId);
            del.executeUpdate();
        }
    }

    @Test
    void queries_without_at_returns_latest_version() {
        ResponseEntity<String> res = http.exchange(
                baseUrl + "/queries",
                HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(new HttpHeaders()),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains(queryId.toString());
        assertThat(res.getBody()).contains("Q1 @ t2");
        assertThat(res.getBody()).contains("\"queryPrompt\":\"Prompt for Q1 @ t2\"");
        assertThat(res.getBody()).contains("\"status\":\"INGESTED\"");
    }

    @Test
    void queries_with_at_returns_as_of_version() {
        String at = "2025-05-15T00:00:00Z";

        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> res = http.exchange(
                baseUrl + "/queries?at=" + at,
                HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(h),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"asOf\":\"" + at + "\"");
        assertThat(res.getBody()).contains(queryId.toString());
        assertThat(res.getBody()).contains("Q1 @ t1");
        assertThat(res.getBody()).contains("\"queryPrompt\":\"Prompt for Q1 @ t1\"");
        assertThat(res.getBody()).contains("\"status\":\"INGESTED\"");
    }

    private void waitForTable(Connection c, String table) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            try (var rs = c.getMetaData().getTables(null, null, table, null)) {
                if (rs.next()) return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out waiting for table '" + table + "'");
    }

}
