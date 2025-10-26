package uk.gov.hmcts.cp.cdk.filters.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import uk.gov.hmcts.cp.cdk.filters.audit.model.AuditPayload;
import uk.gov.hmcts.cp.cdk.filters.audit.model.Metadata;

import static java.util.UUID.randomUUID;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
    void dontPostMessageToArtemis_WhenAuditPayloadIsNull() {

        auditService.postMessageToArtemis(null);

        verifyNoInteractions(objectMapper, jmsTemplate);
    }

    @Test
    void postMessageToArtemis_logsAndSendsMessageWhenSerializationSucceeds() throws JsonProcessingException, JMSException {
        AuditPayload auditPayload = mock(AuditPayload.class);
        String serializedMessage = "{\"key\":\"value\"}";
        when(objectMapper.writeValueAsString(auditPayload)).thenReturn(serializedMessage);
        when(auditPayload.timestamp()).thenReturn("2024-10-10T10:00:00Z");
        final String auditMethodName = "dummy-name";
        when(auditPayload._metadata()).thenReturn(Metadata.builder().id(randomUUID()).name(auditMethodName).build());

        auditService.postMessageToArtemis(auditPayload);
        // Inside your test method
        ArgumentCaptor<MessagePostProcessor> captor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(jmsTemplate).convertAndSend(eq("jms.topic.auditing.event"), eq(serializedMessage), captor.capture());

        Message mockMessage = mock(Message.class);
        captor.getValue().postProcessMessage(mockMessage);
        verify(mockMessage).setStringProperty(eq("CPPNAME"), eq(auditMethodName));

        verify(objectMapper).writeValueAsString(auditPayload);
    }

    @Test
    void postMessageToArtemis_logsErrorWhenSerializationFails() throws JsonProcessingException {
        AuditPayload auditPayload = mock(AuditPayload.class);
        when(objectMapper.writeValueAsString(auditPayload)).thenThrow(new JsonProcessingException("Serialization error") {
        });

        auditService.postMessageToArtemis(auditPayload);

        verify(objectMapper).writeValueAsString(auditPayload);
        verify(jmsTemplate, never()).convertAndSend(anyString(), anyString());
    }
}
