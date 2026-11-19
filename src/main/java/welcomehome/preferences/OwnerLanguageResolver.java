package welcomehome.preferences;

import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import welcomehome.config.Languages;

/**
 * The language of each page: a signed-in owner's own; else the one asked for with {@code ?lang=}, else the
 * browser's, among those the app has texts for; else the default. Looked up once per request, as a page asks for it many times.
 */
class OwnerLanguageResolver implements LocaleResolver {

    /** The request parameter with which a page that needs no sign-in is asked for in a language. */
    static final String LANGUAGE_PARAMETER = "lang";

    private static final String RESOLVED = OwnerLanguageResolver.class.getName() + ".language";

    private final OwnerLanguageDao owners;
    private final AcceptHeaderLocaleResolver browser = new AcceptHeaderLocaleResolver();

    OwnerLanguageResolver(OwnerLanguageDao owners) {
        this.owners = owners;
        browser.setSupportedLocales(Languages.SUPPORTED);
        browser.setDefaultLocale(Languages.DEFAULT);
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        if (request.getAttribute(RESOLVED) instanceof Locale resolved) {
            return resolved;
        }
        var principal = request.getUserPrincipal();
        var language = principal == null ? null
                : owners.findLanguage(principal.getName().toLowerCase(Locale.ROOT)).orElse(null);
        var resolved = language != null ? language : chosen(request).orElseGet(() -> browser.resolveLocale(request));
        request.setAttribute(RESOLVED, resolved);
        return resolved;
    }

    /**
     * The language someone who is not signed in asked for with {@code ?lang=}, as a guest's page offers: their
     * browser may ask for a language the app lacks, and so get Spanish, when they read another the app has.
     */
    private static Optional<Locale> chosen(HttpServletRequest request) {
        var tag = request.getParameter(LANGUAGE_PARAMETER);
        return tag == null ? Optional.empty() : Languages.of(tag);
    }

    /** An owner changes their language at /settings/preferences; a guest's comes from their browser or their choice. */
    @Override
    public void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable Locale locale) {
        throw new UnsupportedOperationException("The language is the owner's setting or the browser's");
    }
}
