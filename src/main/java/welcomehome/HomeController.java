package welcomehome;

import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import welcomehome.audit.RecentAttempts;
import welcomehome.auth.CurrentOwner;
import welcomehome.auth.EmailVerification;
import welcomehome.locks.Locks;
import welcomehome.nuki.NukiConnections;

/**
 * The owner's home page: their locks and the latest attempts with their links. It also warns when their email
 * is not verified, and when Nuki is not connected or has rejected their API token. Configuration's entry
 * point is here too, since its sections live in several modules.
 */
@Controller
class HomeController {

    static final int RECENT_ATTEMPTS = 10;

    private final NukiConnections nukiConnections;
    private final EmailVerification emailVerification;
    private final Locks locks;
    private final RecentAttempts recentAttempts;

    HomeController(NukiConnections nukiConnections, EmailVerification emailVerification, Locks locks,
                   RecentAttempts recentAttempts) {
        this.nukiConnections = nukiConnections;
        this.emailVerification = emailVerification;
        this.locks = locks;
        this.recentAttempts = recentAttempts;
    }

    @GetMapping("/")
    String home(CurrentOwner owner, Locale locale, Model model) {
        model.addAttribute("email", owner.email());
        model.addAttribute("nukiConnection", nukiConnections.find(owner.id()).orElse(null));
        model.addAttribute("emailVerified", emailVerification.isVerified(owner.id()));
        model.addAttribute("locks", locks.findAll(owner.id()));
        model.addAttribute("attempts", recentAttempts.latest(owner.id(), RECENT_ATTEMPTS, locale));
        return "home";
    }

    /** Configuration has one page per section; the menu leads to the first. */
    @GetMapping("/settings")
    String settings() {
        return "redirect:/nuki/connection";
    }
}
