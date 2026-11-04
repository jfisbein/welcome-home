package welcomehome.auth;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Publishes an {@link OwnerSignedIn} when an owner signs in with a passkey, then answers as Spring Security would.
 * The passkey is the one used last: signing in has just set its last use.
 */
class SignInAnnouncer implements AuthenticationSuccessHandler {

    private final AuthenticationSuccessHandler answer;
    private final OwnerAccountDao owners;
    private final PasskeyDao passkeys;
    private final ApplicationEventPublisher events;

    SignInAnnouncer(AuthenticationSuccessHandler answer, OwnerAccountDao owners, PasskeyDao passkeys,
                    ApplicationEventPublisher events) {
        this.answer = answer;
        this.owners = owners;
        this.passkeys = passkeys;
        this.events = events;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        owners.findByEmail(authentication.getName().toLowerCase(Locale.ROOT)).ifPresent(owner ->
                events.publishEvent(new OwnerSignedIn(owner.id(), passkeys.lastUsedLabel(owner.id()).orElse(null))));
        answer.onAuthenticationSuccess(request, response, authentication);
    }
}
