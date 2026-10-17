package welcomehome;

import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The owner's panel. For now it only greets the owner and links admins to the admin panel; locks and
 * links come later.
 */
@Controller
class HomeController {

    @GetMapping("/")
    String home(Principal owner, HttpServletRequest request, Model model) {
        model.addAttribute("email", owner.getName());
        model.addAttribute("isAdmin", request.isUserInRole("ADMIN"));
        return "home";
    }
}
