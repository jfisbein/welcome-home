package welcomehome.config;

import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * A lock's time zone as people read it: its name in their language, such as "hora de Europa central", never its
 * id, such as {@code Europe/Madrid}. Every page and email that tells a lock's zone takes it from here, templates as
 * {@code @timeZoneNames}, so that it reads the same everywhere; only the list that chooses one also shows ids,
 * as zones that share a name must still be told apart there.
 */
@Component
public class TimeZoneNames {

    /** Creates it; Spring does. */
    public TimeZoneNames() {
    }

    /**
     * A time zone's name, the same for the zones that share its rules: Europe/Berlin, which Nuki reports for a lock
     * in Madrid, is "hora de Europa central" like Europe/Madrid.
     *
     * @param zoneId a time zone's id, such as {@code Europe/Madrid}
     * @param locale the reader's language
     * @return its name in that language
     */
    public String of(String zoneId, Locale locale) {
        return ZoneId.of(zoneId).getDisplayName(TextStyle.FULL, locale);
    }
}
