package uk.gov.hmcts.cp.cdk.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lists all versions for a single query via the live HTTP API.
 * Assumes the service exposes GET /queries/{queryId}/versions
 */
public class QueryVersionsHttpLiveTest {

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

    @BeforeEach
    void seedVersionsViaApi() throws Exception {
        queryId = UUID.randomUUID();

        // Seed catalogue row first
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement ins = c.prepareStatement("INSERT INTO queries (query_id, label) VALUES (?, 'Versions Test Query')")) {
            ins.setObject(1, queryId);
            ins.executeUpdate();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_TYPE_JSON);

        // v1 @ 2025-05-01
        Map<String, Object> b1 = new HashMap<>();
        b1.put("effectiveAt", "2025-05-01T12:00:00Z");
        Map<String, Object> q1 = new HashMap<>();
        q1.put("queryId", queryId.toString());
        q1.put("userQuery", "V1 user text");
        q1.put("queryPrompt", "V1 prompt");
        b1.put("queries", List.of(q1));
        ResponseEntity<String> r1 = http.postForEntity(baseUrl + "/queries", new HttpEntity<>(b1, headers), String.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // v2 @ 2025-06-01
        Map<String, Object> b2 = new HashMap<>();
        b2.put("effectiveAt", "2025-06-01T12:00:00Z");
        Map<String, Object> q2 = new HashMap<>();
        q2.put("queryId", queryId.toString());
        q2.put("userQuery", "V2 user text");
        q2.put("queryPrompt", "V2 prompt");
        b2.put("queries", List.of(q2));
        ResponseEntity<String> r2 = http.postForEntity(baseUrl + "/queries", new HttpEntity<>(b2, headers), String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass)) {
            try (PreparedStatement delV = c.prepareStatement("DELETE FROM query_versions WHERE query_id = ?")) {
                delV.setObject(1, queryId);
                delV.executeUpdate();
            }
            try (PreparedStatement delQ = c.prepareStatement("DELETE FROM queries WHERE query_id = ?")) {
                delQ.setObject(1, queryId);
                delQ.executeUpdate();
            }
        }
    }

    @Test
    void list_versions_returns_all_versions_for_query() {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(VND_TYPE_JSON));

        ResponseEntity<String> res = http.exchange(
                baseUrl + "/queries/" + queryId + "/versions",
                HttpMethod.GET,
                new HttpEntity<>(h),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("V1 user text");
        assertThat(res.getBody()).contains("V1 prompt");
        assertThat(res.getBody()).contains("V2 user text");
        assertThat(res.getBody()).contains("V2 prompt");
    }
}
