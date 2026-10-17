package welcomehome.access;

import java.time.Instant;
import java.time.ZoneId;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The page a guest's URL opens. It only informs: messaging apps fetch links to preview them, so a GET never
 * opens the door. Opening is a POST after the guest presses the button. Search engines are asked not to
 * index the page.
 */
@Controller
class GuestController {

    private final GuestLinks guestLinks;

    GuestController(GuestLinks guestLinks) {
        this.guestLinks = guestLinks;
    }

    @GetMapping("/o/{token}")
    String guestPage(@PathVariable String token, Model model, HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        model.addAttribute("noindex", true);
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
        model.addAttribute("lockActive", link.lockActive());
        model.addAttribute("canOpen", guestLink.canOpen());
        return "access/guest";
    }
}
