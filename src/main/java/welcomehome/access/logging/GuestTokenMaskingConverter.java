package welcomehome.access.logging;

import java.util.regex.Pattern;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback's message with guests' tokens masked: a token in a log opens the door as well as the link does.
 * Spring logs request paths at DEBUG, so every {@code /o/{token}} becomes {@code /o/***}. logback-spring.xml
 * puts it in place of {@code %m}.
 */
public class GuestTokenMaskingConverter extends MessageConverter {

    private static final Pattern GUEST_PATH = Pattern.compile("/o/[^/\\s\"'?#\\]]+");

    /** Logback creates it from the configuration. */
    public GuestTokenMaskingConverter() {
    }

    @Override
    public String convert(ILoggingEvent event) {
        return GUEST_PATH.matcher(super.convert(event)).replaceAll("/o/***");
    }
}
