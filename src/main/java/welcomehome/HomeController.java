package welcomehome;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import welcomehome.auth.CurrentOwner;
import welcomehome.nuki.NukiConnections;

/**
 * The owner's panel: it greets the owner and warns when Nuki is not connected or has rejected their API token.
 * Locks, links, notifications and passkeys are in the menu.
 */
@Controller
class HomeController {

    private final NukiConnections nukiConnections;

    HomeController(NukiConnections nukiConnections) {
        this.nukiConnections = nukiConnections;
    }

    @GetMapping("/")
    String home(CurrentOwner owner, Model model) {
        model.addAttribute("email", owner.email());
        model.addAttribute("nukiConnection", nukiConnections.find(owner.id()).orElse(null));
        return "home";
    }
}
