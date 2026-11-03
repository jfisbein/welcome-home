package welcomehome.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * What the menu of every page needs to know of the signed-in owner: whether to offer the admin panel.
 */
@ControllerAdvice
class NavigationModel {

    @ModelAttribute("isAdmin")
    boolean isAdmin(HttpServletRequest request) {
        return request.isUserInRole(OwnerUserDetailsService.ADMIN);
    }
}
