package welcomehome.auth;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInput;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInputs;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import welcomehome.admin.InvitationService;
import welcomehome.auth.RegistrationException.Reason;

/**
 * Registers a new owner from an invitation, in two steps that mirror the passkey ceremony:
 * {@link #start} checks the invitation and the email and returns the passkey creation options without
 * writing anything; {@link #finish} verifies the new passkey and, in one transaction, redeems the
 * invitation and stores the owner and the passkey. A failure at any point leaves the invitation usable.
 */
@Service
class OwnerRegistration {

    private static final String PASSKEY_LABEL = "Passkey";
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    /** user_entities.name is varchar(100). */
    private static final int MAX_EMAIL_LENGTH = 100;

    private final InvitationService invitations;
    private final OwnerAccountDao owners;
    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final WebAuthnRelyingPartyOperations relyingPartyOperations;
    private final PublicKeyCredentialRpEntity relyingParty;

    OwnerRegistration(InvitationService invitations, OwnerAccountDao owners,
                      PublicKeyCredentialUserEntityRepository userEntities,
                      WebAuthnRelyingPartyOperations relyingPartyOperations, PublicKeyCredentialRpEntity relyingParty) {
        this.invitations = invitations;
        this.owners = owners;
        this.userEntities = userEntities;
        this.relyingPartyOperations = relyingPartyOperations;
        this.relyingParty = relyingParty;
    }

    PendingRegistration start(String invitationToken, String email) {
        if (invitations.findRedeemable(invitationToken).isEmpty()) {
            throw new RegistrationException(Reason.INVITATION_NOT_REDEEMABLE);
        }
        var normalizedEmail = normalize(email);
        if (owners.findByEmail(normalizedEmail).isPresent() || userEntities.findByUsername(normalizedEmail) != null) {
            throw new RegistrationException(Reason.EMAIL_TAKEN);
        }
        var user = ImmutablePublicKeyCredentialUserEntity.builder()
                .id(Bytes.random())
                .name(normalizedEmail)
                .displayName(normalizedEmail)
                .build();
        // The same choices as Spring Security's own registration: discoverable passkeys, no attestation.
        var options = PublicKeyCredentialCreationOptions.builder()
                .rp(relyingParty)
                .user(user)
                .challenge(Bytes.random())
                .pubKeyCredParams(PublicKeyCredentialParameters.EdDSA, PublicKeyCredentialParameters.ES256,
                        PublicKeyCredentialParameters.RS256)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .build())
                .attestation(AttestationConveyancePreference.NONE)
                .extensions(new ImmutableAuthenticationExtensionsClientInputs(
                        ImmutableAuthenticationExtensionsClientInput.credProps))
                .timeout(Duration.ofMinutes(5))
                .build();
        return new PendingRegistration(invitationToken, normalizedEmail, options);
    }

    @Transactional
    RegisteredOwner finish(PendingRegistration pending, PublicKeyCredential<AuthenticatorAttestationResponse> passkey) {
        var invitation = invitations.redeem(pending.invitationToken())
                .orElseThrow(() -> new RegistrationException(Reason.INVITATION_NOT_REDEEMABLE));
        if (owners.findByEmail(pending.email()).isPresent()) {
            throw new RegistrationException(Reason.EMAIL_TAKEN);
        }
        var ownerId = owners.insert(pending.email(), invitation.grantsAdmin());
        userEntities.save(pending.options().getUser());
        try {
            relyingPartyOperations.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                    pending.options(), new RelyingPartyPublicKey(passkey, PASSKEY_LABEL)));
        } catch (RuntimeException e) {
            throw new RegistrationException(Reason.PASSKEY_REJECTED, e);
        }
        return new RegisteredOwner(ownerId, pending.email(), invitation.grantsAdmin());
    }

    private static String normalize(String email) {
        var normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(normalized).matches()) {
            throw new RegistrationException(Reason.INVALID_EMAIL);
        }
        return normalized;
    }

    /** A registration between the two steps, kept in the visitor's session. */
    record PendingRegistration(String invitationToken, String email, PublicKeyCredentialCreationOptions options) {
    }

    record RegisteredOwner(UUID id, String email, boolean isAdmin) {
    }
}
