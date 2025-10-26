package uk.gov.hmcts.cp.cdk.filters.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.cdk.filters.audit.JacksonConfig;
import uk.gov.hmcts.cp.cdk.filters.audit.model.AuditPayload;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditPayloadGenerationServiceTest {

    private static final String HEADER_ATTR_CJSCPPUID = "CJSCPPUID";
    private static final String HEADER_ATTR_CPP_CLIENT_CORRELATION_ID = "CPPCLIENTCORRELATIONID";
    private AuditPayloadGenerationService auditPayloadGenerationService;

    @BeforeEach
    void setUp() {
        auditPayloadGenerationService = new AuditPayloadGenerationService(new JacksonConfig().objectMapper());
    }

    @Test
    @DisplayName("Generates payload with valid JSON body and headers")
    void generatesPayloadWithValidJsonBodyAndHeaders() {
        final String contextPath = "test";
        final String payloadBody = "{\"key\":\"value\"}";
        final String clientCorrelationId = "corr123";
        final String userId = "user123";
        final Map<String, String> headers = Map.of("Content-Type", "application/json", HEADER_ATTR_CJSCPPUID, userId, HEADER_ATTR_CPP_CLIENT_CORRELATION_ID, clientCorrelationId);

        final AuditPayload result = auditPayloadGenerationService.generatePayload(contextPath, payloadBody, headers);

        assertThat(result).isNotNull();
        assertThat(result.origin()).isEqualTo("test");
        assertThat(result.component()).isEqualTo("test-api");
        assertThat(result.timestamp()).isNotBlank();

        assertThat(result._metadata().id()).isNotNull();
        assertThat(result._metadata().name()).isEqualTo("audit.events.audit-recorded");
        assertThat(result._metadata().context().get().user()).isEqualTo(userId);
        assertThat(result._metadata().correlation().get().client()).isEqualTo(clientCorrelationId);
        assertThat(result._metadata().createdAt()).isNotBlank();

        assertThat(result.content().get("key").asText()).isEqualTo("value");

        assertThat(result.content().get("_metadata").get("id").asText()).isNotBlank();
        assertThat(result.content().get("_metadata").get("name").asText()).isEqualTo("application/json");
        assertThat(result.content().get("_metadata").get("context").get("user").asText()).isEqualTo(userId);
        assertThat(result.content().get("_metadata").get("correlation").get("client").asText()).isEqualTo(clientCorrelationId);
        assertThat(result.content().get("_metadata").get("createdAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Generates payload with invalid JSON body using raw string")
    void generatesPayloadWithInvalidJsonBody() {
        String contextPath = "test";
        String payloadBody = "invalid-json";
        Map<String, String> headers = Map.of("Content-Type", "application/json");

        final AuditPayload result = auditPayloadGenerationService.generatePayload(contextPath, payloadBody, headers);

        assertThat(result).isNotNull();
        assertThat(result.origin()).isEqualTo("test");
        assertThat(result.component()).isEqualTo("test-api");
        assertThat(result.content().get("_payload").asText()).isEqualTo(payloadBody);
    }

    @Test
    @DisplayName("Generates payload with null headers and query parameters")
    void generatesPayloadWithNullHeadersAndQueryParameters() {
        final String contextPath = "/test";
        final String payloadBody = "{\"key\":\"value\"}";

        final AuditPayload result = auditPayloadGenerationService.generatePayload(contextPath, payloadBody, null, null, null);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("Generates payload using payload, headers, path and query parameters")
    void generatesPayloadWithPathParameters() {
        final String contextPath = "test";
        final String payloadBody = "{\"key\":\"value\"}";
        final Map<String, String> headers = Map.of("Content-Type", "application/json");
        final Map<String, String> pathParams = Map.of("id", "123");
        final Map<String, String> queryParams = Map.of("queryKey", "queryValue");

        final AuditPayload result = auditPayloadGenerationService.generatePayload(contextPath, payloadBody, headers, queryParams, pathParams);

        assertThat(result).isNotNull();
        assertThat(result.origin()).isEqualTo("test");
        assertThat(result.component()).isEqualTo("test-api");
        assertThat(result.timestamp()).isNotBlank();

        assertThat(result._metadata().id()).isNotNull();
        assertThat(result._metadata().name()).isEqualTo("audit.events.audit-recorded");
        assertThat(result._metadata().createdAt()).isNotBlank();

        assertThat(result.content().get("id").asText()).isEqualTo("123");
        assertThat(result.content().get("key").asText()).isEqualTo("value");
        assertThat(result.content().get("queryKey").asText()).isEqualTo("queryValue");

        assertThat(result.content().get("_metadata").get("id").asText()).isNotBlank();
        assertThat(result.content().get("_metadata").get("name").asText()).isEqualTo("application/json");
        assertThat(result.content().get("_metadata").get("createdAt").asText()).isNotBlank();
    }
}
