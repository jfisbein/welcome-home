package welcomehome.audit;

import java.time.ZoneId;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import welcomehome.audit.AttemptRow.Origin;

/**
 * Turns recorded attempts into rows for the home page and the history: the time in the lock's zone, the outcome
 * in words and the guest's country, both in the reader's language.
 */
@Component
class AttemptRows {

    /** Cloudflare's code for requests from the Tor network. */
    static final String TOR = "T1";

    /** 🇦, the regional indicator symbol for the letter A. */
    private static final int REGIONAL_INDICATOR_A = 0x1F1E6;

    private final MessageSource messages;

    AttemptRows(MessageSource messages) {
        this.messages = messages;
    }

    /**
     * The row of an attempt.
     *
     * @param zone   the time zone to show it in
     * @param locale the language of its outcome and country
     */
    AttemptRow row(StoredAttempt attempt, ZoneId zone, Locale locale) {
        return new AttemptRow(attempt.occurredAt().atZoneSameInstant(zone), attempt.lockId(), attempt.lockName(),
                attempt.linkId(), attempt.linkLabel(), attempt.entranceName(), attempt.result(),
                outcome(attempt.reason(), locale), origin(attempt.ip(), attempt.country(), locale));
    }

    /** What an event's reason code means to the owner; a code no outcome has is shown as it is. */
    private String outcome(@Nullable String reason, Locale locale) {
        return AccessOutcome.ofCode(reason)
                .map(outcome -> messages.getMessage("outcome." + outcome.code(), null, locale))
                .orElse(reason == null ? "" : reason);
    }

    /** The country's flag, and its name in the reader's language. Tor has no flag. */
    private Origin origin(@Nullable String ip, @Nullable String code, Locale locale) {
        if (code == null) {
            return new Origin(ip, null, null);
        }
        if (code.equals(TOR)) {
            return new Origin(ip, null, messages.getMessage("history.tor", null, locale));
        }
        var name = Locale.of("", code).getDisplayCountry(locale);
        return new Origin(ip, flag(code), name.isEmpty() ? code : name);
    }

    /**
     * The flag emoji of an ISO 3166 code: its two letters as regional indicator symbols, which systems draw as
     * the country's flag (Windows shows the two letters instead).
     */
    static @Nullable String flag(String code) {
        if (code.length() != 2 || !code.chars().allMatch(letter -> letter >= 'A' && letter <= 'Z')) {
            return null;
        }
        var flag = new StringBuilder();
        code.chars().forEach(letter -> flag.appendCodePoint(REGIONAL_INDICATOR_A + letter - 'A'));
        return flag.toString();
    }
}
