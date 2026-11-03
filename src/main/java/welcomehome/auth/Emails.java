package welcomehome.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/** An owner's email as the app keeps it: trimmed, lower case and plausible. */
final class Emails {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    /** user_entities.name, where Spring Security keeps it too, is varchar(100). */
    static final int MAX_LENGTH = 100;

    private Emails() {
    }

    /** @return the email trimmed and in lower case, or empty if it is not plausible or too long */
    static Optional<String> normalize(@Nullable String email) {
        var normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        return normalized.length() > MAX_LENGTH || !EMAIL.matcher(normalized).matches()
                ? Optional.empty() : Optional.of(normalized);
    }
}
