package uk.gov.hmcts.cp.cdk.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.cp.cdk.util.BrokerUtil;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for ingestion process endpoint:
 * - POST /ingestion-process
 */
public class IngestionProcessHttpLiveTest {

    // Custom VND type defined by OpenAPI contract
    private static final MediaType VND_TYPE_JSON =
            MediaType.valueOf("application/vnd.casedocumentknowledge-service.ingestion-process+json");

    // Base URL (points to local service when running via composeUp)
    private final String baseUrl = System.getProperty(
            "app.baseUrl",
            "http://localhost:8082/casedocumentknowledge-service"
    );

    // Shared HTTP client
    private final RestTemplate http = new RestTemplate();

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

            // Validate HTTP 200 and body fields
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"phase\":\"STARTED\"");
            assertThat(response.getBody()).contains("\"message\":\"Ingestion process started successfully");
            assertThat(response.getBody()).contains("\"lastUpdated\"");
            assertThat(response.getBody()).contains("STARTED");

            // Validate audit message published (if applicable)
           /** auditResponse = brokerUtil.getMessageMatching(json ->
                    json.has("content") &&
                            "STARTED".equals(json.get("content").get("phase").asText()) &&
                            "Ingestion process started".equals(json.get("content").get("message").asText())
            );**/
        }

        //assertNotNull(auditResponse, "Expected an audit event for ingestion process start");
    }
}
