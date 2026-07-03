package uk.gov.hmcts.cp.cdk.services;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculates a contiguous window of hearing dates starting from the given date.
 *
 * Advances sequentially through calendar days, counting only weekdays toward
 * {@code daysAhead}. The window ends on the {@code daysAhead}-th weekday;
 * any weekend days that fall inside that window are included. Weekends are only
 * excluded when they would be the terminal date — the window extends to the next
 * weekday instead.
 */
@Component
public class HearingDatesCalculator {

    public List<LocalDate> calculate(final LocalDate hearingDate, final int daysAhead) {
        if (daysAhead <= 0) {
            return List.of();
        }

        final LocalDate endDate = findEndDate(hearingDate, daysAhead);

        final List<LocalDate> hearingDates = new ArrayList<>();
        for (LocalDate d = hearingDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            hearingDates.add(d);
        }
        return hearingDates;
    }

    private LocalDate findEndDate(final LocalDate from, final int daysAhead) {
        LocalDate current = from;
        int weekdayCount = 0;
        while (weekdayCount < daysAhead) {
            if (!isWeekend(current)) {
                weekdayCount++;
            }
            if (weekdayCount < daysAhead) {
                current = current.plusDays(1);
            }
        }
        return current;
    }

    private boolean isWeekend(final LocalDate date) {
        final DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }
}
