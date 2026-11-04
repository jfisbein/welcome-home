package welcomehome.auth;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Sends a form from a page whose session has ended to the sign-in page, rather than answering 403. Sessions end
 * when the app restarts, as they live in memory, and after 30 minutes without use; the form's CSRF token went with
 * the session, so it can only fail, and nothing was saved. Signing out is then already done, so it lands where
 * signing out does. Everything else is refused as before: a script, which reads the 403 itself; a form with a bad
 * token from a browser whose session is alive, or that sent none; and any request that is not allowed.
 */
class EndedSessionFormHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler refuse = new AccessDeniedHandlerImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        if (exception instanceof CsrfException && sessionEnded(request) && isForm(request)) {
            var signingOut = "/logout".equals(request.getServletPath());
            response.sendRedirect(request.getContextPath() + (signingOut ? "/login?logout" : "/login?expired"));
            return;
        }
        refuse.handle(request, response, exception);
    }

    /** The browser sent a session that the server no longer has. */
    private static boolean sessionEnded(HttpServletRequest request) {
        return request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid();
    }

    /** An HTML form, which the browser follows a redirect for; scripts here send JSON. */
    private static boolean isForm(HttpServletRequest request) {
        var contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            var type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_FORM_URLENCODED.includes(type) || MediaType.MULTIPART_FORM_DATA.includes(type);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
