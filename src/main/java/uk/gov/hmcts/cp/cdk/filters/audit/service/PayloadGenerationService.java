package uk.gov.hmcts.cp.cdk.filters.audit.service;

import static java.util.UUID.randomUUID;

import uk.gov.hmcts.cp.cdk.filters.audit.model.AuditPayload;
import uk.gov.hmcts.cp.cdk.filters.audit.model.Metadata;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class PayloadGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PayloadGenerationService.class);
    private static final String ATTRIBUTE_PAYLOAD_KEY = "_payload";
    private static final String ATTRIBUTE_METADATA_KEY = "_metadata";
    private static final String HEADER_USER_ID = "CJSCPPUID";
    private static final String HEADER_CLIENT_CORRELATION_ID = "CPPCLIENTCORRELATIONID";

    private final ObjectMapper objectMapper;

    public ObjectNode generatePayload(final String contextPath, final String payloadBody, final Map<String, String> headers) {
        return generatePayload(contextPath, payloadBody, headers, Map.of(), Map.of());
    }

    public ObjectNode generatePayload(final String contextPath, final String payloadBody, final Map<String, String> headers, final Map<String, String> queryParams, final Map<String, String> pathParams) {
        AuditPayload auditPayload = AuditPayload.builder()
                .content(constructPayloadWithMetadata(payloadBody, headers, queryParams, pathParams))
                .timestamp(currentTimestamp())
                .origin(contextPath)
                .component(contextPath + "-api")
                .build();

        ObjectNode objectNode = objectMapper.valueToTree(auditPayload);
        addMetadataToNode(generateMetadata(headers, "audit.events.audit-recorded"), objectNode);
        return objectNode;
    }

    private ObjectNode constructPayloadWithMetadata(final String rawJsonString, final Map<String, String> headers, final Map<String, String> queryParams, final Map<String, String> pathParams) {
        Metadata metadata = generateMetadata(headers);

        try {
            final JsonNode node = objectMapper.readTree(rawJsonString);
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

    private ObjectNode createObjectNode(final JsonNode node, final String rawJsonString) {
        if (node != null) {
            if (node.isObject()) {
                return (ObjectNode) node;
            } else if (node.isArray()) {
                return objectMapper.createObjectNode().set(ATTRIBUTE_PAYLOAD_KEY, node);
            }
        }
        return objectMapper.createObjectNode().put(ATTRIBUTE_PAYLOAD_KEY, rawJsonString);
    }

    private Metadata generateMetadata(final Map<String, String> headers) {
        return generateMetadata(headers, getHeaderMatchingKey(headers, "Accept", "Content-Type"));
    }

    private Metadata generateMetadata(final Map<String, String> headers, final String methodName) {
        final Metadata.MetadataBuilder metadataBuilder = Metadata.builder()
                .id(randomUUID())
                .name(methodName)
                .createdAt(currentTimestamp());

        setOptionalMetadata(headers, metadataBuilder);
        return metadataBuilder.build();
    }

    private void setOptionalMetadata(Map<String, String> headers, Metadata.MetadataBuilder metadataBuilder) {
        final String userId = getHeaderMatchingKey(headers, HEADER_USER_ID);
        final String clientCorrelationId = getHeaderMatchingKey(headers, HEADER_CLIENT_CORRELATION_ID);

        if (null != userId) {
            metadataBuilder.context(Optional.of(new Metadata.Context(userId)));
        }
        if (null != clientCorrelationId) {
            metadataBuilder.correlation(Optional.of(new Metadata.Correlation(clientCorrelationId)));
        }
    }

    private String getHeaderMatchingKey(final Map<String, String> headers, String... keys) {
        for (String key : keys) {
            final String value = headers.getOrDefault(key, null);
            if (value != null) return value;
        }
        return null;
    }

    private ObjectNode createPayloadWithMetadata(String rawJsonString, Metadata metadata) {
        ObjectNode objectNode = objectMapper.createObjectNode();
        objectNode.put(ATTRIBUTE_PAYLOAD_KEY, rawJsonString);
        addMetadataToNode(metadata, objectNode);
        return objectNode;
    }

    private void addMetadataToNode(final Metadata metadata, final ObjectNode objectNode) {
        objectNode.set(ATTRIBUTE_METADATA_KEY, objectMapper.valueToTree(metadata));
    }

    private String currentTimestamp() {
        return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).toString();
    }
}