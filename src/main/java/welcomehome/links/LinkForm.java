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
 * The link form, shared by creating and editing a link: a label, and a start and an end as local date and
 * time in the lock's time zone, as the browser's datetime-local input sends them.
 *
 * @param label      who the link is for
 * @param validFrom  the start, such as {@code 2030-11-01T15:00}
 * @param validUntil the end
 */
record LinkForm(String label, String validFrom, String validUntil) {

    static final int MAX_LABEL_LENGTH = 100;
    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** A new link's form: from now, for one day. */
    static LinkForm startingNow(ZoneId zone) {
        var now = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
        return new LinkForm("", INPUT_FORMAT.format(now), INPUT_FORMAT.format(now.plusDays(1)));
    }

    /** An existing link's form. */
    static LinkForm of(String label, Instant validFrom, Instant validUntil, ZoneId zone) {
        return new LinkForm(label, INPUT_FORMAT.format(validFrom.atZone(zone)), INPUT_FORMAT.format(validUntil.atZone(zone)));
    }

    /**
     * Checks the form: a label of 1 to {@link #MAX_LABEL_LENGTH} characters, and an end after the start and
     * after {@code now}. The start may be in the past, for a link valid from now on.
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
        return new Validation(strippedLabel, from, until, labelError, from == null, untilError);
    }

    /** Puts the form's values, and the errors if any, where the link-fields template fragment reads them. */
    void addTo(Model model, @Nullable Validation validation) {
        model.addAttribute("label", label);
        model.addAttribute("validFrom", validFrom);
        model.addAttribute("validUntil", validUntil);
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
     * @param labelError      whether the label is blank or too long
     * @param validFromError  whether the start is missing or not a date and time
     * @param validUntilError {@code invalid}, {@code before-start}, {@code past}, or null if the end is fine
     */
    record Validation(String label, @Nullable Instant validFrom, @Nullable Instant validUntil, boolean labelError,
                      boolean validFromError, @Nullable String validUntilError) {

        boolean isValid() {
            return !labelError && !validFromError && validUntilError == null;
        }
    }
}
