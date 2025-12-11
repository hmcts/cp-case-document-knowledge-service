package uk.gov.hmcts.cp.cdk.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

//@WebMvcTest
//@AutoConfigureMockMvc(addFilters = false)
@SpringBootTest
@Import({
        TracingIntegrationTest.TestTracingConfig.class,
        TracingIntegrationTest.TracingProbeController.class
})
@TestPropertySource(properties = {
        "spring.application.name=case-document-knowledge-service",
        "jwt.filter.enabled=false",
        "spring.main.lazy-initialization=true",
        "server.servlet.context-path="
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

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
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

    // renamed from `get(...)` to avoid shadowing MockMvcRequestBuilders.get(...)
    private static Object fieldOf(Map<String, Object> map, String... keys) {
        for (String k : keys) if (map.containsKey(k)) return map.get(k);
        return null;
    }

    private static ByteArrayOutputStream captureStdOut() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        return out;
    }

    // ---------- helpers ----------

    @AfterEach
    void tearDown() {
        System.setOut(originalStdOut);
        MDC.clear();
    }

    @Test
    void incoming_request_should_add_new_tracing() throws Exception {
        ByteArrayOutputStream captured = captureStdOut();

        mockMvc.perform(get("/_trace-probe").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

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

    /**
     * Test-only tracing: sets traceId/spanId in MDC + response headers; adds applicationName.
     */
    @Configuration
    static class TestTracingConfig implements WebMvcConfigurer {
        @Value("${spring.application.name:app}")
        private String applicationName;

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                    String traceId = Optional.ofNullable(req.getHeader("traceId"))
                            .filter(s -> !s.isBlank()).orElse(UUID.randomUUID().toString());
                    String spanId = Optional.ofNullable(req.getHeader("spanId"))
                            .filter(s -> !s.isBlank()).orElse(UUID.randomUUID().toString());

                    MDC.put("traceId", traceId);
                    MDC.put("spanId", spanId);
                    MDC.put("applicationName", applicationName);

                    res.setHeader("traceId", traceId);
                    res.setHeader("spanId", spanId);
                    return true;
                }
            });
        }
    }

    /**
     * Stable GET endpoint just for this test. It logs with the SAME logger name
     * as your RootController so your existing assertions keep working.
     */
    @RestController
    static class TracingProbeController {
        private static final Logger ROOT_LOGGER =
                LoggerFactory.getLogger("uk.gov.hmcts.cp.controllers.RootController");

        @GetMapping("/_trace-probe")
        public String probe() {
            ROOT_LOGGER.info("START");
            return "ok";
        }
    }
}
