package welcomehome.links;

import java.time.Instant;
import java.time.ZoneId;
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

    LockLinksController(Locks locks, AccessLinks accessLinks) {
        this.locks = locks;
        this.accessLinks = accessLinks;
    }

    @GetMapping
    String links(CurrentOwner owner, @PathVariable UUID lockId, Model model) {
        var lock = findLock(owner, lockId);
        LinkForm.startingNow(ZoneId.of(lock.timeZone())).addTo(model, null);
        return view(owner, lock, model);
    }

    @PostMapping
    String create(CurrentOwner owner, @PathVariable UUID lockId, @RequestParam(defaultValue = "") String label,
                  @RequestParam(defaultValue = "") String validFrom, @RequestParam(defaultValue = "") String validUntil,
                  Model model, HttpServletResponse response, RedirectAttributes redirect) {
        var lock = findLock(owner, lockId);
        if (!lock.active()) {
            response.setStatus(HttpStatus.CONFLICT.value());
            return view(owner, lock, model);
        }
        var zone = ZoneId.of(lock.timeZone());
        var form = new LinkForm(label, validFrom, validUntil);
        var validation = form.validate(zone, Instant.now());
        if (!validation.isValid()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            form.addTo(model, validation);
            return view(owner, lock, model);
        }
        var from = Objects.requireNonNull(validation.validFrom());
        var until = Objects.requireNonNull(validation.validUntil());
        var url = accessLinks.create(owner.id(), lock.id(), validation.label(), from, until);
        redirect.addFlashAttribute("createdUrl", url);
        redirect.addFlashAttribute("createdLabel", validation.label());
        redirect.addFlashAttribute("createdFrom", from.atZone(zone));
        redirect.addFlashAttribute("createdUntil", until.atZone(zone));
        return "redirect:/locks/" + lock.id() + "/links";
    }

    private String view(CurrentOwner owner, OwnerLock lock, Model model) {
        model.addAttribute("lock", lock);
        model.addAttribute("links", accessLinks.list(owner.id(), lock));
        return VIEW;
    }

    private OwnerLock findLock(CurrentOwner owner, UUID lockId) {
        return locks.find(owner.id(), lockId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
