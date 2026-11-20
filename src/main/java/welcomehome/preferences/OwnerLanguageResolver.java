package welcomehome.preferences;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import welcomehome.config.Languages;

/**
 * The language of each page: the one asked for with {@code ?lang=}, which a guest's page offers; else a signed-in
 * owner's own; else the browser's, among those the app has texts for; else the default. What is asked for comes
 * first so that an owner trying their link as a guest, signed in, can read it in each language as a guest would. Looked up once per request, as a page asks for it many times.
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
        var resolved = chosen(request).or(() -> ofTheOwner(request)).orElseGet(() -> browser.resolveLocale(request));
        request.setAttribute(RESOLVED, resolved);
        return resolved;
    }

    private Optional<Locale> ofTheOwner(HttpServletRequest request) {
        var principal = request.getUserPrincipal();
        if (principal == null) {
            return Optional.empty();
        }
        try {
            // A signed-in owner's name is their id.
            return owners.findLanguage(UUID.fromString(principal.getName()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The language asked for with {@code ?lang=}, as a guest's page offers: their
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
