package welcomehome.admin;

import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;
import welcomehome.auth.InvitationService;
import welcomehome.auth.OwnerAccounts;

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
    private final OwnerAccounts ownerAccounts;

    AdminController(OwnerDao owners, InvitationService invitations, OwnerAccounts ownerAccounts) {
        this.owners = owners;
        this.invitations = invitations;
        this.ownerAccounts = ownerAccounts;
    }

    @GetMapping("/owners")
    String owners(CurrentOwner admin, Model model) {
        model.addAttribute("owners", owners.findAll());
        model.addAttribute("adminEmail", admin.email());
        model.addAttribute("pending", invitations.findPending());
        return "admin/owners";
    }

    /**
     * Deletes an owner with everything of theirs: passkeys, Nuki connection, locks, links and history. An admin
     * cannot delete themselves, so there is always an admin left.
     */
    @DeleteMapping("/owners")
    String deleteOwner(@RequestParam String email, CurrentOwner admin, RedirectAttributes redirect) {
        if (email.equals(admin.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An admin cannot delete themselves");
        }
        if (!owners.findIdByEmail(email).map(ownerAccounts::delete).orElse(false)) {
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
     * Cancels an invitation or a recovery link: one sent to the wrong person, or that leaked, stops working at once.
     * Any admin may cancel any link. The owner list shows what is left.
     */
    @DeleteMapping("/invitations/{id}")
    String cancelInvitation(@PathVariable UUID id, RedirectAttributes redirect) {
        var recipient = invitations.cancel(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        redirect.addFlashAttribute("cancelledFor", recipient);
        return "redirect:/admin/owners#pending";
    }

    /**
     * Creates the invitation and redirects to the form, which shows its link once: only a hash of the
     * token is stored, so the link travels in a flash attribute and is gone on the next page load.
     */
    @PostMapping("/invitations")
    String createInvitation(@RequestParam(defaultValue = "") String note,
                            @RequestParam(defaultValue = "false") boolean grantsAdmin,
                            CurrentOwner admin, Model model, HttpServletResponse response,
                            RedirectAttributes redirect) {
        var strippedNote = note.strip();
        if (strippedNote.isEmpty() || strippedNote.length() > MAX_NOTE_LENGTH) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("noteError", true);
            model.addAttribute("note", note);
            return "admin/invitations";
        }
        redirect.addFlashAttribute("invitation", invitations.createInvitation(admin.id(), strippedNote, grantsAdmin));
        redirect.addFlashAttribute("invitedNote", strippedNote);
        return "redirect:/admin/invitations";
    }

    /**
     * Creates a passkey recovery link for an owner and redirects to the owner list, which shows it once, like
     * an invitation's.
     */
    @PostMapping("/recoveries")
    String createRecovery(@RequestParam String email, CurrentOwner admin, RedirectAttributes redirect) {
        var ownerId = owners.findIdByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        redirect.addFlashAttribute("recovery", invitations.createPasskeyRecovery(admin.id(), ownerId));
        redirect.addFlashAttribute("recoveredEmail", email);
        return "redirect:/admin/owners";
    }
}
