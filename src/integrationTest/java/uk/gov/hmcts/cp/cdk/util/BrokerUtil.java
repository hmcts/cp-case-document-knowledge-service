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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrokerUtil implements AutoCloseable {

    private static final String TOPIC_NAME = "jms.topic.auditing.event";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger LOGGER =
            LoggerFactory.getLogger(BrokerUtil.class);
    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;
    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
    private final ActiveMQConnectionFactory connectionFactory;

    public BrokerUtil() throws Exception {
        System.setProperty("org.apache.activemq.artemis.use.global.client.thread.pool", "false");

        final String host = env("CP_CDK_ARTEMIS_HOST_PRIMARY", "localhost");
        final int port = Integer.parseInt(env("CP_CDK_ARTEMIS_PORT", "61617"));
        final boolean ssl = Boolean.parseBoolean(env("CP_CDK_ARTEMIS_SSL_ENABLED", "true"));
        final boolean verifyHost = Boolean.parseBoolean(env("CP_CDK_ARTEMIS_VERIFY_HOST", "false"));
        final String user = env("CP_CDK_ARTEMIS_USER", "");
        final String pass = env("CP_CDK_ARTEMIS_PASSWORD", "");

        String trustStorePath = env("CP_CDK_ARTEMIS_KEYSTORE", null);
        final String trustStorePass = env("CP_CDK_ARTEMIS_KEYSTORE_PASSWORD", "changeit");
        if (trustStorePath == null || trustStorePath.isBlank()) {
            final URL ksUrl = Thread.currentThread().getContextClassLoader().getResource("ssl/keystore.jks");
            if (ksUrl == null) {
                throw new IllegalStateException("ssl/keystore.jks not found in resources");
            }
            trustStorePath = new File(ksUrl.toURI()).getAbsolutePath();
        }

        String baseUrl = String.format(
                "tcp://%s:%d?ha=false&reconnectAttempts=0&initialConnectAttempts=10&retryInterval=100&connectionTTL=15000&callTimeout=3000",
                host, port
        );

        if (ssl) {
            baseUrl += String.format(
                    "&sslEnabled=true&verifyHost=%b&trustStorePath=%s&trustStorePassword=%s",
                    verifyHost, encode(trustStorePath), encode(trustStorePass)
            );
        }
        connectionFactory = new ActiveMQConnectionFactory(baseUrl);
        if (!user.isEmpty() || !pass.isEmpty()) {
            connectionFactory.setUser(Objects.toString(user, ""));
            connectionFactory.setPassword(Objects.toString(pass, ""));
        }

        connection = connectionFactory.createConnection();
        connection.setClientID(

                randomUUID().toString());
        connection.start();

        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        final String selector = "CPPNAME = 'audit.events.audit-recorded'";
        final Topic topic = session.createTopic(TOPIC_NAME);
        consumer = session.createConsumer(topic, selector);

        consumer.setMessageListener(message ->

        {
            if (message instanceof TextMessage textMessage) {
                try {
                    receivedMessages.add(textMessage.getText());
                } catch (JMSException ignore) {
                    LOGGER.warn(ignore.getMessage());
                }
            }
        });


    }

    private static String env(final String key, final String def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            v = System.getProperty(key, def);
        }
        return v;
    }

    private static String encode(final String s) {
        return s.replace(" ", "%20").replace(":", "%3A");
    }

    public String getMessageMatching(final Predicate<JsonNode> matcher) throws Exception {
        final long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        String matchMessage = null;
        while (System.currentTimeMillis() < end) {
            final String message = receivedMessages.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (message == null) {
                break;
            }

            final JsonNode json = OBJECT_MAPPER.readTree(message);
            if (matcher.test(json)) {
                matchMessage = message;
                break;
            }
        }
        return matchMessage;
    }

    @Override
    public void close() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (session != null) {
            session.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (connectionFactory != null) {
            connectionFactory.close();
        }

    }
}
