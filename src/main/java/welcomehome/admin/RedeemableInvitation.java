package welcomehome.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation that is not used and not expired.
 *
 * @param id          the invitation's id
 * @param grantsAdmin whether the owner who redeems it becomes an admin
 * @param expiresAt   when the invitation can no longer be redeemed
 */
public record RedeemableInvitation(UUID id, boolean grantsAdmin, Instant expiresAt) {
}
