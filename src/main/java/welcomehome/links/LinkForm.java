package welcomehome.links;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import org.jspecify.annotations.Nullable;
import org.springframework.ui.Model;

/**
 * The link form, shared by creating and editing a link: a label, a start and an end as local date and time in the
 * lock's time zone, as the browser's datetime-local input sends them, and the days and hours at which it opens.
 *
 * @param label      who the link is for
 * @param validFrom  the start, such as {@code 2030-11-01T15:00}
 * @param validUntil the end
 * @param schedule   the days and hours
 * @param pin        the PIN the guest must type, or blank for none
 */
record LinkForm(String label, String validFrom, String validUntil, ScheduleForm schedule, String pin) {

    static final int MAX_LABEL_LENGTH = 100;
    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** A new link's form: from now, for one day. */
    static LinkForm startingNow(ZoneId zone) {
        var now = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
        return new LinkForm("", INPUT_FORMAT.format(now), INPUT_FORMAT.format(now.plusDays(1)), ScheduleForm.empty(),
                "");
    }

    /** An existing link's form. */
    static LinkForm of(String label, Instant validFrom, Instant validUntil, LinkSchedule schedule, ZoneId zone,
                       String pin) {
        return new LinkForm(label, INPUT_FORMAT.format(validFrom.atZone(zone)), INPUT_FORMAT.format(validUntil.atZone(zone)),
                ScheduleForm.of(schedule), pin);
    }

    /**
     * Checks the form: a label of 1 to {@link #MAX_LABEL_LENGTH} characters, an end after the start and after
     * {@code now}, and a schedule with hours that are fine on each day, under which the link can still open before
     * its end, and a PIN, if any, of {@value LinkPins#MIN_LENGTH} to {@value LinkPins#MAX_LENGTH} digits. The start may
     * be in the past, for a link valid from now on.
     */
    Validation validate(ZoneId zone, Instant now) {
        var strippedLabel = label.strip();
        var from = parse(validFrom, zone);
        var until = parse(validUntil, zone);
        String untilError = null;
        if (until == null) {
            untilError = "invalid";
        } else if (from != null && !until.isAfter(from)) {
            untilError = "before-start";
        } else if (!until.isAfter(now)) {
            untilError = "past";
        }
        var labelError = strippedLabel.isEmpty() || strippedLabel.length() > MAX_LABEL_LENGTH;
        var checkedSchedule = schedule.isValid() ? schedule.schedule() : LinkSchedule.ANY_TIME;
        var neverOpens = from != null && until != null && untilError == null
                && checkedSchedule.firstOpening(from.isAfter(now) ? from : now, until, zone).isEmpty();
        var strippedPin = pin.strip();
        var pinError = !strippedPin.isEmpty() && !LinkPins.isValid(strippedPin);
        return new Validation(strippedLabel, from, until, checkedSchedule, labelError, from == null, untilError,
                !schedule.isValid(), neverOpens, strippedPin.isEmpty() ? null : strippedPin, pinError);
    }

    /** Puts the form's values, and the errors if any, where the link-fields template fragment reads them. */
    void addTo(Model model, @Nullable Validation validation) {
        model.addAttribute("label", label);
        model.addAttribute("validFrom", validFrom);
        model.addAttribute("validUntil", validUntil);
        model.addAttribute("scheduleDays", schedule.days());
        model.addAttribute("scheduleOpen", schedule.anySelected());
        model.addAttribute("scheduleNeverOpens", validation != null && validation.scheduleNeverOpens());
        model.addAttribute("pin", pin);
        model.addAttribute("pinError", validation != null && validation.pinError());
        model.addAttribute("pinMinLength", LinkPins.MIN_LENGTH);
        model.addAttribute("pinMaxLength", LinkPins.MAX_LENGTH);
        if (validation != null) {
            model.addAttribute("labelError", validation.labelError());
            model.addAttribute("validFromError", validation.validFromError());
            model.addAttribute("validUntilError", validation.validUntilError());
        }
    }

    /**
     * A local date and time in the lock's zone. In a daylight saving gap the time moves forward by the gap's
     * length; in an overlap the earlier of the two instants is used.
     */
    private static @Nullable Instant parse(String localDateTime, ZoneId zone) {
        try {
            return LocalDateTime.parse(localDateTime).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The checked form.
     *
     * @param label           the label without surrounding spaces
     * @param validFrom       the start, or null if it is not a date and time
     * @param validUntil      the end, or null if it is not a date and time
     * @param schedule        the days and hours, or any time if {@code scheduleError}
     * @param labelError      whether the label is blank or too long
     * @param validFromError  whether the start is missing or not a date and time
     * @param validUntilError {@code invalid}, {@code before-start}, {@code past}, or null if the end is fine
     * @param scheduleError   whether a day's hours are wrong, which its row shows
     * @param scheduleNeverOpens whether the schedule leaves no time at which the link could still open
     * @param pin             the PIN without surrounding spaces, or null for none
     * @param pinError        whether the PIN is not {@value LinkPins#MIN_LENGTH} to {@value LinkPins#MAX_LENGTH} digits
     */
    record Validation(String label, @Nullable Instant validFrom, @Nullable Instant validUntil, LinkSchedule schedule,
                      boolean labelError, boolean validFromError, @Nullable String validUntilError,
                      boolean scheduleError, boolean scheduleNeverOpens, @Nullable String pin, boolean pinError) {

        boolean isValid() {
            return !labelError && !validFromError && validUntilError == null && !scheduleError && !scheduleNeverOpens
                    && !pinError;
        }
    }
}
