package uk.gov.hmcts.cp.cdk.util;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Time helpers centralised for reuse  cleanliness. */
public final class TimeUtils {

    private TimeUtils() {
        throw new AssertionError("No instances");
    }

    /** @return current time in UTC */
    public static OffsetDateTime utcNow() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Convert common JDBC/driver temporals to UTC {@link OffsetDateTime}. */
    public static OffsetDateTime toUtc(final Object temporal) {
        return switch (temporal) {
            case null -> null;
            case OffsetDateTime odt -> odt.withOffsetSameInstant(ZoneOffset.UTC);
            case Instant instant -> instant.atOffset(ZoneOffset.UTC);
            case LocalDateTime localDateTime -> localDateTime.atOffset(ZoneOffset.UTC);
            case Timestamp timestamp -> timestamp.toInstant().atOffset(ZoneOffset.UTC);
            default -> throw new IllegalArgumentException(
                    "Unsupported temporal type: " + temporal.getClass().getName()
            );
        };
    }
}
