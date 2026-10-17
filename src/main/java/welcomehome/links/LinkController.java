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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;
import welcomehome.links.LinkDao.EditableLink;

/**
 * One link: its edit page and the actions on it. Each action returns to its lock's links page; another
 * owner's link, or a revoked one for editing and regenerating, is not found.
 */
@Controller
@RequestMapping("/links/{linkId}")
class LinkController {

    private final AccessLinks accessLinks;

    LinkController(AccessLinks accessLinks) {
        this.accessLinks = accessLinks;
    }

    @GetMapping
    String edit(CurrentOwner owner, @PathVariable UUID linkId, Model model) {
        var link = findEditable(owner, linkId);
        LinkForm.of(link.label(), link.validFrom().toInstant(), link.validUntil().toInstant(), ZoneId.of(link.timeZone()))
                .addTo(model, null);
        model.addAttribute("link", link);
        return "links/edit";
    }

    @PatchMapping
    String update(CurrentOwner owner, @PathVariable UUID linkId, @RequestParam(defaultValue = "") String label,
                  @RequestParam(defaultValue = "") String validFrom, @RequestParam(defaultValue = "") String validUntil,
                  Model model, HttpServletResponse response, RedirectAttributes redirect) {
        var link = findEditable(owner, linkId);
        var form = new LinkForm(label, validFrom, validUntil);
        var validation = form.validate(ZoneId.of(link.timeZone()), Instant.now());
        if (!validation.isValid()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            form.addTo(model, validation);
            model.addAttribute("link", link);
            return "links/edit";
        }
        if (!accessLinks.update(owner.id(), linkId, validation.label(),
                Objects.requireNonNull(validation.validFrom()), Objects.requireNonNull(validation.validUntil()))) {
            throw notFound();
        }
        redirect.addFlashAttribute("updatedLabel", validation.label());
        return "redirect:/locks/" + link.lockId() + "/links";
    }

    @PostMapping("/revoke")
    String revoke(CurrentOwner owner, @PathVariable UUID linkId, RedirectAttributes redirect) {
        var revoked = accessLinks.revoke(owner.id(), linkId).orElseThrow(LinkController::notFound);
        redirect.addFlashAttribute("revokedLabel", revoked.label());
        return "redirect:/locks/" + revoked.lockId() + "/links";
    }

    @PostMapping("/regenerate")
    String regenerate(CurrentOwner owner, @PathVariable UUID linkId, RedirectAttributes redirect) {
        var regenerated = accessLinks.regenerate(owner.id(), linkId).orElseThrow(LinkController::notFound);
        redirect.addFlashAttribute("regeneratedUrl", regenerated.url());
        redirect.addFlashAttribute("regeneratedLabel", regenerated.label());
        return "redirect:/locks/" + regenerated.lockId() + "/links";
    }

    private EditableLink findEditable(CurrentOwner owner, UUID linkId) {
        return accessLinks.findEditable(owner.id(), linkId).orElseThrow(LinkController::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
