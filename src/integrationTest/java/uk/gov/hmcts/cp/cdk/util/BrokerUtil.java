package uk.gov.hmcts.cp.cdk.http;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Assertions;

public class BrokerUtil {

    private static final String BROKER_URL = "tcp://localhost:61616"; // match your app config
    private static final String TOPIC_NAME = "jms.topic.auditing.event";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();

    public BrokerUtil() throws Exception {
        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(BROKER_URL);
        connection = connectionFactory.createConnection();
        connection.setClientID("test-client"); // required for durable subscriptions
        connection.start();

        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic(TOPIC_NAME);
        consumer = session.createConsumer(topic);

        consumer.setMessageListener(message -> {
            if (message instanceof TextMessage) {
                try {
                    receivedMessages.add(((TextMessage) message).getText());
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


    public String getMessageMatching() throws Exception {
        String message = receivedMessages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        return message;
    }

    public void teardown() throws Exception {
        consumer.close();
        session.close();
        connection.close();
    }
}