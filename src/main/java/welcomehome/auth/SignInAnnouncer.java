package welcomehome.auth;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Publishes an {@link OwnerSignedIn} when an owner signs in with a passkey, and notes when in their session
 * ({@link RecentSignIn}), then answers as Spring Security would.
 * The passkey is the one used last: signing in has just set its last use.
 */
class SignInAnnouncer implements AuthenticationSuccessHandler {

    private final AuthenticationSuccessHandler answer;
    private final PasskeyDao passkeys;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SignInAnnouncer(AuthenticationSuccessHandler answer, PasskeyDao passkeys, ApplicationEventPublisher events,
                    Clock clock) {
        this.clock = clock;
        this.answer = answer;
        this.passkeys = passkeys;
        this.events = events;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        RecentSignIn.mark(request, clock);
        OwnerName.ownerId(authentication.getName()).ifPresent(ownerId ->
                events.publishEvent(new OwnerSignedIn(ownerId, passkeys.lastUsedLabel(ownerId).orElse(null), false)));
        answer.onAuthenticationSuccess(request, response, authentication);
    }
}
