package uk.gov.hmcts.cp.cdk.correlation;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * The single definition of CDKS's correlation-ID convention (DD-43183 Story 1, ADR-001, ADR-002,
 * ADR-007): one canonical inbound header, one accepted alias, one MDC key, and one validation rule
 * applied to every externally sourced value.
 *
 * <p>Header names and the MDC key are compile-time constants rather than {@code @ConfigurationProperties}
 * — deliberate (GATE-1, ADR-001(6)): a header name another CPP service or the audit filter already
 * expects is a contract, not an environment-tunable value.
 */
@Slf4j
public final class CorrelationIds {

    /** Canonical inbound header — the CPP platform convention, already read by cp-audit-filter-springboot. */
    public static final String HEADER_CPP = "CPPCLIENTCORRELATIONID";

    /** Accepted inbound alias, response header, and second outbound header. Deprecated inbound, honoured indefinitely. */
    public static final String HEADER_X_CORRELATION_ID = "X-Correlation-Id";

    /** The one MDC key. Read into {@code DiscoveryTriggerResponse.correlationId} — do not rename (ADR-002). */
    public static final String MDC_KEY = "correlationId";

    private static final List<String> INBOUND_PRECEDENCE = List.of(HEADER_CPP, HEADER_X_CORRELATION_ID);

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final int MAX_LENGTH = 64;

    private CorrelationIds() {
        throw new AssertionError("No instances");
    }

    /**
     * Resolves the inbound correlation ID for a request: first non-blank of the canonical header
     * then the alias, each passed through {@link #sanitise(String)}; if neither yields a usable
     * value, a fresh one is generated. Never returns blank.
     */
    public static String resolveInbound(final HttpServletRequest request) {
        for (final String headerName : INBOUND_PRECEDENCE) {
            final String sanitised = sanitise(request.getHeader(headerName), headerName);
            if (sanitised != null) {
                return sanitised;
            }
        }
        return generate();
    }

    /**
     * Validates a single externally sourced value (an inbound header, or a {@code jobData} field)
     * against the allow-list and length bound. Returns {@code null} if the value is absent, blank,
     * or rejected — never throws, never returns a mangled/truncated variant of the input.
     */
    public static String sanitise(final String raw) {
        return sanitise(raw, null);
    }

    private static String sanitise(final String raw, final String sourceHeaderNameForLogging) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > MAX_LENGTH) {
            warnRejected(sourceHeaderNameForLogging, raw.length(), "too-long");
            return null;
        }
        if (!ALLOWED.matcher(raw).matches()) {
            warnRejected(sourceHeaderNameForLogging, raw.length(), "illegal-character");
            return null;
        }
        return raw;
    }

    private static void warnRejected(final String sourceHeaderNameForLogging, final int length, final String reason) {
        if (sourceHeaderNameForLogging != null) {
            log.warn("Rejected inbound correlation value on header={}, length={}, reason={}",
                    sourceHeaderNameForLogging, length, reason);
        } else {
            log.warn("Rejected correlation value, length={}, reason={}", length, reason);
        }
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Returns the ambient MDC correlation value if present and valid, else generates and writes a
     * fresh one into MDC. Use at a dispatch site that is about to seed a new unit of work's
     * {@code jobData} (JobManager) so the generated value is visible to whatever reads MDC next.
     */
    public static String currentOrGenerate() {
        final String current = sanitise(MDC.get(MDC_KEY));
        if (current != null) {
            return current;
        }
        final String generated = generate();
        MDC.put(MDC_KEY, generated);
        return generated;
    }

    /**
     * Returns the ambient MDC correlation value if present and valid, else generates one —
     * <strong>never writes MDC</strong>. Use in a context that must not mutate MDC as a side effect
     * of merely reading it (e.g. the outbound interceptor, which is MDC-read-only by design).
     */
    public static String currentOrRandom() {
        final String current = sanitise(MDC.get(MDC_KEY));
        return current != null ? current : generate();
    }
}
