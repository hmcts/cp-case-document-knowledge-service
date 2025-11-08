package uk.gov.hmcts.cp.cdk.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static java.util.UUID.randomUUID;

public class BrokerUtil implements AutoCloseable {

    private static final String BROKER_URL = "tcp://localhost:61616"; // match your app config
    private static final String TOPIC_NAME = "jms.topic.auditing.event";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;
    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();

    public BrokerUtil() throws Exception {

        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(BROKER_URL);
        connection = connectionFactory.createConnection();
        connection.setClientID(randomUUID().toString()); // required for durable subscriptions
        connection.start();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        final String selector = "CPPNAME = 'audit.events.audit-recorded'";
        final Topic topic = session.createTopic(TOPIC_NAME);
        consumer = session.createConsumer(topic, selector);

        consumer.setMessageListener(message -> {
            if (message instanceof TextMessage textMessage) {
                try {
                    receivedMessages.add(textMessage.getText());
                } catch (JMSException e) {
                    // do something
                }
            }
        });
    }

    public String getMessageMatching(Predicate<JsonNode> matcher) throws Exception {
        long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (System.currentTimeMillis() < end) {
            String message = receivedMessages.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (message == null) break;
            System.out.println(message);
            try {
                JsonNode json = OBJECT_MAPPER.readTree(message);
                if (matcher.test(json)) {
                    return message;
                }
            } catch (Exception e) {
                throw e;
            }
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        consumer.close();
        session.close();
        connection.close();
    }
}