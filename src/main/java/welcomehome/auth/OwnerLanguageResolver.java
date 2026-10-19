package welcomehome.auth;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import welcomehome.config.Languages;

/**
 * The language of each page: a signed-in owner's own, else the browser's among those the app has texts for,
 * else the default. Looked up once per request, as a page asks for it many times.
 */
class OwnerLanguageResolver implements LocaleResolver {

    private static final String RESOLVED = OwnerLanguageResolver.class.getName() + ".language";

    private final OwnerAccountDao owners;
    private final AcceptHeaderLocaleResolver browser = new AcceptHeaderLocaleResolver();

    OwnerLanguageResolver(OwnerAccountDao owners) {
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
        var resolved = language == null ? browser.resolveLocale(request) : language;
        request.setAttribute(RESOLVED, resolved);
        return resolved;
    }

    /** An owner changes their language at /language; a guest's comes from their browser. */
    @Override
    public void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable Locale locale) {
        throw new UnsupportedOperationException("The language is the owner's setting or the browser's");
    }
}
