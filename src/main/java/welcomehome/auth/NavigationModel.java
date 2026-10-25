package welcomehome.auth;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ModelAttribute;
import welcomehome.config.Languages;

/**
 * What the navigation bar of every page needs to know, and the color theme every page is drawn in.
 */
@ControllerAdvice
class NavigationModel {

    @ModelAttribute("isAdmin")
    boolean isAdmin(HttpServletRequest request) {
        return request.isUserInRole(OwnerUserDetailsService.ADMIN);
    }

    /** The theme chosen in this browser, for {@code data-theme}; null to follow the system's. */
    @ModelAttribute("theme")
    @Nullable String theme(@CookieValue(name = Theme.COOKIE, required = false) @Nullable String cookie) {
        return Theme.of(cookie).map(Theme::value).orElse(null);
    }

    /** The languages an owner may choose, each named in itself. */
    @ModelAttribute("languages")
    List<LanguageOption> languages() {
        return Languages.SUPPORTED.stream()
                .map(language -> new LanguageOption(language.toLanguageTag(), Languages.nameOf(language)))
                .toList();
    }

    /**
     * A language to choose.
     *
     * @param tag  its tag, such as {@code en}
     * @param name its name in itself, such as English
     */
    record LanguageOption(String tag, String name) {
    }
}
