package welcomehome.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tells {@link OwnerSessions} of every session signed in as an owner. Part of the security filter chain, so not a
 * bean, which would also run outside it.
 */
class OwnerSessionTrackingFilter extends OncePerRequestFilter {

    private final OwnerSessions sessions;

    OwnerSessionTrackingFilter(OwnerSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var session = request.getSession(false);
        if (session != null) {
            // Before the anonymous filter: only a real sign-in has an authentication here.
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                sessions.seen(authentication.getName(), session);
            }
        }
        chain.doFilter(request, response);
    }
}
