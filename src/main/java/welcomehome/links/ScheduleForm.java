package welcomehome.links;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * The link form's schedule: a row per day, Monday first, whose day may be ticked and whose hours the browser's time
 * inputs send, as {@code day-MONDAY}, {@code opens-MONDAY} and {@code closes-MONDAY}. No day ticked means any time.
 * A day that is not ticked keeps its hours in the form, and they count for nothing.
 *
 * @param days the seven rows
 */
record ScheduleForm(List<Day> days) {

    private static final String DEFAULT_OPENS = "09:00";
    private static final String DEFAULT_CLOSES = "17:00";
    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** A form with no day ticked. */
    static ScheduleForm empty() {
        return of(LinkSchedule.ANY_TIME);
    }

    /** The form as the browser sent it. */
    static ScheduleForm of(Map<String, String> params) {
        return new ScheduleForm(Arrays.stream(DayOfWeek.values())
                .map(day -> new Day(day, params.containsKey("day-" + day),
                        params.getOrDefault("opens-" + day, ""), params.getOrDefault("closes-" + day, "")))
                .toList());
    }

    /** An existing link's schedule. */
    static ScheduleForm of(LinkSchedule schedule) {
        return new ScheduleForm(Arrays.stream(DayOfWeek.values())
                .map(day -> schedule.days().stream().filter(hours -> hours.day() == day).findFirst()
                        .map(hours -> new Day(day, true, INPUT_FORMAT.format(hours.opens()), INPUT_FORMAT.format(hours.closes())))
                        .orElseGet(() -> new Day(day, false, DEFAULT_OPENS, DEFAULT_CLOSES)))
                .toList());
    }

    boolean anySelected() {
        return days.stream().anyMatch(Day::selected);
    }

    boolean isValid() {
        return days.stream().noneMatch(Day::invalid);
    }

    /** The schedule of a valid form. */
    LinkSchedule schedule() {
        return new LinkSchedule(days.stream().filter(Day::selected)
                .map(day -> new DayHours(day.day(), Objects.requireNonNull(parse(day.opens())),
                        Objects.requireNonNull(parse(day.closes()))))
                .toList());
    }

    private static @Nullable LocalTime parse(String time) {
        try {
            return LocalTime.parse(time);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * A day's row.
     *
     * @param day      the day
     * @param selected whether the link opens on it
     * @param opens    when, as the browser's time input sends it, such as {@code 09:00}
     * @param closes   until when
     */
    record Day(DayOfWeek day, boolean selected, String opens, String closes) {

        /** The day's name, capitalized for a row of its own, in the reader's language. Public for the template. */
        public String name(Locale locale) {
            return StringUtils.capitalize(day.getDisplayName(TextStyle.FULL, locale));
        }

        /**
         * A ticked day whose hours are missing or are not times, or that closes when it opens at another time than
         * midnight: 00:00 to 00:00 is the whole day, and 09:00 to 09:00 more likely a slip than 24 hours. Public for
         * the template.
         */
        public boolean invalid() {
            if (!selected) {
                return false;
            }
            var opening = parse(opens);
            var closing = parse(closes);
            return opening == null || closing == null
                    || (opening.equals(closing) && !opening.equals(LocalTime.MIDNIGHT));
        }
    }
}
