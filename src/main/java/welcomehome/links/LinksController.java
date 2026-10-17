package welcomehome.links;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
 * A lock's links. Dates are entered as local date and time in the lock's time zone, as the browser's
 * datetime-local input sends them, and stored as instants.
 */
@Controller
@RequestMapping("/locks/{lockId}/links")
class LinksController {

    private static final String VIEW = "links/lock-links";
    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final Locks locks;
    private final AccessLinks accessLinks;

    LinksController(Locks locks, AccessLinks accessLinks) {
        this.locks = locks;
        this.accessLinks = accessLinks;
    }

    @GetMapping
    String links(CurrentOwner owner, @PathVariable UUID lockId, Model model) {
        var lock = findLock(owner, lockId);
        var now = LocalDateTime.now(ZoneId.of(lock.timeZone())).truncatedTo(ChronoUnit.MINUTES);
        model.addAttribute("label", "");
        model.addAttribute("validFrom", INPUT_FORMAT.format(now));
        model.addAttribute("validUntil", INPUT_FORMAT.format(now.plusDays(1)));
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
        var strippedLabel = label.strip();
        var from = parse(validFrom, zone);
        var until = parse(validUntil, zone);
        var labelError = strippedLabel.isEmpty() || strippedLabel.length() > AccessLinks.MAX_LABEL_LENGTH;
        String untilError = null;
        if (until.isEmpty()) {
            untilError = "invalid";
        } else if (from.isPresent() && !until.get().isAfter(from.get())) {
            untilError = "before-start";
        } else if (!until.get().isAfter(Instant.now())) {
            untilError = "past";
        }
        if (labelError || from.isEmpty() || untilError != null) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("label", label);
            model.addAttribute("validFrom", validFrom);
            model.addAttribute("validUntil", validUntil);
            model.addAttribute("labelError", labelError);
            model.addAttribute("validFromError", from.isEmpty());
            model.addAttribute("validUntilError", untilError);
            return view(owner, lock, model);
        }
        var url = accessLinks.create(owner.id(), lock.id(), strippedLabel, from.get(), until.get());
        redirect.addFlashAttribute("createdUrl", url);
        redirect.addFlashAttribute("createdLabel", strippedLabel);
        redirect.addFlashAttribute("createdFrom", from.get().atZone(zone));
        redirect.addFlashAttribute("createdUntil", until.get().atZone(zone));
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

    /**
     * A local date and time in the lock's zone. In a daylight saving gap the time moves forward by the gap's
     * length; in an overlap the earlier of the two instants is used.
     */
    private static Optional<Instant> parse(String localDateTime, ZoneId zone) {
        try {
            return Optional.of(LocalDateTime.parse(localDateTime).atZone(zone).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
