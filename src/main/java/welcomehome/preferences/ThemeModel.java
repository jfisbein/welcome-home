package welcomehome.preferences;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ModelAttribute;

/** The color theme every page is drawn in, for {@code <html data-theme>}. */
@ControllerAdvice
class ThemeModel {

    /** The theme chosen in this browser; null to follow the system's. */
    @ModelAttribute("theme")
    @Nullable String theme(@CookieValue(name = Theme.COOKIE, required = false) @Nullable String cookie) {
        return Theme.of(cookie).map(Theme::value).orElse(null);
    }
}
