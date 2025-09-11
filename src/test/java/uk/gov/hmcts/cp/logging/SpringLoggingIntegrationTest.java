package uk.gov.hmcts.cp.logging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Slf4j
public class SpringLoggingIntegrationTest {

    private PrintStream originalStdOut = System.out;

    @AfterEach
    void afterEach() {
        System.setOut(originalStdOut);
    }

    @Test
    void springboot_test_should_log_correct_fields() throws IOException {
        MDC.put("any-mdc-field", "1234-1234");
        ByteArrayOutputStream capturedStdOut = captureStdOut();
        log.info("spring boot test message");

        Map<String, Object> capturedFields = new ObjectMapper().readValue(capturedStdOut.toString(), new TypeReference<>() {
        });

        assertThat(capturedFields.get("any-mdc-field")).isEqualTo("1234-1234");
        assertThat(capturedFields.get("timestamp")).isNotNull();
        assertThat(capturedFields.get("logger_name")).isEqualTo("uk.gov.hmcts.cp.logging.SpringLoggingIntegrationTest");
        assertThat(capturedFields.get("thread_name")).isEqualTo("Test worker");
        assertThat(capturedFields.get("level")).isEqualTo("INFO");
        assertThat(capturedFields.get("message")).isEqualTo("spring boot test message");
    }

    private ByteArrayOutputStream captureStdOut() {
        final ByteArrayOutputStream capturedStdOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedStdOut));
        return capturedStdOut;
    }
}
