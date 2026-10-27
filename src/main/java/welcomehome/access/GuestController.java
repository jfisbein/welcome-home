package welcomehome.access;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import welcomehome.audit.AccessOutcome;
import welcomehome.links.LinkStatus;
import welcomehome.locks.OwnerLock;

/**
 * The page a guest's URL opens. It only informs: messaging apps fetch links to preview them, so a GET never
 * opens the door. Opening is a POST once the guest slides to open. Search engines are asked not to
 * index the page. It creates no session: it has no CSRF token to keep, as opening needs none. Each door the link
 * opens has its own slide: the lock, and before it the Opener at its building's entrance, if it has one.
 */
@Controller
class GuestController {

    /** Every outcome's code, for the page to carry what the guest reads after sliding to open. */
    private static final List<String> OUTCOMES = Arrays.stream(AccessOutcome.values()).map(AccessOutcome::code).toList();

    private final GuestLinks guestLinks;
    private final MessageSource messages;

    GuestController(GuestLinks guestLinks, MessageSource messages) {
        this.guestLinks = guestLinks;
        this.messages = messages;
    }

    @GetMapping("/o/{token}")
    String guestPage(@PathVariable String token, Locale locale, Model model, HttpServletResponse response) {
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
        var doors = guestLink.status() == LinkStatus.ACTIVE ? doors(link, locale) : List.<GuestDoor>of();
        model.addAttribute("doors", doors);
        model.addAttribute("canOpen", doors.stream().anyMatch(GuestDoor::available));
        return "access/guest";
    }

    /** The doors a link opens, in the order the guest reaches them: its lock's building entrance first, if any. */
    private List<GuestDoor> doors(ResolvedLink link, Locale locale) {
        var doors = new ArrayList<GuestDoor>();
        guestLinks.entrance(link).ifPresent(entrance -> doors.add(new GuestDoor("open-entrance", Door.ENTRANCE.code(),
                true, guestLinks.entranceAvailable(link, entrance), outcomeMessages(true, locale))));
        var opener = guestLinks.lock(link).map(OwnerLock::opener).orElse(false);
        doors.add(new GuestDoor("open", Door.LOCK.code(), opener, guestLinks.doorAvailable(link),
                outcomeMessages(opener, locale)));
        return doors;
    }

    /**
     * What the guest reads after each outcome. An Opener has its own message where a lock's would speak of a lock
     * or of a door that stays open, and a lock's otherwise.
     */
    private Map<String, String> outcomeMessages(boolean opener, Locale locale) {
        var outcomeMessages = new LinkedHashMap<String, String>();
        for (var outcome : OUTCOMES) {
            var message = messages.getMessage("guest.result." + outcome, null, locale);
            if (opener) {
                message = Objects.requireNonNullElse(
                        messages.getMessage("guest.entrance.result." + outcome, null, null, locale), message);
            }
            outcomeMessages.put(outcome, message);
        }
        return outcomeMessages;
    }
}
