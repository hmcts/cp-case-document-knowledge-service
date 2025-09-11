package uk.gov.hmcts.cp.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class JunitLoggingTest {

    @Test
    void junit_should_log_correct_fields() throws JsonProcessingException {
        MDC.put("traceId", "1234-1234");
        ByteArrayOutputStream capturedStdOut = captureStdOut();
        log.info("junit test message");

        Map<String, Object> capturedFields = new ObjectMapper().readValue(capturedStdOut.toString(), new TypeReference<>() {
        });

        assertThat(capturedFields.get("traceId")).isEqualTo("1234-1234");
        assertThat(capturedFields.get("timestamp")).isNotNull();
        assertThat(capturedFields.get("logger_name")).isEqualTo("uk.gov.hmcts.cp.logging.JunitLoggingTest");
        assertThat(capturedFields.get("thread_name")).isEqualTo("Test worker");
        assertThat(capturedFields.get("level")).isEqualTo("INFO");
        assertThat(capturedFields.get("message")).isEqualTo("junit test message");
    }

    private ByteArrayOutputStream captureStdOut() {
        final ByteArrayOutputStream capturedStdOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedStdOut));
        return capturedStdOut;
    }
}
