package uk.gov.hmcts.cp.cdk.filters.audit.service;

import uk.gov.hmcts.cp.cdk.filters.audit.model.AuditPayload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);

    private final JmsTemplate jmsTemplate;

    private final ObjectMapper objectMapper;

    public void postMessageToArtemis(final AuditPayload auditPayload) {

        try {
            final String valueAsString = objectMapper.writeValueAsString(auditPayload);
            LOGGER.info("Posting audit message to Artemis: {}", valueAsString);
            jmsTemplate.convertAndSend("jms.topic.auditing.event", valueAsString);
        } catch (JsonProcessingException e) {
            // Log the error but don't re-throw to avoid breaking the main request flow
            LOGGER.error("Failed to post audit message to Artemis - {}", e.getMessage(), e);
        }

    }
}