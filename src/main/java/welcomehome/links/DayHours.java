package welcomehome.links;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

/**
 * The hours at which a link opens on one day of the week, local to its lock: from {@code opens}, inclusive, to
 * {@code closes}, exclusive. A closing time not after the opening time is on the next day, so that 22:00 to 02:00
 * crosses midnight and 00:00 to 00:00 is the whole day; such hours belong to the day they start on.
 *
 * @param day    the day they start on
 * @param opens  when the link starts to open the door
 * @param closes when it stops
 */
public record DayHours(DayOfWeek day, LocalTime opens, LocalTime closes) {

    /** Whether a local date and time falls within these hours, this week's or any other's. */
    boolean covers(LocalDateTime at) {
        var time = at.toLocalTime();
        if (at.getDayOfWeek() == day && !time.isBefore(opens) && (endsNextDay() || time.isBefore(closes))) {
            return true;
        }
        return endsNextDay() && at.getDayOfWeek() == day.plus(1) && time.isBefore(closes);
    }

    /** When these hours next start, after a local date and time. */
    LocalDateTime nextOpeningAfter(LocalDateTime at) {
        var opening = at.toLocalDate().with(TemporalAdjusters.nextOrSame(day)).atTime(opens);
        return opening.isAfter(at) ? opening : opening.plusWeeks(1);
    }

    boolean isAllDay() {
        return opens.equals(LocalTime.MIDNIGHT) && closes.equals(LocalTime.MIDNIGHT);
    }

    private boolean endsNextDay() {
        return !closes.isAfter(opens);
    }
}
