package uk.gov.hmcts.cp.cdk.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code traceId} and {@code spanId} are reserved to Micrometer Tracing's own
 * {@code Slf4JEventListener} (ADR-002) — no {@code src/main} source file may write either key to
 * MDC. A regression here would silently reintroduce the destructive collision DD-43183 removes.
 */
class MdcReservedKeyTest {

    private static final Path SRC_MAIN_JAVA = Path.of("src", "main", "java");
    private static final Pattern MDC_PUT_TRACE_OR_SPAN_ID = Pattern.compile(
            "MDC\\.put\\(\\s*\"(traceId|spanId)\"");

    @Test
    @DisplayName("AC-005: no src/main source file contains an MDC.put of traceId or spanId")
    void noSourceFileWritesTraceIdOrSpanIdToMdc() throws IOException {
        final List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SRC_MAIN_JAVA)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            final String content = Files.readString(path);
                            if (MDC_PUT_TRACE_OR_SPAN_ID.matcher(content).find()) {
                                offenders.add(path.toString());
                            }
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        assertThat(offenders)
                .as("traceId and spanId are reserved to Micrometer Tracing (ADR-002); "
                        + "CDKS must never MDC.put either")
                .isEmpty();
    }
}
