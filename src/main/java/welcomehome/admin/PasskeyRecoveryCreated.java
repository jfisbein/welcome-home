package welcomehome.admin;

import java.util.UUID;

/**
 * A passkey recovery link was created for an owner, from the admin panel or the command line.
 *
 * @param ownerId the owner whom the link adds a passkey to
 */
public record PasskeyRecoveryCreated(UUID ownerId) {
}
