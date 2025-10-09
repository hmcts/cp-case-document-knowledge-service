package uk.gov.hmcts.cp.cdk.filters.audit.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;

class AuditServiceTest {

    private JmsTemplate jmsTemplate;
    private ObjectMapper objectMapper;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        jmsTemplate = mock(JmsTemplate.class);
        objectMapper = mock(ObjectMapper.class);
        auditService = new AuditService(jmsTemplate, objectMapper);
    }

    @Test
    void postMessageToArtemis_logsAndSendsMessageWhenSerializationSucceeds() throws JsonProcessingException {
        ObjectNode auditMessage = mock(ObjectNode.class);
        String serializedMessage = "{\"key\":\"value\"}";
        when(objectMapper.writeValueAsString(auditMessage)).thenReturn(serializedMessage);

        auditService.postMessageToArtemis(auditMessage);

        verify(objectMapper).writeValueAsString(auditMessage);
        verify(jmsTemplate).convertAndSend(eq("jms.topic.auditing.event"), eq(serializedMessage));
    }

    @Test
    void postMessageToArtemis_logsErrorWhenSerializationFails() throws JsonProcessingException {
        ObjectNode auditMessage = mock(ObjectNode.class);
        when(objectMapper.writeValueAsString(auditMessage)).thenThrow(new JsonProcessingException("Serialization error") {
        });

        auditService.postMessageToArtemis(auditMessage);

        verify(objectMapper).writeValueAsString(auditMessage);
        verify(jmsTemplate, never()).convertAndSend(anyString(), anyString());
    }
}
