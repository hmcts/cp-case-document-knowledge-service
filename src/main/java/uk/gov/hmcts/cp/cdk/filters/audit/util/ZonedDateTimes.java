package uk.gov.hmcts.cp.cdk.filters.audit.util;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class ZonedDateTimes {

    private ZonedDateTimes() {
    }

    public static String toString(final ZonedDateTime source) {
        return source.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
    }
}
