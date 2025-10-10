package uk.gov.hmcts.cp.cdk.filters.audit.service;

import static java.util.UUID.randomUUID;

import uk.gov.hmcts.cp.cdk.filters.audit.model.AuditPayload;
import uk.gov.hmcts.cp.cdk.filters.audit.model.Metadata;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class PayloadGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PayloadGenerationService.class);
    private static final String PAYLOAD_KEY = "_payload";
    private static final String METADATA_KEY = "_metadata";
    private static final String HEADER_USER_ID = "CJSCPPUID";
    private static final String HEADER_CLIENT_CORRELATION_ID = "CPPCLIENTCORRELATIONID";

    private final ObjectMapper objectMapper;

    public ObjectNode generatePayload(final String payloadBody, final Map<String, String> headers) {
        return generatePayload(payloadBody, headers, Map.of(), Map.of());
    }

    public ObjectNode generatePayload(final String payloadBody, final Map<String, String> headers, final Map<String, String> queryParams, final Map<String, String> pathParams) {
        AuditPayload auditPayload = new AuditPayload.Builder()
                .content(constructPayloadWithMetadata(payloadBody, headers, queryParams, pathParams))
                .timestamp(currentTimestamp())
                .origin("casedocumentknowledge-service")
                .component("casedocumentknowledge-service-api")
                .build();

        ObjectNode objectNode = objectMapper.valueToTree(auditPayload);
        addMetadataToNode(generateMetadata(headers, "audit.events.audit-recorded"), objectNode);
        return objectNode;
    }

    private ObjectNode constructPayloadWithMetadata(final String rawJsonString, final Map<String, String> headers, final Map<String, String> queryParams, final Map<String, String> pathParams) {
        Metadata metadata = generateMetadata(headers);

        try {
            JsonNode node = objectMapper.readTree(rawJsonString);
            ObjectNode objectNode = createObjectNode(node, rawJsonString);

            if (queryParams != null) {
                queryParams.forEach((key, value) -> objectNode.set(key, objectMapper.convertValue(value, JsonNode.class)));
            }

            if (pathParams != null) {
                pathParams.forEach((key, value) -> objectNode.set(key, objectMapper.convertValue(value, JsonNode.class)));
            }

            addMetadataToNode(metadata, objectNode);
            return objectNode;
        } catch (JsonProcessingException e) {
            return createPayloadWithMetadata(rawJsonString, metadata);
        }
    }

    private ObjectNode createObjectNode(JsonNode node, String rawJsonString) {
        ObjectNode objectNode = objectMapper.createObjectNode();

        if (node != null && node.isObject()) {
            return (ObjectNode) node;
        } else if (node != null && node.isArray()) {
            objectNode.set(PAYLOAD_KEY, node);
        } else if (node == null && StringUtils.isNotEmpty(rawJsonString)) {
            objectNode.put(PAYLOAD_KEY, rawJsonString);
        }

        return objectNode;
    }

    private Metadata generateMetadata(final Map<String, String> headers) {
        return generateMetadata(headers, getHeaderMatchingKey(headers, "Accept", "Content-Type"));
    }

    private Metadata generateMetadata(final Map<String, String> headers, final String methodName) {
        Metadata.Builder builder = new Metadata.Builder()
                .id(randomUUID())
                .name(methodName)
                .createdAt(currentTimestamp());

        setOptionalMetadata(headers, builder);
        return builder.build();
    }

    private void setOptionalMetadata(Map<String, String> headers, Metadata.Builder builder) {
        String userId = getHeaderMatchingKey(headers, HEADER_USER_ID);
        String clientCorrelationId = getHeaderMatchingKey(headers, HEADER_CLIENT_CORRELATION_ID);

        if (userId != null) {
            builder.context(new Metadata.Context(userId));
        }
        if (clientCorrelationId != null) {
            builder.correlation(new Metadata.Correlation(clientCorrelationId));
        }
    }

    private String getHeaderMatchingKey(final Map<String, String> headers, String... keys) {
        for (String key : keys) {
            String value = headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            if (value != null) return value;
        }
        return null;
    }

    private ObjectNode createPayloadWithMetadata(String rawJsonString, Metadata metadata) {
        ObjectNode objectNode = objectMapper.createObjectNode();
        objectNode.put(PAYLOAD_KEY, rawJsonString);
        addMetadataToNode(metadata, objectNode);
        return objectNode;
    }

    private void addMetadataToNode(final Metadata metadata, final ObjectNode objectNode) {
        objectNode.set(METADATA_KEY, objectMapper.valueToTree(metadata));
    }

    private String currentTimestamp() {
        return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).toString();
    }
}