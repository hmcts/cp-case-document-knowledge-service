package uk.gov.hmcts.cp.cdk.filters.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);

    private final JmsTemplate jmsTemplate;

    private final ObjectMapper objectMapper;

    public AuditService(JmsTemplate jmsTemplate, ObjectMapper objectMapper) {
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
    }

    public void postMessageToArtemis(ObjectNode auditMessage) {

        try {
            final String valueAsString = objectMapper.writeValueAsString(auditMessage);
            LOGGER.info("Posting audit message to Artemis: {}", valueAsString);
            jmsTemplate.convertAndSend("jms.topic.auditing.event", valueAsString);
        } catch (JsonProcessingException e) {
            // Log the error but don't re-throw to avoid breaking the main request flow
            LOGGER.error("Failed to post audit message to Artemis", e);
        }

    }
}