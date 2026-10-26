package welcomehome.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The languages the app has texts for, in {@code i18n/messages*.properties}. Spanish, the default bundle, is
 * the language of anyone who asks for none of them.
 */
public final class Languages {

    /** The default bundle's language. */
    public static final Locale DEFAULT = Locale.of("es");

    /** Every language with a bundle, the default first. */
    public static final List<Locale> SUPPORTED =
            List.of(DEFAULT, Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN, Locale.ITALIAN);

    private Languages() {
    }

    /**
     * Finds a supported language.
     *
     * @param tag a language tag, such as {@code en}
     * @return the language, or empty if the app has no texts for it
     */
    public static Optional<Locale> of(String tag) {
        return SUPPORTED.stream().filter(language -> language.toLanguageTag().equals(tag)).findFirst();
    }

    /**
     * A language's name in that language, as a list of languages shows it.
     *
     * @param language a supported language
     * @return its name, such as Español or English
     */
    public static String nameOf(Locale language) {
        var name = language.getDisplayLanguage(language);
        return name.substring(0, 1).toUpperCase(language) + name.substring(1);
    }
}
