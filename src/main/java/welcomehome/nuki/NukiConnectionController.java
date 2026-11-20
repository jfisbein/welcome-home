package welcomehome.nuki;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;

/**
 * The owner's Nuki connection page: its state, the way to connect, by authorizing welcome-home in Nuki
 * ({@link NukiAuthorizationController}), and to disconnect. Without an OAuth2 client the page says that this
 * install cannot connect Nuki.
 */
@Controller
@RequestMapping("/settings/nuki")
class NukiConnectionController {

    private static final String VIEW = "nuki/connection";

    private final NukiConnections connections;
    private final NukiProperties properties;

    NukiConnectionController(NukiConnections connections, NukiProperties properties) {
        this.connections = connections;
        this.properties = properties;
    }

    @GetMapping
    String connection(CurrentOwner owner, Model model) {
        model.addAttribute("connection", connections.find(owner.id()).orElse(null));
        model.addAttribute("authorization", properties.oauth().enabled());
        return VIEW;
    }

    @DeleteMapping
    String disconnect(CurrentOwner owner, RedirectAttributes redirect) {
        if (connections.remove(owner.id())) {
            redirect.addFlashAttribute("disconnected", true);
        }
        return "redirect:/settings/nuki";
    }
}
