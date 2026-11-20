package welcomehome.auth;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses, from a session whose owner did not sign in lately ({@link RecentSignIn}), what would hand their account to
 * whoever sits at their unlocked browser: changing the email, where every notice goes, and adding a passkey, which
 * Spring Security's own endpoints do. The owner is sent to confirm with a passkey ({@link ConfirmController}) and
 * makes the change again. Part of the security filter chain, before it authorizes: a visitor who is not signed in
 * goes on to be sent to sign in.
 */
class RecentSignInFilter extends OncePerRequestFilter {

    static final String NOT_RECENT = "sign-in-not-recent";

    /** What is guarded, as "METHOD path", and the page its owner goes back to once they have confirmed. */
    private static final Map<String, String> GUARDED = Map.of(
            "PUT /settings/account/email", ConfirmController.ACCOUNT,
            "POST /webauthn/register/options", ConfirmController.PASSKEYS,
            "POST /webauthn/register", ConfirmController.PASSKEYS);

    private final Clock clock;

    RecentSignInFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        var page = GUARDED.get(request.getMethod() + " " + path);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var signedIn = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        if (page == null || !signedIn || RecentSignIn.isRecent(request, clock)) {
            chain.doFilter(request, response);
            return;
        }
        if (path.startsWith("/webauthn/")) {
            // A script asked, which reads the answer itself (passkeys.js).
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"" + NOT_RECENT + "\"}");
            return;
        }
        response.sendRedirect(request.getContextPath() + "/settings/confirm?for=" + page);
    }
}
