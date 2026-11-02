package welcomehome.links;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import welcomehome.db.OwnerTransactions;

/** Reads a link's schedule and says it in a person's language. */
@Service
public class LinkSchedules {

    private final LinkDao links;
    private final OwnerTransactions ownerTransactions;
    private final MessageSource messages;

    LinkSchedules(LinkDao links, OwnerTransactions ownerTransactions, MessageSource messages) {
        this.links = links;
        this.ownerTransactions = ownerTransactions;
        this.messages = messages;
    }

    /**
     * Reads a link's schedule, in its owner's context: for a guest, only once the link's token is verified.
     *
     * @param ownerId the link's owner
     * @param linkId  the link
     * @return its schedule, which allows any time if it has no hours
     */
    public LinkSchedule of(UUID ownerId, UUID linkId) {
        return ownerTransactions.execute(ownerId, () -> links.findSchedule(ownerId, linkId));
    }

    /**
     * Says a schedule in a language, a line per run of consecutive days with the same hours, such as
     * "Monday to Friday: 09:00–17:00".
     *
     * @param schedule the schedule
     * @param locale   the reader's language
     * @return its lines, none if it allows any time
     */
    public List<String> describe(LinkSchedule schedule, Locale locale) {
        var lines = new ArrayList<String>();
        var days = schedule.days();
        for (int start = 0, end; start < days.size(); start = end + 1) {
            end = start;
            while (end + 1 < days.size() && continues(days.get(end), days.get(end + 1))) {
                end++;
            }
            lines.add(StringUtils.capitalize(message("link-schedule.line", locale,
                    dayRange(days.get(start), days.get(end), locale), hours(days.get(start), locale))));
        }
        return lines;
    }

    /** A later day that is the next one, with the same hours. Monday comes first, so no run wraps around Sunday. */
    private static boolean continues(DayHours day, DayHours next) {
        return next.day() == day.day().plus(1) && next.opens().equals(day.opens()) && next.closes().equals(day.closes());
    }

    private String dayRange(DayHours first, DayHours last, Locale locale) {
        return first.day() == last.day() ? dayName(first, locale)
                : message("link-schedule.days", locale, dayName(first, locale), dayName(last, locale));
    }

    private static String dayName(DayHours day, Locale locale) {
        return day.day().getDisplayName(TextStyle.FULL, locale);
    }

    private String hours(DayHours day, Locale locale) {
        if (day.isAllDay()) {
            return message("link-schedule.all-day", locale);
        }
        var format = DateTimeFormatter.ofPattern(message("format.time", locale));
        return message("link-schedule.hours", locale, format.format(day.opens()), format.format(day.closes()));
    }

    private String message(String key, Locale locale, Object... arguments) {
        return messages.getMessage(key, arguments, locale);
    }
}
