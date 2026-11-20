package welcomehome.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;

/**
 * When a session's owner last proved who they are, with a passkey or by creating one from an invitation. What
 * changes who gets into an account, or who is told about it, asks for that to be recent ({@link RecentSignInFilter}):
 * a session left open in a browser is then not enough to take the account over.
 */
final class RecentSignIn {

    /** How long ago at most: long enough to sign in and get to the page, short for a browser left unattended. */
    static final Duration WINDOW = Duration.ofMinutes(10);

    static final String ATTRIBUTE = RecentSignIn.class.getName() + ".at";

    private RecentSignIn() {
    }

    static void mark(HttpServletRequest request, Clock clock) {
        request.getSession().setAttribute(ATTRIBUTE, clock.instant());
    }

    /** False for a session that never said when, which no sign-in leaves. */
    static boolean isRecent(HttpServletRequest request, Clock clock) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(ATTRIBUTE) instanceof Instant at
                && at.plus(WINDOW).isAfter(clock.instant());
    }
}
