package welcomehome.links;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;
import welcomehome.locks.Locks;
import welcomehome.locks.OwnerLock;

/**
 * A lock's links: their list and new links. Dates are entered in the lock's time zone and stored as instants.
 */
@Controller
@RequestMapping("/locks/{lockId}/links")
class LockLinksController {

    private static final String VIEW = "links/lock-links";

    private final Locks locks;
    private final AccessLinks accessLinks;
    private final LinkSchedules schedules;

    LockLinksController(Locks locks, AccessLinks accessLinks, LinkSchedules schedules) {
        this.locks = locks;
        this.accessLinks = accessLinks;
        this.schedules = schedules;
    }

    @GetMapping
    String links(CurrentOwner owner, @PathVariable UUID lockId, Locale locale, Model model) {
        var lock = findLock(owner, lockId);
        LinkForm.startingNow(ZoneId.of(lock.timeZone())).addTo(model, null);
        return view(owner, lock, locale, model);
    }

    @PostMapping
    String create(CurrentOwner owner, @PathVariable UUID lockId, @RequestParam(defaultValue = "") String label,
                  @RequestParam(defaultValue = "") String validFrom, @RequestParam(defaultValue = "") String validUntil,
                  @RequestParam Map<String, String> params, Locale locale, Model model, HttpServletResponse response,
                  RedirectAttributes redirect) {
        var lock = findLock(owner, lockId);
        if (!lock.active()) {
            response.setStatus(HttpStatus.CONFLICT.value());
            return view(owner, lock, locale, model);
        }
        var zone = ZoneId.of(lock.timeZone());
        var form = new LinkForm(label, validFrom, validUntil, ScheduleForm.of(params));
        var validation = form.validate(zone, Instant.now());
        if (!validation.isValid()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            form.addTo(model, validation);
            return view(owner, lock, locale, model);
        }
        var from = Objects.requireNonNull(validation.validFrom());
        var until = Objects.requireNonNull(validation.validUntil());
        var url = accessLinks.create(owner.id(), lock.id(), validation.label(), from, until, validation.schedule());
        redirect.addFlashAttribute("createdUrl", url);
        redirect.addFlashAttribute("createdLabel", validation.label());
        redirect.addFlashAttribute("createdFrom", from.atZone(zone));
        redirect.addFlashAttribute("createdUntil", until.atZone(zone));
        redirect.addFlashAttribute("createdSchedule", schedules.describe(validation.schedule(), locale));
        return "redirect:/locks/" + lock.id() + "/links";
    }

    /** The links that can still open come first; those that ended are folded away below them. */
    private String view(CurrentOwner owner, OwnerLock lock, Locale locale, Model model) {
        var links = accessLinks.list(owner.id(), lock, locale);
        model.addAttribute("lock", lock);
        model.addAttribute("links", links.stream().filter(link -> link.status().isUsable()).toList());
        model.addAttribute("endedLinks", links.stream().filter(link -> !link.status().isUsable()).toList());
        return VIEW;
    }

    private OwnerLock findLock(CurrentOwner owner, UUID lockId) {
        return locks.find(owner.id(), lockId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
