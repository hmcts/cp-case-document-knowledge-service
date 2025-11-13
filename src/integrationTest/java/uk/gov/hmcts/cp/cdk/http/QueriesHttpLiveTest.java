package uk.gov.hmcts.cp.cdk.http;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.testsupport.TestConstants.HEADER_NAME;
import static uk.gov.hmcts.cp.cdk.testsupport.TestConstants.HEADER_VALUE;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public class QueriesHttpLiveTest extends AbstractHttpLiveTest {

    public final MediaType VND_TYPE_JSON = MediaType.valueOf("application/vnd.casedocumentknowledge-service.queries+json");
    private UUID queryId;
    private UUID qid1;
    private UUID qid2;
    private UUID qid3;
    private ObjectMapper objectMapper;

    @BeforeEach
    void insertData() throws Exception {
        queryId = UUID.randomUUID();
        qid1 = UUID.randomUUID();
        qid2 = UUID.randomUUID();
        qid3 = UUID.randomUUID();

        // Seed the catalogue row first (POST /queries only upserts DEFINITIONS, not catalogue)
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO queries (query_id, label, created_at, \"order\") VALUES (?, ?, ?, ?)"
             )) {
            ps.setObject(1, queryId);
            ps.setString(2, "Test Query");
            ps.setInt(4, 1000);
            ps.setObject(3, OffsetDateTime.parse("2025-04-01T00:00:00Z"));
            ps.executeUpdate();

            ps.setObject(1, qid1);
            ps.setString(2, "Query 1");
            ps.setObject(3, OffsetDateTime.now());
            ps.setInt(4, 300);
            ps.executeUpdate();

            ps.setObject(1, qid2);
            ps.setString(2, "Query 2");
            ps.setObject(3, OffsetDateTime.now());
            ps.setInt(4, 100);
            ps.executeUpdate();

            ps.setObject(1, qid3);
            ps.setString(2, "Query 3");
            ps.setObject(3, OffsetDateTime.now());
            ps.setInt(4, 200);
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

        // ---------- Snapshot t2 ----------
        Map<String, Object> body3 = new HashMap<>();
        body3.put("effectiveAt", "2025-06-01T12:00:00Z");

        Map<String, Object> body4 = new HashMap<>();
        body4.put("effectiveAt", "2025-06-01T12:00:00Z");

        Map<String, Object> body5 = new HashMap<>();
        body5.put("effectiveAt", "2025-06-01T12:00:00Z");

        Map<String, Object> q3 = new HashMap<>();
        q3.put("queryId", qid1.toString());
        q3.put("userQuery", "Q1 @ t2");
        q3.put("queryPrompt", "Prompt for Q1 @ t2");

        Map<String, Object> q4 = new HashMap<>();
        q4.put("queryId", qid2.toString());
        q4.put("userQuery", "Q2 @ t2");
        q4.put("queryPrompt", "Prompt for Q2 @ t2");

        Map<String, Object> q5 = new HashMap<>();
        q5.put("queryId", qid3.toString());
        q5.put("userQuery", "Q3 @ t2");
        q5.put("queryPrompt", "Prompt for Q3 @ t2");
        body3.put("queries", List.of(q3));
        body4.put("queries", List.of(q4));
        body5.put("queries", List.of(q5));
        ResponseEntity<String> r3 = http.postForEntity(
                baseUrl + "/queries",
                new HttpEntity<>(body3, headers),
                String.class
        );

        ResponseEntity<String> r4 = http.postForEntity(
                baseUrl + "/queries",
                new HttpEntity<>(body4, headers),
                String.class
        );

        ResponseEntity<String> r5 = http.postForEntity(
                baseUrl + "/queries",
                new HttpEntity<>(body5, headers),
                String.class
        );
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r4.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r5.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);


    }

    @AfterEach
    void cleanup() throws Exception {
        List<UUID> queryIds = List.of(
                queryId, // the one you inserted in @BeforeEach
                qid3,
                qid2,
                qid1
        );


        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement delqv = c.prepareStatement(
                     "DELETE FROM query_versions WHERE query_id = ANY(?)"
             )) {
            delqv.setArray(1, c.createArrayOf("UUID", queryIds.toArray()));
            delqv.executeUpdate();
        }

        // Then delete from parent table (queries)
        try (Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass);
             PreparedStatement del = c.prepareStatement(
                     "DELETE FROM queries WHERE query_id = ANY(?)"
             )) {
            del.setArray(1, c.createArrayOf("UUID", queryIds.toArray()));
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

    @Test
    void queries_are_returned_in_ascending_order() throws Exception {


        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(VND_TYPE_JSON));
        headers.set(HEADER_NAME, HEADER_VALUE);

        ResponseEntity<String> res = http.exchange(
                baseUrl + "/queries?caseId=e9987338-ebae-480c-825e-aad78da3ef4f",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            objectMapper = new ObjectMapper();
            var root = objectMapper.readTree(res.getBody());
            var items = root.get("queries");

        assertThat(items).isNotNull();
            assertThat(items.size()).isGreaterThanOrEqualTo(2);

        int indexQuery1 = -1;
        int indexQuery2 = -1;
        int indexQuery3 = -1;

        for (int i = 0; i < items.size(); i++) {
            String label = items.get(i).get("label").asText();
            if ("Query 1".equals(label)) {
                indexQuery1 = i;
            } else if ("Query 2".equals(label)) {
                indexQuery2 = i;
            } else if ("Query 3".equals(label)) {
                indexQuery3 = i;
            }
        }

        assertThat(indexQuery1).isNotEqualTo(-1);
        assertThat(indexQuery2).isNotEqualTo(-1);
        assertThat(indexQuery3).isNotEqualTo(-1);

        // Assert order: Query 2 < Query 3 < Query 1
        assertThat(indexQuery2)
                .as("Query 2 should appear before Query 3")
                .isLessThan(indexQuery3);

        assertThat(indexQuery3)
                .as("Query 3 should appear before Query 1")
                .isLessThan(indexQuery1);

    }

}
