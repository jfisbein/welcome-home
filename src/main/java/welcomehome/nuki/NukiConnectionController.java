package welcomehome.nuki;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;

/**
 * The owner's Nuki connection page: its state, and a form to paste an API token. The token is never shown
 * back, not even after a failed attempt.
 */
@Controller
@RequestMapping("/nuki/connection")
class NukiConnectionController {

    private static final String VIEW = "nuki/connection";

    private final NukiConnections connections;

    NukiConnectionController(NukiConnections connections) {
        this.connections = connections;
    }

    @GetMapping
    String connection(CurrentOwner owner, Model model) {
        model.addAttribute("connection", connections.find(owner.id()).orElse(null));
        return VIEW;
    }

    @PutMapping
    String connect(CurrentOwner owner, @RequestParam(defaultValue = "") String apiToken, Model model,
                   HttpServletResponse response, RedirectAttributes redirect) {
        try {
            var locks = connections.connect(owner.id(), apiToken);
            redirect.addFlashAttribute("connectedLocks", locks.stream().map(SmartLock::name).toList());
            return "redirect:/nuki/connection";
        } catch (LockProviderException e) {
            var unavailable = e.reason() == LockProviderException.Reason.UNAVAILABLE;
            response.setStatus(unavailable ? HttpStatus.SERVICE_UNAVAILABLE.value() : HttpStatus.BAD_REQUEST.value());
            model.addAttribute("error", unavailable ? "unavailable" : "rejected");
            return connection(owner, model);
        }
    }
}
