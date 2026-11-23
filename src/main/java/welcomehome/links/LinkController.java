package welcomehome.links;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;
import welcomehome.links.LinkDao.EditableLink;
import welcomehome.locks.Locks;

/**
 * One link: its page, with what it is now, the form that edits it and the actions on it. Each action returns to
 * the link's page, and deleting it to its lock's links; another owner's link, or a revoked or paused one for
 * editing and regenerating, is not found. Only a link that ended can be deleted, and only one that could open can
 * be paused.
 */
@Controller
@RequestMapping("/links/{linkId}")
class LinkController {

    private static final String VIEW = "links/link";

    private final AccessLinks accessLinks;
    private final Locks locks;
    private final Clock clock;
    private final LinkSchedules schedules;

    LinkController(AccessLinks accessLinks, Locks locks, LinkSchedules schedules, Clock clock) {
        this.clock = clock;
        this.locks = locks;
        this.accessLinks = accessLinks;
        this.schedules = schedules;
    }

    @GetMapping
    String link(CurrentOwner owner, @PathVariable UUID linkId, Locale locale, Model model) {
        // A revoked or paused link has a page too, without the form: it is where it is resumed or deleted.
        accessLinks.findEditable(owner.id(), linkId).ifPresent(link ->
                LinkForm.of(link.label(), link.validFrom().toInstant(), link.validUntil().toInstant(),
                                schedules.of(owner.id(), linkId), ZoneId.of(link.timeZone()),
                                accessLinks.pinOf(owner.id(), linkId).orElse(""))
                        .addTo(model, null));
        return view(owner, linkId, locale, model);
    }

    @PatchMapping
    String update(CurrentOwner owner, @PathVariable UUID linkId, @RequestParam(defaultValue = "") String label,
                  @RequestParam(defaultValue = "") String validFrom, @RequestParam(defaultValue = "") String validUntil,
                  @RequestParam(defaultValue = "") String pin, @RequestParam Map<String, String> params, Locale locale, Model model,
                  HttpServletResponse response, RedirectAttributes redirect) {
        var link = findEditable(owner, linkId);
        var form = new LinkForm(label, validFrom, validUntil, ScheduleForm.of(params), pin);
        var validation = form.validate(ZoneId.of(link.timeZone()), clock.instant());
        if (!validation.isValid()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            form.addTo(model, validation);
            return view(owner, linkId, locale, model);
        }
        if (!accessLinks.update(owner.id(), linkId, validation.label(),
                Objects.requireNonNull(validation.validFrom()), Objects.requireNonNull(validation.validUntil()),
                validation.schedule(), validation.pin())) {
            throw notFound();
        }
        redirect.addFlashAttribute("updatedLabel", validation.label());
        var days = LinkForm.daysIfLong(validation.validFrom(), validation.validUntil(), clock.instant());
        if (days != null) {
            redirect.addFlashAttribute("updatedDays", days);
        }
        return "redirect:/links/" + linkId;
    }

    @PostMapping("/revoke")
    String revoke(CurrentOwner owner, @PathVariable UUID linkId, RedirectAttributes redirect) {
        var revoked = accessLinks.revoke(owner.id(), linkId).orElseThrow(LinkController::notFound);
        redirect.addFlashAttribute("revokedLabel", revoked.label());
        return "redirect:/links/" + linkId;
    }

    @PostMapping("/pause")
    String pause(CurrentOwner owner, @PathVariable UUID linkId, RedirectAttributes redirect) {
        var paused = accessLinks.pause(owner.id(), linkId).orElseThrow(LinkController::notFound);
        redirect.addFlashAttribute("pausedLabel", paused.label());
        return "redirect:/links/" + linkId;
    }

    @PostMapping("/resume")
    String resume(CurrentOwner owner, @PathVariable UUID linkId, RedirectAttributes redirect) {
        var resumed = accessLinks.resume(owner.id(), linkId).orElseThrow(LinkController::notFound);
        redirect.addFlashAttribute("resumedLabel", resumed.label());
        return "redirect:/links/" + linkId;
    }

    @DeleteMapping
    String delete(CurrentOwner owner, @PathVariable UUID linkId, RedirectAttributes redirect) {
        var deleted = accessLinks.deleteEnded(owner.id(), linkId);
        if (deleted.isEmpty()) {
            throw accessLinks.exists(owner.id(), linkId)
                    ? new ResponseStatusException(HttpStatus.CONFLICT, "Only a link that ended can be deleted")
                    : notFound();
        }
        redirect.addFlashAttribute("deletedLabel", deleted.get().label());
        return "redirect:/locks/" + deleted.get().lockId() + "/links";
    }

    @PostMapping("/regenerate")
    String regenerate(CurrentOwner owner, @PathVariable UUID linkId, RedirectAttributes redirect) {
        var regenerated = accessLinks.regenerate(owner.id(), linkId).orElseThrow(LinkController::notFound);
        // The new URL is the one the link's page shows from now on.
        redirect.addFlashAttribute("regeneratedLabel", regenerated.label());
        return "redirect:/links/" + linkId;
    }

    /** The link's page: the form's values are in the model already when the link can be edited. */
    private String view(CurrentOwner owner, UUID linkId, Locale locale, Model model) {
        var lock = accessLinks.lockOf(owner.id(), linkId).flatMap(lockId -> locks.find(owner.id(), lockId))
                .orElseThrow(LinkController::notFound);
        model.addAttribute("lock", lock);
        model.addAttribute("link", accessLinks.find(owner.id(), lock, linkId, locale)
                .orElseThrow(LinkController::notFound));
        model.addAttribute("editable", model.containsAttribute("label"));
        return VIEW;
    }

    private EditableLink findEditable(CurrentOwner owner, UUID linkId) {
        return accessLinks.findEditable(owner.id(), linkId).orElseThrow(LinkController::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
