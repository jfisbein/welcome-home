package welcomehome.auth;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An owner signed in: with a passkey, or by creating one with an invitation or a passkey recovery link. Published
 * on the request's thread, before its answer, so a listener may read where the request came from.
 *
 * @param ownerId        the owner
 * @param passkey        the name of the passkey they signed in with or created, or {@code null} if it cannot be told
 * @param byRecoveryLink whether they got in with a passkey recovery link: the one way someone else may enter an
 *                       account
 */
public record OwnerSignedIn(UUID ownerId, @Nullable String passkey, boolean byRecoveryLink) {
}
