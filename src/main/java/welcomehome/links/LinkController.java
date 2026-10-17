package welcomehome.links;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;

/**
 * Actions on one link. Each returns to its lock's links page; another owner's link is not found.
 */
@Controller
@RequestMapping("/links/{linkId}")
class LinkController {

    private final AccessLinks accessLinks;

    LinkController(AccessLinks accessLinks) {
        this.accessLinks = accessLinks;
    }

    @PostMapping("/revoke")
    String revoke(CurrentOwner owner, @PathVariable UUID linkId, RedirectAttributes redirect) {
        var revoked = accessLinks.revoke(owner.id(), linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        redirect.addFlashAttribute("revokedLabel", revoked.label());
        return "redirect:/locks/" + revoked.lockId() + "/links";
    }
}
