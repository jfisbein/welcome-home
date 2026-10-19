package welcomehome.access;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import welcomehome.audit.AccessOutcome;
import welcomehome.links.LinkStatus;

/**
 * The page a guest's URL opens. It only informs: messaging apps fetch links to preview them, so a GET never
 * opens the door. Opening is a POST after the guest presses the button. Search engines are asked not to
 * index the page. It creates no session: it has no CSRF token to keep, as opening needs none.
 */
@Controller
class GuestController {

    /** Every outcome's code, for the page to carry what the guest reads after pressing the button. */
    private static final List<String> OUTCOMES = Arrays.stream(AccessOutcome.values()).map(AccessOutcome::code).toList();

    private final GuestLinks guestLinks;

    GuestController(GuestLinks guestLinks) {
        this.guestLinks = guestLinks;
    }

    @GetMapping("/o/{token}")
    String guestPage(@PathVariable String token, Model model, HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        model.addAttribute("noindex", true);
        model.addAttribute("sessionless", true);
        var found = guestLinks.find(token, Instant.now());
        if (found.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "access/invalid";
        }
        var guestLink = found.get();
        var link = guestLink.link();
        var zone = ZoneId.of(link.lockTimeZone());
        model.addAttribute("lockName", link.lockName());
        model.addAttribute("timeZone", link.lockTimeZone());
        model.addAttribute("validFrom", link.validFrom().atZoneSameInstant(zone));
        model.addAttribute("validUntil", link.validUntil().atZoneSameInstant(zone));
        model.addAttribute("status", guestLink.status());
        var active = guestLink.status() == LinkStatus.ACTIVE;
        var available = active && guestLinks.doorAvailable(link);
        model.addAttribute("canOpen", available);
        model.addAttribute("outcomes", OUTCOMES);
        model.addAttribute("unavailable", active && !available);
        return "access/guest";
    }
}
