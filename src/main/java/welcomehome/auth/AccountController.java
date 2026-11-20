package welcomehome.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.OwnerAccounts.EmailTakenException;

/**
 * The owner's account, a section of configuration: changing their email, and deleting their account. The email is
 * where their notices go and what their passkeys show, not a credential nor what the session knows them by, so
 * it changes at once and nothing else does; the new address is verified as a new owner's is, and the previous one
 * is told.
 */
@Controller
@RequestMapping("/settings/account")
class AccountController {

    private static final String VIEW = "account";

    private final SecurityContextHolderStrategy securityContextHolder = SecurityContextHolder.getContextHolderStrategy();

    private final OwnerAccounts accounts;
    private final EmailVerification emailVerification;

    AccountController(OwnerAccounts accounts, EmailVerification emailVerification) {
        this.accounts = accounts;
        this.emailVerification = emailVerification;
    }

    @GetMapping
    String page(CurrentOwner owner, Model model) {
        model.addAttribute("email", owner.email());
        return view(owner, model);
    }

    /**
     * Changes the owner's email. Their sessions go on, this one and any other: they know the owner by their id.
     */
    @PutMapping("/email")
    String changeEmail(CurrentOwner owner, @RequestParam(defaultValue = "") String email, Model model,
                       HttpServletResponse response, RedirectAttributes redirect) {
        var normalized = Emails.normalize(email);
        if (normalized.isEmpty()) {
            return refused(owner, email, "invalid", model, response);
        }
        var newEmail = normalized.get();
        try {
            if (!accounts.changeEmail(owner.id(), newEmail)) {
                return "redirect:/settings/account";
            }
        } catch (EmailTakenException e) {
            return refused(owner, email, "taken", model, response);
        }
        redirect.addFlashAttribute("emailChanged", newEmail);
        return "redirect:/settings/account";
    }

    /** Deletes the owner's account with everything of theirs, and signs them out. The only admin cannot leave. */
    @DeleteMapping
    String delete(CurrentOwner owner, HttpServletRequest request, HttpServletResponse response) {
        if (accounts.isOnlyAdmin(owner.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The only admin cannot delete their account");
        }
        accounts.delete(owner.id());
        new SecurityContextLogoutHandler().logout(request, response, securityContextHolder.getContext().getAuthentication());
        return "redirect:/login?deleted";
    }

    private String refused(CurrentOwner owner, String email, String error, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("email", email);
        model.addAttribute("emailError", error);
        return view(owner, model);
    }

    private String view(CurrentOwner owner, Model model) {
        model.addAttribute("currentEmail", owner.email());
        model.addAttribute("verified", emailVerification.isVerified(owner.id()));
        model.addAttribute("onlyAdmin", accounts.isOnlyAdmin(owner.id()));
        model.addAttribute("maxEmailLength", Emails.MAX_LENGTH);
        return VIEW;
    }
}
