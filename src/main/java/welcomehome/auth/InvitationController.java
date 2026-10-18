package welcomehome.auth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import welcomehome.admin.InvitationService;
import welcomehome.auth.OwnerRegistration.PendingRegistration;
import welcomehome.auth.RegistrationException.Reason;

/**
 * The invitation link: the page asks for an email and creates a passkey, then the new owner is signed in. A
 * passkey recovery link uses the same path and flow, without the email: the passkey is added to the owner the
 * link is for, who is then signed in. The passkey JSON uses Spring Security's WebAuthn format, read and
 * written with its Jackson module.
 */
@Controller
@RequestMapping("/invite/{token}")
class InvitationController {

    private static final String PENDING_REGISTRATION = InvitationController.class.getName() + ".pending";

    private final JsonMapper webAuthnJson = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    private final SecurityContextHolderStrategy securityContextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    private final InvitationService invitations;
    private final OwnerRegistration registration;
    private final OwnerUserDetailsService userDetailsService;

    InvitationController(InvitationService invitations, OwnerRegistration registration,
                         OwnerUserDetailsService userDetailsService) {
        this.invitations = invitations;
        this.registration = registration;
        this.userDetailsService = userDetailsService;
    }

    @GetMapping
    String invitation(@PathVariable String token, HttpServletResponse response, Model model) {
        var invitation = invitations.findRedeemable(token);
        if (invitation.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "invitation-invalid";
        }
        if (invitation.get().recoversOwnerId() == null) {
            return "invitation";
        }
        var owner = registration.recoveredOwner(invitation.get());
        if (owner.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "invitation-invalid";
        }
        model.addAttribute("email", owner.get().email());
        return "passkey-recovery";
    }

    @PostMapping(path = "/options", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    String creationOptions(@PathVariable String token, @RequestBody OptionsRequest request, HttpSession session) {
        var pending = registration.start(token, request.email());
        session.setAttribute(PENDING_REGISTRATION, pending);
        return webAuthnJson.writeValueAsString(pending.options());
    }

    @PostMapping(path = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    Map<String, String> register(@PathVariable String token, @RequestBody String passkeyJson,
                                 HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession();
        var pending = (PendingRegistration) session.getAttribute(PENDING_REGISTRATION);
        session.removeAttribute(PENDING_REGISTRATION);
        if (pending == null || !pending.invitationToken().equals(token)) {
            throw new RegistrationException(Reason.NO_REGISTRATION_IN_PROGRESS);
        }
        var owner = registration.finish(pending, readPasskey(passkeyJson));
        signIn(pending, owner.email(), request, response);
        return Map.of("redirectUrl", "/");
    }

    @ExceptionHandler(RegistrationException.class)
    ResponseEntity<Map<String, String>> registrationFailed(RegistrationException e) {
        return ResponseEntity.status(e.reason().status).body(Map.of("error", e.reason().code));
    }

    private PublicKeyCredential<AuthenticatorAttestationResponse> readPasskey(String json) {
        try {
            return webAuthnJson.readValue(json, new TypeReference<>() {
            });
        } catch (JacksonException e) {
            throw new RegistrationException(Reason.PASSKEY_REJECTED, e);
        }
    }

    private void signIn(PendingRegistration pending, String email, HttpServletRequest request,
                        HttpServletResponse response) {
        var owner = userDetailsService.loadUserByUsername(email);
        var context = securityContextHolder.createEmptyContext();
        context.setAuthentication(new WebAuthnAuthentication(pending.options().getUser(), owner.getAuthorities()));
        securityContextHolder.setContext(context);
        request.changeSessionId();
        securityContextRepository.saveContext(context, request, response);
    }

    /** {@code email} is empty for a passkey recovery link. */
    record OptionsRequest(@Nullable String email) {
    }
}
