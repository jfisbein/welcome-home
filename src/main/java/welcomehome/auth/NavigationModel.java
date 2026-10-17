package welcomehome.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * What the navigation bar of every page needs to know.
 */
@ControllerAdvice
class NavigationModel {

    @ModelAttribute("isAdmin")
    boolean isAdmin(HttpServletRequest request) {
        return request.isUserInRole(OwnerUserDetailsService.ADMIN);
    }
}
