package uk.gov.hmcts.cp.cdk.http;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.cdk.testsupport.TestConstants.CJSCPPUID;
import static uk.gov.hmcts.cp.cdk.testsupport.TestConstants.USER_WITH_PERMISSIONS;
import static uk.gov.hmcts.cp.cdk.testsupport.TestConstants.USER_WITH_SYSTEM_USER_GROUPS;

import uk.gov.hmcts.cp.cdk.testsupport.AbstractHttpLiveTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
                     "INSERT INTO queries (query_id, label, created_at, display_order) VALUES (?, ?, ?, ?)"
             )) {
            insertQuery(ps, queryId, "Test Query", OffsetDateTime.parse("2025-04-01T00:00:00Z"), 1000);
            insertQuery(ps, qid1, "Query 1", OffsetDateTime.now(), 300);
            insertQuery(ps, qid2, "Query 2", OffsetDateTime.now(), 100);
            insertQuery(ps, qid3, "Query 3", OffsetDateTime.now(), 200);
        }


        ResponseEntity<String> r1 = postQuerySnapshot(queryId, "2025-05-01T12:00:00Z", "Q1 @ t1", "Prompt for Q1 @ t1");
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> r2 = postQuerySnapshot(queryId, "2025-06-01T12:00:00Z", "Q1 @ t2", "Prompt for Q1 @ t2");
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> r3 = postQuerySnapshot(qid1, "2025-06-01T12:00:00Z", "Q1 @ t2", "Prompt for Q1 @ t2");
        ResponseEntity<String> r4 = postQuerySnapshot(qid2, "2025-06-01T12:00:00Z", "Q2 @ t2", "Prompt for Q2 @ t2");
        ResponseEntity<String> r5 = postQuerySnapshot(qid3, "2025-06-01T12:00:00Z", "Q3 @ t2", "Prompt for Q3 @ t2");

        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r4.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r5.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    }

    private void insertQuery(PreparedStatement ps, UUID id, String label, OffsetDateTime createdAt, int displayOrder) throws SQLException {
        ps.setObject(1, id);
        ps.setString(2, label);
        ps.setObject(3, createdAt);
        ps.setInt(4, displayOrder);
        ps.executeUpdate();
    }

    private ResponseEntity<String> postQuerySnapshot(UUID queryId, String effectiveAt, String userQuery, String queryPrompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("effectiveAt", effectiveAt);

        Map<String, Object> query = new HashMap<>();
        query.put("queryId", queryId.toString());
        query.put("userQuery", userQuery);
        query.put("queryPrompt", queryPrompt);

        body.put("queries", List.of(query));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_TYPE_JSON);
        // Add your custom header
        headers.set(CJSCPPUID, USER_WITH_PERMISSIONS);

        return http.postForEntity(baseUrl + "/queries", new HttpEntity<>(body, headers), String.class);
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


    @ParameterizedTest
    @ValueSource(strings = {USER_WITH_SYSTEM_USER_GROUPS, USER_WITH_PERMISSIONS})
    void queries_without_at_returns_latest_version(final String loggedInUser) {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(VND_TYPE_JSON));
        // Add your custom header
        h.set(CJSCPPUID, loggedInUser);

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
        h.set(CJSCPPUID, USER_WITH_SYSTEM_USER_GROUPS);

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
        headers.set(CJSCPPUID, USER_WITH_SYSTEM_USER_GROUPS);

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
