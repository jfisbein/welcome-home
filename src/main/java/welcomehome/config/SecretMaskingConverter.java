package welcomehome.config;

import java.util.regex.Pattern;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback's message with the secrets that travel in URLs masked. A guest's token in a log opens the door as well as
 * the link does: Spring logs request paths at DEBUG, so every {@code /o/{token}} becomes {@code /o/***}. So does the
 * query of {@code /nuki/callback}, where Nuki sends an owner back with the code that gets their tokens. Here, and
 * not in the module of either, as it is the app's logging that it guards, whatever module logs.
 * logback-spring.xml puts it in place of {@code %m}.
 */
public class SecretMaskingConverter extends MessageConverter {

    private static final Pattern GUEST_PATH = Pattern.compile("/o/[^/\\s\"'?#\\]]+");
    private static final Pattern NUKI_CALLBACK_QUERY = Pattern.compile("/nuki/callback\\?[^\\s\"'#\\]]+");

    /** Logback creates it from the configuration. */
    public SecretMaskingConverter() {
    }

    @Override
    public String convert(ILoggingEvent event) {
        var message = GUEST_PATH.matcher(super.convert(event)).replaceAll("/o/***");
        return NUKI_CALLBACK_QUERY.matcher(message).replaceAll("/nuki/callback?***");
    }
}
