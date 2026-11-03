package welcomehome.config;

import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * A lock's time zone as people read it: its name in their language, such as "hora de España peninsular", never its
 * id, such as {@code Europe/Madrid}. Every page and email that tells a lock's zone takes it from here, templates as
 * {@code @timeZoneNames}, so that it reads the same everywhere; only the list that chooses one also shows ids,
 * as zones that share a name must still be told apart there.
 */
@Component
public class TimeZoneNames {

    private final MessageSource messages;

    /**
     * Creates it; Spring does.
     *
     * @param messages the bundles, which name the zones that Java names too broadly for owners in Spain
     */
    public TimeZoneNames(MessageSource messages) {
        this.messages = messages;
    }

    /**
     * A time zone's name: the bundle's ({@code time-zone.<id>}) for Spain's zones, where Java says "hora de Europa
     * central" for Madrid, and otherwise Java's, the same for the zones that share its rules.
     *
     * @param zoneId a time zone's id, such as {@code Europe/Madrid}
     * @param locale the reader's language
     * @return its name in that language
     */
    public String of(String zoneId, Locale locale) {
        var name = messages.getMessage("time-zone." + zoneId, null, null, locale);
        return name != null ? name : ZoneId.of(zoneId).getDisplayName(TextStyle.FULL, locale);
    }
}
