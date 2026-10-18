package welcomehome.admin;

import java.security.Principal;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The admin panel: the owner list, new invitations, passkey recovery links and deleting owners. Only admins reach it (see the
 * security configuration), and it shows owners' emails and registration dates, nothing of their locks or
 * links.
 */
@Controller
@RequestMapping("/admin")
class AdminController {

    private static final int MAX_NOTE_LENGTH = 100;

    private final OwnerDao owners;
    private final InvitationService invitations;

    AdminController(OwnerDao owners, InvitationService invitations) {
        this.owners = owners;
        this.invitations = invitations;
    }

    @GetMapping("/owners")
    String owners(Principal admin, Model model) {
        model.addAttribute("owners", owners.findAll());
        model.addAttribute("adminEmail", admin.getName());
        return "admin/owners";
    }

    /**
     * Deletes an owner with everything of theirs: passkeys, Nuki connection, locks, links and history. An admin
     * cannot delete themselves, so there is always an admin left.
     */
    @DeleteMapping("/owners")
    String deleteOwner(@RequestParam String email, Principal admin, RedirectAttributes redirect) {
        if (email.equals(admin.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An admin cannot delete themselves");
        }
        if (!owners.delete(email)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        redirect.addFlashAttribute("deletedEmail", email);
        return "redirect:/admin/owners";
    }

    @GetMapping("/invitations")
    String invitationForm() {
        return "admin/invitations";
    }

    /**
     * Creates the invitation and redirects to the form, which shows its link once: only a hash of the
     * token is stored, so the link travels in a flash attribute and is gone on the next page load.
     */
    @PostMapping("/invitations")
    String createInvitation(@RequestParam(defaultValue = "") String note,
                            @RequestParam(defaultValue = "false") boolean grantsAdmin,
                            Principal admin, Model model, HttpServletResponse response,
                            RedirectAttributes redirect) {
        var strippedNote = note.strip();
        if (strippedNote.isEmpty() || strippedNote.length() > MAX_NOTE_LENGTH) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("noteError", true);
            model.addAttribute("note", note);
            return "admin/invitations";
        }
        redirect.addFlashAttribute("invitation", invitations.createInvitation(adminId(admin), strippedNote, grantsAdmin));
        redirect.addFlashAttribute("invitedNote", strippedNote);
        return "redirect:/admin/invitations";
    }

    /**
     * Creates a passkey recovery link for an owner and redirects to the owner list, which shows it once, like
     * an invitation's.
     */
    @PostMapping("/recoveries")
    String createRecovery(@RequestParam String email, Principal admin, RedirectAttributes redirect) {
        var ownerId = owners.findIdByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        redirect.addFlashAttribute("recovery", invitations.createPasskeyRecovery(adminId(admin), ownerId));
        redirect.addFlashAttribute("recoveredEmail", email);
        return "redirect:/admin/owners";
    }

    private UUID adminId(Principal admin) {
        return owners.findIdByEmail(admin.getName())
                .orElseThrow(() -> new IllegalStateException("The signed-in admin is not an owner"));
    }
}
