package welcomehome.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;

/**
 * The name a signed-in owner goes by: their id, as text. It is the name of their session's authentication and of
 * their entry in the passkey store, which Spring Security finds, and creates, by that name. Their email is what
 * they read, on the pages and beside a passkey on their device, and may change without anything else changing.
 */
final class OwnerName {

    private OwnerName() {
    }

    static String of(UUID ownerId) {
        return ownerId.toString();
    }

    /** The owner a name is of, or empty for a name that is no owner's id. */
    static Optional<UUID> ownerId(String name) {
        try {
            return Optional.of(UUID.fromString(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The owner's entry of the passkey store as it is stored: named by their id, showing their email. */
    static PublicKeyCredentialUserEntity stored(PublicKeyCredentialUserEntity user, UUID ownerId, String email) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .id(user.getId())
                .name(of(ownerId))
                .displayName(email)
                .build();
    }

    /**
     * The same entry as an authenticator is given it: under the owner's email, which is what a passkey shows on
     * their device. Its id, which is what ties a passkey to its owner, is the same.
     */
    static PublicKeyCredentialUserEntity shown(PublicKeyCredentialUserEntity user, String email) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .id(user.getId())
                .name(email)
                .displayName(email)
                .build();
    }
}
