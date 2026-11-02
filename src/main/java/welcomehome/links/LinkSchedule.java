package welcomehome.links;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The days and hours at which a link opens, within its dates, whichever way those fall: the dates say whether a
 * link is valid ({@link LinkStatus}), and its schedule at what times of the week. Hours are local to the lock, so
 * they follow its clocks when they change. A link without any hours opens at any time.
 *
 * @param days the hours of each day that has any, Monday first
 */
public record LinkSchedule(List<DayHours> days) {

    /** The schedule of a link that opens at any time within its dates. */
    public static final LinkSchedule ANY_TIME = new LinkSchedule(List.of());

    /**
     * Orders the days, Monday first.
     *
     * @param days the hours of each day that has any
     */
    public LinkSchedule {
        days = days.stream().sorted(Comparator.comparing(DayHours::day)).toList();
    }

    /**
     * Tells whether the schedule lets the link open at an instant.
     *
     * @param at   the instant
     * @param zone the lock's time zone
     * @return whether the instant is within the schedule, as every instant is when it has no hours
     */
    public boolean allows(Instant at, ZoneId zone) {
        var local = at.atZone(zone).toLocalDateTime();
        return days.isEmpty() || days.stream().anyMatch(hours -> hours.covers(local));
    }

    /**
     * Finds when the link can first open within a period.
     *
     * @param from  the period's start
     * @param until the period's end, exclusive
     * @param zone  the lock's time zone
     * @return {@code from} if the schedule allows it, or else when its hours next start, or empty if that is not
     * within the period
     */
    public Optional<ZonedDateTime> firstOpening(Instant from, Instant until, ZoneId zone) {
        if (!from.isBefore(until)) {
            return Optional.empty();
        }
        if (allows(from, zone)) {
            return Optional.of(from.atZone(zone));
        }
        var local = from.atZone(zone).toLocalDateTime();
        return days.stream()
                .map(hours -> hours.nextOpeningAfter(local).atZone(zone))
                .min(Comparator.comparing(ZonedDateTime::toInstant))
                .filter(opening -> opening.toInstant().isBefore(until));
    }
}
