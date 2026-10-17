package welcomehome;

import java.security.Principal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The owner's panel. For now it only greets the owner; locks and links come later.
 */
@Controller
class HomeController {

    @GetMapping("/")
    String home(Principal owner, Model model) {
        model.addAttribute("email", owner.getName());
        return "home";
    }
}
