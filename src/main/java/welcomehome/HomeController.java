package welcomehome;

import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import welcomehome.audit.RecentAttempts;
import welcomehome.auth.CurrentOwner;
import welcomehome.auth.EmailVerification;
import welcomehome.locks.LockChecks;
import welcomehome.locks.Locks;
import welcomehome.nuki.NukiConnections;

/**
 * The owner's home page: their locks, what is wrong with them, and the latest attempts with their links. It also
 * warns when their email is not verified, and when Nuki is not connected or has rejected their API token.
 */
@Controller
class HomeController {

    static final int RECENT_ATTEMPTS = 10;

    private final NukiConnections nukiConnections;
    private final EmailVerification emailVerification;
    private final Locks locks;
    private final LockChecks lockChecks;
    private final RecentAttempts recentAttempts;

    HomeController(NukiConnections nukiConnections, EmailVerification emailVerification, Locks locks,
                   LockChecks lockChecks, RecentAttempts recentAttempts) {
        this.nukiConnections = nukiConnections;
        this.emailVerification = emailVerification;
        this.locks = locks;
        this.lockChecks = lockChecks;
        this.recentAttempts = recentAttempts;
    }

    @GetMapping("/")
    String home(CurrentOwner owner, Locale locale, Model model) {
        var ownerLocks = locks.findAll(owner.id());
        // Before reading the connection: Nuki rejecting the token here disconnects it, which this page then says.
        model.addAttribute("lockWarnings", lockChecks.warnings(owner.id(), ownerLocks));
        model.addAttribute("email", owner.email());
        model.addAttribute("nukiConnection", nukiConnections.find(owner.id()).orElse(null));
        model.addAttribute("emailVerified", emailVerification.isVerified(owner.id()));
        model.addAttribute("locks", ownerLocks);
        model.addAttribute("attempts", recentAttempts.latest(owner.id(), RECENT_ATTEMPTS, locale));
        return "home";
    }
}
