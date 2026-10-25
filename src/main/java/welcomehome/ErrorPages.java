package welcomehome;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

/**
 * The page for every error a browser gets, in place of Spring Boot's default: the app's layout, in the
 * visitor's language, with the error's status code. A missing id and another owner's are both a 404, so they
 * read the same. A signed-in owner gets the menu; anyone else a page that starts no session, as guests' pages
 * do. Clients that ask for JSON, as the guest page's script does, still get Spring Boot's JSON.
 */
@Component
class ErrorPages implements ErrorViewResolver {

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        var signedIn = request.getUserPrincipal() != null;
        return new ModelAndView("error", Map.of(
                "status", status.value(),
                "kind", kind(status),
                "signedIn", signedIn,
                // Without a session, the page must not create one for a CSRF token it has no form to use.
                "sessionless", !signedIn), status);
    }

    /** Which texts the page shows: a 404 and a 403 have their own, other errors one by class. */
    private static String kind(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND || status == HttpStatus.FORBIDDEN) {
            return String.valueOf(status.value());
        }
        return status.is4xxClientError() ? "4xx" : "5xx";
    }
}
