package welcomehome;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import welcomehome.auth.CurrentOwner;
import welcomehome.auth.EmailVerification;
import welcomehome.nuki.NukiConnections;

/**
 * The owner's panel: it greets the owner, warns when their email is not verified, and when Nuki is not
 * connected or has rejected their API token. The owner chooses their language here too.
 * Locks, links, notifications and passkeys are in the menu.
 */
@Controller
class HomeController {

    private final NukiConnections nukiConnections;
    private final EmailVerification emailVerification;

    HomeController(NukiConnections nukiConnections, EmailVerification emailVerification) {
        this.nukiConnections = nukiConnections;
        this.emailVerification = emailVerification;
    }

    @GetMapping("/")
    String home(CurrentOwner owner, Model model) {
        model.addAttribute("email", owner.email());
        model.addAttribute("nukiConnection", nukiConnections.find(owner.id()).orElse(null));
        model.addAttribute("emailVerified", emailVerification.isVerified(owner.id()));
        return "home";
    }
}
