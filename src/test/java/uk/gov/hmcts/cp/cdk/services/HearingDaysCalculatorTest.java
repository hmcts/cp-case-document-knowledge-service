package uk.gov.hmcts.cp.cdk.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Reference week: 2026-07-06 (Mon) → 2026-07-12 (Sun), next Mon = 2026-07-13
@DisplayName("HearingDatesCalculator tests")
class HearingDaysCalculatorTest {

    private static final LocalDate MON  = LocalDate.of(2026, 7,  6);
    private static final LocalDate TUE  = LocalDate.of(2026, 7,  7);
    private static final LocalDate WED  = LocalDate.of(2026, 7,  8);
    private static final LocalDate THU  = LocalDate.of(2026, 7,  9);
    private static final LocalDate FRI  = LocalDate.of(2026, 7, 10);
    private static final LocalDate SAT  = LocalDate.of(2026, 7, 11);
    private static final LocalDate SUN  = LocalDate.of(2026, 7, 12);
    private static final LocalDate MON2 = LocalDate.of(2026, 7, 13);
    private static final LocalDate TUE2 = LocalDate.of(2026, 7, 14);
    private static final LocalDate WED2 = LocalDate.of(2026, 7, 15);
    private static final LocalDate THU2 = LocalDate.of(2026, 7, 16);
    private static final LocalDate FRI2 = LocalDate.of(2026, 7, 17);

    private HearingDaysCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new HearingDaysCalculator();
    }

    // -------------------------------------------------------------------------
    // Monday, Tuesday, Wednesday starts
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Monday start returns exactly numberOfDays consecutive weekdays")
    void calculate_monday_returnsConsecutiveWeekdays() {
        assertThat(calculator.calculate(MON, 3))
                .containsExactly(MON, TUE, WED);
    }

    @Test
    @DisplayName("Tuesday start returns exactly numberOfDays consecutive weekdays")
    void calculate_tuesday_returnsConsecutiveWeekdays() {
        assertThat(calculator.calculate(TUE, 3))
                .containsExactly(TUE, WED, THU);
    }

    @Test
    @DisplayName("Wednesday start returns exactly numberOfDays consecutive weekdays")
    void calculate_wednesday_returnsConsecutiveWeekdays() {
        assertThat(calculator.calculate(WED, 3))
                .containsExactly(WED, THU, FRI);
    }

    @Test
    @DisplayName("Monday start with five days skips the weekend entirely")
    void calculate_monday_fiveDays_skipsWeekend() {
        assertThat(calculator.calculate(MON, 5))
                .containsExactly(MON, TUE, WED, THU, FRI);
    }

    @Test
    @DisplayName("Monday start with six days bridges weekend, including Saturday and Sunday as intermediate days")
    void calculate_monday_sixDays_bridgesWeekend() {
        assertThat(calculator.calculate(MON, 6))
                .containsExactly(MON, TUE, WED, THU, FRI, SAT, SUN, MON2);
    }

    // -------------------------------------------------------------------------
    // Thursday, Friday starts — window crosses the weekend
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Thursday start includes weekend bridge; result has more than numberOfDays items")
    void calculate_thursday_includesWeekendBridge() {
        final List<LocalDate> result = calculator.calculate(THU, 3);
        assertThat(result).containsExactly(THU, FRI, SAT, SUN, MON2);
    }

    @Test
    @DisplayName("Friday start includes weekend bridge; result spans to Monday and Tuesday")
    void calculate_friday_includesWeekendBridge() {
        assertThat(calculator.calculate(FRI, 3))
                .containsExactly(FRI, SAT, SUN, MON2, TUE2);
    }

    @Test
    @DisplayName("Thursday with five days returns all seven sequential days covering both weekends")
    void calculate_thursday_fiveDays_coversFullSequence() {
        assertThat(calculator.calculate(THU, 5))
                .containsExactly(THU, FRI, SAT, SUN, MON2, TUE2, WED2);
    }

    // -------------------------------------------------------------------------
    // Saturday, Sunday starts — window opens on a weekend
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Saturday start includes both Saturday and Sunday as the opening sequence")
    void calculate_saturday_includesBothWeekendDays() {
        assertThat(calculator.calculate(SAT, 3))
                .containsExactly(SAT, SUN, MON2, TUE2, WED2);
    }

    @Test
    @DisplayName("Sunday start includes the opening Sunday before weekdays")
    void calculate_sunday_includesStartingSunday() {
        assertThat(calculator.calculate(SUN, 3))
                .containsExactly(SUN, MON2, TUE2, WED2);
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Zero days returns an empty list")
    void calculate_zeroDays_returnsEmpty() {
        assertThat(calculator.calculate(MON, 0)).isEmpty();
    }

    @Test
    @DisplayName("One day from Monday returns only Monday")
    void calculate_monday_oneDay_returnsOnlyMonday() {
        assertThat(calculator.calculate(MON, 1))
                .containsExactly(MON);
    }

    @Test
    @DisplayName("One day from Saturday includes Saturday but counts toward the next Monday")
    void calculate_saturday_oneDay_returnsSaturdaySundayAndMonday() {
        assertThat(calculator.calculate(SAT, 1))
                .containsExactly(SAT, SUN, MON2);
    }

    @Test
    @DisplayName("Result size equals daysAhead when no weekend falls inside the window")
    void calculate_noWeekendInWindow_resultSizeEqualsNumberOfDays() {
        assertThat(calculator.calculate(MON, 4)).hasSize(4);  // Mon–Thu, no weekend crossed
        assertThat(calculator.calculate(TUE, 4)).hasSize(4);  // Tue–Fri, no weekend crossed
    }

    @Test
    @DisplayName("Wednesday start with four days crosses the weekend and includes Saturday and Sunday")
    void calculate_wednesday_fourDays_crossesWeekend_includesSaturdayAndSunday() {
        assertThat(calculator.calculate(WED, 4))
                .containsExactly(WED, THU, FRI, SAT, SUN, MON2);
    }

    @Test
    @DisplayName("Result has more items than daysAhead when the window spans a weekend")
    void calculate_windowSpansWeekend_resultSizeExceedsDaysAhead() {
        // Thu → 3 weekdays counted, but result has 5 items (Sat + Sun included as intermediaries)
        assertThat(calculator.calculate(THU, 3)).hasSizeGreaterThan(3);
    }
}
