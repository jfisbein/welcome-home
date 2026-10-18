package welcomehome.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The owner's passkeys: list, rename and delete. New passkeys are added in the browser through Spring
 * Security's own endpoints, {@code POST /webauthn/register/options} and {@code POST /webauthn/register}
 * (see static/js/passkeys.js). Another owner's passkey is not found.
 */
@Controller
@RequestMapping("/passkeys")
class PasskeysController {

    static final int MAX_LABEL_LENGTH = 60;

    private final PasskeyDao passkeys;

    PasskeysController(PasskeyDao passkeys) {
        this.passkeys = passkeys;
    }

    @GetMapping
    String list(CurrentOwner owner, Model model) {
        var all = passkeys.findAll(owner.email());
        model.addAttribute("passkeys", all);
        model.addAttribute("canDelete", all.size() > 1);
        model.addAttribute("maxLabelLength", MAX_LABEL_LENGTH);
        return "passkeys";
    }

    @PatchMapping("/{credentialId}")
    String rename(CurrentOwner owner, @PathVariable String credentialId,
                  @RequestParam(defaultValue = "") String label, RedirectAttributes redirect) {
        var stripped = label.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_LABEL_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid passkey name");
        }
        if (!passkeys.rename(owner.email(), credentialId, stripped)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        redirect.addFlashAttribute("renamed", stripped);
        return "redirect:/passkeys";
    }

    @DeleteMapping("/{credentialId}")
    String delete(CurrentOwner owner, @PathVariable String credentialId, RedirectAttributes redirect) {
        var passkey = passkeys.findAll(owner.email()).stream()
                .filter(candidate -> candidate.credentialId().equals(credentialId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!passkeys.deleteUnlessLast(owner.email(), credentialId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The last passkey cannot be deleted");
        }
        redirect.addFlashAttribute("deleted", passkey.label());
        return "redirect:/passkeys";
    }
}
