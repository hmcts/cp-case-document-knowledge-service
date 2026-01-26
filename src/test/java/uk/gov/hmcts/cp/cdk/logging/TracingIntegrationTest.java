package uk.gov.hmcts.cp.cdk.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@TestPropertySource(properties = {
        "spring.application.name=case-document-knowledge-service",
        "jwt.filter.enabled=false"
})
@WebMvcTest(controllers = TracingProbeController.class)
@ContextConfiguration(classes = {
        TracingProbeController.class,
        TestTracingConfig.class
})
@Slf4j
class TracingIntegrationTest {

    private final PrintStream originalStdOut = System.out;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext wac;

    @Value("${spring.application.name}")
    private String springApplicationName;

    @AfterEach
    void tearDown() {
        System.setOut(originalStdOut);
        MDC.clear();
    }

    @Test
    void incoming_request_should_add_new_tracing() throws Exception {
        ByteArrayOutputStream captured = captureStdOut();

        mockMvc.perform(get("/_trace-probe")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("traceId"));

        Map<String, Object> fields = parseLastJsonLine(captured);
        assertThat(fields.get("traceId")).as("traceId").isNotNull();
        assertThat(fields.get("spanId")).as("spanId").isNotNull();

        // logger name can be abbreviated by Logback; assert on the stable tail
        String loggerName = String.valueOf(fieldOf(fields, "logger_name", "logger"));
        assertThat(loggerName)
                .as("logger name")
                .matches("(^|.*\\.)RootController$");  // accepts "RootController", "u.g.h.cp.controllers.RootController", or full FQCN

        assertThat(fieldOf(fields, "message")).isEqualTo("START");

        assertThat(fieldOf(fields, "message")).isEqualTo("START");
    }

    @Test
    void incoming_request_with_traceId_should_pass_through() throws Exception {
        ByteArrayOutputStream captured = captureStdOut();

        var result = mockMvc.perform(
                get("/_trace-probe")
                        .header("traceId", "1234-1234")
                        .header("spanId", "567-567")
                        .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk()).andReturn();

        Map<String, Object> fields = parseLastJsonLine(captured);
        assertThat(fields.get("traceId")).isEqualTo("1234-1234");
        assertThat(fields.get("spanId")).isEqualTo("567-567");
        assertThat(fields.get("applicationName")).isEqualTo(springApplicationName);

        assertThat(result.getResponse().getHeader("traceId")).isEqualTo(fields.get("traceId"));
        assertThat(result.getResponse().getHeader("spanId")).isEqualTo(fields.get("spanId"));
    }

    private static Map<String, Object> parseLastJsonLine(ByteArrayOutputStream buf) throws Exception {
        String[] lines = buf.toString().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && line.startsWith("{") && line.endsWith("}")) {
                return new ObjectMapper().readValue(line, new TypeReference<>() {
                });
            }
        }
        throw new IllegalStateException("No JSON log line found on STDOUT");
    }

    private static Object fieldOf(Map<String, Object> map, String... keys) {
        for (String k : keys) if (map.containsKey(k)) return map.get(k);
        return null;
    }

    private static ByteArrayOutputStream captureStdOut() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        return out;
    }

}
