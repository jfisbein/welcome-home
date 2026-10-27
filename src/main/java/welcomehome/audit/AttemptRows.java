package welcomehome.audit;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

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
     * @param entranceName the name of the Opener at the lock's building entrance, if the guest opened that
     * @param reason       the code that {@link AccessEvent} recorded
     * @param country      the country code Cloudflare gave, if any
     */
    AttemptRow row(OffsetDateTime occurredAt, ZoneId zone, UUID lockId, String lockName, UUID linkId,
                   String linkLabel, @Nullable String entranceName, AccessResult result, @Nullable String reason,
                   @Nullable String ip, @Nullable String country, Locale locale) {
        return new AttemptRow(occurredAt.atZoneSameInstant(zone), lockId, lockName, linkId, linkLabel, entranceName,
                result, outcome(reason, locale), origin(ip, country, locale));
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
