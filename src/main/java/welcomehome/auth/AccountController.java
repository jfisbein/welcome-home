package welcomehome.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
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
 * where their notices go and the name their passkeys know them by, not a credential, so it changes at once; the new
 * address is verified as a new owner's is, and the previous one is told.
 */
@Controller
@RequestMapping("/settings/account")
class AccountController {

    private static final String VIEW = "account";

    private final SecurityContextHolderStrategy securityContextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    private final OwnerAccounts accounts;
    private final EmailVerification emailVerification;
    private final OwnerSessions sessions;
    private final OwnerUserDetailsService userDetailsService;
    private final PublicKeyCredentialUserEntityRepository userEntities;

    AccountController(OwnerAccounts accounts, EmailVerification emailVerification, OwnerSessions sessions,
                      OwnerUserDetailsService userDetailsService, PublicKeyCredentialUserEntityRepository userEntities) {
        this.accounts = accounts;
        this.emailVerification = emailVerification;
        this.sessions = sessions;
        this.userDetailsService = userDetailsService;
        this.userEntities = userEntities;
    }

    @GetMapping
    String page(CurrentOwner owner, Model model) {
        model.addAttribute("email", owner.email());
        return view(owner, model);
    }

    /**
     * Changes the owner's email. Their other sessions end, as they still know the owner by the previous one; this one
     * goes on under the new one.
     */
    @PutMapping("/email")
    String changeEmail(CurrentOwner owner, @RequestParam(defaultValue = "") String email, Model model,
                       HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
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
        var session = request.getSession(false);
        sessions.endOthers(owner.email(), session == null ? null : session.getId());
        signInAs(newEmail, request, response);
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

    /** The session's authentication, under the owner's new email, with what it allowed before. */
    private void signInAs(String email, HttpServletRequest request, HttpServletResponse response) {
        var owner = userDetailsService.loadUserByUsername(email);
        var passkeyUser = userEntities.findByUsername(email);
        Authentication authentication = passkeyUser == null
                ? UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.getAuthorities())
                : new WebAuthnAuthentication(passkeyUser, owner.getAuthorities());
        var context = securityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        securityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
