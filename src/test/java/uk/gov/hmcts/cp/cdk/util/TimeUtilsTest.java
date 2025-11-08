package uk.gov.hmcts.cp.cdk.util;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Time Utils tests")
class TimeUtilsTest {

    @Test
    @DisplayName("Utc Now returns Time With Utc Offset")
    void utcNow_returnsTimeWithUtcOffset() {
        final OffsetDateTime nowUtc = TimeUtils.utcNow();
        assertThat(nowUtc.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("To Utc null returns Null")
    void toUtc_null_returnsNull() {
        final OffsetDateTime result = TimeUtils.toUtc(null);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("To Utc from Offset Date Time converts To Utc Preserving Instant")
    void toUtc_fromOffsetDateTime_convertsToUtcPreservingInstant() {
        final OffsetDateTime source = OffsetDateTime.of(2025, 1, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2));
        final OffsetDateTime result = TimeUtils.toUtc(source);

        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(result.toInstant()).isEqualTo(source.toInstant()); // same point in time
        // also check wall-clock hour adjusted correctly (12:00+02:00 -> 10:00Z)
        assertThat(result.getHour()).isEqualTo(10);
    }

    @Test
    @DisplayName("To Utc from Instant converts To Utc Same Instant")
    void toUtc_fromInstant_convertsToUtcSameInstant() {
        final Instant instant = Instant.parse("2025-06-01T10:15:30Z");
        final OffsetDateTime result = TimeUtils.toUtc(instant);

        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(result.toInstant()).isEqualTo(instant);
    }

    @Test
    @DisplayName("To Utc from Local Date Time attaches Utc Offset")
    void toUtc_fromLocalDateTime_attachesUtcOffset() {
        final LocalDateTime localDateTime = LocalDateTime.of(2025, 10, 6, 9, 30, 0);
        final OffsetDateTime result = TimeUtils.toUtc(localDateTime);

        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(result.toLocalDateTime()).isEqualTo(localDateTime);
    }

    @Test
    @DisplayName("To Utc from Timestamp converts To Utc Same Instant")
    void toUtc_fromTimestamp_convertsToUtcSameInstant() {
        final Instant instant = Instant.parse("2025-03-04T05:06:07Z");
        final Timestamp timestamp = Timestamp.from(instant);

        final OffsetDateTime result = TimeUtils.toUtc(timestamp);

        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(result.toInstant()).isEqualTo(instant);
    }

    @Test
    @DisplayName("To Utc unsupported Type throws Illegal Argument Exception")
    void toUtc_unsupportedType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> TimeUtils.toUtc(42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported temporal type");
    }

    @Test
    @DisplayName("Constructor is Private And Throws Assertion Error")
    void constructor_isPrivateAndThrowsAssertionError() throws Exception {
        final Constructor<TimeUtils> ctor = TimeUtils.class.getDeclaredConstructor();
        ctor.setAccessible(true);

        assertThatThrownBy(ctor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class);
    }
}
