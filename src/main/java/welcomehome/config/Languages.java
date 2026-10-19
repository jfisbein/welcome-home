package welcomehome.config;

import java.util.List;
import java.util.Locale;

/**
 * The languages the app has texts for, in {@code i18n/messages*.properties}. Spanish, the default bundle, is
 * the language of anyone who asks for none of them.
 */
public final class Languages {

    /** The default bundle's language. */
    public static final Locale DEFAULT = Locale.of("es");

    /** Every language with a bundle, the default first. */
    public static final List<Locale> SUPPORTED = List.of(DEFAULT);

    private Languages() {
    }
}
