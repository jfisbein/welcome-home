package welcomehome.auth;

import java.util.Arrays;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * A color theme chosen in this browser, kept in a cookie. Without one, pages follow the system's, as Pico CSS
 * does when {@code <html>} has no {@code data-theme}.
 */
enum Theme {

    LIGHT("light"),
    DARK("dark");

    /** The cookie that keeps the choice: functional, set only when the owner chooses. */
    static final String COOKIE = "theme";

    private final String value;

    Theme(String value) {
        this.value = value;
    }

    /** @return the value of the cookie and of {@code data-theme} */
    String value() {
        return value;
    }

    /** @return the theme with this value, or empty for none or an unknown one, which mean the system's */
    static Optional<Theme> of(@Nullable String value) {
        return Arrays.stream(values()).filter(theme -> theme.value.equals(value)).findFirst();
    }
}
