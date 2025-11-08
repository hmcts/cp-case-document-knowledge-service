package uk.gov.hmcts.cp.cdk.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import uk.gov.hmcts.cp.cdk.util.BrokerUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * End-to-end tests for ingestion process endpoint:
 * - POST /ingestion-process
 */
public class IngestionProcessHttpLiveTest {
    private static final RestTemplate http = new RestTemplate();
    // Custom VND type defined by OpenAPI contract
    private static final MediaType VND_TYPE_JSON =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process+json");
    public static final MediaType VND_TYPE_JSON_QUERIES = MediaType.valueOf("application/vnd.casedocumentknowledge-service.queries+json");
    public static final MediaType VND_TYPE_JSON_CATA = MediaType.valueOf("    application/vnd.casedocumentknowledge-service.query-catalogue+json");

    // Base URL (points to local service when running via composeUp)
    private static final String baseUrl = System.getProperty(
            "app.baseUrl",
            "http://localhost:8082/casedocumentknowledge-service"
    );
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
    public static void seedQueriesAndLabels() {

        labelQuery(QID_CASE_SUMMARY, LABEL_CASE_SUMMARY);
        labelQuery(QID_EVIDENCE_BUNDLE, LABEL_EVIDENCE_BUNDLE);
        labelQuery(QID_NEXT_STEPS, LABEL_NEXT_STEPS);


        final HttpHeaders upsertHeaders = new HttpHeaders();
        upsertHeaders.setContentType(VND_TYPE_JSON_QUERIES);
        upsertHeaders.setAccept(List.of(VND_TYPE_JSON_QUERIES));
        upsertHeaders.add("CJSCPPUID", "la-user-1");

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
        // Contract returns 202 Accepted on upsert
        assertThat(upsertResp.getStatusCode()).isIn(HttpStatus.ACCEPTED, HttpStatus.OK);

    }

    private static void labelQuery(final UUID queryId, final String label) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(VND_TYPE_JSON_CATA);
        headers.setAccept(List.of(VND_TYPE_JSON_CATA));
        headers.add("CJSCPPUID", "la-user-1");

        final String body = """
                { "label": "%s" }
                """.formatted(escapeJson(label));

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
            headers.add("CJSCPPUID", "la-user-1");

            // Build request body — matches the OpenAPI schema
            UUID courtCentreId = UUID.randomUUID();
            UUID roomId = UUID.randomUUID();
            String effectiveAt = "2025-05-01T12:00:00Z";
            String date = "2025-10-23";

            String requestBody = """
                    {
                      "courtCentreId": "%s",
                      "roomId": "%s",
                      "date": "%s",
                      "effectiveAt": "%s"
                    }
                    """.formatted(courtCentreId, roomId, date, effectiveAt);

            final HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            // Execute POST /ingestion-process
            ResponseEntity<String> response = http.exchange(
                    baseUrl + "/ingestions/start",
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            // Validate HTTP 202 and body fields
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).contains("\"phase\":\"STARTED\"");
            assertThat(response.getBody()).contains("\"message\":\"Ingestion request accepted");
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
}
