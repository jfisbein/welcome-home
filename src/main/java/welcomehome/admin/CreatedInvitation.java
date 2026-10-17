package welcomehome.admin;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * An invitation just created. Only a hash of its token is stored, so this URL cannot be shown again.
 *
 * @param id        the invitation's id
 * @param url       the link to give to the new owner
 * @param expiresAt when the invitation can no longer be redeemed
 */
public record CreatedInvitation(UUID id, URI url, Instant expiresAt) {
}
