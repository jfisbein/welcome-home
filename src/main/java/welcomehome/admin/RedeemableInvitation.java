package welcomehome.admin;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An invitation that is not used and not expired.
 *
 * @param id              the invitation's id
 * @param grantsAdmin     whether the owner who redeems it becomes an admin
 * @param recoversOwnerId for a passkey recovery link, the owner who gets a new passkey; {@code null} for an
 *                        invitation to register as a new owner
 * @param expiresAt       when the invitation can no longer be redeemed
 */
public record RedeemableInvitation(UUID id, boolean grantsAdmin, @Nullable UUID recoversOwnerId, Instant expiresAt) {
}
