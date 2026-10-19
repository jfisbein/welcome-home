package welcomehome.auth;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInput;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInputs;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import welcomehome.admin.InvitationService;
import welcomehome.admin.RedeemableInvitation;
import welcomehome.auth.OwnerAccountDao.OwnerAccount;
import welcomehome.auth.RegistrationException.Reason;

/**
 * Registers a new owner from an invitation, or adds a passkey to an existing owner from a passkey recovery
 * link, in two steps that mirror the passkey ceremony: {@link #start} checks the invitation (and, for a new
 * owner, the email) and returns the passkey creation options without writing anything; {@link #finish}
 * verifies the new passkey and, in one transaction, redeems the invitation and stores the passkey, and the
 * new owner. A failure at any point leaves the invitation usable. A recovered owner keeps their passkeys.
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
    private final UserCredentialRepository userCredentials;
    private final WebAuthnRelyingPartyOperations relyingPartyOperations;
    private final PublicKeyCredentialRpEntity relyingParty;
    private final EmailVerification emailVerification;

    OwnerRegistration(InvitationService invitations, OwnerAccountDao owners,
                      PublicKeyCredentialUserEntityRepository userEntities, UserCredentialRepository userCredentials,
                      WebAuthnRelyingPartyOperations relyingPartyOperations, PublicKeyCredentialRpEntity relyingParty,
                      EmailVerification emailVerification) {
        this.invitations = invitations;
        this.owners = owners;
        this.userEntities = userEntities;
        this.userCredentials = userCredentials;
        this.relyingPartyOperations = relyingPartyOperations;
        this.relyingParty = relyingParty;
        this.emailVerification = emailVerification;
    }

    /** The owner a passkey recovery link is for, or empty for an invitation to register as a new owner. */
    Optional<OwnerAccount> recoveredOwner(RedeemableInvitation invitation) {
        var ownerId = invitation.recoversOwnerId();
        return ownerId == null ? Optional.empty() : owners.findById(ownerId);
    }

    /**
     * Checks the invitation and returns the passkey creation options.
     *
     * @param email the new owner's email; ignored for a passkey recovery link, which names its owner
     */
    PendingRegistration start(String invitationToken, @Nullable String email) {
        var invitation = invitations.findRedeemable(invitationToken)
                .orElseThrow(() -> new RegistrationException(Reason.INVITATION_NOT_REDEEMABLE));
        if (invitation.recoversOwnerId() == null) {
            return startNewOwner(invitationToken, email);
        }
        var owner = recoveredOwner(invitation)
                .orElseThrow(() -> new RegistrationException(Reason.INVITATION_NOT_REDEEMABLE));
        return startRecovery(invitationToken, owner);
    }

    private PendingRegistration startNewOwner(String invitationToken, @Nullable String email) {
        var normalizedEmail = normalize(email);
        if (owners.findByEmail(normalizedEmail).isPresent() || userEntities.findByUsername(normalizedEmail) != null) {
            throw new RegistrationException(Reason.EMAIL_TAKEN);
        }
        return new PendingRegistration(invitationToken, normalizedEmail, null,
                creationOptions(newUserEntity(normalizedEmail), List.of()));
    }

    private PendingRegistration startRecovery(String invitationToken, OwnerAccount owner) {
        var existing = userEntities.findByUsername(owner.email());
        var user = existing != null ? existing : newUserEntity(owner.email());
        // The authenticator refuses to create a second passkey where the owner already has one.
        var ownPasskeys = userCredentials.findByUserId(user.getId()).stream()
                .map(passkey -> PublicKeyCredentialDescriptor.builder()
                        .id(passkey.getCredentialId())
                        .transports(passkey.getTransports())
                        .build())
                .toList();
        return new PendingRegistration(invitationToken, owner.email(), owner.id(),
                creationOptions(user, ownPasskeys));
    }

    private static PublicKeyCredentialUserEntity newUserEntity(String email) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .id(Bytes.random())
                .name(email)
                .displayName(email)
                .build();
    }

    private PublicKeyCredentialCreationOptions creationOptions(PublicKeyCredentialUserEntity user,
                                                               List<PublicKeyCredentialDescriptor> excluded) {
        // The same choices as Spring Security's own registration: discoverable passkeys, no attestation.
        return PublicKeyCredentialCreationOptions.builder()
                .rp(relyingParty)
                .user(user)
                .challenge(Bytes.random())
                .excludeCredentials(excluded)
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
    }

    /** @param language a new owner's language: that of the pages they registered from */
    @Transactional
    RegisteredOwner finish(PendingRegistration pending, PublicKeyCredential<AuthenticatorAttestationResponse> passkey,
                           Locale language) {
        var invitation = invitations.redeem(pending.invitationToken())
                .orElseThrow(() -> new RegistrationException(Reason.INVITATION_NOT_REDEEMABLE));
        var recoveredOwnerId = pending.recoveredOwnerId();
        var owner = recoveredOwnerId == null
                ? insertOwner(pending.email(), invitation.grantsAdmin(), language)
                : owners.findById(recoveredOwnerId)
                        .orElseThrow(() -> new RegistrationException(Reason.INVITATION_NOT_REDEEMABLE));
        userEntities.save(pending.options().getUser());
        try {
            relyingPartyOperations.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                    pending.options(), new RelyingPartyPublicKey(passkey, PASSKEY_LABEL)));
        } catch (RuntimeException e) {
            throw new RegistrationException(Reason.PASSKEY_REJECTED, e);
        }
        if (recoveredOwnerId == null) {
            // Sent after commit: a registration that fails sends nothing.
            emailVerification.start(owner.id());
        }
        return new RegisteredOwner(owner.id(), owner.email(), owner.isAdmin());
    }

    private OwnerAccount insertOwner(String email, boolean isAdmin, Locale language) {
        if (owners.findByEmail(email).isPresent()) {
            throw new RegistrationException(Reason.EMAIL_TAKEN);
        }
        return new OwnerAccount(owners.insert(email, isAdmin, language), email, isAdmin);
    }

    private static String normalize(@Nullable String email) {
        var normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(normalized).matches()) {
            throw new RegistrationException(Reason.INVALID_EMAIL);
        }
        return normalized;
    }

    /**
     * A registration between the two steps, kept in the visitor's session. {@code recoveredOwnerId} is the
     * owner who gets the passkey, or {@code null} for a new owner.
     */
    record PendingRegistration(String invitationToken, String email, @Nullable UUID recoveredOwnerId,
                               PublicKeyCredentialCreationOptions options) {
    }

    record RegisteredOwner(UUID id, String email, boolean isAdmin) {
    }
}
