package welcomehome.nuki;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;

/**
 * The owner's Nuki connection page: its state, the way to connect with OAuth2 when there is a client for it
 * ({@link NukiAuthorizationController}), and a form to paste an API token. The token is never shown back, not
 * even after a failed attempt.
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

    @PutMapping
    String connect(CurrentOwner owner, @RequestParam(defaultValue = "") String apiToken, Model model,
                   HttpServletResponse response, RedirectAttributes redirect) {
        try {
            var locks = connections.connect(owner.id(), apiToken);
            redirect.addFlashAttribute("connectedLocks", locks.stream().map(SmartLock::name).toList());
            return "redirect:/settings/nuki";
        } catch (LockProviderException e) {
            var error = switch (e.reason()) {
                case NUKI_ERROR, NO_ANSWER -> "unavailable";
                case MISSING_PERMISSION -> "missing-permission";
                case NOT_CONNECTED, ACCESS_DENIED, LOCK_NOT_FOUND -> "rejected";
            };
            response.setStatus("unavailable".equals(error) ? HttpStatus.SERVICE_UNAVAILABLE.value()
                    : HttpStatus.BAD_REQUEST.value());
            model.addAttribute("error", error);
            return connection(owner, model);
        }
    }

    @DeleteMapping
    String disconnect(CurrentOwner owner, RedirectAttributes redirect) {
        connections.remove(owner.id())
                .ifPresent(credential -> redirect.addFlashAttribute("disconnected", credential.name()));
        return "redirect:/settings/nuki";
    }
}
