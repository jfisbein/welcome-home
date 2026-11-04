package welcomehome.auth;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An owner signed in with a passkey. Published on the request's thread, before its answer, so a listener may read
 * where the request came from.
 *
 * @param ownerId the owner
 * @param passkey the name of the passkey they signed in with, or {@code null} if it cannot be told
 */
public record OwnerSignedIn(UUID ownerId, @Nullable String passkey) {
}
