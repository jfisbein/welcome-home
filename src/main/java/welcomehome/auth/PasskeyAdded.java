package welcomehome.auth;

import java.util.UUID;

/**
 * A passkey was added to an owner who already had one, from {@code /settings/passkeys} or with a passkey recovery link.
 * Not published for a new owner's first passkey.
 *
 * @param ownerId the owner
 * @param label      the new passkey's name
 */
public record PasskeyAdded(UUID ownerId, String label) {
}
