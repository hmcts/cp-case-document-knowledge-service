package uk.gov.hmcts.cp.cdk.util;

import static java.util.UUID.randomUUID;

import java.io.File;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

public class BrokerUtil implements AutoCloseable {

    private static final String TOPIC_NAME = "jms.topic.auditing.event";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;
    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();

    public BrokerUtil() throws Exception {
        System.setProperty("org.apache.activemq.artemis.use.global.client.thread.pool", "false");

        final String host = env("CP_CDK_ARTEMIS_HOST_PRIMARY", "localhost");
        final int port = Integer.parseInt(env("CP_CDK_ARTEMIS_PORT", "61617"));
        final boolean ssl = Boolean.parseBoolean(env("CP_CDK_ARTEMIS_SSL_ENABLED", "true"));
        final boolean verifyHost = Boolean.parseBoolean(env("CP_CDK_ARTEMIS_VERIFY_HOST", "false"));
        final String user = env("CP_CDK_ARTEMIS_USER", "");
        final String pass = env("CP_CDK_ARTEMIS_PASSWORD", "");

        String trustStorePath = env("CP_CDK_ARTEMIS_KEYSTORE", null);
        String trustStorePass = env("CP_CDK_ARTEMIS_KEYSTORE_PASSWORD", "changeit");
        if (trustStorePath == null || trustStorePath.isBlank()) {
            URL ksUrl = getClass().getClassLoader().getResource("ssl/keystore.jks");
            if (ksUrl == null) {
                throw new IllegalStateException("ssl/keystore.jks not found in resources");
            }
            trustStorePath = new File(ksUrl.toURI()).getAbsolutePath();
        }

        StringBuilder url = new StringBuilder("tcp://" + host + ":" + port + "?");
        url.append("ha=false");
        url.append("&reconnectAttempts=0");
        url.append("&initialConnectAttempts=10");
        url.append("&retryInterval=100");
        url.append("&connectionTTL=15000");
        url.append("&callTimeout=3000");
        url.append("&closeTimeout=2000");
        if (ssl) {
            url.append("&sslEnabled=true");
            url.append("&verifyHost=").append(verifyHost);
            url.append("&trustStorePath=").append(encode(trustStorePath));
            url.append("&trustStorePassword=").append(encode(trustStorePass));
        }

        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(url.toString());
        if (!user.isEmpty() || !pass.isEmpty()) {
            connectionFactory.setUser(Objects.toString(user, ""));
            connectionFactory.setPassword(Objects.toString(pass, ""));
        }

        connection = connectionFactory.createConnection();
        connection.setClientID(randomUUID().toString());
        connection.start();

        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        final String selector = "CPPNAME = 'audit.events.audit-recorded'";
        final Topic topic = session.createTopic(TOPIC_NAME);
        consumer = session.createConsumer(topic, selector);

        consumer.setMessageListener(message -> {
            if (message instanceof TextMessage textMessage) {
                try {
                    receivedMessages.add(textMessage.getText());
                } catch (JMSException ignore) {
                }
            }
        });
    }

    public String getMessageMatching(Predicate<JsonNode> matcher) throws Exception {
        long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (System.currentTimeMillis() < end) {
            String message = receivedMessages.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (message == null) break;

            JsonNode json = OBJECT_MAPPER.readTree(message);
            if (matcher.test(json)) {
                return message;
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

    private static String env(String key, String def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) v = System.getProperty(key, def);
        return v;
    }

    private static String encode(String s) {
        return s.replace(" ", "%20").replace(":", "%3A");
    }
}
